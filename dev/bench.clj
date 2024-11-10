(ns bench
  (:require
   [better-clojure.edn :as edn2]
   [charred.api :as charred]
   [cheshire.core :as cheshire]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.reader.edn :as tools]
   [duti.core :as duti]
   [jsonista.core :as jsonista])
  (:import
   [java.io File FileFilter]))

(defn bench-json [& _]
  (doseq [file (-> (io/file "dev/data")
                 (.listFiles ^FileFilter #(str/ends-with? (File/.getName %) ".json"))
                 (->> (sort-by File/.getName)))
          :let [content (slurp file)]]
    (duti/benching (File/.getName file)
      (doseq [[name parse-fn] [["cheshire" #(cheshire/parse-string % keyword)]
                               ["jsonista" #(jsonista/read-value % jsonista/keyword-keys-object-mapper)]
                               ["charred"  (charred/parse-json-fn {:key-fn keyword})]]]
        (duti/benching name
          (duti/bench
            (parse-fn content)))))))

(comment
  (bench-json))

; ┌────────────────┬─────────────┬─────────────┬─────────────┬─────────────┬─────────────┐
; │ json           │          10 │         100 │          1K │         10K │        100K │
; │────────────────┼─────────────┼─────────────┼─────────────┼─────────────┼─────────────┤
; │ cheshire       │    0.548 μs │    0.985 μs │    4.288 μs │   38.888 μs │  386.009 μs │
; │ jsonista       │    0.135 μs │    0.669 μs │    3.109 μs │   35.575 μs │  332.501 μs │
; │ charred        │    0.087 μs │    0.427 μs │    2.479 μs │   32.974 μs │  326.401 μs │
; └────────────────┴─────────────┴─────────────┴─────────────┴─────────────┴─────────────┘

(defn gen-edn-basic []
  (doseq [file (-> (io/file "dev/data")
                 (.listFiles ^FileFilter #(str/ends-with? (File/.getName %) ".json")))]
    (let [content  (slurp file)
          edn      (charred/read-json content {:key-fn keyword})
          edn-name (str/replace (File/.getName file) #"json_(\d+).json" "edn_basic_$1.edn")]
      (spit (io/file "dev/data" edn-name) (pr-str edn)))))

(comment
  (gen-edn-basic))

(defn bench-edn [{pattern :pattern
                  :or {pattern #".*\.edn"}}]
  (doseq [file (-> (io/file "dev/data")
                 (.listFiles ^FileFilter #(boolean (re-matches pattern (File/.getName %))))
                 (->> (sort-by File/.getName)))
          :let [content (slurp file)]]
    (duti/benching (File/.getName file)
      (doseq [[name parse-fn] [#_["clojure.edn" edn/read-string]
                               #_["tools.reader" tools/read-string]
                               ["better-clojure" edn2/read-string]]]
        (duti/benching name
          (duti/bench
            (parse-fn content)))))))

(comment
  (bench-edn {:pattern #"edn_basic_\d+\.edn"})
  (bench-edn {:pattern #"edn_basic_10000\.edn"}))

; ┌────────────────┬─────────────┬─────────────┬─────────────┬─────────────┬─────────────┐
; │ edn_basic      │          10 │         100 │          1K │         10K │        100K │
; │────────────────┼─────────────┼─────────────┼─────────────┼─────────────┼─────────────┤
; │ clojure.edn    │    0.471 μs │    2.684 μs │   16.808 μs │  200.455 μs │ 1911.017 μs │
; │ tools.reader   │    0.414 μs │    3.351 μs │   28.355 μs │  433.135 μs │ 4109.846 μs │
; │ better-clojure │    0.211 µs │    0.498 µs │    3.002 µs │   37.830 µs │  359.163 µs │
; └────────────────┴─────────────┴─────────────┴─────────────┴─────────────┴─────────────┘

(comment
  (bench-edn {:pattern #"edn_basic_\d+\.edn"}))

(defn rand-num ^String [max-len]
  (let [len (rand-nth (for [i (range 1 (inc max-len))
                            n (repeat (- max-len i) i)]
                        n))]
    (str/join (concat
                [(rand-nth ["" "" "" "" "" "" "" "" "" "" "-"])
                 (rand-nth "123456789")]
                (repeatedly (- len 1) #(rand-nth "0123456789"))))))

(defn gen-ints [cnt]
  (with-open [w (io/writer (str "dev/data/ints_" cnt ".edn"))]
    (.write w "[")
    (dotimes [_ cnt]
      (.write w (rand-num 18))
      (.write w " "))
    (.write w "]")))

(comment
  (gen-ints 1400)
  (bench-edn {:pattern #"ints_1400\.edn"})
  (let [s (slurp "dev/data/ints_1400.edn")]
    (duti/bench
      (edn2/read-string s))
    (duti/profile-for 30000
      (edn2/read-string s))))

; ┌────────────────┬─────────────┐
; │ ints           │        1400 │
; │────────────────┼─────────────┤
; │ clojure.edn    │  426.189 μs │
; │ better-clojure │   45.737 μs │
; └────────────────┴─────────────┘

(defn gen-keywords [cnt]
  (let [kw-fn (fn [] (str/join (repeatedly (+ 1 (rand-int 10)) #(rand-nth "abcdefghijklmnopqrstuvwxyz!?*-+<>"))))
        kws   (vec (repeatedly 30 kw-fn))]
    (with-open [w (io/writer (str "dev/data/keywords_" cnt ".edn"))]
      (.write w "[")
      (dotimes [_ cnt]
        (if (< (rand) 0.5)
          (do
            (.write w ":")
            (.write w ^String (rand-nth kws))
            (.write w " "))
          (do
            (.write w ":")
            (.write w ^String (rand-nth kws))
            (.write w "/")
            (.write w ^String (rand-nth kws))
            (.write w " "))))
      (.write w "]"))))

(comment
  (gen-keywords 10)
  (bench-edn {:pattern #"keywords_10\.edn"})
  (bench-edn {:pattern #"keywords_100\.edn"})
  (bench-edn {:pattern #"keywords_1000\.edn"})
  (bench-edn {:pattern #"keywords_10000\.edn"})
  (bench-edn {:pattern #"keywords_\d+\.edn"})
  
  (let [s (slurp "dev/data/keywords_10000.edn")]
    (duti/bench
      (edn2/read-string s))
    (duti/profile-for 30000
      (edn2/read-string s))))

; ┌────────────────┬─────────────┬─────────────┬─────────────┬─────────────┐
; │ keywords       │          10 │         100 │        1000 │       10000 │
; │────────────────┼─────────────┼─────────────┼─────────────┼─────────────┤
; │ clojure.edn    │             │             │  372.045 μs │             │
; | no cache       |    0.570 µs |    5.227 µs |   59.297 µs |  764.711 µs |
; │ better-clojure │    0.811 µs │    5.798 µs │   62.301 μs │  672.947 µs │
; └────────────────┴─────────────┴─────────────┴─────────────┴─────────────┘
