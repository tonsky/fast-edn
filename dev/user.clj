(ns user
  (:require
   [duti.core :as duti]
   [virgil]))

(duti/set-dirs "src" "dev" "test")

(defn reload []
  (virgil/compile-java ["src/fast_edn"])
  (duti/reload {:only :all}))

(def -main
  duti/-main)

(defn test-all []
  (virgil/compile-java ["src/fast_edn"])
  (duti/test #"fast-edn\.test"))

(comment
  (duti/test #"fast-edn\..*test"))

(defn -test-main [_]
  (virgil/compile-java ["src/fast_edn"])
  (duti/test-exit #"fast-edn\.(.*-)?test"))

(defn -bench-json [_]
  (virgil/compile-java ["src/fast_edn"])
  (require 'bench)
  (@(resolve 'bench/bench)
   {:files   #".*\.json"
    :parsers (keys @(resolve 'bench/json-parsers))}))

(defn -bench-edn [_]
  (virgil/compile-java ["src/fast_edn"])
  (require 'bench)
  (@(resolve 'bench/bench)
   {:files   #".*\.edn"
    :parsers (keys @(resolve 'bench/edn-parsers))}))

(defn -bench-transit [_]
  (virgil/compile-java ["src/fast_edn"])
  (require 'bench)
  (@(resolve 'bench/bench)
   {:files   #"transit_.*"
    :parsers (concat
               (keys @(resolve 'bench/transit-json-parsers))
               (keys @(resolve 'bench/transit-msgpack-parsers)))}))
