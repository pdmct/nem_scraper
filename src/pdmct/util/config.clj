(ns pdmct.util.config
  (:require [edn-config.core :refer (read-config)]
            [clojure.java.io :as io]))

(def config-map
  (:config 
   (read-config (io/resource "config_local.edn"))))
  
(defn get-alert-phones
  [config]
  (-> config
      :alert-phones))

(defn get-twilio-sid
  [config]
  (-> config
      :twilio-sid))

(defn get-twilio-token
  [config]
  (-> config
      :twilio-token))

(defn get-twilio-phone
  [config]
  (-> config
      :twilio-phone))

(defn get-amber-key
  [config]
  (-> config
      :amber-api-key))

(defn get-host
  [config]
  (-> config
      :select-live-host))

(defn get-serial
  [config]
  (-> config
      :select-link-serial))

(defn get-dashboard
  [config]
  (-> config
      :dashboard-id))

(defn poll-interval
  [config]
  (-> config
      :poll-interval-ms))

(defn redis-uri 
  [config]
  (-> config
      :redis-uri))
