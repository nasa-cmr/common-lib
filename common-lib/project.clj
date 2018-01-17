(defproject nasa-cmr/cmr-common-lib "0.1.1-SNAPSHOT"
  :description "Provides common utility code for CMR projects."
  :url "https://github.com/nasa/Common-Metadata-Repository/tree/master/common-lib"
  :exclusions [
    [cheshire]
    [clj-time]
    [commons-codec/commons-codec]
    [instaparse]
    [org.clojure/core.async]
    [org.clojure/tools.reader]
    [org.eclipse.jetty/jetty-http]
    [org.eclipse.jetty/jetty-io]
    [org.eclipse.jetty/jetty-util]
    [ring/ring-core]]
  :dependencies [
    [camel-snake-kebab "0.4.0"]
    [cheshire "5.8.0"]
    [clj-time "0.14.2"]
    [clojail "1.0.6"]
    [clojurewerkz/quartzite "2.1.0"]
    [clojusc/ltest "0.3.0-SNAPSHOT"]
    [com.dadrox/quiet-slf4j "0.1"]
    [com.gfredericks/test.chuck "0.2.8"]
    [com.github.fge/json-schema-validator "2.2.6"]
    [com.taoensso/timbre "4.10.0"]
    [commons-codec/commons-codec "1.11"]
    [compojure "1.6.0"]
    [environ "1.1.0"]
    [instaparse "1.4.8"]
    [net.jpountz.lz4/lz4 "1.3.0"]
    [org.clojure/clojure "1.8.0"]
    [org.clojure/core.async "0.4.474"]
    [org.clojure/core.cache "0.6.5"]
    [org.clojure/data.codec "0.1.1"]
    [org.clojure/data.xml "0.0.8"]
    [org.clojure/test.check "0.9.0"]
    [org.clojure/tools.nrepl "0.2.13"]
    [org.clojure/tools.reader "1.1.1"]
    [org.eclipse.jetty/jetty-http "9.2.10.v20150310"]
    [org.eclipse.jetty/jetty-io "9.2.10.v20150310"]
    [org.eclipse.jetty/jetty-servlets "9.2.10.v20150310"]
    [org.eclipse.jetty/jetty-util "9.2.10.v20150310"]
    [org.ow2.asm/asm "5.1"]
    [potemkin "0.4.4"]
    [ring/ring-core "1.6.3"]
    [ring/ring-jetty-adapter "1.6.3"]
    [ring/ring-json "0.4.0"]]
  :plugins [
    [lein-exec "0.3.7"]
    [lein-shell "0.5.0"]
    [test2junit "1.3.3"]]
  :global-vars {*warn-on-reflection* true}
  ;; The ^replace is done to disable the tiered compilation for accurate benchmarks
  ;; See https://github.com/technomancy/leiningen/wiki/Faster
  :jvm-opts ^:replace ["-server"
                       "-Dclojure.compiler.direct-linking=true"]
  :profiles {
    :dev {
      :dependencies [
        [org.clojure/tools.namespace "0.2.11"]
        [org.clojars.gjahad/debug-repl "0.3.3"]
        [criterium "0.4.4"]
        [proto-repl "0.3.1"]
        [clj-http "2.3.0"]]
      :jvm-opts ^:replace ["-server"]
                           ;; XXX Note that profiling can be kept in a profile,
                           ;;     with no need to comment/uncomment.
                           ;; Uncomment this to enable assertions. Turn off during performance tests.
                           ; "-ea"

                           ;; Use the following to enable JMX profiling with visualvm
                           ; "-Dcom.sun.management.jmxremote"
                           ; "-Dcom.sun.management.jmxremote.ssl=false"
                           ; "-Dcom.sun.management.jmxremote.authenticate=false"
                           ; "-Dcom.sun.management.jmxremote.port=1098"]
      :source-paths ["src" "dev" "test"]}
    :static {}
    ;; This profile is used for linting and static analysis. To run for this
    ;; project, use `lein lint` from inside the project directory. To run for
    ;; all projects at the same time, use the same command but from the top-
    ;; level directory.
    :lint {
      :source-paths ^:replace ["src"]
      :test-paths ^:replace []
      :plugins [
        [jonase/eastwood "0.2.5"]
        [lein-ancient "0.6.15"]
        [lein-bikeshed "0.5.0"]
        [lein-kibit "0.1.6"]
        [venantius/yagni "0.1.4"]]}}
  :aliases {;; Alias to test2junit for consistency with lein-test-out
            "test-out" ["test2junit"]
            ;; Linting aliases
            "kibit" ["do" ["with-profile" "lint" "shell" "echo" "== Kibit =="]
                          ["with-profile" "lint" "kibit"]]
            "eastwood" ["with-profile" "lint" "eastwood" "{:namespaces [:source-paths]}"]
            "bikeshed" ["with-profile" "lint" "bikeshed" "--max-line-length=100"]
            "yagni" ["with-profile" "lint" "yagni"]
            "check-deps" ["with-profile" "lint" "ancient" ":all"]
            "lint" ["do" ["check"] ["kibit"] ["eastwood"]]
            ;; Placeholder for future docs and enabler of top-level alias
            "generate-static" ["with-profile" "static" "shell" "echo"]})
