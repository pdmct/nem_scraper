q(ns pdmct.io.solarmax
  (:require
   [clojure.string :as str]
   [manifold.deferred :as d]
   [manifold.stream :as s]
   [clojure.edn :as edn]
   [aleph.tcp :as tcp]
   [gloss.core :as gloss]
   [gloss.io :as io]
   [clojure.math.numeric-tower :as math]
   [pdmct.util.crc.crc :as crc]))


(def field-map
  "IDC" {:name  "dc_current"
         :net-var "Current_positive_1"
         :type :uint16
         :unit :A
         :scale 0.0001
         :max  6.5535
         :min 0)
  "UL1" {:name "voltage_phase1"
         :net-var "Voltage_1"
         :type :uint16
         :unit :V
         :scale 0.001
         :max 65.5350
         :min 0}
  "TKK" {:name "inverter_temp"
         :net-var "Inverter_operation_temp"
         :type :uint16
         :unit :degC
         :scale 1
         :max 65535
         :min}
  "IL1" {:name "current_phase1"
         :net-var "Current_postive_2"
         :type :uint16
         :scale 0.01
         :max 655.35
         :min 0}
  "SYS" {:name "sys"
         :net-var "SYS"
         }
  "TNF" {:name "generated frequency"
         :net-var "frequency"
         :type :uint16
         :scale 1
         :max 65.535
         :min 0}
  "UDC" {:name "DC voltage"
         :net-var "Voltage_2"
         :type :uint16
         :unit :V
         :scale 0.1
         :max 6553.5
         :min 0}
  "PAC" {:name "power_output"
         :net-var "power"
         :type :uint32
         :unit :W
         :scale 0.5
         :max   1073741823
         :min 0}
  "PRL" {:name "relative_output"
         :net-var "Percent"
         :type :uint16
         :scale 1
         :max 100
         :min 0}
  "KT0" {:name "total yeild (kWh)"
         :net-var "Energy 2"
         :type :uint32
         :scale 1
         :max 2147483647
         :min 0})


(def req  "{01;FB;3E|64:IDC;UL1;TKK;IL1;SYS;TNF;UDC;PAC;PRL;KT0;SYS|0F66}")

(defn calc-crc
  " crc is the sum of all characters (in ascii) from the first address to the last FRS (including the last FRS)"
  [packet]
  (->> packet
       ((fn [s]
          (subs s 1 (inc (str/last-index-of s "|")))))
       seq
       (map int)
       (reduce +)))

(defn hexify
  "convert an int into a HEX value"
  [n]
  (format "%1$04X" n))

(defn unhexify
  "convert a hex value onto an int
  -- number is little endian so 0F66 -> 3942"
  [n]
  (->> (partition 2 n)
       reverse
       (map-indexed (fn [idx base]
                      (* (math/expt 16 idx)
                         (Integer/parseInt (apply str (reverse base)) 16))))
       (reduce +)))


(def inverter-protocol
  (gloss/compile-frame
   (gloss/finite-frame
     (gloss/prefix [(gloss/string :ascii :length 10) :uint16 :suffix ":"] second (fn [n] ["{01;FB;70|" n ":"]))
     [(gloss/string-integer :ascii :delimiters [","])
    ;[(gloss/repeated
    ;   [(gloss/string :ascii :length 4 :suffix "=")
    ;    (gloss/string-integer :ascii :delimiters [";"])])
     :suffix "|1A5F}"])
   pr-str
   edn/read-string))

(defn run-decode
  [frame val]
  (io/decode frame val))


(defn wrap-duplex-stream
  [protocol s]
  (let [out (s/stream)]
    (s/connect
       out
      s)
    (s/splice
      out
      (str s))))


(defn client
  [host port]
  (d/chain (tcp/client {:host host, :port port})
    #(wrap-duplex-stream inverter-protocol %)))
