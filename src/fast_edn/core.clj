(ns fast-edn.core
  (:refer-clojure :exclude [merge read read-string])
  (:require
   [clojure.java.io :as io])
  (:import
   [java.io ByteArrayInputStream CharArrayReader File FileReader InputStream InputStreamReader Reader StringReader]
   [fast_edn EdnParser]))

(defn- merge [m1 m2]
  (if (empty? m2)
    m1
    (persistent! (reduce-kv assoc! (transient m1) m2))))

(defn reader ^Reader [source]
  (condp instance? source
    Reader      source
    String      (StringReader. ^String source)
    InputStream (InputStreamReader. source)
    File        (FileReader. ^File source)
    byte/1      (InputStreamReader. (ByteArrayInputStream. source))
    char/1      (CharArrayReader. source)
    
    (throw (ex-info (str "Expected Reader, InputStream, File, byte[], char[] or String, got: " (class source)) {:source source}))))

(defn parser
  "Creates a parser that can be reused in `read-impl` in case you need
   to parse a lot of small EDNs. Not thread-safe"
  ^EdnParser [opts]
  (EdnParser.
    (:buffer opts 1024)
    (merge default-data-readers (:readers opts))
    (:default opts)
    (not (contains? opts :eof))
    (:eof opts)))

(defn read-impl
  "Read method that can reuse EdnParser created in `parser`"
  [^EdnParser parser ^Reader reader]
  (-> parser
    (.withReader reader)
    (.readObject)))

(defn read
  "Reads the next object from source (*in* by default).
   
   Source can be Reader, InputStream, File, byte[], char[], String.

   Reads data in the EDN format: https://github.com/edn-format/edn

   opts is a map that can include the following keys:
  
     :eof      - Value to return on end-of-file. When not supplied, eof throws
                 an exception.
     :readers  - A map of tag symbol -> data-reader fn to be considered
                 before default-data-readers
     :default  - A function of two args, that will, if present and no reader is
                 found for a tag, be called with the tag and the value
     :buffer   - Size of buffer to read from source (1024 by default)"
  ([]
   (read *in*))
  ([source]
   (read {} source))
  ([opts source]
   (read-impl (parser opts) (reader source))))

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
     :buffer   - Size of buffer to read from source (1024 by default)"
  ([s]
   (when s
     (read-impl
       (EdnParser. 1024 default-data-readers nil false nil)
       (StringReader. s))))
  ([opts s]
   (when s
     (read-impl (parser opts) (StringReader. s)))))
