(ns donut.dough.cli-bump
  "Bumps the donut CLI's patch version, stored in
  donut-cli/donut-cli/resources/VERSION."
  (:require
   [babashka.fs :as fs]
   [donut.dough.process :as process]
   [donut.dough.structure :as structure]
   [donut.dough.versions :as versions]))

(defn version-path
  []
  (fs/path (structure/repo-path "donut-cli") "donut-cli" "resources" "VERSION"))

(defn version
  []
  (versions/read-version-file (version-path)))

(defn -main
  [& _]
  (process/tee (str "[donut-cli] version " (versions/bump-version-file! (version-path)))))
