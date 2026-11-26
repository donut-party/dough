(ns donut.dough.cli-update-deps
  "Points the donut CLI's bb.edn at the newest party.donut/generate and
  party.donut/generators releases on Clojars, and at the single-page-app
  template's current main commit. Every lookup fails loudly: a stale pin
  that looks updated is worse than a task that stops."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [donut.dough.clojars :as clojars]
   [donut.dough.deps :as deps]
   [donut.dough.file :as file]
   [donut.dough.process :as process]
   [donut.dough.structure :as structure]))

(def template-repo "donut-party/single-page-app-template")

(def clojars-libs '[party.donut/generate party.donut/generators])

(defn bb-edn-path
  []
  (fs/path (structure/repo-path "donut-cli") "donut-cli" "bb.edn"))

(defn template-main-sha
  []
  (str/trim (process/sh-out "gh" "api" (str "repos/" template-repo "/branches/main")
                            "--jq" ".commit.sha")))

(defn update-pins
  "Sets the template's :git/sha and each lib's :mvn/version in a bb.edn
  string. `versions` maps lib symbols to versions."
  [bb-edn-string {:keys [versions template-sha]}]
  (reduce (fn [s [lib version]] (deps/update-mvn-version s lib version))
          (deps/update-git-sha bb-edn-string 'party.donut/single-page-app template-sha)
          versions))

(defn -main
  [& _]
  (let [pins {:versions     (into (sorted-map) (map (juxt identity clojars/latest-release)) clojars-libs)
              :template-sha (template-main-sha)}]
    (doseq [[lib version] (:versions pins)]
      (process/tee (format "[donut-cli] %s %s" lib version)))
    (process/tee (format "[donut-cli] party.donut/single-page-app %s" (:template-sha pins)))
    (file/update-file (bb-edn-path) update-pins pins)))
