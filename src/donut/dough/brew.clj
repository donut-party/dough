(ns donut.dough.brew
  "Helpers for working with locally-installed homebrew formulas, e.g. to
  overwrite an installed formula's files with local working copies."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [donut.dough.process :as process]))

(defn repository
  []
  (str/trim (process/sh-out "brew --repository")))

(defn cellar-path
  ([formula]
   (cellar-path (repository) formula))
  ([repository formula]
   (fs/path repository "Cellar" (str formula))))

(defn installed-versions
  "Installed versions of a formula, oldest first."
  ([formula]
   (installed-versions (repository) formula))
  ([repository formula]
   (->> (fs/list-dir (cellar-path repository formula))
        (map fs/file-name)
        sort
        vec)))

(defn libexec-path
  "Path to the libexec dir of the most recently installed version of a
  formula."
  ([formula]
   (libexec-path (repository) formula))
  ([repository formula]
   (let [version (or (last (installed-versions repository formula))
                     (throw (ex-info (str "no installed versions of formula " formula)
                                     {:formula formula})))]
     (fs/path (cellar-path repository formula) version "libexec"))))

(defn copy-local!
  "Replaces the brew-installed donut CLI's files with the local working copy
  at `source` (donut-cli/donut-cli), so `donut` runs the local code."
  [source]
  (let [destination (fs/path (libexec-path "donut") "donut-cli")]
    (fs/delete-tree destination)
    (fs/copy-tree source destination)
    (str destination)))
