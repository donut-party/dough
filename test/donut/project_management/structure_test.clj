(ns donut.project-management.structure-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [donut.project-management.structure :as structure]))

(deftest repo-path-test
  (is (= (str (fs/path "/tmp/framework" "bakery"))
         (str (structure/repo-path "/tmp/framework" "bakery"))))
  (testing "\".\" names the framework root itself"
    (is (= "/tmp/framework"
           (str (structure/repo-path "/tmp/framework" "."))))))

(deftest framework-root-test
  (testing "framework root is the parent of this repo"
    (is (= (str (fs/parent (structure/project-management-root)))
           (structure/framework-root))))
  (testing "this repo is inside the framework root"
    (is (= (str (structure/project-management-root))
           (str (structure/repo-path "project-management"))))))
