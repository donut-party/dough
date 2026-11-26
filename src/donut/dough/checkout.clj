(ns donut.dough.checkout
  "Implements `dough checkout <change-set> [--stash]`: puts every repo in a
  change set on its branch. `dough checkout main` instead returns every
  sibling repo to its default branch. Nothing is checked out unless every
  repo can be, so a change set is never left half applied."
  (:require
   [babashka.fs :as fs]
   [donut.dough.change-set :as change-set]
   [donut.dough.git :as git]
   [donut.dough.structure :as structure]))

(def usage "usage: dough checkout <change-set | main> [--stash]")

(defn default-branch-targets
  "Every sibling repo that's off its default branch, mapped to that branch."
  [framework-root]
  (into {}
        (keep (fn [dir]
                (let [default (git/default-branch dir)]
                  (when (not= default (git/current-branch dir))
                    [(str (fs/file-name dir)) default]))))
        (structure/sibling-repo-paths framework-root (structure/dough-root))))

(defn targets
  [framework-root changes-dir target]
  (if (= "main" target)
    (default-branch-targets framework-root)
    (:repos (change-set/read-change-set changes-dir target))))

(def ^:private action-labels
  {:already-on         "already on"
   :checkout           "checked out"
   :stash-and-checkout "stashed changes, checked out"
   :refuse-dirty       "REFUSED: uncommitted changes (rerun with --stash)"
   :missing-branch     "REFUSED: branch doesn't exist"})

(defn print-plan!
  [plan]
  (doseq [{:keys [repo target-branch action]} plan]
    (println (format "  %-28s %-24s %s" repo target-branch (action-labels action)))))

(defn checkout!
  ([target opts] (checkout! (structure/framework-root) (change-set/changes-dir) target opts))
  ([framework-root changes-dir target opts]
   (let [plan (change-set/checkout-plan framework-root (targets framework-root changes-dir target) opts)]
     (if (change-set/blocked? plan)
       (do (print-plan! plan)
           (throw (ex-info "nothing was checked out" {:babashka/exit 1 :plan plan})))
       (do (change-set/checkout! plan target)
           (print-plan! plan)
           plan)))))

(defn -main
  [& args]
  (let [stash? (boolean (some #{"--stash"} args))
        [target] (remove #{"--stash"} args)]
    (when-not target
      (throw (ex-info usage {:babashka/exit 1})))
    (checkout! target {:stash? stash?})))
