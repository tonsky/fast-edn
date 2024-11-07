(ns bench
  (:require
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
; │                │          10 │         100 │          1K │         10K │        100K │
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

(defn bench-edn [{prefix :prefix
                  :or {prefix ".*"}}]
  (doseq [file (-> (io/file "dev/data")
                 (.listFiles ^FileFilter #(boolean (re-matches (re-pattern (str prefix "_\\d+\\.edn")) (File/.getName %))))
                 (->> (sort-by File/.getName)))
          :let [content (slurp file)]]
    (duti/benching (File/.getName file)
      (doseq [[name parse-fn] [["clojure.edn" edn/read-string]
                               ["tools.reader" tools/read-string]]]
        (duti/benching name
          (duti/bench
            (parse-fn content)))))))

(comment
  (bench-edn {:prefix "edn_basic"}))

; ┌────────────────┬─────────────┬─────────────┬─────────────┬─────────────┬─────────────┐
; │                │          10 │         100 │          1K │         10K │        100K │
; │────────────────┼─────────────┼─────────────┼─────────────┼─────────────┼─────────────┤
; │ clojure.edn    │    0.471 μs │    2.684 μs │   16.808 μs │  200.455 μs │ 1911.017 μs │
; │ tools.reader   │    0.414 μs │    3.351 μs │   28.355 μs │  433.135 μs │ 4109.846 μs │
; │ better-clojure │             │             │             │             │             │
; └────────────────┴─────────────┴─────────────┴─────────────┴─────────────┴─────────────┘

(comment
  (bench-edn {:prefix "edn_full"}))
