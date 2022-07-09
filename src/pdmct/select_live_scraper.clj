(ns pdmct.select-live-scraper
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [net.cgrand.enlive-html :as html]
            [clj-http.client :as client]
            [tech.v3.dataset :as ds]
            [diehard.core :as dh]
            [pdmct.util.config :as cfg]))


(def select-live-url (str "https://select.live/dashboard/hfdata/" cfg/get-dashboard))

(def ^:dynamic *local-device-url* (str "https://" (cfg/get-host cfg/config-map) "/cgi-bin/solarmonweb/devices/" (cfg/get-serial cfg/config-map) "/point"))

(defn get-current-data
  " note; this only works on local network "
  []
  (dh/with-retry {:retry-on Exception
                  :max-retries 5
                  :delay-ms 10000
		  :on-retry (fn [val ex] (prn (str "retrying..." val " : "ex)))
		  :on-failed-attempt (fn [val ex] (prn (str "failed attempt..." val " : "ex)))}
    (json/read-str (:body (client/get *local-device-url* {:insecure? true})) :key-fn keyword)))
