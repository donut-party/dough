(ns donut.project-management.sync-repos
  "Syncs every sibling donut repo with GitHub: fetches, then tries to rebase
  local main onto origin's main and, if a different branch is checked out,
  that branch onto its upstream. Prints a report of what happened.

  A repo only ends up changed if it can end up *fully* synced. Any conflict
  -- in the main rebase, the branch rebase, or reapplying stashed dirty
  changes afterward -- causes that repo's rebases to be undone, so a repo
  is always left either fully synced or exactly as it started."
  (:require
   [babashka.fs :as fs]
   [bblgum.core :as gum]
   [bling.core :as bling]
   [clojure.string :as str]
   [donut.bb-utils.process :as process]
   [donut.project-management.git :as git]
   [donut.project-management.structure :as structure]))

(defn repo-paths
  "Sibling repos worth syncing: git repos with a `origin` remote to sync
  against (this rules out scratch dirs like tmp-fix-verify that aren't git
  repos at all, and repos like event-bus that have no remote configured)."
  []
  (->> (structure/sibling-repo-paths)
       (filter #(git/remote-url % "origin"))))

(defn sync-repo!
  "Fetches repo-dir and tries to bring it in sync with its remote. Returns a
  result map describing what happened; see `->row` for how it's reported."
  [repo-dir]
  (git/fetch! repo-dir)
  (let [main-branch     (git/default-branch repo-dir)
        starting-branch (git/current-branch repo-dir)
        on-main?        (= starting-branch main-branch)
        dirty?          (git/dirty? repo-dir)
        main-sha        (git/rev-parse repo-dir main-branch)
        branch-sha      (when-not on-main? (git/rev-parse repo-dir starting-branch))
        stashed?        (and dirty? (git/stash-push! repo-dir))

        main-result     (git/rebase! repo-dir (str "origin/" main-branch) main-branch)

        branch-upstream (when-not on-main? (git/upstream-ref repo-dir starting-branch))
        branch-result   (when branch-upstream
                           (git/checkout! repo-dir starting-branch)
                           (git/rebase! repo-dir branch-upstream starting-branch))

        _               (git/checkout! repo-dir starting-branch)

        dirty-conflict? (when stashed?
                           (let [popped? (git/stash-pop! repo-dir)]
                             (when-not popped?
                               ;; Undo whatever rebasing happened so the
                               ;; stash re-applies onto the original tips,
                               ;; which is guaranteed conflict-free.
                               (git/reset-hard! repo-dir "HEAD")
                               (when-not (:conflict? main-result)
                                 (if on-main?
                                   (git/reset-hard! repo-dir main-sha)
                                   (git/update-ref! repo-dir main-branch main-sha)))
                               (when (and branch-upstream (not (:conflict? branch-result)))
                                 (git/reset-hard! repo-dir branch-sha))
                               (git/checkout! repo-dir starting-branch)
                               (git/stash-pop! repo-dir))
                             (not popped?)))]
    {:repo             (str (fs/file-name repo-dir))
     :dirty?           dirty?
     :dirty-conflict?  (if stashed? dirty-conflict? :na)
     :main-conflict?   (:conflict? main-result)
     :branch-conflict? (if branch-upstream (:conflict? branch-result) :na)}))

(defn synced?
  [{:keys [dirty-conflict? main-conflict? branch-conflict?]}]
  (not (or main-conflict? (true? dirty-conflict?) (true? branch-conflict?))))

(def columns ["repo" "sync?" "dirty?" "dirty conflicts?" "main conflicts?" "branch conflicts?"])

(defn- cell
  [v]
  (case v
    :na    "NA"
    true   "yes"
    false  "no"))

(defn ->row
  [result]
  [(:repo result)
   (if (synced? result) (bling/bling [:positive "✓"]) (bling/bling [:negative "✗"]))
   (cell (:dirty? result))
   (cell (:dirty-conflict? result))
   (cell (:main-conflict? result))
   (cell (:branch-conflict? result))])

(defn ->csv
  [rows]
  (str/join "\n" (map #(str/join "," %) rows)))

(defn print-report!
  [rows]
  (gum/gum :table :in (->csv rows) :as :ignored :print true
           :columns columns
           :border.foreground "212"
           :header.foreground "212"))

(defn -main
  [& _]
  (let [results (mapv (fn [repo-dir]
                         (process/tee (format "[%s] syncing..." (fs/file-name repo-dir)))
                         (sync-repo! repo-dir))
                       (repo-paths))]
    (print-report! (mapv ->row results))))
