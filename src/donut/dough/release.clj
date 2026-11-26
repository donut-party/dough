(ns donut.dough.release
  "Implements `dough release [repo...] [--dry-run]`: releases every library
  repo whose default branch is ahead of its `release` branch (or just the
  named repos), in dependency order. Releasing a repo pushes its default
  branch's tip to `release`, which runs its CI deploy job, then waits for
  that CI run to pass and for each of its libraries' new versions
  (`<version-prefix>.<commit count>`, the donut.build scheme) to appear on
  Clojars. Stops at the first failure, so nothing is released on top of a
  broken dependency."
  (:require
   [babashka.fs :as fs]
   [babashka.http-client :as http]
   [babashka.process :as ps]
   [cheshire.core :as json]
   [clojure.string :as str]
   [donut.dough.git :as git]
   [donut.dough.github :as github]
   [donut.dough.graph :as graph]
   [donut.dough.process :as process]
   [donut.dough.registry :as registry]
   [donut.dough.structure :as structure]))

(def release-branch "release")

(defn- fail!
  [msg]
  (throw (ex-info msg {:babashka/exit 1})))

(defn lib-versions
  "{lib version} each library in `repo-libs` ({lib dir}) gets when built at
  a commit with `commit-count` commits."
  [repo-libs commit-count]
  (into (sorted-map)
        (keep (fn [[lib dir]]
                (when-let [prefix (:version-prefix (registry/donut-lib (fs/path dir "deps.edn")))]
                  [lib (str prefix "." commit-count)])))
        repo-libs))

(defn repo-plan
  "Fetches `repo` and describes its release state."
  [framework-root model repo]
  (let [dir     (structure/repo-path framework-root repo)
        _       (git/fetch! dir)
        main    (str "origin/" (git/default-branch dir))
        release (str "origin/" release-branch)
        sha     (git/rev-parse dir main)
        commits (parse-long (str/trim (:out (ps/sh {:dir (str dir)} "git" "rev-list" "--count" main))))
        [ahead behind] (if (git/rev-parse dir release)
                         (git/ahead-behind dir release main)
                         [commits 0])]
    {:repo      repo
     :dir       dir
     :sha       sha
     :pending?  (pos? ahead)
     :ahead     ahead
     :diverged? (pos? behind)
     :versions  (lib-versions (graph/repo-libs model repo) commits)}))

(defn select-repos
  "Repos to release, in dependency order: the named ones, or else every
  library repo that's still active on GitHub."
  [order named active-repos]
  (if (seq named)
    (let [unknown (remove (set order) named)]
      (when (seq unknown)
        (fail! (str "not a checked-out library repo: " (str/join ", " unknown))))
      (filterv (set named) order))
    (filterv active-repos order)))

(defn print-plan!
  [plans]
  (doseq [{:keys [repo pending? ahead diverged? versions]} plans]
    (println (format "  %-28s %s" repo
                     (cond
                       diverged?      "BLOCKED: release has commits that aren't on the default branch"
                       (not pending?) "up to date"
                       :else          (str ahead " new commit(s) -> "
                                           (str/join ", " (map (fn [[lib v]] (str (name lib) " " v)) versions))))))))

;; ---------------------------------------------------------------------------
;; waiting on CI and Clojars

(defn- poll
  "Calls (f) every `interval-ms` until it returns a truthy value or
  `timeout-ms` passes; returns that value or nil."
  [f interval-ms timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (f)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep interval-ms)
            (recur))))))

(defn- release-run
  "The completed GitHub Actions CI run on the release branch for `sha`."
  [repo sha]
  (let [{:keys [exit out]} (ps/sh "gh" "run" "list" "-R" (str github/org "/" repo)
                                  "--branch" release-branch "--limit" "10"
                                  "--json" "headSha,status,conclusion,url,workflowName")]
    (when (zero? exit)
      (->> (json/parse-string out true)
           (filter #(and (= sha (:headSha %)) (= "CI" (:workflowName %)) (= "completed" (:status %))))
           first))))

(defn published?
  [lib version]
  (let [path (str (str/replace (namespace lib) "." "/") "/" (name lib) "/" version "/" (name lib) "-" version ".pom")]
    (= 200 (:status (http/head (str "https://repo.clojars.org/" path) {:throw false})))))

(defn release-repo!
  [{:keys [repo dir sha versions diverged?]}]
  (when diverged?
    (fail! (str repo ": " release-branch " has commits that aren't on the default branch; fix it by hand")))
  (process/tee (format "\n-> %s: push %s to %s" repo (subs sha 0 7) release-branch) :bold)
  (let [{:keys [exit err]} (ps/sh {:dir (str dir)} "git" "push" "origin" (str sha ":refs/heads/" release-branch))]
    (when-not (zero? exit)
      (fail! (str repo ": push to " release-branch " failed: " err))))
  (let [run (or (poll #(release-run repo sha) 15000 (* 40 60 1000))
                (fail! (str repo ": no finished CI run for " (subs sha 0 7) " on " release-branch " after 40 minutes")))]
    (when-not (= "success" (:conclusion run))
      (fail! (str repo ": release CI " (:conclusion run) ": " (:url run))))
    (println "   CI passed:" (:url run)))
  (doseq [[lib version] versions]
    (or (poll #(published? lib version) 15000 (* 15 60 1000))
        (fail! (str lib " " version " didn't appear on Clojars within 15 minutes")))
    (println "   published" lib version)))

(defn release!
  [framework-root named {:keys [dry-run?]}]
  (let [model  (graph/local-repos framework-root)
        active (set (map :name (github/org-repos)))
        plans  (mapv #(repo-plan framework-root model %) (select-repos (:order model) named active))]
    (print-plan! plans)
    (when-not dry-run?
      (doseq [plan plans
              :when (:pending? plan)]
        (release-repo! plan)))
    plans))

(defn -main
  [& args]
  (release! (structure/framework-root)
            (remove #{"--dry-run"} args)
            {:dry-run? (boolean (some #{"--dry-run"} args))}))
