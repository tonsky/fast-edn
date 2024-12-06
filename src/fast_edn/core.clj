(ns fast-edn.core
  (:refer-clojure :exclude [read read-string])
  (:require
   [clojure.java.io :as io])
  (:import
   [java.io Reader StringReader]
   [fast_edn EdnReader]))

(defn read
  "Reads the next object from source (*in* by default).
   
   Source can be anything that clojure.java.io/reader accepts
   (Reader, InputStream, File, URI, URL, Socket, byte[], char[], String).

   Reads data in the EDN format: https://github.com/edn-format/edn

   opts is a map that can include the following keys:
  
     :eof      - Value to return on end-of-file. When not supplied, eof throws
                 an exception.
     :readers  - A map of tag symbol -> data-reader fn to be considered
                 before default-data-readers
     :default  - A function of two args, that will, if present and no reader is
                 found for a tag, be called with the tag and the value
     :buffer   - Size of buffer to read from source (8192 by default)"
  ([]
   (read *in*))
  ([source]
   (read {} source))
  ([opts source]
   (let [parser (EdnReader.
                  (io/reader source)
                  (:buffer opts 8192)
                  (merge default-data-readers (:readers opts))
                  (:default opts)
                  (not (contains? opts :eof))
                  (:eof opts))]
     (.readObject parser))))

(defn read-string
  "Reads one object from the string s. Returns nil when s is nil or empty.

   Reads data in the EDN format: https://github.com/edn-format/edn

   opts is a map that can include the following keys:
  
     :eof      - Value to return on end-of-file. When not supplied, eof throws
                 an exception.
     :readers  - A map of tag symbol -> data-reader fn to be considered
                 before default-data-readers
     :default  - A function of two args, that will, if present and no reader is
                 found for a tag, be called with the tag and the value
     :buffer   - Size of buffer to read from source (8192 by default)"
  ([s]
   (read-string {:eof nil} s))
  ([opts ^String s]
   (when s
     (read opts (StringReader. s)))))
