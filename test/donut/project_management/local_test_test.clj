(ns donut.project-management.local-test-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [donut.project-management.local-test :as local-test]))

(deftest donut-cli-steps-test
  (is (= [["donut-cli" "./develop/bin/develop update-deps"]
          ["donut-cli" "./develop/bin/develop copy-local"]]
         (mapv (juxt :repo :cmd) local-test/donut-cli-steps))))

(deftest regenerate-steps-test
  (is (= [["."             "rm -rf template-test"]
          ["."             "donut new donut-template-test/test-app -t template-test"]
          ["template-test" "donut generate entity-scaffold user"]
          ["template-test" "donut generate --lib party.donut/devtools:0.0.47 :donut.devtools.generators/install"]]
         (mapv (juxt :repo :cmd) (local-test/regenerate-steps "0.0.47")))))

(deftest parse-installed-version-test
  (testing "version is the last non-blank line of build output"
    (is (= "0.0.47" (local-test/parse-installed-version "building jar\ninstalling\n0.0.47\n")))
    (is (= "1.0.213" (local-test/parse-installed-version "1.0.213"))))
  (testing "throws when output doesn't end with a version"
    (is (thrown? Exception (local-test/parse-installed-version "error: something broke")))
    (is (thrown? Exception (local-test/parse-installed-version "")))
    (is (thrown? Exception (local-test/parse-installed-version nil)))))
