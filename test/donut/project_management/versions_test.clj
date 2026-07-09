(ns donut.project-management.versions-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [donut.project-management.versions :as versions]))

(deftest parse-test
  (is (= [1 2 3] (versions/parse "1.2.3")))
  (is (= [0 0 47] (versions/parse "0.0.47\n"))))

(deftest unparse-test
  (is (= "1.2.3" (versions/unparse [1 2 3]))))

(deftest bump-test
  (testing "defaults to patch"
    (is (= "1.2.4" (versions/bump "1.2.3"))))
  (testing "bumping a segment zeroes the segments after it"
    (is (= "1.3.0" (versions/bump "1.2.3" :minor)))
    (is (= "2.0.0" (versions/bump "1.2.3" :major)))))

(deftest bump-version-file!-test
  (let [path (fs/path (fs/create-temp-dir) "VERSION")]
    (spit (str path) "0.1.9\n")
    (is (= "0.1.10" (versions/bump-version-file! path)))
    (is (= "0.1.10" (versions/read-version-file path)))
    (is (= "0.2.0" (versions/bump-version-file! path :minor)))))
