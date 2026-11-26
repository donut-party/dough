(ns donut.dough.change-set
  "A change set names the branches, one per repo, that make up one
  cross-repo change. Each is a committed file, changes/<name>.edn:

    {:description \"route sharing fixes\"
     :repos       {\"generators\" \"compiler-fixes\"
                   \"frontend\"   \"compiler-fixes\"}}

  Repo names are the local directory names under the framework root.
  Change sets only coordinate development; nothing published reads them."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [donut.dough.git :as git]
   [donut.dough.structure :as structure]))

(def name-pattern #"[a-z0-9][a-z0-9._-]*")

(defn changes-dir
  []
  (fs/path (structure/dough-root) "changes"))

(defn path
  ([change-name] (path (changes-dir) change-name))
  ([dir change-name] (fs/path dir (str change-name ".edn"))))

(defn validate-name!
  [change-name]
  (when-not (and (string? change-name) (re-matches name-pattern change-name))
    (throw (ex-info (str "invalid change set name " (pr-str change-name)
                         ": use lowercase letters, digits, '.', '_' and '-'")
                    {:name change-name})))
  change-name)

(defn read-change-set
  "The change set named `change-name`, with its :name. Throws if it doesn't
  exist."
  ([change-name] (read-change-set (changes-dir) change-name))
  ([dir change-name]
   (let [file (fs/file (path dir change-name))]
     (when-not (fs/exists? file)
       (throw (ex-info (str "no change set named " change-name " in " dir) {:name change-name})))
     (assoc (edn/read-string (slurp file)) :name change-name))))

(defn write-change-set!
  ([change-set] (write-change-set! (changes-dir) change-set))
  ([dir {:keys [name description repos]}]
   (fs/create-dirs dir)
   (spit (fs/file (path dir name))
         (with-out-str
           (pprint/pprint {:description description
                           :repos       (into (sorted-map) repos)})))))

(defn delete-change-set!
  ([change-name] (delete-change-set! (changes-dir) change-name))
  ([dir change-name] (fs/delete (path dir change-name))))

(defn list-change-sets
  ([] (list-change-sets (changes-dir)))
  ([dir]
   (if (fs/exists? dir)
     (->> (fs/glob dir "*.edn")
          (map #(read-change-set dir (fs/strip-ext (fs/file-name %))))
          (sort-by :name)
          vec)
     [])))

(defn change-sets-by-repo-branch
  "{[repo branch] #{change-set-name ...}} across `change-sets`."
  [change-sets]
  (reduce (fn [m {:keys [name repos]}]
            (reduce (fn [m [repo branch]] (update m [repo branch] (fnil conj (sorted-set)) name))
                    m
                    repos))
          {}
          change-sets))

;; ---------------------------------------------------------------------------
;; checkout

(defn checkout-action
  "What `dough checkout` should do for one repo, given its current state:
  :already-on, :checkout, :stash-and-checkout, :refuse-dirty, or
  :missing-branch."
  [{:keys [current-branch target-branch dirty? branch-exists?]} {:keys [stash?]}]
  (cond
    (= current-branch target-branch) :already-on
    (not branch-exists?)             :missing-branch
    (and dirty? stash?)              :stash-and-checkout
    dirty?                           :refuse-dirty
    :else                            :checkout))

(defn repo-state
  [framework-root repo target-branch]
  (let [dir (structure/repo-path framework-root repo)]
    {:repo           repo
     :dir            dir
     :target-branch  target-branch
     :current-branch (git/current-branch dir)
     :dirty?         (git/dirty? dir)
     :branch-exists? (git/branch-exists? dir target-branch)}))

(defn checkout-plan
  "One step per repo: its state plus the :action `checkout!` will take.
  `targets` maps repo names to the branch each should be on."
  [framework-root targets opts]
  (->> targets
       (sort-by key)
       (mapv (fn [[repo branch]]
               (let [state (repo-state framework-root repo branch)]
                 (assoc state :action (checkout-action state opts)))))))

(defn blocked?
  "A plan is blocked if any repo would refuse; then nothing is checked out,
  so a change set is never left half applied."
  [plan]
  (boolean (some (comp #{:refuse-dirty :missing-branch} :action) plan)))

(defn checkout!
  "Carries out an unblocked plan. Stashes are never popped onto the new
  branch: the stash message names the change set so it can be found."
  [plan change-name]
  (doseq [{:keys [dir target-branch action]} plan]
    (case action
      :stash-and-checkout (do (git/stash-push! dir (str "dough checkout " change-name " autostash"))
                              (git/checkout! dir target-branch))
      :checkout           (git/checkout! dir target-branch)
      nil))
  plan)
