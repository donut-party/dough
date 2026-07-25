(ns donut.project-management.rebuild-test-app-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [donut.project-management.rebuild-test-app :as rebuild-test-app]))

(deftest donut-cli-steps-test
  (is (= [["donut-cli" "./develop/bin/develop update-deps"]
          ["donut-cli" "./develop/bin/develop copy-local"]]
         (mapv (juxt :repo :cmd) rebuild-test-app/donut-cli-steps))))

(deftest regenerate-steps-test
  (is (= [["."             "rm -rf template-test"]
          ["."             "donut new donut-template-test/test-app -t template-test"]
          ["template-test" "donut generate entity-scaffold user"]
          ["template-test" "donut generate --lib party.donut/app-devtools:0.0.47 :donut.app-devtools.generators/install"]]
         (mapv (juxt :repo :cmd) (rebuild-test-app/regenerate-steps "0.0.47")))))

(deftest parse-installed-version-test
  (testing "version is the last non-blank line of build output"
    (is (= "0.0.47" (rebuild-test-app/parse-installed-version "building jar\ninstalling\n0.0.47\n")))
    (is (= "1.0.213" (rebuild-test-app/parse-installed-version "1.0.213"))))
  (testing "throws when output doesn't end with a version"
    (is (thrown? Exception (rebuild-test-app/parse-installed-version "error: something broke")))
    (is (thrown? Exception (rebuild-test-app/parse-installed-version "")))
    (is (thrown? Exception (rebuild-test-app/parse-installed-version nil)))))
