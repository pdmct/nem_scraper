(ns pdmct.util.config
  (:require [nomad.config :as n]
            [clojure.java.io :as io]))

(def config-map
  {:dashboard-id <insert your id here>
   :select-link-serial "<insert your serial here>"
   :select-link-host "<ip addr of host>"
   :poll-interval-ms 60000})

(defn get-host
  [config]
  (-> config
      :select-link-host))

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
