(ns donut.dough.brew-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [donut.dough.brew :as brew]))

(defn temp-repository
  "creates a fake brew repository with the given formula versions installed"
  [formula versions]
  (let [repository (fs/create-temp-dir)]
    (doseq [version versions]
      (fs/create-dirs (fs/path repository "Cellar" formula version "libexec")))
    (str repository)))

(deftest cellar-path-test
  (is (= (str (fs/path "/opt/homebrew" "Cellar" "donut"))
         (str (brew/cellar-path "/opt/homebrew" "donut")))))

(deftest installed-versions-test
  (let [repository (temp-repository "donut" ["0.0.2" "0.0.10"])]
    (is (= ["0.0.10" "0.0.2"] ;; lexicographic sort
           (brew/installed-versions repository "donut")))))

(deftest libexec-path-test
  (let [repository (temp-repository "donut" ["0.0.2"])]
    (is (= (str (fs/path repository "Cellar" "donut" "0.0.2" "libexec"))
           (str (brew/libexec-path repository "donut"))))))

(deftest libexec-path-throws-when-not-installed
  (let [repository (temp-repository "donut" [])]
    (is (thrown? Exception (brew/libexec-path repository "donut")))))
