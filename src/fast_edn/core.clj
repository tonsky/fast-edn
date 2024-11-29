(ns fast-edn.core
  (:refer-clojure :exclude [read read-string])
  (:import
   [fast_edn CharReader EDNReader]))

#_(defn read
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
  (EDNReader. (merge default-data-readers (:readers opts)) (:default opts) (not (contains? opts :eof)) (:eof opts)))

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
       (.beginParse rdr (CharReader. s (:buffer opts 1024)))
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
    "123N" 123N
    "10000000000000000000" 10000000000000000000
    
    "0xAB" 0xAB
    "0XAB" 0XAB
    "010"  010
    "+0xAB" +0xAB
    "+0XAB" +0XAB
    "+010"  +010
    "-0xAB" -0xAB
    "-0XAB" -0XAB
    "-010"  -010
    
    "2r1111" 2r1111
    "36rabcxyz" 36rabcxyz

    
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

#_(clojure.test/test-ns *ns*)

(comment
  36r123XYZ
  22/7
  \u0020
  (read-string "\">\\123<\"")
  (clojure.core/read-string "\"\\/\"")
  (read-string (slurp "dev/data/keywords_1000.edn"))
  (.-numerator (read-string "1/-2"))
  (Character/digit \8 8)
  (read-string "#inst\"1985\"")
  (edn/read-string "\"\\090\"")
  (read-string "1/2/3")
  (read-string "#c/d 1")
  (Long/parseLong "FFFFFFFFFFFFFFFFFF" 16)
  
  (symbol "ns" "1a")
  
  (count (Long/toString Long/MAX_VALUE 16))
  (count (Long/toString Long/MAX_VALUE 8))
  (read-string "{:a ; }\n :b}")
  (read-string "[11    ]")
  (read-string "123N")
  (read-string "[123 ]")
  (type 123N)
  (java.math.BigInteger. "-123")
  (type (clojure.edn/read-string "-a"))
  (clojure.edn/read-string "2r1111")
  (clojure.edn/read-string "36rabcxyz")
  (type (clojure.edn/read-string "1m"))
  (type (clojure.edn/read-string "36r1M"))
  )