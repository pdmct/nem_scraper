(ns pdmct.service-monitor
  (:require [clojure.java.shell :refer [sh]]
            [clojure.data.json :as json]))

(def service-name "nem-scrapper.service")

(defn is-service-active?
  [service-name]
  (let [status (sh "systemctl" "is-active" service-name)]
    (zero? (:exit status))))

(defn is-service-failed?
  [service-name]
  (let [status (sh "systemctl" "is-active" service-name)]
    (= (:exit status) 3)))

(defn get-last-log-timestamp
  [service-name]
  (let [journal-output (sh "journalctl" "-r" "-u" service-name "-o" "json" "--no-pager")]
    (if (zero? (:exit journal-output))
      (let [entries (json/read-str (:out journal-output))]
        (Long/parseLong (get-in entries ["__REALTIME_TIMESTAMP"])))
      (do (println "journalctl returned non-zero")
          nil))))

(defn restart-service
  [service-name]
  ;; lets' just kill this process and systemd should restart it
  (throw (Exception. "Logs stopped ... exiting")))

(defn monitor-service-log
  []
  (while true
    (when (or (is-service-active? service-name)
              (is-service-failed? service-name))
      (let [last-log-timestamp (get-last-log-timestamp service-name)
            current-time (* 1000 (System/currentTimeMillis))
            five-minutes-us (* 5 60 1000 1000)]
        (when (and last-log-timestamp
                   (> (- current-time last-log-timestamp) five-minutes-us))
          (do (print "restarting service")
              (restart-service service-name)))))
    (Thread/sleep (* 60 1000))))


