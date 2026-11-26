(ns donut.dough.change
  "Implements `dough change`: create, list, push, and finish change sets.
  See donut.dough.change-set for the file format."
  (:require
   [babashka.fs :as fs]
   [bling.core :as bling]
   [clojure.string :as str]
   [donut.dough.change-set :as change-set]
   [donut.dough.git :as git]
   [donut.dough.land :as land]
   [donut.dough.structure :as structure]))

(def usage
  "usage: dough change new <name> <repo>... [--description TEXT]
       dough change list
       dough change push <name>
       dough change clone <name> [--shallow]
       dough change done <name>
       dough change land <name> [--dry-run]")

(defn- fail!
  [msg]
  (throw (ex-info msg {:babashka/exit 1})))

(defn- repo-dir!
  [framework-root repo]
  (let [dir (structure/repo-path framework-root repo)]
    (when-not (git/rev-parse dir "HEAD")
      (fail! (str repo " is not a git repo under " framework-root)))
    dir))

(defn parse-new-args
  "[name repos description] from `dough change new` arguments."
  [args]
  (loop [[arg & more] args, positional [], description nil]
    (cond
      (nil? arg)                  [(first positional) (vec (rest positional)) description]
      (= "--description" arg)     (recur (rest more) positional (first more))
      :else                       (recur more (conj positional arg) description))))

(defn new!
  "Creates branch `change-name` in each repo from its default branch (an
  existing branch of that name is kept) and writes the change set file."
  ([args] (new! (structure/framework-root) (change-set/changes-dir) args))
  ([framework-root changes-dir args]
   (let [[change-name repos description] (parse-new-args args)]
     (change-set/validate-name! change-name)
     (when (empty? repos) (fail! usage))
     (when (fs/exists? (change-set/path changes-dir change-name))
       (fail! (str "change set " change-name " already exists")))
     (doseq [repo repos
             :let [dir (repo-dir! framework-root repo)]]
       (if (git/branch-exists? dir change-name)
         (println (format "  %-28s branch %s already exists, keeping it" repo change-name))
         (let [result (git/create-branch! dir change-name (git/default-branch dir))]
           (when-not (git/success? result)
             (fail! (str repo ": git branch failed: " (:err result))))
           (println (format "  %-28s created branch %s" repo change-name)))))
     (change-set/write-change-set! changes-dir {:name        change-name
                                                :description (or description "")
                                                :repos       (zipmap repos (repeat change-name))})
     (println (str "wrote " (change-set/path changes-dir change-name)
                   "; run `dough checkout " change-name "` to switch to it")))))

(defn list!
  ([] (list! (structure/framework-root) (change-set/changes-dir)))
  ([framework-root changes-dir]
   (let [change-sets (change-set/list-change-sets changes-dir)]
     (when (empty? change-sets)
       (println "no open change sets"))
     (doseq [{:keys [name description repos]} change-sets]
       (println (bling/bling [:bold name]) (if (str/blank? description) "" (str "  " description)))
       (doseq [[repo branch] (sort-by key repos)
               :let [dir     (structure/repo-path framework-root repo)
                     [ahead behind] (git/ahead-behind dir (git/default-branch dir) branch)]]
         (println (format "  %-28s %-24s %s" repo branch
                          (if ahead (format "+%d -%d vs %s" ahead behind (git/default-branch dir)) "branch missing"))))))))

(defn push!
  ([change-name] (push! (structure/framework-root) (change-set/changes-dir) change-name))
  ([framework-root changes-dir change-name]
   (let [{:keys [repos]} (change-set/read-change-set changes-dir change-name)]
     (doseq [[repo branch] (sort-by key repos)
             :let [result (git/push-branch! (structure/repo-path framework-root repo) branch)]]
       (when-not (git/success? result)
         (fail! (str repo ": push of " branch " failed: " (:err result))))
       (println (format "  %-28s pushed %s" repo branch))))))

(defn git-base
  "Where repos are cloned from: DOUGH_GIT_BASE (CI sets an https URL with a
  token), else the donut-party org over SSH."
  []
  (or (System/getenv "DOUGH_GIT_BASE") "git@github.com:donut-party"))

(defn clone!
  "Clones every repo in the change set that isn't checked out yet, on its
  branch. `--shallow` makes depth-1 clones, enough for CI."
  ([change-name opts] (clone! (structure/framework-root) (change-set/changes-dir) change-name opts))
  ([framework-root changes-dir change-name {:keys [shallow? base] :or {base (git-base)}}]
   (let [{:keys [repos]} (change-set/read-change-set changes-dir change-name)]
     (doseq [[repo branch] (sort-by key repos)]
       (if (fs/exists? (structure/repo-path framework-root repo))
         (println (format "  %-28s already checked out" repo))
         (let [result (git/clone-branch! framework-root (str base "/" repo ".git") repo branch shallow?)]
           (when-not (git/success? result)
             (fail! (str repo ": clone of " branch " failed: " (:err result))))
           (println (format "  %-28s cloned on %s" repo branch))))))))

(defn unmerged
  "Repos whose change-set branch isn't fully merged into origin's default
  branch (after a fetch)."
  [framework-root {:keys [repos]}]
  (->> (sort-by key repos)
       (keep (fn [[repo branch]]
               (let [dir (structure/repo-path framework-root repo)]
                 (git/fetch! dir)
                 (when-not (git/merged? dir branch (str "origin/" (git/default-branch dir)))
                   repo))))
       vec))

(defn done!
  ([change-name] (done! (structure/framework-root) (change-set/changes-dir) change-name))
  ([framework-root changes-dir change-name]
   (let [change-set (change-set/read-change-set changes-dir change-name)
         unmerged   (unmerged framework-root change-set)]
     (when (seq unmerged)
       (fail! (str "not merged into the default branch yet: " (str/join ", " unmerged))))
     (change-set/delete-change-set! changes-dir change-name)
     (println (str "every branch is merged; deleted " (change-set/path changes-dir change-name))))))

(defn -main
  [& [command & args]]
  (case command
    "new"   (new! args)
    "list"  (list!)
    "push"  (push! (first args))
    "clone" (clone! (first (remove #{"--shallow"} args)) {:shallow? (boolean (some #{"--shallow"} args))})
    "done"  (done! (first args))
    "land"  (land/land! (structure/framework-root) (change-set/changes-dir)
                        (first (remove #{"--dry-run"} args))
                        {:dry-run? (boolean (some #{"--dry-run"} args))})
    (fail! usage)))
