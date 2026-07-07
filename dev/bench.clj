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
   [criterium.core :as criterium]
   [fast-edn.generators :as cgen]
   [fast-edn.core :as fast-edn]
   [jsonista.core :as jsonista])
  (:import
   [com.sun.management ThreadMXBean]
   [java.io ByteArrayInputStream File FileFilter]
   [java.lang.management ManagementFactory]
   [java.nio.file Files]))

(def ^:dynamic *bench-stack*
  [])

(defmacro benching
  "Like `testing`, but for bench"
  [name & body]
  `(binding [*bench-stack* (conj *bench-stack* ~name)]
     ~@body))

(defn format-value [{:keys [unit format]
                     :or {format "%.3f"}}
                    value]
  (case unit
    "ns"   (clojure.core/format (str format " ns") (* value 1e9))
    "μs"   (clojure.core/format (str format " μs") (* value 1e6))
    "ms"   (clojure.core/format (str format " ms") (* value 1e3))
    "s"    (clojure.core/format (str format " s")  value)
    #_else (let [[factor unit] (criterium/scale-time value)]
             (criterium/format-value value factor unit))))

(defn bench-impl [opts name body-fn]
  (println (str "Benchmarking " (str/join " → " (conj *bench-stack* name))))
  (let [bean   ^ThreadMXBean (ManagementFactory/getThreadMXBean)
        bytes  (.getCurrentThreadAllocatedBytes bean)
        res    (criterium/benchmark* body-fn opts)
        mean   (format-value opts (first (:mean res)))
        stddev (format-value opts (Math/sqrt (first (:variance res))))
        calls  (:execution-count res)
        bytes  (- (.getCurrentThreadAllocatedBytes bean) bytes)
        alloc  (/ bytes (+ calls (:warmup-executions res)))]
    (println (str "└╴Mean time: " mean ", alloc: " (format "%.2f" (/ alloc 1024.0)) " KB, stddev: " stddev ", calls: " calls))
    mean))

(defn- bench-name [body]
  (let [name (str/join " " body)]
    (if (< (count name) 100) name (str (subs name 0 100) "..."))))

(defmacro quick-bench
  "Runs body in a loop and prints median execution time"
  [& body]
  (let [[opts body] (if (map? (first body))
                      [(first body) (next body)]
                      [nil body])]
    `(bench-impl (merge criterium/*default-quick-bench-opts* ~opts) ~(bench-name body) (fn [] ~@body))))

(defmacro long-bench
  "Runs body in a loop and prints median execution time"
  [& body]
  (let [[opts body] (if (map? (first body))
                      [(first body) (next body)]
                      [nil body])]
    `(bench-impl (merge criterium/*default-benchmark-opts* ~opts) ~(bench-name body) (fn [] ~@body))))

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
                   profile       :quick}
              :as opts}]
  (prn opts)
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
              :let [rows (benching (File/.getName file)
                           (doall
                             (for [parser-name parser-names
                                   :when (has-file? parser-name [file])
                                   :let [parse-fn (all-parsers parser-name)
                                         content  (case parser-name
                                                    "transit+msgpack" (Files/readAllBytes (.toPath file))
                                                    #_else            (slurp file))
                                         time     (benching parser-name
                                                    (case profile
                                                      :quick (quick-bench {:unit "μs"} (parse-fn content))
                                                      :long  (long-bench {:unit "μs"} (parse-fn content))))]]
                               [parser-name (str/replace time " μs" "")])))]
              :when (not (empty? rows))]
          (into {:file (file-name file)} rows))
        (doall)
        (group-by :file)
        (map (fn [[k vs]] (reduce merge vs)))
        (sort-by :file)))))


(comment
  (bench {:files #".*\.json"}))

; ┌──────────────┬──────────┬──────────┬─────────┐
; │        :file │ cheshire │ jsonista │ charred │
; ├──────────────┼──────────┼──────────┼─────────┤
; │     basic_10 │    0.465 │    0.102 │   0.208 │
; │    basic_100 │    0.846 │    0.428 │   0.515 │
; │   basic_1000 │    2.939 │    2.164 │   2.133 │
; │  basic_10000 │   33.179 │   26.616 │  23.806 │
; │ basic_100000 │  310.753 │  245.934 │ 216.516 │
; └──────────────┴──────────┴──────────┴─────────┘

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
    (count s))
  (bench {:files #"nested_\d+\.edn"}))

; ┌───────────────┬─────────────┬──────────────┬──────────┐
; │         :file │ clojure.edn │ tools.reader │ fast-edn │
; ├───────────────┼─────────────┼──────────────┼──────────┤
; │ nested_100000 │    1629.181 │     2262.653 │  271.468 │
; └───────────────┴─────────────┴──────────────┴──────────┘

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
  (bench {:files #"basic_.*\..*"
          :parsers ["transit+json" "transit+msgpack" "clojure.edn" "tools.reader" "fast-edn"]}))

; ┌──────────────┬──────────────┬─────────────────┬─────────────┬──────────────┬──────────┐
; │        :file │ transit+json │ transit+msgpack │ clojure.edn │ tools.reader │ fast-edn │
; ├──────────────┼──────────────┼─────────────────┼─────────────┼──────────────┼──────────┤
; │     basic_10 │        1.716 │           1.524 │       0.318 │        0.307 │    0.228 │
; │    basic_100 │        2.186 │           1.963 │       2.097 │        2.090 │    0.436 │
; │   basic_1000 │        5.238 │           4.394 │      12.275 │       16.728 │    2.161 │
; │  basic_10000 │       35.861 │          27.562 │     132.793 │      203.034 │   28.806 │
; │ basic_100000 │      334.743 │         244.902 │    1258.169 │     1906.155 │  277.867 │
; └──────────────┴──────────────┴─────────────────┴─────────────┴──────────────┴──────────┘

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
  (quick-bench (fast-edn.core/read-string ints-1400))
  (bench {:files #"ints_\d+\.edn"}))

; ┌───────────┬─────────────┬──────────────┬──────────┐
; │     :file │ clojure.edn │ tools.reader │ fast-edn │
; ├───────────┼─────────────┼──────────────┼──────────┤
; │ ints_1400 │     267.837 │      332.093 │   16.859 │
; └───────────┴─────────────┴──────────────┴──────────┘

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

; ┌─────────────────┬─────────────┬──────────────┬──────────┐
; │           :file │ clojure.edn │ tools.reader │ fast-edn │
; ├─────────────────┼─────────────┼──────────────┼──────────┤
; │    strings_1000 │     335.783 │     1346.058 │   28.081 │
; │ strings_uni_250 │     337.933 │      869.899 │   48.366 │
; └─────────────────┴─────────────┴──────────────┴──────────┘

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

; ┌────────────────┬─────────────┬──────────────┬──────────┐
; │          :file │ clojure.edn │ tools.reader │ fast-edn │
; ├────────────────┼─────────────┼──────────────┼──────────┤
; │    keywords_10 │       2.534 │        2.946 │    0.445 │
; │   keywords_100 │      22.237 │       24.912 │    2.991 │
; │  keywords_1000 │     246.033 │      295.482 │   33.188 │
; │ keywords_10000 │    2740.071 │     3429.553 │  391.785 │
; └────────────────┴─────────────┴──────────────┴──────────┘

;; M1 Pro

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


(comment
  (bench {:files #".*\.edn" :parsers ["fast-edn"]}))

;; M4 Air

; ┌─────────────────┬──────────┐
; │           :file │ fast-edn │
; ├─────────────────┼──────────┤
; │        basic_10 │    0.232 │
; │       basic_100 │    0.424 │
; │      basic_1000 │    1.945 │
; │     basic_10000 │   27.839 │
; │    basic_100000 │  269.533 │
; │       ints_1400 │   14.614 │
; │     keywords_10 │    0.447 │
; │    keywords_100 │    2.923 │
; │   keywords_1000 │   32.378 │
; │  keywords_10000 │  395.640 │
; │   nested_100000 │  296.052 │
; │    strings_1000 │   29.740 │
; │ strings_uni_250 │   48.874 │
; └─────────────────┴──────────┘


(comment
  (quick-bench
    (clojure.instant/read-instant-date "2024-12-17T15:54:00.000+01:00"))
  (quick-bench
    (fast-edn.core/read-instant-date "2024-12-17T15:54:00.000+01:00")))

(defn -main [& {:as opts}]
  (let [profile (or (:profile opts) :quick)]
    (bench {:files   #".*\.json"
            :profile profile})

    (bench {:files   #"nested_\d+\.edn"
            :profile profile})

    (bench {:files   #"basic_.*\..*"
            :parsers ["transit+json" "transit+msgpack" "clojure.edn" "tools.reader" "fast-edn"]
            :profile profile})

    (bench {:files #"ints_\d+\.edn"
            :profile profile})

    (bench {:files   #"strings_.*\.edn"
            :profile profile})

    (bench {:files   #"keywords.*\.edn"
            :profile profile})

    (bench {:files   #".*\.edn"
            :parsers ["fast-edn"]
            :profile profile})))

(defn -main-readme [& {:as opts}]
  (bench {:files #".*\.edn"
          :parsers ["clojure.edn" "fast-edn"]})

  (bench {:files #"basic_.*\.(edn|json)"
          :parsers ["cheshire" "jsonista" "charred" "fast-edn"]})

  (bench {:files #"basic_.*\..*"
          :parsers ["clojure.edn" "transit+msgpack" "transit+json" "fast-edn"]}))

(defn -bench-json [_]
  (bench
    {:files   #".*\.json"
     :parsers (keys json-parsers)}))

(defn -bench-edn [_]
  (bench
    {:files   #".*\.edn"
     :parsers (keys edn-parsers)}))

(defn -bench-transit [_]
  (bench
    {:files   #"transit_.*"
     :parsers (concat
                (keys transit-json-parsers)
                (keys transit-msgpack-parsers))}))
