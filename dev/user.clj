(ns user
  (:require
   [clj-reload.core :as clj-reload]
   [clojure+.hashp :as hashp]
   [clojure+.print :as print]
   [clojure+.error :as error]
   [clojure+.test :as test]
   [virgil]))

(hashp/install!)
(print/install!)
(error/install!)


; reload

(clj-reload/init
  {:dirs      ["src" "dev" "test"]
   :no-reload '#{user}})

(defn compile-java []
  (virgil/compile-java ["src/fast_edn"]))

(defn reload []
  (compile-java)
  (clj-reload/reload {:only :all}))


; tests

(test/install!)

(defn test-all []
  (compile-java)
  (clj-reload/reload {:only #"fast-edn\.test"})
  (test/run #"fast-edn\.test"))

(defn -test-main [{:keys [re]}]
  (let [re (re-pattern (or re "fast-edn\\.(.*-)?test"))]
    (compile-java)
    (clj-reload/reload {:only re})
    (let [{:keys [fail error]} (test/run re)]
      (System/exit (+ fail error)))))


; benchmarks

(defn -bench-json [_]
  (compile-java)
  (require 'bench)
  (@(resolve 'bench/bench)
   {:files   #".*\.json"
    :parsers (keys @(resolve 'bench/json-parsers))}))

(defn -bench-edn [_]
  (compile-java)
  (require 'bench)
  (@(resolve 'bench/bench)
   {:files   #".*\.edn"
    :parsers (keys @(resolve 'bench/edn-parsers))}))

(defn -bench-transit [_]
  (compile-java)
  (require 'bench)
  (@(resolve 'bench/bench)
   {:files   #"transit_.*"
    :parsers (concat
               (keys @(resolve 'bench/transit-json-parsers))
               (keys @(resolve 'bench/transit-msgpack-parsers)))}))
