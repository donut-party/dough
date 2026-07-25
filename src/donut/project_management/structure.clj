(ns donut.project-management.structure
  "Locates donut repos on disk.

  Every donut repo lives in a single parent directory, the \"framework
  root\". This repo (project-management) is one of them, so the framework
  root is this repo's parent directory."
  (:require
   [babashka.fs :as fs]
   [donut.bb-utils.project :as project]))

(defn project-management-root
  "Root of this repo. Prefers the location of the bb.edn the current task
  was invoked with so that tasks run correctly from any cwd (see bin/run);
  falls back to the enclosing git repo."
  []
  (or (some-> (System/getProperty "babashka.config")
              fs/canonicalize
              fs/parent)
      (fs/path (project/project-root-git))))

(defn framework-root
  "The directory containing all donut repos."
  []
  (str (fs/parent (project-management-root))))

(defn repo-path
  "Path to a donut repo, e.g. (repo-path \"app-devtools\").
  \".\" names the framework root itself."
  ([repo-name]
   (repo-path (framework-root) repo-name))
  ([framework-root repo-name]
   (-> (fs/path framework-root (str repo-name))
       fs/normalize)))

(defn sibling-repo-paths
  "Every git repo in `root` other than `this-repo`: the libraries, tooling,
  and test apps the sync-repos task might touch. A directory counts as a
  repo if it has a `.git` dir; scratch/generated dirs like template-test
  that don't are skipped automatically."
  ([] (sibling-repo-paths (framework-root) (project-management-root)))
  ([root this-repo]
   (->> (fs/list-dir root)
        (filter fs/directory?)
        (remove #(fs/same-file? % this-repo))
        (filter #(fs/exists? (fs/path % ".git")))
        (sort-by fs/file-name))))
