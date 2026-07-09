(ns donut.project-management.deps
  "Pure helpers for editing dependency coordinates in deps.edn / bb.edn
  strings, preserving formatting and comments.

  Consolidates donut.bb-utils.project/update-dependency-version and
  donut-cli/develop's update-git-sha. Unlike those, the search for the
  coordinate key is confined to the dependency's own coordinate map, so a
  dependency that lacks the key can't cause the next dependency's entry to
  be rewritten."
  (:require
   [rewrite-clj.zip :as z]))

(defn update-dep-value
  "In deps-string, set `coord-key` (e.g. :mvn/version, :git/sha) of
  `dep-name`'s coordinate map to `value`. Returns deps-string unchanged when
  the dependency or key isn't present."
  [deps-string dep-name coord-key value]
  (or (some-> (z/of-string deps-string)
              (z/find-value z/next (symbol dep-name))
              z/right ; the coordinate map
              z/down
              (z/find-value z/right coord-key)
              z/right
              (z/replace value)
              z/root-string)
      deps-string))

(defn update-mvn-version
  [deps-string dep-name version]
  (update-dep-value deps-string dep-name :mvn/version version))

(defn update-git-sha
  [deps-string dep-name sha]
  (update-dep-value deps-string dep-name :git/sha sha))
