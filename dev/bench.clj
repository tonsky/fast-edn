(ns bench
  (:require
   [charred.api :as charred]
   [cheshire.core :as cheshire]
   [clojure.data.generators :as gen]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.reader.edn :as tools]
   [duti.core :as duti]
   [fast-edn.generators :as cgen]
   [fast-edn.core :as edn2]
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
; ├────────────────┼─────────────┼─────────────┼─────────────┼─────────────┼─────────────┤
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

(defn gen-edn-nested
  ([]
   (gen-edn-nested 8))
  ([depth]
   (if (= depth 0)
     (gen/one-of
       #(gen/geometric 0.0001) ; long
       #(gen/string gen/printable-ascii-char (gen/geometric 0.05))
       gen/double
       gen/boolean)
     (into {} (for [_ (range (+ 1 (rand-int 4)))]
                [(gen/keyword (gen/geometric 0.1))
                 (gen-edn-nested (dec depth))])))))

(comment
  (let [s (with-out-str
            (clojure.pprint/pprint (gen-edn-nested)))]
    (spit (io/file "dev/data/edn_nested_100000.edn") s)
    (count s)))

(defn bench-edn [{pattern :pattern
                  profile :profile
                  :or {pattern #".*\.edn"
                       profile :quick}}]
  (doseq [file (-> (io/file "dev/data")
                 (.listFiles ^FileFilter #(boolean (re-matches pattern (File/.getName %))))
                 (->> (sort-by File/.getName)))
          :let [content (slurp file)]]
    (duti/benching (File/.getName file)
      (doseq [[name parse-fn] [#_["clojure.edn" edn/read-string]
                               #_["tools.reader" tools/read-string]
                               ["fast-edn" edn2/read-string]]]
        (duti/benching name
          (case profile
            :quick (duti/bench (parse-fn content))
            :long  (duti/long-bench (parse-fn content))))))))

(comment
  (bench-edn {:pattern #"edn_basic_\d+\.edn"})
  (bench-edn {:pattern #"edn_basic_10000\.edn"})) ;; 41.7 μs / 41.643379 μs

; ┌────────────────┬─────────────┬─────────────┬─────────────┬─────────────┬─────────────┐
; │ edn_basic      │          10 │         100 │          1K │         10K │        100K │
; ├────────────────┼─────────────┼─────────────┼─────────────┼─────────────┼─────────────┤
; │ clojure.edn    │    0.471 μs │    2.684 μs │   16.808 μs │  200.455 μs │ 1911.017 μs │
; │ tools.reader   │    0.414 μs │    3.351 μs │   28.355 μs │  433.135 μs │ 4109.846 μs │
; │ fast-edn       │    0.222 µs │    0.598 µs │    3.578 µs │   42.295 µs │  404.197 µs │
; └────────────────┴─────────────┴─────────────┴─────────────┴─────────────┴─────────────┘

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

(def ints-1400
  (slurp "dev/data/ints_1400.edn"))

(comment
  (gen-ints 1400)
  (duti.core/bench (fast-edn.core/read-string ints-1400))
  (bench-edn {:pattern #"ints_1400\.edn"}))

; ┌────────────────┬─────────────┐
; │ ints           │        1400 │
; ├────────────────┼─────────────┤
; │ clojure.edn    │  426.189 μs │
; │ fast-edn       │   62.529 μs │
; └────────────────┴─────────────┘

(defn rand-string ^String []
  (str/join (repeatedly (+ 5 (rand-int 95)) #(rand-nth "abcdefghijklmnopqrstuvwxyz!?*-+<>,.: /"))))
     
(defn gen-strings [cnt]
  (with-open [w (io/writer (str "dev/data/strings_" cnt ".edn"))]
    (.write w "[")
    (dotimes [_ cnt]
      (let [s (rand-string)]
        (.write w "\"")
        (.write w s)
        (.write w "\" ")))
    (.write w "]")))

(defn gen-uni-strings [cnt]
  (with-open [w (io/writer (str "dev/data/strings_uni_" cnt ".edn"))]
    (.write w "[")
    (dotimes [_ cnt]
      (.write w "\"")
      (dotimes [_ (+ 5 (rand-int 95))]
        (let [ch ^Character (rand-nth "абвгдеёжзиклмнопрстуфхцчщщъыьэюя!?*-+<>,.: /\n\\\"")]
          (.write w
            (cond
              (= \\ ch)        "\\\\"
              (= \" ch)        "\\\""
              (= \newline ch)  "\\n"
              (< (int ch) 128) (str ch)
              :else            (format "\\u%04x" (int ch))))))
      (.write w "\" "))
    (.write w "]")))

(def strings-1000
  (slurp "dev/data/strings_1000.edn"))

(def strings-250
  (slurp "dev/data/strings_uni_250.edn"))

(comment
  (gen-strings 1000)
  (let [s (slurp "dev/data/strings_1000.edn")]
    (duti.core/bench
      (clojure.edn/read-string s)))

  (let [s1000 (slurp "dev/data/strings_1000.edn")
        s250  (slurp "dev/data/strings_uni_250_safe.edn")]
    (duti.core/bench (fast-edn.core/read-string s1000))
    (duti.core/profile-times 100000 (fast-edn.core/read-string s1000))
    (duti.core/bench (fast-edn.core/read-string s250))
    (duti.core/profile-times 100000 (fast-edn.core/read-string s250))
    (duti.core/bench (fast-edn.core/read-string s1000))
    (duti.core/profile-times 100000 (fast-edn.core/read-string s1000)))


  (do
    (duti.core/bench (fast-edn.core/read-string strings-1000))
    (duti.core/bench (fast-edn.core/read-string strings-250))
    (duti.core/bench (fast-edn.core/read-string strings-1000)))

  (duti.core/bench (clojure.edn/read-string strings-1000))
  (duti.core/bench (clojure.edn/read-string strings-250))

  (duti.core/long-bench (fast-edn.core/read-string strings-250))
  (duti.core/long-bench (fast-edn.core/read-string s1000))

  (fast-edn.core/read-string (slurp "dev/data/strings_1000.edn"))
  (fast-edn.core/read-string (slurp "dev/data/strings_uni_250_safe.edn"))

  (gen-uni-strings 250)
  
  ;; 1.22 μs
  
  (let [s (slurp "dev/data/edn_basic_100.edn")]
    (duti.core/bench
      (fast-edn.core/read-string {:buffer 1024} s)))
  
  (let [s (slurp "dev/data/edn_nested_100000.edn")
        p (fast-edn.core/parser {:buffer 1024, :eof nil})]
    (duti.core/bench
      (fast-edn.core/read-impl p (java.io.StringReader. s)))
    #_(duti.core/profile-for 10000
      (fast-edn.core/read-impl p (java.io.StringReader. s))))

  (bench-edn {:profile :long
              :pattern #"strings_.*"}))

; ┌────────────────┬─────────────┬─────────────┐
; │ strings        │        1000 │     uni_250 │
; ├────────────────┼─────────────┼─────────────┤
; │ clojure.edn    │  670.574 μs │  668.553 μs │
; │ fast-edn       │   47.531 μs │   131.72 μs │
; └────────────────┴─────────────┴─────────────┘

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
  (doseq [n (range 10 100 1000 10000)]
    (gen-keywords 10))
  (bench-edn {:pattern #"edn_basic_100000\.edn"})
  (bench-edn {:pattern #"edn_nested.*\.edn"})
  (bench-edn {:pattern #".*\.edn"}))

;                    53673a3   d715795   38e48ac   7cc4e77
;                    -------   -------   -------   -------   -------
; edn_basic_10         0.115     0.122     0.668     0.289     0.278
; edn_basic_100        0.548     0.528     1.019     0.669     0.617
; edn_basic_1000       3.125     3.178     4.022     2.913     2.958
; edn_basic_10000     40.650    39.409    42.018    40.775    38.801
; edn_basic_100000   397.643   376.625   401.514   398.696   369.616
; edn_nested_100000                                904.766   476.696
; ints_1400           35.974    30.800    30.236    32.013    30.545
; keywords_10          0.641     0.639     1.153     0.741     0.630
; keywords_100         5.720     5.730     6.178     5.462     5.031
; keywords_1000       66.411    62.685    66.299    61.439    57.294
; keywords_10000     820.167   807.494   796.849   781.260   644.352
; strings_1000        43.875    44.212    43.315    41.988    42.111
; strings_uni_250    120.249   117.282   108.563   113.033   102.445

; ┌────────────────┬─────────────┬─────────────┬─────────────┬─────────────┐
; │ keywords       │          10 │         100 │        1000 │       10000 │
; ├────────────────┼─────────────┼─────────────┼─────────────┼─────────────┤
; │ clojure.edn    │             │             │  372.045 μs │             │
; │ fast-edn       │    0.638 µs │    5.733 µs │   65.429 μs │  802.938 µs │
; └────────────────┴─────────────┴─────────────┴─────────────┴─────────────┘
