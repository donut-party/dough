(ns donut.project-management.repo-status-test
  "Pure-fn tests plus integration tests that exercise repo-status against a
  real temp git repo -- most-recent-mtime and the git plumbing are only
  trustworthy if proven against an actual working tree."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as ps]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [donut.project-management.repo-status :as repo-status]))

;; ---------------------------------------------------------------------------
;; pure fns

(deftest needs-attention?-test
  (testing "clean on default branch: nothing to report"
    (is (false? (repo-status/needs-attention? {:branch "main" :default-branch "main" :dirty? false}))))
  (testing "dirty is always worth reporting"
    (is (true? (repo-status/needs-attention? {:branch "main" :default-branch "main" :dirty? true}))))
  (testing "off the default branch is worth reporting even if clean"
    (is (true? (repo-status/needs-attention? {:branch "feature" :default-branch "main" :dirty? false})))))

(deftest ->row-test
  (let [row (repo-status/->row {:repo "graphputer" :branch "feature" :dirty? false
                                 :mtime "2027-05-31" :commit-date "2027-05-21"})]
    (is (= ["graphputer" "feature" "no" "2027-05-31" "2027-05-21"] row)))
  (testing "dirty is highlighted, not a plain \"yes\""
    (let [row (repo-status/->row {:repo "graphputer" :branch "main" :dirty? true
                                   :mtime "2027-05-31" :commit-date "2027-05-21"})]
      (is (not= "yes" (nth row 2)))
      (is (str/includes? (nth row 2) "yes")))))

(deftest ->csv-test
  (is (= "a,b\nc,d" (repo-status/->csv [["a" "b"] ["c" "d"]]))))

;; ---------------------------------------------------------------------------
;; integration: real temp dirs / git repos

(defn- sh!
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

(defn- init-repo!
  [dir]
  (sh! dir "init" "-b" "main")
  (sh! dir "config" "user.email" "test@example.com")
  (sh! dir "config" "user.name" "Test"))

(deftest most-recent-mtime-test
  (fs/with-temp-dir [dir {}]
    (let [older (fs/file (fs/path dir "older.txt"))
          newer (fs/file (fs/path dir "newer.txt"))]
      (spit older "old")
      (spit newer "new")
      (fs/set-last-modified-time older (- (System/currentTimeMillis) 100000))
      (fs/set-last-modified-time newer (System/currentTimeMillis))
      (is (= (repo-status/most-recent-mtime dir)
             (str (.toLocalDate (.atZone (java.time.Instant/ofEpochMilli (fs/file-time->millis (fs/last-modified-time newer)))
                                          (java.time.ZoneId/systemDefault))))))))
  (testing ".git internals don't count as \"any file\""
    (fs/with-temp-dir [dir {}]
      (let [source (fs/file (fs/path dir "source.txt"))
            git-dir (fs/create-dirs (fs/path dir ".git"))
            git-file (fs/file (fs/path git-dir "COMMIT_EDITMSG"))]
        (spit source "src")
        (fs/set-last-modified-time source (- (System/currentTimeMillis) 100000))
        (spit git-file "noise")
        (fs/set-last-modified-time git-file (System/currentTimeMillis))
        (is (= (repo-status/most-recent-mtime dir)
               (str (.toLocalDate (.atZone (java.time.Instant/ofEpochMilli (fs/file-time->millis (fs/last-modified-time source)))
                                            (java.time.ZoneId/systemDefault))))))))))

(deftest repo-status-clean-on-default-branch-test
  (fs/with-temp-dir [dir {}]
    (init-repo! dir)
    (write-and-commit! dir "README.md" "hello\n" "initial commit")
    (let [status (repo-status/repo-status dir)]
      (is (= "main" (:branch status)))
      (is (= "main" (:default-branch status)))
      (is (false? (:dirty? status)))
      (is (false? (repo-status/needs-attention? status))))))

(deftest repo-status-dirty-test
  (fs/with-temp-dir [dir {}]
    (init-repo! dir)
    (write-and-commit! dir "README.md" "hello\n" "initial commit")
    (spit (fs/file (fs/path dir "README.md")) "changed\n")
    (let [status (repo-status/repo-status dir)]
      (is (true? (:dirty? status)))
      (is (true? (repo-status/needs-attention? status))))))

(deftest repo-status-on-feature-branch-test
  (fs/with-temp-dir [dir {}]
    (init-repo! dir)
    (write-and-commit! dir "README.md" "hello\n" "initial commit")
    (sh! dir "checkout" "-b" "feature")
    (let [status (repo-status/repo-status dir)]
      (is (= "feature" (:branch status)))
      (is (= "main" (:default-branch status)))
      (is (false? (:dirty? status)))
      (is (true? (repo-status/needs-attention? status))))))
