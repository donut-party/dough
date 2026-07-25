(ns donut.project-management.repo-status
  "Implements the repo-status task: reports every sibling donut repo that's
  mid-work -- checked out on a branch other than its default, or dirty --
  so spare-time work across repos is easy to pick back up."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as ps]
   [bblgum.core :as gum]
   [bling.core :as bling]
   [clojure.string :as str]
   [donut.project-management.git :as git]
   [donut.project-management.structure :as structure])
  (:import
   [java.time Instant ZoneId]))

(defn- format-date
  [millis]
  (str (.toLocalDate (.atZone (Instant/ofEpochMilli millis) (ZoneId/systemDefault)))))

(defn- dirty-mtime-epoch-seconds
  "Epoch seconds of the most recently modified file among dirty-paths
  (relative to dir), via a single batched stat call (BSD stat, as shipped
  on macOS)."
  [dir dirty-paths]
  (let [{:keys [out]} (apply ps/sh "stat" "-f" "%m" (map #(str (fs/path dir %)) dirty-paths))]
    (->> (str/split-lines (str/trim out))
         (map #(Long/parseLong (str/trim %)))
         (apply max))))

(defn most-recent-mtime
  "YYYY-MM-DD of the most recently modified file that's actually relevant
  to \"what did I touch\": for a dirty repo, that's the most recently
  modified dirty file (a single batched stat call over just those paths,
  not a walk of the whole tree -- node_modules and friends never come into
  it). A clean repo has nothing dirty to point at, so its modified date is
  just its last commit's."
  [dir dirty-paths commit-date]
  (if (seq dirty-paths)
    (format-date (* 1000 (dirty-mtime-epoch-seconds dir dirty-paths)))
    commit-date))

(defn repo-status
  [repo-dir]
  (let [dirty-paths (git/status-paths repo-dir)
        commit-date (git/last-commit-date repo-dir)]
    {:repo           (str (fs/file-name repo-dir))
     :branch         (git/current-branch repo-dir)
     :default-branch (git/default-branch repo-dir)
     :dirty?         (boolean (seq dirty-paths))
     :mtime          (most-recent-mtime repo-dir dirty-paths commit-date)
     :commit-date    commit-date}))

(defn needs-attention?
  "A repo is worth reporting if it's not sitting clean on its default
  branch -- i.e. there's something to pick back up."
  [{:keys [branch default-branch dirty?]}]
  (or dirty? (not= branch default-branch)))

(def columns ["repo name" "branch" "dirty?" "modified" "committed"])

(defn ->row
  [{:keys [repo branch dirty? mtime commit-date]}]
  [repo
   branch
   (if dirty? (bling/bling [:warning "yes"]) "no")
   mtime
   commit-date])

(defn ->csv
  [rows]
  (str/join "\n" (map #(str/join "," %) rows)))

(defn print-report!
  [statuses]
  ;; gum (as of 0.17.0) applies --header.foreground to the first *data* row
  ;; instead of the header row -- a gum bug, not a bblgum one -- so it's
  ;; left unset here to avoid pink-highlighting that row.
  (gum/gum :table :in (->csv (map ->row statuses)) :as :ignored :print true
           :columns columns
           :border.foreground "212"))

(defn -main
  [& _]
  ;; pmap, not an unbounded future per repo: with ~40 sibling repos, firing
  ;; every git subprocess and filesystem walk at once causes enough
  ;; CPU/process-table/disk-I/O contention to be *slower* than bounding
  ;; concurrency to roughly the CPU count, which pmap does automatically.
  (let [statuses (->> (structure/sibling-repo-paths)
                       (pmap repo-status)
                       (filter needs-attention?)
                       (sort-by :repo))]
    (if (empty? statuses)
      (bling/print-bling [:muted "\nNothing to pick up -- every repo is clean and on its default branch.\n"])
      (print-report! statuses))))
