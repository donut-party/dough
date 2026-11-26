(ns donut.dough.land
  "Implements `dough change land <name> [--dry-run]`: lands a change set in
  the order that never leaves a default branch pointing at something
  unreleased:

  1. each library repo in the change set, in dependency order: merge its
     branch into the default branch, push, and release it (dough release);
  2. the app template, if it's in the change set: merge and push;
  3. `dough pin`: point the template at the new releases and the CLI at the
     template's new main, committing and pushing each;
  4. every other repo (the CLI, apps): merge and push;
  5. delete the change set file and push that.

  Stops at the first failure; rerunning picks up where it stopped, since
  merged branches merge as no-ops and released repos aren't re-released."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as ps]
   [clojure.string :as str]
   [donut.dough.change-set :as change-set]
   [donut.dough.cli-update-deps :as cli-update-deps]
   [donut.dough.git :as git]
   [donut.dough.graph :as graph]
   [donut.dough.pin :as pin]
   [donut.dough.process :as process]
   [donut.dough.registry :as registry]
   [donut.dough.release :as release]
   [donut.dough.structure :as structure]))

(defn- fail!
  [msg]
  (throw (ex-info msg {:babashka/exit 1})))

(defn land-steps
  "The ordered steps for a change set's repos, given the library repos in
  dependency order."
  [repos library-order]
  (let [repos     (set repos)
        libraries (filterv repos library-order)
        template? (contains? repos registry/template-repo)
        others    (sort (remove (into #{registry/template-repo} libraries) repos))]
    (vec (concat (mapcat (fn [repo] [[:merge repo] [:release repo]]) libraries)
                 (when template? [[:merge registry/template-repo]])
                 [[:pin]]
                 (map (fn [repo] [:merge repo]) others)
                 [[:done]]))))

(defn- git!
  [dir & args]
  (let [{:keys [exit out err] :as result} (apply ps/sh {:dir (str dir)} "git" args)]
    (when-not (zero? exit)
      (fail! (str (fs/file-name dir) ": git " (str/join " " args) " failed: " err)))
    (assoc result :out (str/trim out))))

(defn merge-branch!
  "Merges `branch` into the default branch and pushes it."
  [dir branch]
  (when (git/dirty? dir)
    (fail! (str (fs/file-name dir) " has uncommitted changes")))
  (let [default (git/default-branch dir)]
    (git! dir "checkout" default)
    (git! dir "pull" "--ff-only" "origin" default)
    (let [{:keys [exit err]} (ps/sh {:dir (str dir)} "git" "merge" "--no-edit" branch)]
      (when-not (zero? exit)
        (ps/sh {:dir (str dir)} "git" "merge" "--abort")
        (fail! (str (fs/file-name dir) ": merging " branch " into " default " failed, merge aborted: " err))))
    (git! dir "push" "origin" default)
    (process/tee (format "   %s: merged %s into %s and pushed" (fs/file-name dir) branch default) nil)))

(defn commit-and-push!
  "Commits `paths` (relative to `dir`) if they changed, and pushes."
  [dir paths message]
  (when (seq (:out (apply git! dir "status" "--porcelain" "--" paths)))
    (apply git! dir "add" "--" paths)
    (apply git! dir "commit" "-m" message "--" paths)
    (git! dir "push" "origin" (str "HEAD:" (git/default-branch dir)))
    (process/tee (format "   %s: committed and pushed %s" (fs/file-name dir) (str/join ", " paths)) nil)))

(defn pin!
  [framework-root]
  (let [template (structure/repo-path framework-root registry/template-repo)
        cli      (structure/repo-path framework-root "donut-cli")]
    (pin/pin-template! {})
    (commit-and-push! template [(str (fs/relativize template (pin/template-deps-path)))]
                      "Pin donut libraries to their newest releases")
    (cli-update-deps/-main)
    (commit-and-push! cli ["donut-cli/bb.edn"]
                      "Pin generate, generators, and the template to their newest releases")))

(defn land!
  [framework-root changes-dir change-name {:keys [dry-run?]}]
  (let [{:keys [repos] :as cs} (change-set/read-change-set changes-dir change-name)
        model (graph/local-repos framework-root)
        steps (land-steps (keys repos) (:order model))]
    (doseq [[step repo] steps]
      (println (str "  " (name step) (when repo (str " " repo)))))
    (when-not dry-run?
      (doseq [[step repo] steps]
        (process/tee (str "\n-> " (name step) (when repo (str " " repo))) :bold)
        (case step
          :merge   (merge-branch! (structure/repo-path framework-root repo) (get repos repo))
          :release (let [plan (release/repo-plan framework-root model repo)]
                     (if (:pending? plan)
                       (release/release-repo! plan)
                       (println "   already released")))
          :pin     (pin! framework-root)
          :done    (let [dough    (structure/dough-root)
                         rel      (str (fs/relativize dough (change-set/path changes-dir change-name)))
                         tracked? (zero? (:exit (ps/sh {:dir (str dough)} "git" "ls-files" "--error-unmatch" rel)))]
                     (change-set/delete-change-set! changes-dir change-name)
                     (when tracked?
                       (git! dough "rm" "--cached" "--quiet" rel)
                       (git! dough "commit" "-m" (str "Land change set " change-name) "--" rel)
                       (git! dough "push" "origin" (str "HEAD:" (git/default-branch dough))))
                     (println "   deleted" rel)))))
    cs))
