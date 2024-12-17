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
