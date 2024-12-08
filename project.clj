(defproject io.github.tonsky/fast-edn "0.0.0"
  :description "Fast EDN parser"
  :license     {:name "MIT" :url "https://github.com/tonsky/fast-edn/blob/master/LICENSE"}
  :url         "https://github.com/tonsky/fast-edn"
  :dependencies
  [[org.clojure/clojure "1.12.0"]]
  :java-source-paths ["src"]
  :javac-options ["--release" "8" "-Xlint:-options"]
  :deploy-repositories
  {"clojars"
   {:url "https://clojars.org/repo"
    :username "tonsky"
    :password :env/clojars_token
    :sign-releases false}})