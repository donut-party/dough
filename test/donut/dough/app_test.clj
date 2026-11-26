(ns donut.dough.app-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [donut.dough.app :as app]))

(deftest checks-test
  (testing "every shadow build compiles, and a :release alias runs bakery's release build"
    (is (= [["backend tests" ["clojure" "-X:test:donut/local"]]
            ["cljs compile (app)" ["npx" "shadow-cljs" "-A:donut/local" "compile" "app"]]
            ["cljs compile (app-devtools)" ["npx" "shadow-cljs" "-A:donut/local" "compile" "app-devtools"]]
            ["cljs compile (dev)" ["npx" "shadow-cljs" "-A:donut/local" "compile" "dev"]]
            ["cljs release (bakery)" ["clojure" "-X:cljs:release:donut/local"]]
            ["tailwind build" ["npm" "run" "tailwind:release"]]]
           (mapv (juxt :label :cmd) (app/checks #{:dev :app :app-devtools} #{:dev :cljs :release})))))
  (testing "no builds and no :release alias leaves backend tests and tailwind"
    (is (= ["backend tests" "tailwind build"]
           (mapv :label (app/checks nil nil))))))

(deftest setup-steps-test
  (let [steps (app/setup-steps "demo" "0.0.48")]
    (is (= ["generate app" "entity scaffold user" "app-devtools install" "npm install"]
           (mapv :label steps)))
    (is (= ["new" "donut-app/demo" "--target-dir" "demo"] (:cli (first steps))))
    (testing "app-devtools is pinned to the given version"
      (is (= ["generate" "--lib" "party.donut/app-devtools:0.0.48" ":donut.app-devtools.generators/install"]
             (:cli (nth steps 2))))))
  (testing "no version skips app-devtools"
    (is (= ["generate app" "entity scaffold user" "npm install"]
           (mapv :label (app/setup-steps "demo" nil))))))

(deftest prepare-dir!-test
  (fs/with-temp-dir [dir {}]
    (testing "a directory dough didn't make is never deleted"
      (let [mine (fs/path dir "mine")]
        (fs/create-dirs mine)
        (is (thrown? Exception (app/prepare-dir! mine)))
        (is (fs/exists? mine))))
    (testing "a previous `dough app` output is replaced"
      (let [generated (fs/path dir "generated")]
        (fs/create-dirs generated)
        (spit (fs/file generated app/marker) "")
        (app/prepare-dir! generated)
        (is (not (fs/exists? generated)))))))
