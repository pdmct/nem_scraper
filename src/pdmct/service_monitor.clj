(ns pdmct.service-monitor
  (:require [clojure.java.shell :refer [sh]]
            [clojure.data.json :as json]))

(def service-name "nem-scraper.service")

(defn is-service-active?
  [service-name]
  (let [status (sh "systemctl" "is-active" service-name)]
    (zero? (:exit status))))

(defn get-last-log-timestamp
  [service-name]
  (let [journal-output (sh "journalctl" "--unit" service-name "--output" "json" "--no-pager")]
    (if (zero? (:exit journal-output))
      (let [entries (json/read-str (:out journal-output))
            last-entry (last entries)]
        (get-in last-entry ["__REALTIME_TIMESTAMP"]))
      nil)))

(defn restart-service
  [service-name]
  (let [status (sh "systemctl" "restart" service-name)]
    (if (zero? (:exit status))
      (println (str "Service '" service-name "' restarted."))
      (println (str "Failed to restart service '" service-name "': " (:err status))))))

(defn monitor-service-log
  []
  (while true
    (when (is-service-active? service-name)
      (let [last-log-timestamp (get-last-log-timestamp service-name)
            current-time (System/currentTimeMillis)
            five-minutes-ms (* 5 60 1000)]
        (when (and last-log-timestamp
                 (> (- current-time last-log-timestamp) five-minutes-ms))
          (restart-service service-name))))
    (Thread/sleep (* 60 1000))))

