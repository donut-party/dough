(ns donut.project-management.git
  "Thin wrappers over the git CLI, used by the sync-repos and repo-status
  tasks. Every fn takes a repo dir as its first arg and shells out there;
  none of them are pure, and none of them throw on a non-zero exit (callers
  branch on that instead)."
  (:require
   [babashka.process :as ps]
   [clojure.string :as str]))

(defn- sh
  [dir & args]
  (apply ps/sh {:dir (str dir) :out :string :err :string} "git" args))

(defn success?
  [{:keys [exit]}]
  (zero? exit))

(defn dirty?
  [dir]
  (not (str/blank? (:out (sh dir "status" "--porcelain")))))

(defn current-branch
  [dir]
  (str/trim (:out (sh dir "rev-parse" "--abbrev-ref" "HEAD"))))

(defn default-branch
  "The remote's default branch, usually main, read from
  refs/remotes/origin/HEAD. Falls back to \"main\" if that ref was never set
  locally (e.g. `git remote set-head origin -a` was never run)."
  [dir]
  (let [result (sh dir "symbolic-ref" "refs/remotes/origin/HEAD")]
    (if (success? result)
      (last (str/split (str/trim (:out result)) #"/"))
      "main")))

(defn remote-url
  [dir remote]
  (let [result (sh dir "remote" "get-url" remote)]
    (when (success? result) (str/trim (:out result)))))

(defn fetch!
  [dir]
  (sh dir "fetch" "origin" "--prune"))

(defn rev-parse
  [dir ref]
  (let [result (sh dir "rev-parse" ref)]
    (when (success? result) (str/trim (:out result)))))

(defn upstream-ref
  "The remote-tracking branch `branch` pulls from, e.g. \"origin/feature\".
  nil if `branch` has no upstream configured."
  [dir branch]
  (let [result (sh dir "rev-parse" "--abbrev-ref" (str branch "@{u}"))]
    (when (success? result) (str/trim (:out result)))))

(defn checkout!
  [dir branch]
  (sh dir "checkout" branch))

(defn rebase!
  "Attempts to rebase `branch` onto `upstream`, checking `branch` out along
  the way (git rebase's own behavior). Aborts and leaves `branch` untouched
  on conflict."
  [dir upstream branch]
  (let [result (sh dir "rebase" upstream branch)]
    (if (success? result)
      {:conflict? false}
      (do (sh dir "rebase" "--abort")
          {:conflict? true}))))

(defn stash-push!
  "Stashes tracked and untracked changes. Returns true if there was
  anything to stash."
  [dir]
  (let [result (sh dir "stash" "push" "--include-untracked"
                    "-m" "donut sync-repos autostash")]
    (and (success? result)
         (not (str/includes? (:out result) "No local changes to save")))))

(defn stash-pop!
  "Pops the most recent stash. Returns true on a clean apply. On conflict,
  git leaves the conflicted merge in the working tree AND keeps the stash
  entry around, so a caller can still recover the pre-pop state."
  [dir]
  (success? (sh dir "stash" "pop")))

(defn reset-hard!
  [dir ref]
  (sh dir "reset" "--hard" ref))

(defn update-ref!
  "Force-points `branch` at `sha` without checking it out."
  [dir branch sha]
  (sh dir "update-ref" (str "refs/heads/" branch) sha))

(defn clone!
  "Clones url into dir/name."
  [dir url name]
  (sh dir "clone" url name))

(defn last-commit-date
  "The committer date of HEAD, as YYYY-MM-DD."
  [dir]
  (str/trim (:out (sh dir "log" "-1" "--format=%cd" "--date=short"))))
