(ns user
  (:require
   [duti.core :as duti]
   [virgil]))

(duti/set-dirs "src" "dev" "test")

(defn reload []
  (virgil/compile-java ["src/better_clojure"])
  (duti/reload {:only :all}))

(def -main
  duti/-main)

(defn test-all []
  (virgil/compile-java ["src/better_clojure"])
  (duti/reload {:only :all})
  (duti/test #"better-clojure\..*-test"))

(defn -test-main [_]
  (duti/test-exit #"better-clojure\..*-test"))
