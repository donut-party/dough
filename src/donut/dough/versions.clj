(ns donut.dough.versions
  "Parse, bump, and persist dotted version strings like \"1.2.3\". Any
  segment can be bumped, and bumping a segment resets the segments after
  it."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]))

(def level->index
  {:major 0 :minor 1 :patch 2})

(defn parse
  [version-string]
  (mapv parse-long (str/split (str/trim version-string) #"\.")))

(defn unparse
  [parts]
  (str/join "." parts))

(defn bump
  "Bump `level` (:major, :minor, :patch — default :patch) of a version
  string, zeroing the segments after it: (bump \"1.2.3\" :minor) => \"1.3.0\""
  ([version-string]
   (bump version-string :patch))
  ([version-string level]
   (let [idx (level->index level)]
     (->> (parse version-string)
          (map-indexed (fn [i part]
                         (cond
                           (< i idx) part
                           (= i idx) (inc part)
                           :else     0)))
          unparse))))

(defn read-version-file
  [path]
  (str/trim (slurp (fs/file (str path)))))

(defn bump-version-file!
  "Bump the version stored in the file at `path`; returns the new version."
  ([path]
   (bump-version-file! path :patch))
  ([path level]
   (let [new-version (bump (read-version-file path) level)]
     (spit (fs/file (str path)) new-version)
     new-version)))
