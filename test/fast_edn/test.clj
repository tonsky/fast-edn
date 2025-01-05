(ns fast-edn.test
  (:require
   [fast-edn.core :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [is are deftest testing]]))

(deftest read-next-test
  (let [reader (java.io.StringReader. "{:a 1}{:b 2}")
        parser (edn/parser reader)]
    (is (= {:a 1} (edn/read-next parser)))
    (is (= {:b 2} (edn/read-next parser)))
    (is (thrown-with-msg? RuntimeException #"EOF" (edn/read-next parser))))
  
  (let [reader (java.io.StringReader. "{:a 1}{:b 2}")
        parser (edn/parser {:eof ::eof} reader)]
    (is (= {:a 1} (edn/read-next parser)))
    (is (= {:b 2} (edn/read-next parser)))
    (is (= ::eof (edn/read-next parser)))))
  
(deftest set-reader-test
  (let [reader (java.io.StringReader. "{:a 1}")
        parser (edn/parser reader)
        _      (is (= {:a 1} (edn/read-next parser)))
        reader (java.io.StringReader. "{:b 2}")
        _      (edn/set-reader parser reader)
        _      (is (= {:b 2} (edn/read-next parser)))]))
  
(deftest read-once-test
  (let [reader (java.io.StringReader. "{:a 1}{:b 2}")]
    (is (= {:a 1} (edn/read-once reader))))
  
  (let [source (io/input-stream "test/fast_edn/edn.edn")]
    (is (= {:a 1} (edn/read-once source))))
  
  (let [source (io/file "test/fast_edn/edn.edn")]
    (is (= {:a 1} (edn/read-once source))))
  
  (let [source (.getBytes "{:a 1}")]
    (is (= {:a 1} (edn/read-once source))))
  
  (let [source (.toCharArray "{:a 1}")]
    (is (= {:a 1} (edn/read-once source))))
  
  (let [source "{:a 1}"]
    (is (= {:a 1} (edn/read-once source)))))
  
(deftest basics-test
  (are [s v] (= v (edn/read-string s))
    ""    nil
    " "   nil
    ","   nil

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

  (are [s c m] (thrown-with-msg? c m (edn/read-string s))
    "{"          Exception #"EOF while reading map"
    "{1}"        Exception #"Map literal must contain an even number of forms"
    "{1 2, 3}"   Exception #"Map literal must contain an even number of forms"
    "}"          Exception #"Unexpected character: \}.*"
    "{1 2, 1 3}" Exception #"Duplicate key: 1"
    "("          Exception #"EOF while reading list"
    "(1 2"       Exception #"EOF while reading list"
    "["          Exception #"EOF while reading vector"
    "[1 2"       Exception #"EOF while reading vector"
    "#{"         Exception #"EOF while reading set"
    "#{1 2"      Exception #"EOF while reading set"
    "#{1 2 1}"   Exception #"Duplicate key: 1"
    "#"          Exception #"EOF while reading dispatch macro"))


(deftest tokens-test
  (are [s e] (= e (edn/read-string s))
    "nil"   nil
    "true"  true
    "false" false))


(deftest strings-test
  (are [s e] (= e (edn/read-string s))
    "\"str\"" "str"
    "\" \\t \\r \\n \\\\ \\\" \"" " \t \r \n \\ \" "
    "\", ; \\n #\"" ", ; \n #"
    
    ;; not in spec
    "\" \\b \\f \\ua66e \\uA66E \\0 \\12 \\176 \\377 \\1222 \\newline \"" " \b \f \ua66e \uA66E \0 \12 \176 \377 R2 \newline "
    
    ;; extras -- don't work in clojure.edn
    "\"\\18\"" (str \o1 \8)
    )

  (are [s] (thrown? Exception (edn/read-string s))
    "\"\\\""
    "\"\\ \""
    "\"\\x\""
    "\"\\u\""
    "\"\\u1\""
    "\"\\u12\""
    "\"\\u123\""
    "\"\\400\""
    "\"\\80\""
    "\"\\1"))


(deftest string-buf-test
  (doseq [i (range 0 10)
          :let [prefix (str/join (repeat i "."))]]
    (testing (str "prefix \"" prefix "\"")
      (are [s e] (= (str prefix e)
                   (let [s' (str "\"" prefix s "\"")]
                     (edn/read-string {:buffer 10} s')))
        "str" "str"
        "\\n" "\n"
        "\\u0040" "@"))))


(deftest chars-test
  (are [s e] (= e (edn/read-string s))
    "\\c"         \c
    "\\n"         \n
    "\\u"         \u
    "\\o"         \o
    "\\ "         \space
    "\\]"         \]
    "\\newline"   \newline
    "\\return"    \return
    "\\space"     \space
    "\\tab"       \tab
    "\\ua66e"     \ua66e
    "\\uA66E"     \uA66E
    "\\uD7FF"     \uD7FF
    "\\uE000"     \uE000
    
    ;; not in spec
    "\\backspace" \backspace
    "\\formfeed"  \formfeed
    "\\o0"        \o0
    "\\o12"       \o12
    "\\o176"      \o176
    "\\o377"      \o377)
  
  (are [s] (thrown? Exception (edn/read-string s))
    "\\u20"
    "\\"
    "\\new"
    "\\wh"
    "\\uD800"
    "\\uDFFF"
    "\\0378"))

(deftest symbols-test
  (are [s e] (= e (edn/read-string s))
    "-"             '-
    "+"             '+
    "sym"           'sym
    ".*+!-_?$%&=<>" '.*+!-_?$%&=<>
    "-sym"          '-sym
    "+sym"          '+sym
    ".sym"          '.sym
    "sym123"        'sym123
    "/"             '/
    "ns/sym"        'ns/sym
    " ns/sym "      'ns/sym
    "[a/b c/d]"     '[a/b c/d]
    "абв"           'абв
    
    ;; not in spec
    ".1"            '.1
    ".1a"           '.1a
    "ns/-1a"        'ns/-1a
    "ns/+1a"        'ns/+1a
    "ns/.1a"        'ns/.1a
    "ns//"          'ns//
    "ns/sym/sym"    'ns/sym/sym
    "ns//sym"       'ns//sym
    
    ;; extras -- don't work in clojure.edn
    "ns/1a"         (symbol "ns" "1a")
    "ns/sym/"       (symbol "ns" "sym/"))
  
  (are [s] (thrown? Exception (edn/read-string s))
    "ns/"
    "/sym"
    "//"
    "1a"    
    "-1a"
    "+1a"))
    

(deftest keywords-test
  (are [s e] (= e (edn/read-string s))
    ":sym"           :sym
    ":true"          :true
    ":false"         :false
    ":nil"           :nil
    ":.*+!-_?$%&=<>" :.*+!-_?$%&=<>
    ":-sym"          :-sym
    ":+sym"          :+sym
    ":.sym"          :.sym
    ":sym123"        :sym123
    ":ns/sym"        :ns/sym
    " :ns/sym "      :ns/sym
    ":абв"           :абв
    
    ;; not in spec
    ":.1a"           :.1a
    ":/"             :/
    ":ns//"          :ns//
    ":ns///"         :ns///
    ":ns/sym/sym"    :ns/sym/sym
    ":ns//sym"       :ns//sym
    ":ns/-1a"        :ns/-1a
    ":ns/+1a"        :ns/+1a
    ":ns/.1a"        :ns/.1a
    ":1a"            :1a
    ":-1a"           :-1a
    ":+1a"           :+1a
    
    ;; extras -- don't work in clojure.edn
    ":ns/sym/"       (keyword "ns" "sym/")
    ":ns/1a"         (keyword "ns" "1a"))
  
  (are [s] (thrown? Exception (edn/read-string s))
    ":"
    ": "
    "[:]"
    ":ns/"
    ":/sym"
    "://"
    
    ;; extras -- don't work in clojure.edn
    ":///"))


(deftest integers-test
  (are [s e] (let [v (edn/read-string s)]
               (and
                 (= e v)
                 (= (class e) (class v))))
    "0"      0
    "1"      1
    "123"    123
    "+0"     +0
    "+1"     +1
    "+123"   +123
    "-0"     -0
    "-1"     -1
    "-123"   -123
    "0N"      0N
    "1N"      1N
    "123N"    123N
    "+0N"     +0N
    "+1N"     +1N
    "+123N"   +123N
    "-0N"     -0N
    "-1N"     -1N
    "-123N"   -123N
    "10000000000000000000" 10000000000000000000
    
    ;; not in spec
    "07"     07
    "0xFF"   0xFF
    "0xff"   0xff
    "0XFF"   0XFF
    "0Xff"   0Xff
    "+07"    +07
    "+0xFF"  +0xFF
    "+0xff"  +0xff
    "-07"    -07
    "-0xFF"  -0xFF
    "-0xff"  -0xff
    
    "07N"     07N
    "0xFFN"   0xFFN
    "0xffN"   0xffN
    "+07N"    +07N
    "+0xFFN"  +0xFFN
    "+0xffN"  +0xffN
    "-07N"    -07N
    "-0xFFN"  -0xFFN
    "-0xffN"  -0xffN
    
    "2r1111"  2r1111
    "2R1111"  2R1111
    "36rabcxyz" 36rabcxyz
    "36Rabcxyz" 36rabcxyz
    "36rABCXYZ" 36rABCXYZ
    "36RABCXYZ" 36RABCXYZ
    
    ;; extras -- don't work in clojure.edn
    "2r1111N"   (clojure.lang.BigInt/fromLong 2r1111))
  
  (are [s] (thrown? Exception (edn/read-string s))
    "08"
    "0x"
    "0xG"
    "1n"
    ))

(deftest floats-test
  (are [s e] (= e (edn/read-string s))
    "0.0"     0.0
    "1.2"     1.2
    "1e3"     1e3
    "1e+3"    1e+3
    "1e-3"    1e-3
    "1E3"     1E3
    "1E+3"    1E+3
    "1E-3"    1E-3
    "1.2e3"   1.2e3
    "1.2e+3"  1.2e+3
    "1.2e-3"  1.2e-3
    "1.2E3"   1.2E3
    "1.2E+3"  1.2E+3
    "1.2E-3"  1.2E-3
    "0.0M"    0.0M
    "1.2M"    1.2M
    "1e3M"    1e3M
    "1e+3M"   1e+3M
    "1e-3M"   1e-3M
    "1E3M"    1E3M
    "1E+3M"   1E+3M
    "1E-3M"   1E-3M
    "1.2e3M"  1.2e3M
    "1.2e+3M" 1.2e+3M
    "1.2e-3M" 1.2e-3M
    "1.2E3M"  1.2E3M
    "1.2E+3M" 1.2E+3M
    "1.2E-3M" 1.2E-3M

    ;; not in spec
    "1."      1.
    "1.M"     1.M
    "##Inf"   ##Inf
    "##-Inf"  ##-Inf)
  
  ;; not in spec
  (Double/.isNaN (edn/read-string "##NaN"))
  
  (are [s] (thrown? Exception (edn/read-string s))
    "1m"
    "1.1.1"
    "1E2.3"
    "1E2E3"
    "##inf"
    "##INF"
    "##In"
    "##+NaN"))


(deftest lists-test
  (are [s e] (= e (edn/read-string s))
    "()"           '()
    "((()))"       '((()))
    "(1 2 3)"      '(1 2 3)
    "(1 \"a\" :b)" '(1 "a" :b)
    "(\n1,,,2\n)"  '(1 2))
  
  (are [s] (thrown? Exception (edn/read-string s))
    "("
    ")"
    "(]"))


(deftest vectors-test
  (are [s e] (= e (edn/read-string s))
    "[]"           []
    "[[[]]]"       [[[]]]
    "[1 2 3]"      [1 2 3]
    "[1 \"a\" :b]" [1 "a" :b]
    "[\n1,,,2\n]"  [1 2]
    "[-123]"       [-123])
  
  (are [s] (thrown? Exception (edn/read-string s))
    "["
    "]"
    "[)"))


(deftest maps-test
  (are [s e] (= e (edn/read-string s))
    "{}"                 {}
    "{{}{}}"             {{}{}}
    "{1 2 3 4}"          {1 2 3 4}
    "{1 \"a\" :b false}" {1 "a" :b false}
    "{\n1,,,2\n}"        {1 2})
  
  (are [s] (thrown? Exception (edn/read-string s))
    "{"
    "}"
    "{)"
    "{:a}"
    "{:a 1 :a 2}"))


(deftest maps-handover-test
  (let [val {0.1062136078532776 5503700513430014295,
             0.33213048032543335 5127506283599458012,
             0.33707193604791585 -6172714352442823113,
             0.35266574357763414 -3182861713924807326,
             0.5113215717715888 -5265568247920786345,
             0.5456096951003958 2953567803747887223,
             0.6153631149663907 4808857377906182218,
             0.6886930117328257 8960709596500881224,
             0.700584871710543 -5064970727458801751,
             0.770470302247564 -5303540354990263337,
             0.9787952548837326 5485720490705892202}]
    (is (= (edn/read-string (pr-str val)) val))))


;; not in spec
(deftest namespace-maps-test
  (are [s e] (= e (edn/read-string s))
    "#:a{:b 1 :c/d 2 :_/e 3 \"f\" 4 g 5 h/i 6 _/j 7 8 9}" {:a/b 1, :c/d 2, :e 3, "f" 4, 'a/g 5, 'h/i 6, 'j 7, 8 9}
    "#:ns {:a 1}" {:ns/a 1}
    "#:ns,, ,\n, ,, {:a 1}" {:ns/a 1})
  
  (are [s] (thrown? Exception (edn/read-string s))
    "#:ns/name{:a 1}"
    "#abc{:def 1}"
    "#:{:def 1}"
    "#:abc{:def}"))


(deftest sets-test
  (are [s e] (= e (edn/read-string s))
    "#{}"           #{}
    "#{#{#{}}}"     #{#{#{}}}
    "#{1 2 3}"      #{1 2 3}
    "#{1 \"a\" :b}" #{1 "a" :b}
    "#{\n1,,,2\n}"  #{1 2})
  
  (are [s] (thrown? Exception (edn/read-string s))
    "# {}"
    "#{"
    "}"
    "#{)"
    "#{1 1}"))


(deftest tags-test
  (let [opts {:readers {'a   (fn [v] [:a v])
                        'b   (fn [v] [:b v])
                        'c/d (fn [v] [:c/d v])}
              :default (fn [t v] [:unknown t v])}]
    (are [s e] (= e (edn/read-string opts s))
      "#a 1"         [:a 1]
      "#c/d 1"       [:c/d 1]
      "#a #b #c/d 1" [:a [:b [:c/d 1]]]
      "#e 1"         [:unknown 'e 1]))
    
  (are [s] (thrown? Exception (edn/read-string s))
    "#a 1"
    "#1 2"
    "# a 1"))


(deftest instants-test
  (are [s e] (= e (edn/read-string s))
    "#inst \"1985\"" #inst "1985"
    "#inst \"1985-04\"" #inst "1985-04"
    "#inst \"1985-04-12\"" #inst "1985-04-12"
    "#inst \"1985-04-12T23\"" #inst "1985-04-12T23"
    "#inst \"1985-04-12T23:20\"" #inst "1985-04-12T23:20"
    "#inst \"1985-04-12T23:20:50\"" #inst "1985-04-12T23:20:50"
    "#inst \"1985-04-12T23:20:50.5\"" #inst "1985-04-12T23:20:50.5"
    "#inst \"1985-04-12T23:20:50.52\"" #inst "1985-04-12T23:20:50.52"
    "#inst \"1985-04-12T23:20:50.52\"" #inst "1985-04-12T23:20:50.520"
    "#inst \"1985-04-12T23:20:50.52Z\"" #inst "1985-04-12T23:20:50.520Z"
    "#inst \"1985-04-12T23:20:50.52+05:00\"" #inst "1985-04-12T23:20:50.520+05:00"
    "#inst \n#_abc\n,,, \"1985\"" #inst "1985"
    "#inst\"1985\"" #inst"1985"
    
    ;; extras -- don't work in clojure.edn
    "#inst \"1985-04-12T23:20:50.\"" #inst "1985-04-12T23:20:50")

  (are [s] (thrown? Exception (edn/read-string s))
    "#inst"
    "#inst 1985"
    "#inst \"198\""
    "#inst \"1985-\""
    "#inst \"1985-0\""
    "#inst \"1985-04-\""
    "#inst \"1985-04-1\""
    "#inst \"1985-04-12T\""
    "#inst \"1985-04-12T2\""
    "#inst \"1985-04-12T23:\""
    "#inst \"1985-04-12T23:2\""
    "#inst \"1985-04-12T23:20:\""
    "#inst \"1985-04-12T23:20:5\""
    "#inst \"1985-04-12t23:20:50.52z\""
    "#inst \"1985-04-12 23:20:50.52Z\""
    "#inst \"1985-04-12T23:20:50.52+\""
    "#inst \"1985-04-12T23:20:50.52+0\""
    "#inst \"1985-04-12T23:20:50.52+01\""
    "#inst \"1985-04-12T23:20:50.52+01:0\""
    "#inst \"1985-04-12T23:20:50.52+1:01\""
    "#inst \"1985-04-12T23:20:50.52ZABC\""
    "#inst \"1985-04-12T23:20:50.52+01:02ABC\""))


(deftest uuids-test
  (are [s e] (= e (edn/read-string s))
    "#uuid \"f81d4fae-7dec-11d0-a765-00a0c91e6bf6\"" #uuid "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"
    ;; not in spec
    "#uuid \"f-7-1-a-8\"" #uuid "0000000f-0007-0001-000a-000000000008")
  
  (are [s] (thrown? Exception (edn/read-string s))
    "#uuid"
    "#uuid 1"))


(deftest comments-test
  (are [s e] (= e (edn/read-string s))
    "[1 ; 2 ]\n 3]" [1 3]
    "; 1 [\n[2 3]"  [2 3]
    "; abc"         nil))


(deftest discards-test
  (are [s e] (= e (edn/read-string s))
    "#_1"               nil
    "#_   ,,, 1"        nil
    "[1 #_2 3]"         [1 3]
    "[1 #_ 2 3]"        [1 3]
    "[1 #_\n2 3]"       [1 3]
    "[#_#_1 #_2 3 4 5]" [4 5])
  
  (are [s] (thrown? Exception (edn/read-string s))
    "#_"
    "#_#_"
    "#_#_1"))


(deftest eof-test
  (are [s e] (= e (edn/read-string {:eof :eof} s))
    "" :eof
    "#_ smth" :eof)
  
  (are [s] (= nil (edn/read-string s))
    ""
    "#_ smth"))


;; not in spec
(deftest ratio-test
  (are [s e] (= e (edn/read-string {:eof :eof} s))
    "1/2"       1/2
    "0/1"       0/1
    "-1/2"      -1/2
    
    ;; extras -- don't work in clojure.edn
    "0xFF/0x02"   255/2
    "100N/50N"    2
    "2r1000/0177" 8/127
    "1000000000000000000000000000000/2" 1000000000000000000000000000000/2
    "1/-2"        -1/2
    "-1/-2"       1/2)
  
  (are [s] (thrown? Exception (edn/read-string s))
    "1/"
    "/2"
    "1.1/2"
    "1/2.2"
    "1/2/3"))
  

;; not in spec
(deftest meta-test
  (are [s m e] (let [a (edn/read-string s)]
                 (and
                   (= m (meta a))
                   (= e a)))
    "^:kw {}"     {:kw true} {}
    "^:k ^:w {}"  {:k true :w true} {}
    "^tag {}"     {:tag 'tag} {}
    "^{:k :v} {}" {:k :v} {}
    "^:kw\n,,,{}" {:kw true} {}
    
    ;; extras -- don't work in clojure.edn
    "^[tag] {}"   {:param-tags ['tag]} {}))

(deftest issue-2
  (let [m {:field     :field1
           :condition {:field {:field2 "bar"}}
           :foo1      "bar"
           :foo2      "bar"
           :foo3      "bar"
           :foo4      "bar"
           :foo5      "bar"
           :foo6      "bar"
           :foo7      "bar"}]
    (is (= m (edn/read-string (pr-str m)))))
  (let [m {:a 1
           :b 2
           :c {:d 3 :e 4}}]
    (is (= m (edn/read-string (pr-str m))))))

(deftest issue-3
  (is (= (list :a 1)
         (edn/read-string "(:a 1 ;comment\n)")))
  (is (= [:a 1]
         (edn/read-string "[:a 1 ;comment\n]")))
  (is (= #{:a 1}
         (edn/read-string "#{:a 1 ;comment\n}")))
  (is (= {:a 1}
         (edn/read-string "{:a 1 ;comment\n}"))))
