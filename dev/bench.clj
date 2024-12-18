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
   [fast-edn.core :as fast-edn]
   [jsonista.core :as jsonista])
  (:import
   [java.io File FileFilter]))

(defn print-table [ks rows]
  (when (seq rows)
    (let [widths (map
                   (fn [k]
                     (apply max (count (str k)) (map #(count (str (get % k))) rows)))
                   ks)
          spacers (map #(apply str (repeat % "─")) widths)
          fmts (map #(str "%" % "s") widths)
          fmt-row (fn [leader divider trailer row]
                    (str leader
                      (apply str (interpose divider
                                   (for [[col fmt] (map vector (map #(get row %) ks) fmts)]
                                     (format fmt (str col)))))
                      trailer))]
      (println (fmt-row "┌─" "─┬─" "─┐" (zipmap ks spacers)))
      (println (fmt-row "│ " " │ " " │" (zipmap ks ks)))
      (println (fmt-row "├─" "─┼─" "─┤" (zipmap ks spacers)))
      (doseq [row rows]
        (println (fmt-row "│ " " │ " " │" row)))
      (println (fmt-row "└─" "─┴─" "─┘" (zipmap ks spacers)))
      (println))))

(def json-parsers
  {"cheshire" #(cheshire/parse-string % keyword)
   "jsonista" #(jsonista/read-value % jsonista/keyword-keys-object-mapper)
   "charred"  #((charred/parse-json-fn {:key-fn keyword}) %)})

(def edn-parsers
  {"clojure.edn"  edn/read-string
   "tools.reader" tools/read-string
   "fast-edn"     fast-edn/read-string})

(def all-parsers
  (merge json-parsers edn-parsers))

(defn bench [{files-pattern :files
              parser-names  :parsers
              profile       :profile
              :or {files-pattern #".*\.(json|edn)"
                   parser-names  (keys all-parsers)
                   profile       :quick}}]
  (let [files        (-> (io/file "dev/data")
                       (.listFiles ^FileFilter #(boolean (re-matches files-pattern (File/.getName %))))
                       (->> (sort-by File/.getName)))
        has-ext?     (fn [ext files]
                       (some #(str/ends-with? (File/.getName %) (str "." ext)) files))
        has-file?    (fn [parser-name files]
                       (cond
                         (json-parsers parser-name) (has-ext? "json" files)
                         (edn-parsers parser-name)  (has-ext? "edn" files)))
        parser-names (filter #(has-file? % files) parser-names)]
    (print-table (cons "file" parser-names)
      (doall
        (for [file files 
              :let [content (slurp file)
                    rows    (duti/benching (File/.getName file)
                              (doall
                                (for [parser-name parser-names
                                      :when (has-file? parser-name [file])
                                      :let [parse-fn (all-parsers parser-name)]]
                                  (duti/benching parser-name
                                    [parser-name
                                     (case profile
                                       :quick (duti/bench {:unit "μs"} (parse-fn content))
                                       :long  (duti/long-bench {:unit "μs"} (parse-fn content)))]))))]
              :when (not (empty? rows))]
          (into {"file" (File/.getName file)} rows))))))


(comment
  (bench {:files #".*\.json"}))

; ┌──────────────────┬────────────┬────────────┬────────────┐
; │             file │   cheshire │   jsonista │    charred │
; ├──────────────────┼────────────┼────────────┼────────────┤
; │     json_10.json │   0.606 μs │   0.149 μs │   0.320 μs │
; │    json_100.json │   1.082 μs │   0.616 μs │   0.726 μs │
; │   json_1000.json │   4.345 μs │   3.025 μs │   2.981 μs │
; │  json_10000.json │  38.877 μs │  34.893 μs │  32.376 μs │
; │ json_100000.json │ 383.107 μs │ 336.559 μs │ 310.726 μs │
; └──────────────────┴────────────┴────────────┴────────────┘


(defn gen-edn-basic []
  (doseq [file (-> (io/file "dev/data")
                 (.listFiles ^FileFilter #(str/ends-with? (File/.getName %) ".json")))]
    (let [content  (slurp file)
          edn      (charred/read-json content {:key-fn keyword})
          edn-name (str/replace (File/.getName file) #"json_(\d+).json" "edn_basic_$1.edn")]
      (spit (io/file "dev/data" edn-name) (pr-str edn)))))

(comment
  (gen-edn-basic))

(comment
  (bench {:files #"edn_basic_\d+\.edn"}))

; ┌──────────────────────┬─────────────┬──────────────┬────────────┐
; │                 file │ clojure.edn │ tools.reader │   fast-edn │
; ├──────────────────────┼─────────────┼──────────────┼────────────┤
; │     edn_basic_10.edn │    0.500 μs │     0.468 μs │   0.282 μs │
; │    edn_basic_100.edn │    3.011 μs │     3.520 μs │   0.561 μs │
; │   edn_basic_1000.edn │   19.006 μs │    30.877 μs │   2.794 μs │
; │  edn_basic_10000.edn │  220.692 μs │   380.497 μs │  33.001 μs │
; │ edn_basic_100000.edn │ 2102.978 μs │  3626.522 μs │ 316.593 μs │
; └──────────────────────┴─────────────┴──────────────┴────────────┘


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
    (count s))
  (bench {:files #"edn_nested_\d+\.edn"}))

; ┌───────────────────────┬─────────────┬──────────────┬────────────┐
; │                  file │ clojure.edn │ tools.reader │   fast-edn │
; ├───────────────────────┼─────────────┼──────────────┼────────────┤
; │ edn_nested_100000.edn │ 2642.004 μs │  3491.357 μs │ 449.958 μs │
; └───────────────────────┴─────────────┴──────────────┴────────────┘


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
  (bench {:files #"ints_\d+\.edn"}))

; ┌───────────────┬─────────────┬──────────────┬───────────┐
; │          file │ clojure.edn │ tools.reader │  fast-edn │
; ├───────────────┼─────────────┼──────────────┼───────────┤
; │ ints_1400.edn │  436.923 μs │   540.320 μs │ 26.449 μs │
; └───────────────┴─────────────┴──────────────┴───────────┘


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
  (gen-uni-strings 250)
  (bench {:files #"strings_.*\.edn"}))

; ┌─────────────────────┬─────────────┬──────────────┬───────────┐
; │                file │ clojure.edn │ tools.reader │  fast-edn │
; ├─────────────────────┼─────────────┼──────────────┼───────────┤
; │    strings_1000.edn │  653.714 μs │  2071.018 μs │ 41.417 μs │
; │ strings_uni_250.edn │  642.935 μs │  1285.644 μs │ 99.924 μs │
; └─────────────────────┴─────────────┴──────────────┴───────────┘


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
    (gen-keywords n))
  (bench {:files #"keywords.*\.edn"}))

; ┌────────────────────┬─────────────┬──────────────┬────────────┐
; │               file │ clojure.edn │ tools.reader │   fast-edn │
; ├────────────────────┼─────────────┼──────────────┼────────────┤
; │    keywords_10.edn │    3.996 μs │     4.925 μs │   0.638 μs │
; │   keywords_100.edn │   35.838 μs │    41.746 μs │   4.967 μs │
; │  keywords_1000.edn │  376.494 μs │   443.671 μs │  55.693 μs │
; │ keywords_10000.edn │ 4338.960 μs │  5352.647 μs │ 666.295 μs │
; └────────────────────┴─────────────┴──────────────┴────────────┘


(comment
  (bench {:files #".*\.edn" :parsers ["fast-edn"]}))

; ┌───────────────────────┬─────────┬─────────┬─────────┬─────────┬─────────┬─────────┬─────────┬─────────┐
; │                  file │ 53673a3 │ d715795 │ 38e48ac │ 7cc4e77 │ a8b46be │ 40e8d6b │ 70e85ec │         │
; ├───────────────────────┼─────────┼─────────┼─────────┼─────────┼─────────┼─────────┼─────────┼─────────┤
; │      edn_basic_10.edn │   0.115 │   0.122 │   0.668 │   0.289 │   0.278 │   0.270 │   0.276 │   0.283 │
; │     edn_basic_100.edn │   0.548 │   0.528 │   1.019 │   0.669 │   0.617 │   0.529 │   0.540 │   0.556 │
; │    edn_basic_1000.edn │   3.125 │   3.178 │   4.022 │   2.913 │   2.958 │   2.840 │   2.614 │   2.795 │
; │   edn_basic_10000.edn │  40.650 │  39.409 │  42.018 │  40.775 │  38.801 │  36.320 │  35.388 │  36.819 │
; │  edn_basic_100000.edn │ 397.643 │ 376.625 │ 401.514 │ 398.696 │ 369.616 │ 345.574 │ 340.148 │ 352.318 │
; │ edn_nested_100000.edn │         │         │         │ 904.766 │ 476.696 │ 444.262 │ 448.725 │ 458.325 │
; │         ints_1400.edn │  35.974 │  30.800 │  30.236 │  32.013 │  30.545 │  28.973 │  31.262 │  26.253 │
; │       keywords_10.edn │   0.641 │   0.639 │   1.153 │   0.741 │   0.630 │   0.636 │   0.672 │   0.639 │
; │      keywords_100.edn │   5.720 │   5.730 │   6.178 │   5.462 │   5.031 │   4.976 │   5.037 │   4.954 │
; │     keywords_1000.edn │  66.411 │  62.685 │  66.299 │  61.439 │  57.294 │  54.434 │  56.311 │  55.976 │
; │    keywords_10000.edn │ 820.167 │ 807.494 │ 796.849 │ 781.260 │ 644.352 │ 658.832 │ 670.739 │ 667.226 │
; │      strings_1000.edn │  43.875 │  44.212 │  43.315 │  41.988 │  42.111 │  43.193 │  46.187 │  43.640 │
; │   strings_uni_250.edn │ 120.249 │ 117.282 │ 108.563 │ 113.033 │ 102.445 │ 100.186 │ 108.555 │  93.131 │
; └───────────────────────┴─────────┴─────────┴─────────┴─────────┴─────────┴─────────┴─────────┴─────────┘


(comment
  (duti.core/bench
    (clojure.instant/read-instant-date "2024-12-17T15:54:00.000+01:00"))
  (duti.core/bench
    (fast-edn.core/read-instant-date "2024-12-17T15:54:00.000+01:00")))
