(ns pdmct.nem-scraper
  (:require [clojure.string :as str]
            [net.cgrand.enlive-html :as html]
            [clj-http.client :as client]
            [tech.v3.dataset :as ds]
            ;;[tech.v3.datatype :as dtype]
            [pdmct.util.config :as cfg]
            [pdmct.util.redis :as db]
            [pdmct.service-monitor :refer [monitor-service-log]]
            [diehard.core :as dh]
            [pdmct.select-live-scraper :as sel]
            [pdmct.io.relay :as relay]
            [pdmct.io.twilio :as sms]
            [pdmct.amber :as amber]
            [java-time :as jt]
            [clojure.core.async
             :as async
             :refer [<! >! >!! <!! put! chan go close! thread timeout alts!]])
  (:import [java.io IOException] 
           [java.util.concurrent Executors])
  (:gen-class))

(def nemweb-prices-5min-url "http://nemweb.com.au/Reports/Current/Dispatchprices_PRE_AP/")

(def nemweb-prices-30min-url "http://nemweb.com.au/Reports/Current/TradingIS_Reports/")

(def nemweb-forecast-url "http://nemweb.com.au/Reports/Current/Predispatch_Reports/")

(def vic-region  "VIC1")

(def region-column "REGIONID")
(def price-column "PRE_AP_ENERGY_PRICE")
(def forecast-price-column "RRP")
(def forecast-period-column "PERIODID")

(def poll-interval-ms (or (cfg/poll-interval cfg/config-map) 30000))

(def price-ts-pos 4)
(def fcast-ts-pos 2)

(def trade-price-ts-pos 2)

(dh/defretrypolicy policy
  {:retry-on Exception
   :max-retries 10
   :backoff-ms [10000 180000]
   :on-failed-attempt (fn [val ex] (prn (str "failed attempt..." val " : " ex)))})

(defn get-page
  [url]
  (html/html-resource (java.net.URL. url)))

(defn get-file-hrefs
  [url]
  (html/select
   (get-page url)
   [:a]))

(defn timestamp-from-url
  [url pos]
  (let [name (last (str/split url #"/"))]
    (nth (str/split name #"_")
         pos)))

(defn get-latest-url
  [url]
  (last (sort (filter #(str/includes? % ".zip")
                      (map
                       (comp #(str/join (list url %)) html/text)
                       (get-file-hrefs url))))))


(defn csv-map
  [head lines]
  (map #(zipmap head %) lines))

(defn basic-parse
  [data]
  (let [lines (map #(str/split % #",") (str/split data #"\n"))
        hdr  (nth lines 3)
        data (vec (filter #(str/includes? % "PDREGION") (drop 4 lines)))]
    (csv-map hdr data)))

(defn get-current-prices-fn
  []
  (let [url  (get-latest-url nemweb-prices-5min-url)
        ;;_ (println (str "url: " url))
        ds-ts (timestamp-from-url url price-ts-pos)
        stream (-> url
                   (client/get {:as :stream})
                   (:body)
                   (java.util.zip.ZipInputStream.))]
    (.getNextEntry stream)
    (-> (ds/->dataset stream {:file-type :csv
                              :n-initial-skip-rows 1
                              :header-row? true
                              :num-rows 5})
        (assoc :timestamp ds-ts)
        (ds/set-dataset-name (str "current_" ds-ts)))))

(defn get-trade-price-fn
  " last-read - local-date-time "
  [last-read base-url]
  (let [url (get-latest-url base-url)
        file-ts (jt/local-date-time "yyyMMddHHmm" (timestamp-from-url url trade-price-ts-pos))]
    (if (or (nil? last-read)
            (jt/before? last-read file-ts))
      (-> url
          (client/get {:as :stream})
          (:body)
          (java.util.zip.ZipInputStream.)
          (#(do
              (.getNextEntry %)
              %))
          (ds/->dataset {:file-type :csv
                         :n-initial-skip-rows 8
                         :header-row? true
                         :num-rows 5})
          (assoc :timestamp
                 file-ts)
          (ds/set-dataset-name (str "trade-price_" file-ts))
          (#(vec (list % true))))
      (vec (list nil false)))))


(defn get-current-spot-price
  []
  (dh/with-retry {:policy policy}
    (get-current-prices-fn)))


(defn get-trade-price-30min
  [last-read]
  (dh/with-retry {:policy policy}
    (let [[tp-ds new-read] (get-trade-price-fn last-read nemweb-prices-30min-url)]
      (vec (list (some-> tp-ds
                         (ds/filter-column region-column #(= vic-region %))
                         (#(% "RRP"))
                         (#(% 0)))
                 new-read)))))

(defn get-forecast-prices-fn
  []
  (let [url (get-latest-url nemweb-forecast-url)
        ;;_ (println (str "forecast url: " url))
        ds-ts (timestamp-from-url url fcast-ts-pos)
        stream (-> url
                   (client/get {:as :stream})
                   (:body)
                   (java.util.zip.ZipInputStream.))
        _ (.getNextEntry stream)
        data (slurp stream)
        data (basic-parse data)]
    (-> (ds/->dataset data)
        (assoc :timestamp ds-ts)
        (ds/set-dataset-name (str "forecast_" ds-ts)))))

(defn get-forecast-prices
  []
  (dh/with-retry {:policy policy}
    (get-forecast-prices-fn)))
(defn price-col
  [d & idx]
  ((fnil (d price-column) 0) (first idx)))

(defn forecast-price-col
  [d & idx]
  ((fnil (d forecast-price-col) 0) (first idx)))
(defn forecast-price-cols
  [d & idx]
  (-> d
      (ds/select-columns (vec (list region-column forecast-period-column forecast-price-column)))))

(defn get-region-price
  [prices region]
  (price-col (-> prices
                 (ds/filter-column region-column #(= region %)))))

(defn get-region-forecast
  [forecast region]
  (forecast-price-cols (-> forecast
                           (ds/filter-column region-column #(= region %)))))

(defn calc-running-avg
  "calcs and/or updates a running average"
  [data pxs]
  (let [start (if (empty? data) [0 0.0] data)]
    (reduce (fn [d n]
              (let [[^int cnt ^double avg] d]
                (vec (list (inc cnt)
                           (+ avg (/ (- n avg)
                                     (inc cnt)))))))
            start
            pxs)))

(defn get-current-period-forecast
  "returns the AEMO forecast price (30min) for the next (current) forecast period"
  [forecast-prices]
  (-> forecast-prices
      (ds/filter-column "REGIONID" "VIC1")
      (ds/select-columns ["RRP" "PERIODID"])
      (ds/sort-by-column "PERIODID")
      (ds/mapseq-reader)
      first
      (get "RRP")
      Double.))

(defn charge-battery?
  " determine whether we should charge or not"
  [now
   soc
   current-30min-price
   forecast-prices]
  (let [current-forecast (get-current-period-forecast forecast-prices)
        good-price (< current-forecast 10)
        positive-price (and (< current-forecast 40)
                            (< current-30min-price 40))]
    (or
     good-price
     (and
      positive-price
      (relay/current-state)))))

(defn process-alerts [current-price current-fit battery-data]
  (let [export (< 0 (:grid_w battery-data))
        export-amount (:grid_w battery-data)
        from-phone (cfg/get-twilio-phone cfg/config-map)
        to-phones (cfg/get-alert-phones cfg/config-map)
        sid (cfg/get-twilio-sid cfg/config-map)
        token (cfg/get-twilio-token cfg/config-map)]
    (for [to-phone to-phones
          :let [alert (sms/sms from-phone to-phone
                       (str "ALERT: currently exporting " 
                            (format "%.2f" export-amount) "W with FIT:" 
                            (format "%.2f" current-fit) " c per kWh"))]]
      (if (and (< 150 export-amount)
               export 
               (< current-fit -0.5))
        (do (sms/with-auth sid token
            (sms/send-sms alert))
          true)
      false))))

(defn exit-on-uncaught-exception []
  (.setUncaughtExceptionHandler (Thread/currentThread)
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ thread exception]
       (.println System/err "Uncaught exception in thread:" (.getName thread))
       (.printStackTrace exception)
       (System/exit 1))))) ; Exit the process with a non-zero status code

(defn -main
  [& args]
  (exit-on-uncaught-exception)
  ;; start a monitor for the main thread -- if it stops responding them restart the whole program
  (let [executor (Executors/newSingleThreadExecutor)]
    (future (monitor-service-log) executor))
  (let [relay-chan (chan)
        _ (relay/activate-relay relay-chan)
        _ (relay/stop-charging relay-chan)]
    (loop [dset nil
           iters 0
           last-read nil
           trade-price 100]
      (let [current-time  (jt/local-date-time)
            current-prices (get-current-spot-price)
            [latest-trade-price, new-read] (get-trade-price-30min last-read)
            read-time  (if new-read current-time last-read)
            forecast-prices (get-forecast-prices)
            timestamp      (ds/dataset-name current-prices)
            current-battery-data  (sel/get-current-data)
            soc (:battery_soc (:items current-battery-data))
            curr-price (get-region-price current-prices vic-region)
            current-usage-price (amber/get-current-interval-price)
            current-fit (amber/get-current-fit)
            should-charge? (charge-battery? current-time
                                            soc
                                            trade-price
                                            forecast-prices)]
        (println (str current-time " "
                      timestamp " "
                      vic-region " "
                      current-usage-price  " "
                      trade-price " "
                      (get-current-period-forecast forecast-prices)
                      read-time " "
                      "soc: " soc))
        (if should-charge?
          (relay/start-charging relay-chan)
          (relay/stop-charging relay-chan))
        (db/ts-add :soc "*" soc)
        (db/ts-add :price-5min "*" curr-price)
        (db/ts-add :curr-usage-price "*" current-usage-price)
        (db/ts-add :current-fit "*" current-fit)
        (db/ts-add :load "*" (:load_w (:items current-battery-data)))
        (db/ts-add :grid "*" (:grid_w (:items current-battery-data)))
        (db/ts-add :battery "*" (:battery_w (:items current-battery-data)))
        (db/ts-add :price-30min "*" (if latest-trade-price latest-trade-price trade-price))
        (db/ts-add :charge-signal "*" (if should-charge? 1 0))
        (db/ts-add :current-forecast "*" (get-current-period-forecast forecast-prices))

        (if-let [send-res (process-alerts current-usage-price current-fit (:items current-battery-data))]
          (println (str "sent alert:" (vector send-res) " prices: " current-usage-price " fit: " current-fit " battery: " (:items current-battery-data))))
        (Thread/sleep poll-interval-ms)
        (recur (ds/concat current-prices dset)
               (inc iters)
               read-time
               (if latest-trade-price latest-trade-price trade-price))))))
