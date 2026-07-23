(ns donut.project-management.local-test
  "Installs local versions of donut libs, refreshes the donut CLI, and
  regenerates the template-test app so that changes across repos can be
  tested together."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as ps]
   [clojure.string :as str]
   [donut.bb-utils.file :as file]
   [donut.bb-utils.process :as process]
   [donut.project-management.deps :as deps]
   [donut.project-management.structure :as structure]))

(def install-cmd "clj -T:build install")

(def donut-cli-steps
  "Refresh the donut CLI's deps and overwrite the brew-installed CLI with
  the local working copy."
  [{:repo "donut-cli" :cmd "./develop/bin/develop update-deps"}
   {:repo "donut-cli" :cmd "./develop/bin/develop copy-local"}])

(defn regenerate-steps
  "Rebuild the template-test app with the freshly-installed libs."
  [app-devtools-version]
  [{:repo "."             :cmd "rm -rf template-test"}
   {:repo "."             :cmd "donut new donut-template-test/test-app -t template-test"}
   {:repo "template-test" :cmd "donut generate entity-scaffold user"}
   {:repo "template-test" :cmd (str "donut generate"
                                    " --lib party.donut/app-devtools:" app-devtools-version
                                    " :donut.app-devtools.generators/install")}])

(defn parse-installed-version
  "The donut build scripts print the installed version as the last line of
  `clj -T:build install` output."
  [out]
  (let [version (some->> out
                         str/split-lines
                         (map str/trim)
                         (remove str/blank?)
                         last)]
    (when-not (and version (re-matches #"\d+(\.\d+)*" version))
      (throw (ex-info "couldn't parse installed version from build output"
                      {:out out})))
    version))

(defn run-step!
  [{:keys [repo cmd]}]
  (process/tee (format "\n[%s] %s" repo cmd))
  (ps/shell {:dir (str (structure/repo-path repo))} cmd))

(defn install-lib!
  "Runs `clj -T:build install` in a repo, returning the installed version."
  [repo]
  (process/tee (format "\n[%s] %s" repo install-cmd))
  (let [{:keys [out]} (ps/shell {:dir (str (structure/repo-path repo))
                                 :out :string}
                                install-cmd)]
    (print out)
    (flush)
    (parse-installed-version out)))

(defn update-generators-dep!
  "Points generators at the just-installed party.donut/generate version.

  Does the job of `neil dep upgrade :lib party.donut/generate`, which fails
  when the locally-installed version doesn't exist on a remote repository."
  [generate-version]
  (process/tee (format "\n[generators] set party.donut/generate to %s in deps.edn"
                       generate-version))
  (file/update-file (fs/path (structure/repo-path "generators") "deps.edn")
                    deps/update-mvn-version
                    'party.donut/generate
                    generate-version))

(defn -main
  []
  (update-generators-dep! (install-lib! "generate"))
  (run! run-step! donut-cli-steps)
  (let [app-devtools-version (install-lib! "app-devtools")]
    (run! run-step! (regenerate-steps app-devtools-version)))
  (process/tee "\nlocal-test complete" :bold.green))
