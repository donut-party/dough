(ns donut.project-management.deps-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [donut.project-management.deps :as deps]))

(def deps-string
  "{:deps {party.donut/generate {:mvn/version \"1.0.100\"} ;; keep me
         party.donut/single-page-app {:git/url \"https://github.com/donut-party/single-page-app-template\"
                                      :git/sha \"abc123\"}}}")

(deftest update-mvn-version-test
  (is (= "{:deps {party.donut/generate {:mvn/version \"1.0.200\"} ;; keep me
         party.donut/single-page-app {:git/url \"https://github.com/donut-party/single-page-app-template\"
                                      :git/sha \"abc123\"}}}"
         (deps/update-mvn-version deps-string 'party.donut/generate "1.0.200"))))

(deftest update-git-sha-test
  (is (= "{:deps {party.donut/generate {:mvn/version \"1.0.100\"} ;; keep me
         party.donut/single-page-app {:git/url \"https://github.com/donut-party/single-page-app-template\"
                                      :git/sha \"def456\"}}}"
         (deps/update-git-sha deps-string 'party.donut/single-page-app "def456"))))

(deftest dep-name-accepts-strings
  (is (= "{:deps {party.donut/generate {:mvn/version \"2.0.0\"} ;; keep me
         party.donut/single-page-app {:git/url \"https://github.com/donut-party/single-page-app-template\"
                                      :git/sha \"abc123\"}}}"
         (deps/update-mvn-version deps-string "party.donut/generate" "2.0.0"))))

(deftest missing-dep-returns-string-unchanged
  (is (= deps-string
         (deps/update-mvn-version deps-string 'party.donut/nonexistent "9.9.9"))))

(deftest missing-coord-key-returns-string-unchanged
  (testing "a dep without the coordinate key doesn't cause the next dep's entry to be rewritten"
    (is (= deps-string
           (deps/update-git-sha deps-string 'party.donut/generate "def456")))
    (is (= deps-string
           (deps/update-mvn-version deps-string 'party.donut/single-page-app "9.9.9")))))
