(ns pdmct.util.redis
  (:require [taoensso.carmine :as car :refer (wcar)]
            [diehard.core :as dh]))



(def key-map
  {:solar "solar_gen_power_w"
   :soc   "battery_soc"
   :soc-avg  "battery_soc_avg_5min"
   :price-5min "vic_5min_price"
   :price-30min "vic_30min_price"
   :load "inv_load_w"
   :grid "inv_grid_w"
   :battery "inv_battery_w"
   :charge-signal "charge_signal"
   :relay-state "relay_state"
   :current-forecast "vic_30min_forecast"})


;; the following should come from config
(def server-conn {:pool {}
                  :spec {:uri "<redis uri>"}})

(defmacro wcar* [& body] `(car/wcar server-conn ~@body))

(defn ts-add
  " add a val to a key in redis "
  [keyw ts val]
  (let [the-key (if (keyword? keyw)
                  (get key-map keyw)
                  keyw)]
    (dh/with-retry  {:retry-on Exception
                     :max-retries 3
                     :delay-ms 5000}
      (wcar*
       (car/redis-call [:ts.add the-key ts val])))))

(defn ts-info
  "get info for a key"
  [keyw]
  (let [the-key (if (keyword? keyw)
                  (get key-map keyw)
                  keyw)]
    (reduce #(assoc %1
                    (keyword (first %2))
                    (second %2))
            {}
            (partition 2
                       (wcar*
                        (car/redis-call [:ts.info the-key]))))))

(defn ts-query
  "run a range query on a key"
  [keyw]
  (let [the-key (if (keyword? keyw) (get key-map keyw) keyw)
        start-ms "-"
        end-ms "+"]
    (wcar* (car/redis-call [:ts.range the-key start-ms end-ms]))))
