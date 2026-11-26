(ns donut.dough.registry-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as ps]
   [clojure.test :refer [deftest is]]
   [donut.dough.registry :as registry]))

(defn- write-deps!
  [root rel content]
  (let [f (fs/path root rel "deps.edn")]
    (fs/create-dirs (fs/parent f))
    (spit (fs/file f) content)))

(deftest local-libs-test
  (fs/with-temp-dir [tmp {}]
    (let [root (str (fs/canonicalize tmp))]
      (write-deps! root "system" "{:donut/lib {:name party.donut/system}}")
      (write-deps! root "datapotato/datapotato-xtdb" "{:donut/lib {:name party.donut/datapotato-xtdb}}")
      (write-deps! root "template-test" "{:deps {}}")                 ; an app, not a library
      (write-deps! root "broken" "{:donut/lib")                       ; unreadable: skipped
      (write-deps! root "single-page-app-template" "{:paths [\"src\"]}")
      (is (= {'party.donut/datapotato-xtdb (str (fs/path root "datapotato" "datapotato-xtdb"))
              'party.donut/single-page-app (str (fs/path root "single-page-app-template"))
              'party.donut/system          (str (fs/path root "system"))}
             (registry/local-libs root))))))

(deftest checkout-version-test
  (fs/with-temp-dir [dir {}]
    (write-deps! dir "." "{:donut/lib {:name party.donut/x :version-prefix \"1.0\"}}")
    (doseq [args [["init" "-b" "main"] ["config" "user.email" "t@example.com"] ["config" "user.name" "T"]
                  ["add" "deps.edn"] ["commit" "-m" "one"] ["commit" "--allow-empty" "-m" "two"]]]
      (apply ps/sh {:dir (str dir)} "git" args))
    (is (= "1.0.2" (registry/checkout-version dir)) "prefix plus commit count, as donut.build computes it")
    (is (nil? (registry/checkout-version (fs/path dir "missing"))))))
