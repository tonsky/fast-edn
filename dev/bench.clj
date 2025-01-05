(ns bench
  (:require
   [charred.api :as charred]
   [cheshire.core :as cheshire]
   [clojure.data.generators :as gen]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.reader.edn :as tools]
   [cognitect.transit :as transit]
   [duti.core :as duti]
   [fast-edn.generators :as cgen]
   [fast-edn.core :as fast-edn]
   [jsonista.core :as jsonista])
  (:import
   [java.io ByteArrayInputStream File FileFilter]
   [java.nio.file Files]))

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

(def transit-json-parsers
  {"transit+json" (fn [^String s]
                    (let [bytes (.getBytes s)
                          in    (ByteArrayInputStream. bytes)
                          rdr   (transit/reader in :json)]
                      (transit/read rdr)))})

(def transit-msgpack-parsers
  {"transit+msgpack" (fn [^bytes bytes]
                       (let [in  (ByteArrayInputStream. bytes)
                             rdr (transit/reader in :msgpack)]
                         (transit/read rdr)))})

(def all-parsers
  (merge json-parsers edn-parsers transit-json-parsers transit-msgpack-parsers))

(defn file-name [^File f]
  (first (str/split (.getName f) #"\.")))

(defn file-ext [^File f]
  (second (str/split (.getName f) #"\.")))

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
                       (some #(= ext (file-ext %)) files))
        has-file?    (fn [parser-name files]
                       (cond
                         (json-parsers parser-name)            (has-ext? "json" files)
                         (edn-parsers parser-name)             (has-ext? "edn" files)
                         (transit-json-parsers parser-name)    (has-ext? "transit+json" files)
                         (transit-msgpack-parsers parser-name) (has-ext? "transit+msgpack" files)))
        parser-names (filter #(has-file? % files) parser-names)]
    (print-table (cons :file parser-names)
      (->>
        (for [^File file files 
              :let [rows (duti/benching (File/.getName file)
                           (doall
                             (for [parser-name parser-names
                                   :when (has-file? parser-name [file])
                                   :let [parse-fn (all-parsers parser-name)
                                         content  (case parser-name
                                                    "transit+msgpack" (Files/readAllBytes (.toPath file))
                                                    #_else            (slurp file))
                                         time     (duti/benching parser-name
                                                    (case profile
                                                      :quick (duti/bench {:unit "μs"} (parse-fn content))
                                                      :long  (duti/long-bench {:unit "μs"} (parse-fn content))))]]
                               [parser-name (str/replace time " μs" "")])))]
              :when (not (empty? rows))]
          (into {:file (file-name file)} rows))
        (doall)
        (group-by :file)
        (map (fn [[k vs]] (reduce merge vs)))
        (sort-by :file)))))


(comment
  (bench {:files #".*\.json"}))

; ┌──────────────┬────────────┬────────────┬────────────┐
; │        :file │   cheshire │   jsonista │    charred │
; ├──────────────┼────────────┼────────────┼────────────┤
; │     basic_10 │      0.606 │      0.149 │      0.320 │
; │    basic_100 │      1.082 │      0.616 │      0.726 │
; │   basic_1000 │      4.345 │      3.025 │      2.981 │
; │  basic_10000 │     38.877 │     34.893 │     32.376 │
; │ basic_100000 │    383.107 │    336.559 │    310.726 │
; └──────────────┴────────────┴────────────┴────────────┘


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

; ┌──────────────┬─────────────┬──────────────┬────────────┐
; │        :file │ clojure.edn │ tools.reader │   fast-edn │
; ├──────────────┼─────────────┼──────────────┼────────────┤
; │     basic_10 │       0.500 │        0.468 │      0.282 │
; │    basic_100 │       3.011 │        3.520 │      0.561 │
; │   basic_1000 │      19.006 │       30.877 │      2.794 │
; │  basic_10000 │     220.692 │      380.497 │     33.001 │
; │ basic_100000 │    2102.978 │     3626.522 │    316.593 │
; └──────────────┴─────────────┴──────────────┴────────────┘


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

; ┌───────────────┬─────────────┬──────────────┬────────────┐
; │         :file │ clojure.edn │ tools.reader │   fast-edn │
; ├───────────────┼─────────────┼──────────────┼────────────┤
; │ nested_100000 │    2642.004 │     3491.357 │    449.958 │
; └───────────────┴─────────────┴──────────────┴────────────┘


(defn gen-transit []
  (doseq [file (.listFiles (io/file "dev/data"))
          :let [[_ name] (re-matches #"edn_(basic_.*)\.edn" (File/.getName file))]
          :when name
          :let [edn (read-string (slurp file))]]
    (with-open [out (io/output-stream (io/file "dev/data" (str "transit_" name ".transit+json")))]
      (let [w (transit/writer out :json)]
        (transit/write w edn)))
    (with-open [out (io/output-stream (io/file "dev/data" (str "transit_" name ".transit+msgpack")))]
      (let [w (transit/writer out :msgpack)]
        (transit/write w edn)))))

(comment
  (gen-transit)
  (bench {:files #"basic_100?\..*"
          :parsers ["clojure.edn" "fast-edn" "transit+json" "transit+msgpack"]}))

; ┌──────────────┬─────────────┬─────────────────┬──────────────┬────────────┐
; │        :file │ clojure.edn │ transit+msgpack │ transit+json │   fast-edn │
; ├──────────────┼─────────────┼─────────────────┼──────────────┼────────────┤
; │     basic_10 │       0.481 │           2.832 │        1.474 │      0.273 │
; │    basic_100 │       2.799 │           4.242 │        2.297 │      0.527 │
; │   basic_1000 │      17.548 │          14.738 │        6.583 │      2.695 │
; │  basic_10000 │     211.536 │         125.741 │       46.849 │     38.214 │
; │ basic_100000 │    2016.885 │        1167.972 │      447.013 │    363.691 │
; └──────────────┴─────────────┴─────────────────┴──────────────┴────────────┘


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

; ┌───────────┬─────────────┬──────────────┬───────────┐
; │     :file │ clojure.edn │ tools.reader │  fast-edn │
; ├───────────┼─────────────┼──────────────┼───────────┤
; │ ints_1400 │     436.923 │      540.320 │    26.449 │
; └───────────┴─────────────┴──────────────┴───────────┘


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

; ┌─────────────────┬─────────────┬──────────────┬───────────┐
; │           :file │ clojure.edn │ tools.reader │  fast-edn │
; ├─────────────────┼─────────────┼──────────────┼───────────┤
; │    strings_1000 │     653.714 │     2071.018 │    41.417 │
; │ strings_uni_250 │     642.935 │     1285.644 │    99.924 │
; └─────────────────┴─────────────┴──────────────┴───────────┘


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

; ┌────────────────┬─────────────┬──────────────┬────────────┐
; │          :file │ clojure.edn │ tools.reader │   fast-edn │
; ├────────────────┼─────────────┼──────────────┼────────────┤
; │    keywords_10 │       3.996 │        4.925 │      0.638 │
; │   keywords_100 │      35.838 │       41.746 │      4.967 │
; │  keywords_1000 │     376.494 │      443.671 │     55.693 │
; │ keywords_10000 │    4338.960 │     5352.647 │    666.295 │
; └────────────────┴─────────────┴──────────────┴────────────┘


(comment
  (bench {:files #".*\.edn" :parsers ["fast-edn"]}))

; ┌───────────────────┬─────────┬─────────┬─────────┬─────────┬─────────┬─────────┬─────────┬─────────┬─────────┐
; │             :file │ 53673a3 │ d715795 │ 38e48ac │ 7cc4e77 │ a8b46be │ 40e8d6b │ 70e85ec │ b470f72 │         │
; ├───────────────────┼─────────┼─────────┼─────────┼─────────┼─────────┼─────────┼─────────┼─────────┼─────────┤
; │          basic_10 │   0.115 │   0.122 │   0.668 │   0.289 │   0.278 │   0.270 │   0.276 │   0.283 │   0.290 │
; │         basic_100 │   0.548 │   0.528 │   1.019 │   0.669 │   0.617 │   0.529 │   0.540 │   0.556 │   0.594 │
; │        basic_1000 │   3.125 │   3.178 │   4.022 │   2.913 │   2.958 │   2.840 │   2.614 │   2.795 │   2.815 │
; │       basic_10000 │  40.650 │  39.409 │  42.018 │  40.775 │  38.801 │  36.320 │  35.388 │  36.819 │  37.560 │
; │      basic_100000 │ 397.643 │ 376.625 │ 401.514 │ 398.696 │ 369.616 │ 345.574 │ 340.148 │ 352.318 │ 370.045 │
; │         ints_1400 │  35.974 │  30.800 │  30.236 │  32.013 │  30.545 │  28.973 │  31.262 │  26.253 │  33.164 │
; │       keywords_10 │   0.641 │   0.639 │   1.153 │   0.741 │   0.630 │   0.636 │   0.672 │   0.639 │   0.625 │
; │      keywords_100 │   5.720 │   5.730 │   6.178 │   5.462 │   5.031 │   4.976 │   5.037 │   4.954 │   4.769 │
; │     keywords_1000 │  66.411 │  62.685 │  66.299 │  61.439 │  57.294 │  54.434 │  56.311 │  55.976 │  53.943 │
; │    keywords_10000 │ 820.167 │ 807.494 │ 796.849 │ 781.260 │ 644.352 │ 658.832 │ 670.739 │ 667.226 │ 662.099 │
; │     nested_100000 │         │         │         │ 904.766 │ 476.696 │ 444.262 │ 448.725 │ 458.325 │ 503.644 │
; │      strings_1000 │  43.875 │  44.212 │  43.315 │  41.988 │  42.111 │  43.193 │  46.187 │  43.640 │  40.455 │
; │   strings_uni_250 │ 120.249 │ 117.282 │ 108.563 │ 113.033 │ 102.445 │ 100.186 │ 108.555 │  93.131 │ 108.341 │
; └───────────────────┴─────────┴─────────┴─────────┴─────────┴─────────┴─────────┴─────────┴─────────┴─────────┘

┌─────────────────┬──────────┐
│           :file │ fast-edn │
├─────────────────┼──────────┤
│        basic_10 │    0.290 │
│       basic_100 │    0.594 │
│      basic_1000 │    2.815 │
│     basic_10000 │   37.560 │
│    basic_100000 │  370.045 │
│       ints_1400 │   33.164 │
│     keywords_10 │    0.625 │
│    keywords_100 │    4.769 │
│   keywords_1000 │   53.943 │
│  keywords_10000 │  662.099 │
│   nested_100000 │  503.644 │
│    strings_1000 │   40.455 │
│ strings_uni_250 │  108.341 │
└─────────────────┴──────────┘


(comment
  (duti.core/bench
    (clojure.instant/read-instant-date "2024-12-17T15:54:00.000+01:00"))
  (duti.core/bench
    (fast-edn.core/read-instant-date "2024-12-17T15:54:00.000+01:00")))
