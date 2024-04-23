(ns pdmct.io.ev-charging
  (:require
   [clojure.tools.logging :as log]
   [clojure.core.async
    :as async
    :refer [put! chan timeout <! go]]
   [clojure.data.json :as json]
   [diehard.core :as dh]
   [java-time :as jt]
   [clojure.string :as str]
   [pdmct.util.config :as cfg]
   [clojure.core.async :refer [chan put! <!! go close!]])
  (:import [java.io BufferedReader InputStreamReader])
  (:import [java.lang ProcessBuilder Thread])
  (:gen-class))

; load all of the configuration variables
(def python-exec (cfg/get-python-executable cfg/config-map))
(def ev-charge-control-script (cfg/get-ev-control-script cfg/config-map))
(def tapo-password (cfg/get-tapo-password cfg/config-map))
(def tapo-username (cfg/get-tapo-username cfg/config-map))
(def ev-control-ip (cfg/get-ev-controller-ip cfg/config-map))

(defonce ev-state (atom {:state false
                         :info {}}))

(defn read-stream [input-stream channel]
  (let [reader (BufferedReader. (InputStreamReader. input-stream))]
    (go (loop []
          (let [line (.readLine reader)]
            (if line
              (do (put! channel line)
                  (recur))
              (close! channel)))))))


(defn make-command [interpreter script]
  (vector interpreter script))

(defn run-python-script [cmd args env-vars]
  (let [executable cmd  ; Path to Python in the virtual environment 
        command (apply conj
                 executable (vec args))  ; Construct the command with additional arguments
        process-builder (ProcessBuilder. command)
        _ (doseq [[k v] env-vars]   ; Set environment variables
            (.put (.environment process-builder) k v))
        process (.start process-builder)
        stdout-chan (chan)
        stderr-chan (chan)]
         ;; Read stdout and stderr in separate threads
    (read-stream (.getInputStream process) stdout-chan)
    (read-stream (.getErrorStream process) stderr-chan)
    (let [exit-code (.waitFor process)
          output (concat (<!! stdout-chan) (<!! stderr-chan))]
      (if (zero? exit-code)
        (str (str/join "" (vec output)))
        {:error (str "Script error or non-zero exit status" exit-code " "
                     (str (str/join "" (vec output))))}))))


(declare turn-on)
(declare turn-off)
(declare set-charging-state!)

(defn activate-ev
  "loops forever reading from charging commands"
  [ch]
  (go
    (while true
      (let [cmd (<! ch)]
        (condp = cmd
          :charge (turn-on)
          :stop   (turn-off))))))

(defn turn-on []
  (println (str "turning ev on"))
  (set-charging-state! true))

(defn turn-off []
  (println (str "turning ev off"))
  (set-charging-state! false))

(dh/defretrypolicy policy
  {:retry-on Exception
   :max-retries 2
   :backoff-ms [1000 18000]
   :on-failed-attempt (fn [val ex] (prn (str "failed attempt..." val " : " ex)))})

(defn ev-command [cmd]
  (let [env-vars {"TAPO_USERNAME" tapo-username
                  "TAPO_PASSWORD" tapo-password
                  "IP_ADDRESS" ev-control-ip}
        cmd-args (vector cmd)
        cmd (make-command python-exec ev-charge-control-script)
        _ (println (str "cmd: " cmd))]
    (dh/with-retry {:policy policy}
      (run-python-script cmd cmd-args env-vars))))

(defn start-ev-charging
  []
  (ev-command "--on"))

(defn stop-ev-charging
  []
  (ev-command "--off"))

(defn plus-1min 
  "add 1 min to the timestamp (jf/local-date-time)"
  [timestamp]
  (jt/plus timestamp (jt/minutes 1)))

(defn get-device-info
  " return the info val stored in the ev-state atom from
   the last command
   
   if the state hasn't been updated for 1 min trigger an update and
   save the latest values "
  [current-time]
  (let [update-info? (jt/after? current-time ((fnil plus-1min
                                                    (jt/minus (jt/local-date-time)
                                                              (jt/minutes 10)))
                                              (:last-updated @ev-state)))
        current-state (:state @ev-state)]
    (if update-info?
      (:info (set-charging-state! current-state))
      (:info @ev-state))))

(defn set-charging-state! 
  "initiates the state change based on start-0charing? val
   set this into the ev-state atom and returns the update state"
  [start-charging?]
  (let [curr-state @ev-state
        info (:info curr-state)
        old-state (:state curr-state)]
    (log/info (str "set-charging-state! from " old-state " to " start-charging?))
    (reset! ev-state
            (assoc {:state start-charging?}
                   :last-updated (jt/local-date-time)
                   :info
                   (json/read-str (if start-charging?
                                    (start-ev-charging)
                                    (stop-ev-charging))
                                  :key-fn keyword)))))

; functions to issue commands
(defn start-charging [ch]
  (put! ch :charge))

(defn stop-charging [ch]
  (put! ch :stop))


