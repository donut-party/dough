(ns donut.dough.sync-test
  "Pure-fn tests plus integration tests that exercise sync-repo! against real
  temp git repos -- the branching logic (conflict vs. clean, dirty vs. not)
  is only trustworthy if it's proven against actual git behavior."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as ps]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [donut.dough.git :as git]
   [donut.dough.sync :as sync-repos]))

;; ---------------------------------------------------------------------------
;; pure fns

(deftest synced?-test
  (testing "no conflicts anywhere"
    (is (true? (sync-repos/synced? {:main-conflict? false :branch-conflict? :na :dirty-conflict? :na}))))
  (testing "any yes makes it unsynced"
    (is (false? (sync-repos/synced? {:main-conflict? true :branch-conflict? :na :dirty-conflict? :na})))
    (is (false? (sync-repos/synced? {:main-conflict? false :branch-conflict? true :dirty-conflict? :na})))
    (is (false? (sync-repos/synced? {:main-conflict? false :branch-conflict? :na :dirty-conflict? true})))))

(deftest conflict-labels-test
  (testing "no conflicts"
    (is (= [] (sync-repos/conflict-labels {:dirty-conflict? :na :main-conflict? false :branch-conflict? :na}))))
  (testing "always dirty, main, branch order regardless of which are present"
    (is (= ["dirty" "main" "branch"]
           (sync-repos/conflict-labels {:dirty-conflict? true :main-conflict? true :branch-conflict? true})))
    (is (= ["main" "branch"]
           (sync-repos/conflict-labels {:dirty-conflict? :na :main-conflict? true :branch-conflict? true})))
    (is (= ["dirty"]
           (sync-repos/conflict-labels {:dirty-conflict? true :main-conflict? false :branch-conflict? :na})))))

(deftest ->row-test
  (let [row (sync-repos/->row {:repo "system" :branch "main" :dirty? false :dirty-conflict? :na
                                :main-conflict? false :branch-conflict? true})]
    (is (= "system" (nth row 0)))
    (is (str/includes? (nth row 1) "✗"))
    (is (= "main" (nth row 2)))
    (is (str/includes? (nth row 3) "branch")))
  (testing "conflicts are highlighted, not plain text"
    (let [row (sync-repos/->row {:repo "system" :branch "main" :dirty? true :dirty-conflict? true
                                  :main-conflict? false :branch-conflict? :na})]
      (is (not= "dirty" (nth row 3)) "colored, not plain text")
      (is (str/includes? (nth row 3) "dirty"))))
  (testing "no conflicts reads as \"none\", not blank"
    (let [row (sync-repos/->row {:repo "system" :branch "main" :dirty? false :dirty-conflict? :na
                                  :main-conflict? false :branch-conflict? :na})]
      (is (= "none" (nth row 3))))))

(deftest ->csv-test
  (is (= "a,b\nc,d" (sync-repos/->csv [["a" "b"] ["c" "d"]]))))

(deftest remote-repo-name-test
  (is (= "box.identity" (sync-repos/remote-repo-name "git@github.com:donut-party/box.identity.git")))
  (testing "org/user segment doesn't matter, only the repo name does"
    (is (= "system" (sync-repos/remote-repo-name "git@github.com:donut-power/system.git")))
    (is (= "system" (sync-repos/remote-repo-name "git@github.com:donut-party/system.git"))))
  (testing "https remotes work too"
    (is (= "system" (sync-repos/remote-repo-name "https://github.com/donut-party/system.git"))))
  (is (nil? (sync-repos/remote-repo-name nil))))

(deftest process-pool!-test
  (testing "results come back in input order, regardless of completion order"
    (let [inputs [{:n 0 :sleep-ms 30} {:n 1 :sleep-ms 0} {:n 2 :sleep-ms 20} {:n 3 :sleep-ms 0}]
          results (sync-repos/process-pool! 4
                                             (fn [{:keys [n sleep-ms]}]
                                               (Thread/sleep sleep-ms)
                                               n)
                                             inputs
                                             (constantly nil)
                                             (constantly nil))]
      (is (= [0 1 2 3] results))))
  (testing "every item is processed exactly once, even with more workers than items"
    (let [processed (atom [])
          inputs    (range 10)]
      (sync-repos/process-pool! 4
                                 (fn [n] (swap! processed conj n) n)
                                 inputs
                                 (constantly nil)
                                 (constantly nil))
      (is (= (set inputs) (set @processed)))
      (is (= 10 (count @processed)))))
  (testing "on-start/on-finish fire once per item"
    (let [starts  (atom [])
          finishes (atom [])]
      (sync-repos/process-pool! 3
                                 identity
                                 [:a :b :c :d :e]
                                 (fn [item] (swap! starts conj item))
                                 (fn [item result] (swap! finishes conj [item result])))
      (is (= (frequencies [:a :b :c :d :e]) (frequencies @starts)))
      (is (= (set [[:a :a] [:b :b] [:c :c] [:d :d] [:e :e]]) (set @finishes)))))
  (testing "empty input"
    (is (= [] (sync-repos/process-pool! 4 identity [] (constantly nil) (constantly nil))))))

;; ---------------------------------------------------------------------------
;; integration: sync-repo! against real temp git repos

(defn- sh!
  "Runs a git command in dir, throwing (with stderr) on failure -- test
  setup should never fail silently."
  [dir & args]
  (let [{:keys [exit err] :as result} (apply ps/sh {:dir (str dir) :out :string :err :string} "git" args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (str/join " " args) " failed: " err) result)))
    result))

(defn- write-and-commit!
  [dir filename content message]
  (spit (fs/file (fs/path dir filename)) content)
  (sh! dir "add" filename)
  (sh! dir "commit" "-m" message))

(defn- dirty-modify!
  [dir filename content]
  (spit (fs/file (fs/path dir filename)) content))

(defn- init-remote+clone!
  "A bare `origin` repo with one commit on main, and a clone of it wired up
  the way a real sibling repo checkout is (tracking branches, remote HEAD
  set) so default-branch/upstream-ref behave normally."
  [root]
  (let [bare  (fs/path root "origin.git")
        clone (fs/path root "clone")]
    (fs/create-dirs bare)
    (sh! bare "init" "--bare" "-b" "main")
    (sh! root "clone" (str bare) (str clone))
    (sh! clone "config" "user.email" "test@example.com")
    (sh! clone "config" "user.name" "Test")
    (write-and-commit! clone "README.md" "line one\n" "initial commit")
    (sh! clone "push" "-u" "origin" "main")
    (sh! clone "remote" "set-head" "origin" "-a")
    {:bare bare :clone clone}))

(defn- push-from-second-clone!
  "Simulates another contributor: clones bare fresh, commits filename ->
  content on branch, pushes it."
  [bare root branch filename content message]
  (let [second (fs/path root (str "second-" (name (gensym))))]
    (sh! root "clone" (str bare) (str second))
    (sh! second "config" "user.email" "test@example.com")
    (sh! second "config" "user.name" "Test")
    (when (not= branch "main")
      (sh! second "checkout" "-b" branch (str "origin/" branch)))
    (write-and-commit! second filename content message)
    (sh! second "push" "origin" branch)))

(defmacro with-repo
  [[clone-sym] & body]
  `(fs/with-temp-dir [root# {}]
     (let [{:keys [~'bare ~clone-sym]} (init-remote+clone! root#)
           ~'root                     root#]
       ~@body)))

(deftest sync-repo!-clean-in-sync-test
  (with-repo [clone]
    (let [result (sync-repos/sync-repo! clone)]
      (is (= {:dirty? false :dirty-conflict? :na :main-conflict? false :branch-conflict? :na}
             (dissoc result :repo :branch)))
      (is (true? (sync-repos/synced? result)))
      (is (= "main" (git/current-branch clone))))))

(deftest sync-repo!-main-fast-forwards-cleanly-test
  (with-repo [clone]
    (push-from-second-clone! bare root "main" "OTHER.md" "remote-only\n" "remote change")
    (let [pushed-sha (git/rev-parse bare "main")
          result     (sync-repos/sync-repo! clone)]
      (is (false? (:main-conflict? result)))
      (is (= pushed-sha (git/rev-parse clone "main")) "local main caught up to the fetched remote tip")
      (is (true? (sync-repos/synced? result))))))

(deftest sync-repo!-main-conflict-leaves-main-untouched-test
  (with-repo [clone]
    (let [original-main (git/rev-parse clone "main")]
      ;; a divergent remote commit on main touching the same line...
      (push-from-second-clone! bare root "main" "README.md" "remote version\n" "remote edit")
      ;; ...and a local-only, unpushed commit on main touching it differently
      (write-and-commit! clone "README.md" "local version\n" "local edit")
      (let [local-main (git/rev-parse clone "main")
            result     (sync-repos/sync-repo! clone)]
        (is (true? (:main-conflict? result)))
        (is (false? (sync-repos/synced? result)))
        (is (= local-main (git/rev-parse clone "main")) "conflicted rebase was aborted; main is unchanged")
        (is (not= original-main local-main) "sanity: the local commit really landed")))))

(deftest sync-repo!-branch-conflict-leaves-branch-untouched-test
  (with-repo [clone]
    (sh! clone "checkout" "-b" "feature")
    (sh! clone "push" "-u" "origin" "feature")
    ;; a divergent remote commit on feature...
    (push-from-second-clone! bare root "feature" "README.md" "remote feature version\n" "remote edit")
    ;; ...and a local-only, unpushed commit on feature touching it differently
    (write-and-commit! clone "README.md" "local feature version\n" "local edit")
    (let [local-feature (git/rev-parse clone "feature")
          result        (sync-repos/sync-repo! clone)]
      (is (false? (:main-conflict? result)))
      (is (true? (:branch-conflict? result)))
      (is (false? (sync-repos/synced? result)))
      (is (= local-feature (git/rev-parse clone "feature")) "conflicted branch rebase was aborted")
      (is (= "feature" (git/current-branch clone)) "left checked out on the branch it started on"))))

(deftest sync-repo!-branch-without-upstream-is-na-test
  (with-repo [clone]
    (sh! clone "checkout" "-b" "wip")
    (let [result (sync-repos/sync-repo! clone)]
      (is (= :na (:branch-conflict? result)))
      (is (true? (sync-repos/synced? result)))
      (is (= "wip" (git/current-branch clone))))))

(deftest sync-repo!-dirty-changes-reapply-cleanly-test
  (with-repo [clone]
    (push-from-second-clone! bare root "main" "OTHER.md" "remote-only\n" "remote change")
    (dirty-modify! clone "README.md" "dirty, unrelated line\n")
    (let [result (sync-repos/sync-repo! clone)]
      (is (true? (:dirty? result)))
      (is (false? (:main-conflict? result)))
      (is (false? (:dirty-conflict? result)))
      (is (true? (sync-repos/synced? result)))
      (is (= "dirty, unrelated line\n" (slurp (fs/file (fs/path clone "README.md")))))
      (is (str/includes? (:out (sh! clone "status" "--porcelain")) "README.md")))))

(deftest sync-repo!-dirty-conflict-undoes-main-rebase-test
  (with-repo [clone]
    (let [original-main (git/rev-parse clone "main")]
      (push-from-second-clone! bare root "main" "README.md" "remote version\n" "remote edit")
      (dirty-modify! clone "README.md" "dirty conflicting version\n")
      (let [result (sync-repos/sync-repo! clone)]
        (is (true? (:dirty? result)))
        (is (false? (:main-conflict? result)) "the rebase itself was a clean fast-forward")
        (is (true? (:dirty-conflict? result)) "but reapplying the stash onto the new tip conflicted")
        (is (false? (sync-repos/synced? result)))
        (is (= original-main (git/rev-parse clone "main")) "main rebase was undone")
        (is (= "dirty conflicting version\n" (slurp (fs/file (fs/path clone "README.md"))))
            "the dirty change survived, untouched")))))

(deftest sync-repo!-dirty-conflict-undoes-both-main-and-branch-rebases-test
  (with-repo [clone]
    (sh! clone "checkout" "-b" "feature")
    (sh! clone "push" "-u" "origin" "feature")
    (let [original-main    (git/rev-parse clone "main")
          original-feature (git/rev-parse clone "feature")]
      (push-from-second-clone! bare root "main" "OTHER.md" "remote-only\n" "remote main change")
      (push-from-second-clone! bare root "feature" "README.md" "remote feature version\n" "remote feature edit")
      (dirty-modify! clone "README.md" "dirty conflicting version\n")
      (let [result (sync-repos/sync-repo! clone)]
        (is (true? (:dirty? result)))
        (is (false? (:main-conflict? result)))
        (is (false? (:branch-conflict? result)))
        (is (true? (:dirty-conflict? result)))
        (is (false? (sync-repos/synced? result)))
        (is (= original-main (git/rev-parse clone "main")) "main rebase was undone")
        (is (= original-feature (git/rev-parse clone "feature")) "feature rebase was undone")
        (is (= "feature" (git/current-branch clone)))
        (is (= "dirty conflicting version\n" (slurp (fs/file (fs/path clone "README.md")))))))))
