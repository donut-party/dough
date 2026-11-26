(ns donut.dough.change-set-test
  "Pure-fn tests plus integration tests against real temp git repos: the
  checkout plan and the merged check are only trustworthy if proven against
  actual git behavior."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as ps]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [donut.dough.change :as change]
   [donut.dough.change-set :as change-set]
   [donut.dough.checkout :as checkout]
   [donut.dough.git :as git]))

;; ---------------------------------------------------------------------------
;; pure fns

(deftest validate-name!-test
  (is (= "compiler-fixes" (change-set/validate-name! "compiler-fixes")))
  (is (= "v1.2_b" (change-set/validate-name! "v1.2_b")))
  (doseq [bad [nil "" "Compiler" "-x" "a b" "../x"]]
    (is (thrown? Exception (change-set/validate-name! bad)) (pr-str bad))))

(deftest checkout-action-test
  (let [state {:current-branch "main" :target-branch "fix" :dirty? false :branch-exists? true}]
    (is (= :checkout (change-set/checkout-action state {})))
    (is (= :already-on (change-set/checkout-action (assoc state :current-branch "fix" :dirty? true) {})))
    (is (= :refuse-dirty (change-set/checkout-action (assoc state :dirty? true) {})))
    (is (= :stash-and-checkout (change-set/checkout-action (assoc state :dirty? true) {:stash? true})))
    (is (= :missing-branch (change-set/checkout-action (assoc state :branch-exists? false) {:stash? true})))))

(deftest blocked?-test
  (is (false? (change-set/blocked? [{:action :checkout} {:action :already-on}])))
  (is (true? (change-set/blocked? [{:action :checkout} {:action :refuse-dirty}])))
  (is (true? (change-set/blocked? [{:action :missing-branch}]))))

(deftest change-sets-by-repo-branch-test
  (is (= {["frontend" "fix"]   #{"a" "b"}
          ["generators" "fix"] #{"a"}
          ["frontend" "other"] #{"c"}}
         (change-set/change-sets-by-repo-branch
          [{:name "a" :repos {"frontend" "fix" "generators" "fix"}}
           {:name "b" :repos {"frontend" "fix"}}
           {:name "c" :repos {"frontend" "other"}}]))))

(deftest parse-new-args-test
  (is (= ["fix" ["frontend" "generators"] nil] (change/parse-new-args ["fix" "frontend" "generators"])))
  (is (= ["fix" ["frontend"] "route fixes"]
         (change/parse-new-args ["fix" "--description" "route fixes" "frontend"]))))

;; ---------------------------------------------------------------------------
;; integration: a temp framework root of repos cloned from bare remotes

(defn- sh!
  [dir & args]
  (let [{:keys [exit err] :as result} (apply ps/sh {:dir (str dir) :out :string :err :string} "git" args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (str/join " " args) " failed: " err) result)))
    result))

(defn- commit-file!
  [dir filename content]
  (spit (fs/file (fs/path dir filename)) content)
  (sh! dir "add" filename)
  (sh! dir "commit" "-m" (str "edit " filename)))

(defn- make-repo!
  "remotes/<name>.git (bare) and root/<name> (a clone with one commit on
  main, pushed, remote HEAD set)."
  [root remotes repo-name]
  (let [bare  (fs/path remotes (str repo-name ".git"))
        clone (fs/path root repo-name)]
    (fs/create-dirs bare)
    (sh! bare "init" "--bare" "-b" "main")
    (sh! root "clone" (str bare) repo-name)
    (sh! clone "config" "user.email" "test@example.com")
    (sh! clone "config" "user.name" "Test")
    (commit-file! clone "README.md" "hello\n")
    (sh! clone "push" "-u" "origin" "main")
    (sh! clone "remote" "set-head" "origin" "-a")
    clone))

(defn- with-framework
  "Calls (f root changes) with a temp framework root holding two repos,
  alpha and beta, and an empty changes dir."
  [f]
  (fs/with-temp-dir [tmp {}]
    (let [root    (fs/path tmp "framework")
          remotes (fs/path tmp "remotes")
          changes (fs/path tmp "changes")]
      (fs/create-dirs root)
      (fs/create-dirs remotes)
      (make-repo! root remotes "alpha")
      (make-repo! root remotes "beta")
      (f root changes))))

(defn- branch-of
  [root repo]
  (git/current-branch (fs/path root repo)))

(deftest new-and-checkout-test
  (with-framework (fn [root changes]
    (change/new! root changes ["fix" "alpha" "beta" "--description" "a fix"])
    (testing "new writes the file and creates branches without switching to them"
      (is (= {:name "fix" :description "a fix" :repos {"alpha" "fix" "beta" "fix"}}
             (change-set/read-change-set changes "fix")))
      (is (git/branch-exists? (fs/path root "alpha") "fix"))
      (is (= "main" (branch-of root "alpha"))))
    (testing "an existing change set isn't overwritten"
      (is (thrown? Exception (change/new! root changes ["fix" "alpha"]))))
    (testing "checkout puts every repo on its branch"
      (checkout/checkout! root changes "fix" {})
      (is (= ["fix" "fix"] [(branch-of root "alpha") (branch-of root "beta")])))
    (testing "checkout main returns every repo to its default branch"
      (checkout/checkout! root changes "main" {})
      (is (= ["main" "main"] [(branch-of root "alpha") (branch-of root "beta")]))))))

(deftest checkout-refuses-dirty-repos-test
  (with-framework (fn [root changes]
    (change/new! root changes ["fix" "alpha" "beta"])
    (spit (fs/file (fs/path root "beta" "README.md")) "uncommitted\n")
    (testing "one dirty repo blocks the whole checkout"
      (is (thrown? Exception (checkout/checkout! root changes "fix" {})))
      (is (= ["main" "main"] [(branch-of root "alpha") (branch-of root "beta")])))
    (testing "--stash stashes the dirty repo and checks out everything"
      (checkout/checkout! root changes "fix" {:stash? true})
      (is (= ["fix" "fix"] [(branch-of root "alpha") (branch-of root "beta")]))
      (is (str/includes? (:out (sh! (fs/path root "beta") "stash" "list")) "dough checkout fix autostash"))
      (is (= "hello\n" (slurp (fs/file (fs/path root "beta" "README.md")))))))))

(deftest done-requires-merged-branches-test
  (with-framework (fn [root changes]
    (change/new! root changes ["fix" "alpha" "beta"])
    (checkout/checkout! root changes "fix" {})
    (commit-file! (fs/path root "alpha") "FIX.md" "fixed\n")
    (testing "refuses while a branch has commits not on origin's default branch"
      (is (thrown? Exception (change/done! root changes "fix")))
      (is (fs/exists? (change-set/path changes "fix"))))
    (testing "deletes the file once every branch is merged and pushed"
      (sh! (fs/path root "alpha") "checkout" "main")
      (sh! (fs/path root "alpha") "merge" "--ff-only" "fix")
      (sh! (fs/path root "alpha") "push" "origin" "main")
      (change/done! root changes "fix")
      (is (not (fs/exists? (change-set/path changes "fix"))))))))

(deftest list-change-sets-test
  (with-framework (fn [root changes]
    (is (= [] (change-set/list-change-sets changes)))
    (change/new! root changes ["b-fix" "beta"])
    (change/new! root changes ["a-fix" "alpha"])
    (is (= ["a-fix" "b-fix"] (mapv :name (change-set/list-change-sets changes)))))))
