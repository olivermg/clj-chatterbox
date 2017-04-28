(defproject clj-chatterbox "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [com.stuartsierra/component "0.3.2"]
                 [http-kit "2.2.0"]
                 [ring/ring-defaults "0.2.3"]
                 ;;;[ring.middleware.conditional "0.2.0"]
                 [bk/ring-gzip "0.2.1"]
                 [ring-logger "0.7.7"]
                 [com.taoensso/sente "1.11.0"]
                 [buddy/buddy-auth "1.4.1"]]

  :min-lein-version "2.6.1"

  :source-paths ["src/clj"]
  ;;;:java-source-paths ["src/java"]
  :test-paths ["test/clj"]

  )
