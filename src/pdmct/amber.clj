(ns pdmct.amber
  (:require
   [clj-http.client :as client]
   [clojure.data.json :as json]
   [diehard.core :as dh]
   [pdmct.util.config :as cfg])
  (:gen-class))


(def amber-api-key (cfg/get-amber-key cfg/config-map))

(def amber-base-url "https://api.amber.com.au/v1")

(def sites "/sites")

(def current-prices-template "/sites/%s/prices/current?next=10&previous=0&resolution=30")

(dh/defretrypolicy policy
  {:retry-on Exception
   :max-retries 2
   :backoff-ms [1000 18000]
   :on-failed-attempt (fn [val ex] (prn (str "failed attempt..." val " : " ex)))})

(defn get-amber-url [url]
  (dh/with-retry {:policy policy}
      (client/get url {:headers {:Authorization (str "Bearer " amber-api-key)}})))


(defn get-amber-site-id []
  (let [url (str amber-base-url sites)]
    (get-amber-url url)))
        
(defn get-site-id []
   (-> (get-amber-site-id)
       :body
       (json/read-str :key-fn keyword)
       first
       (get :id)))


(defn get-current-prices* []
  (let [site-id (get-site-id)
        url (str amber-base-url (format current-prices-template site-id))]
    (get-amber-url url)))

(defn get-current-prices []
  (-> (get-current-prices*)
      :body
      (json/read-str :key-fn keyword)))

(defn get-current-interval []
  (-> (get-current-prices)))