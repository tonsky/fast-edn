(ns fast-edn.writer-test
  (:require
   [clojure.data.generators :as gen]
   [clojure.test :refer [deftest is testing]]
   [fast-edn.core :as edn]
   [fast-edn.generators :as cgen]))

(defn pr-ref
  "pr-str with the print settings write-string promises"
  [x]
  (binding [*print-length*         nil
            *print-level*          nil
            *print-readably*       true
            *print-dup*            false
            *print-meta*           false
            *print-namespace-maps* false]
    (pr-str x)))

(defmacro same [& xs]
  `(do ~@(for [x xs] `(let [x# ~x] (is (= (pr-ref x#) (edn/write-string x#)) (pr-str '~x))))))

(defrecord R [a b])

(deftest scalars-test
  (same nil true false))

(deftest strings-test
  (same ""
        "abc"
        "a\"b" "c\\d" "\n" "\t" "\r" "\f" "\b" "\n\t\r\f\b\"\\"
        "x\u0000y\u001fz\u007f"
        "üñí€ 中文"
        "😀 😀"
        "\ud800 unpaired"
        "escape at end\""
        "\"escape at start"
        (apply str (repeat 5000 "long string with an escape \" inside "))
        (apply str (repeat 300000 "x"))))

(deftest chars-test
  (same \a \Z \0 \newline \tab \space \backspace \formfeed \return \" \\ \u0000 \u001f \ü \中))

(deftest numbers-test
  (same 0 -1 1 Long/MIN_VALUE Long/MAX_VALUE
        (int 5) (short -3) (byte 7)
        0.0 -0.0 1.5 -2.25 1.0E10 1.0E-5 1.0E300 Double/MIN_VALUE ##NaN ##Inf ##-Inf
        (float 1.5) (float -0.0) (Float/valueOf Float/NaN) (Float/valueOf Float/POSITIVE_INFINITY)
        (Float/valueOf Float/NEGATIVE_INFINITY) (Float/valueOf (float 1.0E10))
        1N -12345678901234567890N (biginteger 5) (java.math.BigInteger. "123456789012345678901234567890")
        1.5M 0M -1.000M 1E+5M
        1/3 -7/2))

(deftest longs-test
  (let [edges (concat
                [0 1 9 10 99 100 Long/MAX_VALUE (dec Long/MAX_VALUE) (inc Long/MIN_VALUE)
                 Integer/MAX_VALUE (inc Integer/MAX_VALUE) (dec Integer/MAX_VALUE)
                 (* 100 (long Integer/MAX_VALUE)) 2147483699 2147483700]
                (for [e (range 1 19)
                      :let [p (long (Math/pow 10 e))]
                      x [(dec p) p (inc p)]]
                  x))
        rnd    (java.util.Random. 42)
        random (repeatedly 100000 #(let [bits (inc (.nextInt rnd 64))]
                                     (bit-shift-right (.nextLong rnd) (- 64 bits))))]
    (doseq [x (concat edges (map - edges) [Long/MIN_VALUE] random)]
      (when-not (= (Long/toString x) (edn/write-string x))
        (is (= (Long/toString x) (edn/write-string x)))))
    (is (= (pr-ref (vec random)) (edn/write-string (vec random))))))

(deftest symbols-keywords-test
  (same 'a 'a/b 'clojure.core/+ '/ 'a.b/c.d
        :a :a/b :a.b/c :/ (keyword "with space") (symbol "with space")))

(deftest collections-test
  (same [] {} #{} '() (list) (lazy-seq nil)
        [1 [2 [3 []]]] {:a {:b {:c {}}}} #{#{} [] {}}
        {nil 1} {:a 1 nil 2} {nil nil}
        (apply array-map (range 16)) (apply hash-map (range 18))
        (into {nil 0} (map (fn [i] [(keyword (str "k" i)) i]) (range 100)))
        (zipmap (range 5000) (map str (range 5000)))
        (vec (range 100)) (vec (range 1057)) (subvec (vec (range 50)) 3 40)
        (set (range 1000)) (sorted-set 3 1 2) (sorted-map :b 1 :a 2) (sorted-map-by > 1 :a 2 :b)
        '(1 2 3) (cons 1 '(2)) (cons 1 nil) (range 5) (range 0.5 3) (map inc [1 2]) (take 3 (iterate inc 0))
        (seq [1 2 3]) (seq {:a 1}) (keys {:a 1 :b 2}) (vals {:a 1 :b 2}) (rseq [1 2 3])
        (first {:a 1}) (clojure.lang.MapEntry/create :k :v)
        (persistent! (conj! (transient []) 1))))

(deftest fallback-test
  (same (->R 1 "x") [(->R {:a 1} [2])]
        (with-meta {:a 1} {:type :unknown-type})
        (with-meta [1 2] {:type :x})
        (with-meta '(1) {:type :x})
        (with-meta 'sym {:type :x})
        #inst "2020-01-01T00:00:00.000-00:00"
        #uuid "00000000-0000-0000-0000-000000000000"
        (java.sql.Timestamp. 0)
        #"re\"gex"
        (java.util.HashMap. {"a" 1})
        (java.util.ArrayList. [1 2])
        (java.util.HashSet. [1])
        (clojure.lang.PersistentQueue/EMPTY)
        (conj clojure.lang.PersistentQueue/EMPTY 1 2)
        (Object.)
        (atom 1)
        #'clojure.core/inc
        (java.time.Instant/ofEpochSecond 0)))

(deftest inst-uuid-test
  (same (java.util.Date. 0) (java.util.Date. 1) (java.util.Date. -1) (java.util.Date. 1600000000123)
        #inst "1583-01-01T00:00:00.000-00:00" #inst "1582-12-31T23:59:59.999-00:00"
        #inst "1500-06-15T12:00:00.000-00:00" #inst "0001-01-01T00:00:00.000-00:00"
        #inst "9999-12-31T23:59:59.999-00:00" (java.util.Date. 253402300800000) (java.util.Date. Long/MAX_VALUE)
        (java.util.Date. Long/MIN_VALUE)
        (java.util.UUID. 0 0) (java.util.UUID. -1 -1) (java.util.UUID/randomUUID))
  (binding [gen/*rnd* (java.util.Random. 7)]
    (doseq [_ (range 10000)
            :let [l (gen/long)
                  d (java.util.Date. (if (even? l) (rem l 20000000000000) l))]]
      (is (= (pr-ref d) (edn/write-string d)) (str (.getTime d))))))

(deftest meta-not-printed-test
  (same (with-meta {:a (with-meta [1] {:m 1})} {:n 2})
        (with-meta 'sym {:tag 'String})))

(deftest caller-bindings-test
  (binding [*print-length*         1
            *print-level*          1
            *print-namespace-maps* true
            *print-readably*       false
            *print-meta*           true
            *print-dup*            true]
    (is (= "{:a/b [1 2 3 4], :a/c {:d \"x\"}}" (edn/write-string {:a/b [1 2 3 4] :a/c {:d "x"}})))
    (is (= "[#fast_edn.writer_test.R{:a 1, :b \"s\"} (1 2)]"
           (edn/write-string [(->R 1 "s") '(1 2)])))))

(deftest nested-write-string-test
  (testing "print-method that calls write-string itself"
    (defmethod print-method ::nested [x ^java.io.Writer w]
      (.write w (str "#nested " (edn/write-string (vary-meta x dissoc :type)))))
    (try
      (same [1 (with-meta {:a "b"} {:type ::nested}) 2])
      (finally
        (remove-method print-method ::nested)))))

(deftest strict-test
  (is (= "[1 \"a\" #{:b} (c) #inst \"1970-01-01T00:00:00.000-00:00\" #uuid \"00000000-0000-0000-0000-000000000000\"]"
         (edn/write-string [1 "a" #{:b} '(c) (java.util.Date. 0) #uuid "00000000-0000-0000-0000-000000000000"]
                           {:strict true})))
  (is (= "{:a 1}" (edn/write-string (with-meta {:a 1} {:type :x}) {:strict true})))
  (doseq [x [(->R 1 2) (Object.) #"x" (atom 1) [1 (java.util.HashMap.)]]]
    (is (thrown-with-msg? IllegalArgumentException #"No EDN form" (edn/write-string x {:strict true}))
        (pr-str (class x)))))

(deftest generated-test
  (binding [gen/*rnd* (java.util.Random. 42)]
    (doseq [[label g n] [["ednable" cgen/ednable 20000] ["non-ednable" cgen/non-ednable 5000]]]
      (let [bad (->> (repeatedly n g)
                     (remove #(= (pr-ref %) (edn/write-string %)))
                     (take 3)
                     vec)]
        (is (empty? bad) (str label ": " (pr-str (map pr-ref bad))))))))

(deftest dev-data-test
  (doseq [file (sort (.list (java.io.File. "dev/data")))
          :when (.endsWith ^String file ".edn")
          :let [data (edn/read-string (slurp (str "dev/data/" file)))]]
    (is (= (pr-ref data) (edn/write-string data)) file)))
