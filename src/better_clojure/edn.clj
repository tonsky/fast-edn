(ns better-clojure.edn
  (:refer-clojure :exclude [read read-string])
  (:require
   [clojure.edn :as edn])
  (:import
   [better_clojure.edn CharReader EDNReader]))

(defn read
  "Reads the next object from stream. stream defaults to the
  current value of *in*.

  Reads data in the edn format (subset of Clojure data):
  http://edn-format.org

  opts is a map that can include the following keys:
  :eof - value to return on end-of-file. When not supplied, eof throws an exception.
  :readers  - a map of tag symbols to data-reader functions to be considered before default-data-readers.
              When not supplied, only the default-data-readers will be used.
  :default - A function of two args, that will, if present and no reader is found for a tag,
             be called with the tag and the value."
  ([]
   (read *in*))
  ([stream]
   (read {} stream))
  ([opts stream]
   (edn/read stream opts)))

(defn reader ^EDNReader [opts]
  (EDNReader. (not (contains? opts :eof)) (:eof opts)))

(defn read-string
  "Reads one object from the string s. Returns nil when s is nil or empty.

  Reads data in the edn format (subset of Clojure data):
  http://edn-format.org

  opts is a map as per clojure.edn/read"
  ([s]
   (read-string {:eof nil} s))
  ([opts ^String s]
   (when s
     (let [rdr (reader opts)]
       (.beginParse rdr (CharReader. s))
       (.readObject rdr)))))
