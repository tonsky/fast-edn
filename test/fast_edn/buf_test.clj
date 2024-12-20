(ns fast-edn.buf-test
  (:refer-clojure :exclude [rand-int])
  (:require
   [clojure.edn]
   [clojure.data.generators :as gen]
   [clojure.string :as str]
   [clojure.test :refer [deftest is are testing]]
   [clojure.test.generative :refer [defspec]]
   [clojure.test.generative.runner :as runner]
   [fast-edn.core :as edn]
   [fast-edn.generators :as cgen])
  (:import
   [java.io FilterReader StringReader]))

(defn rand-int
  ([n]
   (rand-int 0 n))
  ([min max]
   (+ min (clojure.core/rand-int (- max min)))))

(defn rand-coll []
  (let [coll (distinct
               (repeatedly (* (rand-int 0 10) 2) #(gen/one-of cgen/ednable-scalar)))]
    (if (odd? (count coll))
      (recur)
      coll)))

(defn stringify [coll]
  (let [[open close] (rand-nth [["[" "]"]
                                ["(" ")"]
                                ["{" "}"]
                                ["#{" "}"]])]
    (str/join
      (interleave
        (concat ["" open] (map pr-str coll) [close])
        (repeatedly #(str/join (repeat (rand-int 1 25) " ")))))))

(defn rand-str []
  (stringify (rand-coll)))

(defn random-reader [^String s]
  (let [in (StringReader. s)]
    (proxy [FilterReader] [in]
      (read [buf off len]
        (.read in buf off (+ 1 (rand-int len)))))))

(defspec reader-should-not-break
  (fn [str]
    (edn/read-once {:buffer 10} (random-reader str)))
  [^{:tag fast-edn.buf-test/rand-str} str]
  (when-not (= (clojure.edn/read-string str) %)
    (throw (ex-info "Value cannot roundtrip, see ex-data" {:printed str :read %}))))

(def cpus
  8)

(def time-ms
  10000)

(deftest buf-test
  (runner/run cpus time-ms #'reader-should-not-break))
