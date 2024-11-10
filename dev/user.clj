(ns user
  (:require
   [duti.core :as duti]
   [virgil]))

(duti/set-dirs "src" "dev" "test")

(defn compile-all-java
  ([directories]
   (compile-all-java directories nil false))
  ([directories options verbose?]
   (let [collector    (javax.tools.DiagnosticCollector.)
         options      (java.util.ArrayList. (vec options))
         name->source (virgil.compile/generate-classname->source directories)]
     (println "Compiling" (count name->source) "Java source files in" directories "...")
     (binding [virgil.compile/*print-compiled-classes* verbose?]
       (virgil.compile/compile-java options collector name->source))
     (virgil.util/print-diagnostics collector)
     (when-not (empty? (.getDiagnostics collector))
       (throw (ex-info "Compilation failed" {}))))))

(defn reload []
  (compile-all-java ["src/better_clojure"])
  (duti/reload {:only :all}))

(def -main
  duti/-main)

(defn test-all []
  (virgil/compile-java ["src/better_clojure"])
  (duti/reload {:only :all})
  (duti/test #"better-clojure\..*-test"))

(defn -test-main [_]
  (virgil/compile-java ["src/better_clojure"])
  (duti/test-exit #"better-clojure\..*-test"))
