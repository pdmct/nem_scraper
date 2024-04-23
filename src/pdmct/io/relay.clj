(ns pdmct.io.relay
  (:require [clojure.tools.logging :as log]
            [clojure.core.async
             :as async
             :refer [put! chan timeout <! go]]
   [helins.linux.gpio :as gpio]))



(defonce relay-state (atom false))

(def relay-pin 21)

(defn start-charging [ch]
  (put! ch :charge))

(defn stop-charging [ch]
  (put! ch :stop))


(defn current-state
  []
  @relay-state)

(declare turn-on)
(declare turn-off)

(defn activate-relay
  "loops forever reading from charging commands"
  [ch]
  (go
    (while true
      (let [state (<! ch)]
        (condp = state
          :charge (turn-on)
          :stop   (turn-off))))))

(defn set-relay-state! [state]
  (let [old-state @relay-state]
    (if-let [new-state (compare-and-set! relay-state (not state) state)]
      (log/info (str "set-relay-state! from " old-state " to " state)))))

(defn _set-relay-state! [state]
  (if-let [new-state (compare-and-set! relay-state (not state) state)]
    (with-open [device (gpio/device "dev/gpiochip0")
                relay-handle (gpio/handle device
                                          {relay-pin {:gpio/state false
                                                      :gpio/tag :relay}}
                                          {:gpio/direction :output})]
      (let [buffer (gpio/buffer relay-handle)]
        (gpio/write relay-handle
                    (gpio/set-line+ buffer
                                    {:relay state}))))))

(defn turn-on []
  (println (str "turning relay on"))
  (set-relay-state! true))

(defn turn-off []
  (println (str "turning relay off"))
  (set-relay-state! false))
