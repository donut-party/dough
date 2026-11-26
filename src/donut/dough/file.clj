(ns donut.dough.file
  (:require
   [babashka.fs :as fs]))

(defn update-file
  "Replace the contents of the file at `path` with (apply f contents args)."
  [path f & args]
  (let [file (fs/file (str path))]
    (spit file (apply f (slurp file) args))))
