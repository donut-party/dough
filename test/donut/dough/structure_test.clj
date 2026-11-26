(ns donut.dough.structure-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [donut.dough.structure :as structure]))

(deftest repo-path-test
  (is (= (str (fs/path "/tmp/framework" "app-devtools"))
         (str (structure/repo-path "/tmp/framework" "app-devtools"))))
  (testing "\".\" names the framework root itself"
    (is (= "/tmp/framework"
           (str (structure/repo-path "/tmp/framework" "."))))))

(deftest framework-root-test
  (testing "framework root is the parent of this repo"
    (is (= (str (fs/parent (structure/dough-root)))
           (structure/framework-root))))
  (testing "this repo is inside the framework root"
    (is (= (str (structure/dough-root))
           (str (structure/repo-path "dough"))))))

(deftest sibling-repo-paths-test
  (fs/with-temp-dir [root {}]
    (let [this-repo (fs/path root "dough")]
      (fs/create-dirs (fs/path this-repo ".git"))
      (fs/create-dirs (fs/path root "system" ".git"))
      (fs/create-dirs (fs/path root "endpoint" ".git"))
      (fs/create-dirs (fs/path root "template-test")) ; generated, never a git repo
      (fs/create-file (fs/path root "some-file"))      ; not a directory at all
      (is (= ["endpoint" "system"]
             (mapv fs/file-name (structure/sibling-repo-paths root this-repo)))))))
