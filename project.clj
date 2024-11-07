(defproject io.github.tonsky/better-clojure "0.0.0"
  :description "<description>"
  :license     {:name "MIT" :url "https://github.com/tonsky/better-clojure/blob/master/LICENSE"}
  :url         "https://github.com/tonsky/better-clojure"
  :dependencies
  [[org.clojure/clojure "1.12.0"]]
  :deploy-repositories
  {"clojars"
   {:url "https://clojars.org/repo"
    :username "tonsky"
    :password :env/clojars_token
    :sign-releases false}})