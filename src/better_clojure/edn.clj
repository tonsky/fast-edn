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

(require
  '[clojure.test :refer [deftest is are testing]])

(deftest reader-test
  (are [s v] (= v (read-string s))
    ""    nil
    " "   nil
    ","   nil
    "123" 123
    "0"   0
    "-1"  -1
    "+1"  +1
    "-0"  0
    
    "{}" {}
    "{\"key\" \"value\"}" {"key" "value"}
    "{1 2, 3 4}" {1 2, 3 4}
    
    "()" ()
    "(1 2 3)" '(1 2 3)
    "(,\n1,2\n, \n3\n   )" '(1 2 3)
  
    "[]" []
    "[1 2 3]" '[1 2 3]
    "[,\n1,2\n, \n3\n   ]" '[1 2 3]
    
    "#{}" #{}
    "#{1 2 3}" '#{1 2 3}
    "#{,\n1,2\n, \n3\n   }" '#{1 2 3})
  
  (are [s c m] (thrown-with-msg? c m (read-string s))
    "{"          RuntimeException         #"EOF while reading map"
    "{1}"        RuntimeException         #"Map literal must contain an even number of forms"
    "{1 2, 3}"   RuntimeException         #"Map literal must contain an even number of forms"
    "}"          Exception                #"Parse error - Unexpected character - \}"
    "{1 2, 1 3}" IllegalArgumentException #"Duplicate key: 1"
    "("          RuntimeException         #"EOF while reading list"
    "(1 2"       RuntimeException         #"EOF while reading list"
    "["          RuntimeException         #"EOF while reading vector"
    "[1 2"       RuntimeException         #"EOF while reading vector"
    "#{"         RuntimeException         #"EOF while reading set"
    "#{1 2"      RuntimeException         #"EOF while reading set"
    "#{1 2 1}"   IllegalArgumentException #"Duplicate key: 1"
    "#"          RuntimeException         #"EOF while reading dispatch macro")
  )

(clojure.test/test-ns *ns*)

(comment
  (read-string "123")
  
  (clojure.edn/read-string "#")
  )