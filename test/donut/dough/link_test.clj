(ns donut.dough.link-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [donut.dough.link :as link]))

(def libs {'party.donut/system "/src/system" 'party.donut/frontend "/src/frontend"})

(def expected-alias
  {:override-deps {'party.donut/frontend {:local/root "/src/frontend"}
                   'party.donut/system   {:local/root "/src/system"}}})

(deftest with-alias-test
  (testing "adds the alias to an existing :aliases map, keeping comments and other aliases"
    (let [before "{:aliases {;; Add cross-project aliases here\n           :new {:extra-deps {}}}}"
          after  (link/with-alias before (link/alias-value libs))]
      (is (str/includes? after ";; Add cross-project aliases here"))
      (is (= {:new {:extra-deps {}} :donut/local expected-alias}
             (:aliases (edn/read-string after))))))
  (testing "replaces a previous :donut/local instead of adding a second one"
    (let [before "{:aliases {:donut/local {:override-deps {old/lib {:local/root \"/old\"}}}}}"
          after  (link/with-alias before (link/alias-value libs))]
      (is (= {:donut/local expected-alias} (:aliases (edn/read-string after))))))
  (testing "creates :aliases when the file has none"
    (is (= {:mvn/repos {} :aliases {:donut/local expected-alias}}
           (edn/read-string (link/with-alias "{:mvn/repos {}}" (link/alias-value libs)))))))

(deftest link!-test
  (fs/with-temp-dir [dir {}]
    (let [path (fs/path dir "clojure" "deps.edn")]
      (testing "creates the user deps.edn if it doesn't exist"
        (link/link! path libs)
        (is (= {:aliases {:donut/local expected-alias}} (edn/read-string (slurp (fs/file path)))))))))
