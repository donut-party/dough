(ns donut.project-management.sync-repos
  "Syncs every repo in the donut-party GitHub org: clones any that aren't
  checked out locally yet, fetches, then tries to rebase local main onto
  origin's main and, if a different branch is checked out, that branch
  onto its upstream. Prints a report of what happened.

  A repo only ends up changed if it can end up *fully* synced. Any conflict
  -- in the main rebase, the branch rebase, or reapplying stashed dirty
  changes afterward -- causes that repo's rebases to be undone, so a repo
  is always left either fully synced or exactly as it started."
  (:require
   [babashka.fs :as fs]
   [bblgum.core :as gum]
   [bling.core :as bling]
   [clojure.string :as str]
   [donut.project-management.git :as git]
   [donut.project-management.github :as github]
   [donut.project-management.structure :as structure]))

(defn remote-repo-name
  "The repo name a remote URL identifies, e.g. \"box.identity\" from
  git@github.com:donut-party/box.identity.git -- independent of both the
  host's org/user segment (which can drift when a repo is renamed or
  transferred) and whatever the local directory happens to be named."
  [url]
  (some-> url (str/replace #"\.git$" "") (str/split #"[/:]") last))

(defn repo-descriptors
  "Every donut-party org repo (see donut.project-management.github) other
  than this one -- sync-repos doesn't sync itself, since sibling-repo-paths
  already excludes it from :local-path lookup, and treating it as
  \"missing\" would have this task try to clone into its own directory --
  paired with its local sibling-repo path if it's already cloned."
  []
  (let [this-repo     (remote-repo-name (git/remote-url (structure/project-management-root) "origin"))
        local-by-name (into {}
                             (map (fn [dir] [(remote-repo-name (git/remote-url dir "origin")) dir]))
                             (structure/sibling-repo-paths))]
    (->> (github/org-repos)
         (remove #(= this-repo (:name %)))
         (mapv #(assoc % :local-path (get local-by-name (:name %)))))))

(defn ensure-cloned!
  "The local path for repo, cloning it via SSH into framework-root first
  if it isn't checked out yet."
  [framework-root {:keys [name ssh-url local-path]}]
  (or local-path
      (let [dest   (fs/path framework-root name)
            result (git/clone! framework-root ssh-url name)]
        (when-not (git/success? result)
          (throw (ex-info (str "git clone " ssh-url " failed: " (:err result)) result)))
        dest)))

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
     :branch           starting-branch
     :dirty?           dirty?
     :dirty-conflict?  (if stashed? dirty-conflict? :na)
     :main-conflict?   (:conflict? main-result)
     :branch-conflict? (if branch-upstream (:conflict? branch-result) :na)}))

(defn synced?
  [{:keys [dirty-conflict? main-conflict? branch-conflict?]}]
  (not (or main-conflict? (true? dirty-conflict?) (true? branch-conflict?))))

(def columns ["repo" "branch" "sync?" "dirty?" "dirty conflicts?" "main conflicts?" "branch conflicts?"])

(defn- cell
  [v]
  (case v
    :na    "NA"
    true   "yes"
    false  "no"))

(defn ->row
  [result]
  [(:repo result)
   (:branch result)
   (if (synced? result) (bling/bling [:positive "✓"]) (bling/bling [:negative "✗"]))
   (if (:dirty? result) (bling/bling [:warning "yes"]) "no")
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
           :header.foreground "212"
           ;; otherwise gum highlights row 0 as if it were "selected"
           :selected.foreground ""
           :selected.background ""))

(def ^:private spinner-frames ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn- animate!
  "Runs f, printing an animated spinner + whatever status-atom currently
  holds on a single, self-overwriting line until f returns. f is
  responsible for reset!-ing status-atom as it makes progress."
  [status-atom f]
  (let [stop? (atom false)
        anim  (future
                (loop [i 0]
                  (when-not @stop?
                    (print (str "\r[K" (nth spinner-frames (mod i (count spinner-frames))) " " @status-atom))
                    (flush)
                    (Thread/sleep 80)
                    (recur (inc i)))))]
    (try
      (f)
      (finally
        (reset! stop? true)
        @anim
        (print "\r[K")
        (flush)))))

(defn -main
  [& _]
  (let [framework-root (structure/framework-root)
        repos          (repo-descriptors)
        total          (count repos)
        status         (atom "")
        results        (atom [])]
    (animate! status
              (fn []
                (doseq [[i {:keys [name local-path] :as repo}] (map-indexed vector repos)]
                  (when-not local-path
                    (reset! status (format "clone (%d/%d) %s..." (inc i) total name)))
                  (let [repo-dir (ensure-cloned! framework-root repo)]
                    (reset! status (format "sync (%d/%d) %s..." (inc i) total name))
                    (swap! results conj (sync-repo! repo-dir))))))
    (print-report! (mapv ->row @results))))
