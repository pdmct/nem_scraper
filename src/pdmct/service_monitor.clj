(ns pdmct.service-monitor
  (:require [clojure.tools.logging :as log]
            [clojure.java.shell :refer [sh]]
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
      (do (log/info "journalctl returned non-zero")
          nil))))

(defn restart-service
  [service-name]
  ;; lets' just kill this process and systemd should restart it
  (do
    (log/error "Logs stopped ... exiting")
    (System/exit 1)))


(defn monitor-service-log
  []
  (while true
    (when (or (is-service-active? service-name)
              (is-service-failed? service-name))
      (let [last-log-timestamp (get-last-log-timestamp service-name)
            current-time (* 1000 (System/currentTimeMillis))
            five-minutes-microsec (* 5 60 1000 1000)]
        (when (and last-log-timestamp
                   (> (- current-time last-log-timestamp) 
                      five-minutes-microsec))
          (do (print "restarting service")
              (restart-service service-name)))))
    (println (str "service status active:" (is-service-active? service-name) 
                  " failed:" (is-service-failed? service-name) 
                  " last timestamp:" (get-last-log-timestamp service-name)))
    (Thread/sleep (* 60 1000))))  ;; sleep in milliseconds

(defn monitor-heart-beat [stop-flag heart-beat]
  ;; This function monitors the heart-beat
  (while (not @stop-flag)
    (let [current-time (System/currentTimeMillis)
          elapsed-time (- current-time (:last-updated @heart-beat))]
      (if (> elapsed-time 200000) ; 100 seconds without log output
        (do
          (log/warn "Log output stopped, restarting process.")
              ;; Here you should add the code to restart the process
          (restart-service service-name))
        (do 
          (log/info (str " service status active:" (is-service-active? service-name)
                         " failed:" (is-service-failed? service-name)
                         " elapsed time: " (/ elapsed-time 1000.0)
                         " last timestamp:" (:last-updated @heart-beat)))
          (Thread/sleep 10000)))))) ;; sleep for 30 seconds
