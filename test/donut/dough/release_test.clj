(ns donut.dough.release-test
  "Release ordering, versions, pins, and the land plan: the pure parts of
  shipping a change set."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [donut.dough.graph :as graph]
   [donut.dough.land :as land]
   [donut.dough.pin :as pin]
   [donut.dough.release :as release]))

(deftest topo-sort-test
  (testing "dependencies come first, ties alphabetical"
    (is (= ["compose" "error" "system" "endpoint-test" "frontend"]
           (graph/topo-sort {"system"        #{"compose" "error"}
                             "frontend"      #{"compose" "system"}
                             "endpoint-test" #{"system"}}))))
  (is (thrown? Exception (graph/topo-sort {"a" #{"b"} "b" #{"a"}}))))

(deftest repo-graph-test
  (testing "module deps collapse to repo deps, without self-edges"
    (is (= {"datapotato" #{} "reference" #{"datapotato" "system"} "system" #{}}
           (graph/repo-graph {'party.donut/datapotato           #{}
                              'party.donut/datapotato-next-jdbc #{'party.donut/datapotato}
                              'party.donut/system               #{}
                              'party.donut/reference            #{'party.donut/datapotato-next-jdbc 'party.donut/system}}
                             {'party.donut/datapotato           "datapotato"
                              'party.donut/datapotato-next-jdbc "datapotato"
                              'party.donut/system               "system"
                              'party.donut/reference            "reference"})))))

(deftest repo-name-test
  (is (= "datapotato" (graph/repo-name (fs/path "/f") (fs/path "/f/datapotato/datapotato-xtdb"))))
  (is (= "system" (graph/repo-name (fs/path "/f") (fs/path "/f/system")))))

(deftest lib-versions-test
  (fs/with-temp-dir [dir {}]
    (spit (fs/file dir "deps.edn") "{:donut/lib {:name party.donut/x :version-prefix \"0.0\"}}")
    (is (= {'party.donut/x "0.0.42"} (release/lib-versions {'party.donut/x (str dir)} 42)))))

(deftest select-repos-test
  (let [order ["compose" "system" "bb-utils" "frontend"]]
    (testing "everything active, in dependency order"
      (is (= ["compose" "system" "frontend"] (release/select-repos order [] #{"compose" "system" "frontend"}))))
    (testing "named repos keep dependency order, not argument order"
      (is (= ["compose" "frontend"] (release/select-repos order ["frontend" "compose"] #{}))))
    (is (thrown? Exception (release/select-repos order ["nope"] #{})))))

(def template-deps
  "{:deps {party.donut/system {:mvn/version \"1.0.258\"}
         party.donut/bakery {:mvn/version \"1.0.2\"}
         metosin/malli      {:mvn/version \"0.19.1\"}}
 :aliases {:cljs {:extra-deps {party.donut/frontend {:mvn/version \"0.0.177\"}}}}}")

(deftest pins-test
  (is (= '{party.donut/bakery "1.0.2" party.donut/frontend "0.0.177" party.donut/system "1.0.258"}
         (pin/donut-mvn-deps template-deps)))
  (testing "unreleased libraries (nil) and current pins are left alone"
    (is (= '[[party.donut/frontend "0.0.177" "0.0.180"]]
           (pin/pin-changes (pin/donut-mvn-deps template-deps)
                            '{party.donut/bakery nil party.donut/frontend "0.0.180" party.donut/system "1.0.258"}))))
  (is (= '{party.donut/bakery "1.0.2" party.donut/frontend "0.0.180" party.donut/system "1.0.259"}
         (pin/donut-mvn-deps (pin/apply-pins template-deps '[[party.donut/frontend "0.0.177" "0.0.180"]
                                                             [party.donut/system "1.0.258" "1.0.259"]])))))

(deftest land-steps-test
  (testing "libraries merge and release in dependency order, then the template, pins, the rest"
    (is (= [[:merge "system"] [:release "system"]
            [:merge "frontend"] [:release "frontend"]
            [:merge "single-page-app-template"]
            [:pin]
            [:merge "donut-cli"]
            [:done]]
           (land/land-steps ["donut-cli" "frontend" "single-page-app-template" "system"]
                            ["compose" "system" "frontend"]))))
  (testing "a change set without libraries still pins and finishes"
    (is (= [[:pin] [:merge "donut-cli"] [:done]]
           (land/land-steps ["donut-cli"] ["compose"])))))
