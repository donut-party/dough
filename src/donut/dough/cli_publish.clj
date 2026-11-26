(ns donut.dough.cli-publish
  "Releases the donut CLI: updates its deps, bumps its version, commits
  both, tags v<version>, and pushes main and the tag together. The tag is
  what GitHub archives as the release zip; run brew-formula afterward to
  point the Homebrew formula at it."
  (:require
   [babashka.process :as ps]
   [donut.dough.cli-bump :as cli-bump]
   [donut.dough.cli-update-deps :as cli-update-deps]
   [donut.dough.structure :as structure]))

(defn -main
  [& _]
  (let [dir (str (structure/repo-path "donut-cli"))]
    (cli-update-deps/-main)
    (cli-bump/-main)
    (let [version (cli-bump/version)
          tag     (str "v" version)]
      (ps/shell {:dir dir} "git" "add" "donut-cli/bb.edn" "donut-cli/resources/VERSION")
      (ps/shell {:dir dir} "git" "commit" "-m" (str "Bump version " version))
      (ps/shell {:dir dir} "git" "tag" tag)
      (ps/shell {:dir dir} "git" "push" "--atomic" "origin" "main" tag))))
