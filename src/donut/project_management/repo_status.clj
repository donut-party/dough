(ns donut.project-management.repo-status
  "Implements the repo-status task: reports every sibling donut repo that's
  mid-work -- checked out on a branch other than its default, or dirty --
  so spare-time work across repos is easy to pick back up."
  (:require
   [babashka.fs :as fs]
   [bblgum.core :as gum]
   [bling.core :as bling]
   [clojure.string :as str]
   [donut.project-management.git :as git]
   [donut.project-management.structure :as structure])
  (:import
   [java.time Instant ZoneId]))

(defn- repo-files
  "Every regular file under dir, excluding .git internals -- those churn
  constantly (index, HEAD, etc.) and don't reflect actual work-in-progress."
  [dir]
  (let [git-dir (fs/path dir ".git")]
    (->> (file-seq (fs/file dir))
         (filter fs/regular-file?)
         (remove #(fs/starts-with? % git-dir)))))

(defn- format-date
  [millis]
  (str (.toLocalDate (.atZone (Instant/ofEpochMilli millis) (ZoneId/systemDefault)))))

(defn most-recent-mtime
  "YYYY-MM-DD of the most recently modified file under dir (excluding .git)."
  [dir]
  (->> (repo-files dir)
       (map fs/last-modified-time)
       (map fs/file-time->millis)
       (apply max)
       format-date))

(defn repo-status
  [repo-dir]
  {:repo           (str (fs/file-name repo-dir))
   :branch         (git/current-branch repo-dir)
   :default-branch (git/default-branch repo-dir)
   :dirty?         (git/dirty? repo-dir)
   :mtime          (most-recent-mtime repo-dir)
   :commit-date    (git/last-commit-date repo-dir)
   :commit-msg     (git/last-commit-subject repo-dir)})

(defn needs-attention?
  "A repo is worth reporting if it's not sitting clean on its default
  branch -- i.e. there's something to pick back up."
  [{:keys [branch default-branch dirty?]}]
  (or dirty? (not= branch default-branch)))

(def columns ["branch" "status" "modified" "committed"])

(defn ->row
  [{:keys [branch dirty? mtime commit-date]}]
  [branch
   (if dirty? (bling/bling [:warning "dirty"]) "clean")
   mtime
   commit-date])

(defn ->csv
  [rows]
  (str/join "\n" (map #(str/join "," %) rows)))

(defn print-report!
  [{:keys [repo commit-msg] :as status}]
  (bling/print-bling [:bold.positive (str "\n" repo "\n")])
  (gum/gum :table :in (->csv [(->row status)]) :as :ignored :print true
           :columns columns
           :border.foreground "212"
           :header.foreground "212"
           :selected.foreground ""
           :selected.background "")
  (println commit-msg))

(defn -main
  [& _]
  (let [statuses (->> (structure/sibling-repo-paths)
                       (map repo-status)
                       (filter needs-attention?))]
    (if (empty? statuses)
      (bling/print-bling [:muted "\nNothing to pick up -- every repo is clean and on its default branch.\n"])
      (doseq [status statuses]
        (print-report! status)))))
