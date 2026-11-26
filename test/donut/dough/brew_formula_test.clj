(ns donut.dough.brew-formula-test
  (:require
   [clojure.test :refer [deftest is]]
   [donut.dough.brew-formula :as brew-formula]))

(deftest sha256-hex-test
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
         (brew-formula/sha256-hex (.getBytes "abc" "UTF-8")))))

(deftest render-test
  (is (= "  v = \"0.0.21\"\n  url \"https://example.com/v#{v}.zip\"\n  sha256 \"abc123\"\n"
         (brew-formula/render "  v = \"{{version}}\"\n  url \"https://example.com/v#{v}.zip\"\n  sha256 \"{{sha256}}\"\n"
                              {:version "0.0.21" :sha256 "abc123"}))))
