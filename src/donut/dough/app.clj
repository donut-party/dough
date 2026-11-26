(ns donut.dough.app
  "Implements `dough app <name> [--skip-devtools]`: generates a fresh donut
  app at <framework-root>/<name> from the local checkouts (through `dough
  cli`), then checks it: backend tests, cljs compile, cljs release build,
  and tailwind build, all against the checkouts via the :donut/local alias
  (`dough link`). Answers \"does this change work in a new app?\" in one
  command. Exits non-zero if any check fails."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as ps]
   [bling.core :as bling]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [donut.dough.cli :as cli]
   [donut.dough.link :as link]
   [donut.dough.process :as process]
   [donut.dough.registry :as registry]
   [donut.dough.structure :as structure]))

(def marker
  "Marks a directory as made by `dough app`, so it may be regenerated."
  ".dough-app")

(defn prepare-dir!
  "Deletes a previous `dough app` output at `dir`; refuses to delete
  anything else."
  [dir]
  (when (fs/exists? dir)
    (when-not (fs/exists? (fs/path dir marker))
      (throw (ex-info (str dir " exists and wasn't made by `dough app`; not deleting it")
                      {:babashka/exit 1})))
    (fs/delete-tree dir)))

(defn setup-steps
  "`devtools-version` pins app-devtools in the generated deps.edn (its code
  loads from the checkout); nil skips app-devtools."
  [app-name devtools-version]
  (cond-> [{:label "generate app"         :dir :root :cli ["new" (str "donut-app/" app-name) "--target-dir" app-name]}
           {:label "entity scaffold user" :dir :app  :cli ["generate" "entity-scaffold" "user"]}]
    devtools-version
    (conj {:label "app-devtools install" :dir :app
           :cli   ["generate" "--lib" (str "party.donut/app-devtools:" devtools-version)
                   ":donut.app-devtools.generators/install"]})
    true
    (conj {:label "npm install" :dir :app :cmd ["npm" "install" "--no-audit" "--no-fund"]})))

(defn shadow-builds
  [app-dir]
  (let [f (fs/file app-dir "shadow-cljs.edn")]
    (when (fs/exists? f)
      (set (keys (:builds (edn/read-string {:default tagged-literal} (slurp f))))))))

(defn checks
  "The checks to run in a generated app, given its shadow-cljs builds."
  [builds]
  (let [alias (str "-A" link/alias-key)]
    (cond-> [{:label "backend tests" :cmd ["clojure" (str "-X:test" link/alias-key)]}]
      (contains? builds :dev) (conj {:label "cljs compile (dev)" :cmd ["npx" "shadow-cljs" alias "compile" "dev"]})
      (contains? builds :app) (conj {:label "cljs release (app)" :cmd ["npx" "shadow-cljs" alias "release" "app"]})
      true                    (conj {:label "tailwind build" :cmd ["npm" "run" "tailwind:release"]}))))

(defn- run-step!
  [{:keys [label dir cli cmd]} root app-dir]
  (process/tee (str "\n-> " label) :bold)
  (let [dir (str (if (= dir :root) root app-dir))]
    (:exit (if cli
             (cli/run-cli! cli {:dir dir})
             (apply ps/shell {:dir dir :continue true} cmd)))))

(defn- print-summary!
  [results]
  (println)
  (doseq [{:keys [label exit]} results]
    (println (if (zero? exit)
               (bling/bling [:positive (str "PASS " label)])
               (bling/bling [:negative (str "FAIL " label " (exit " exit ")")])))))

(defn devtools-version
  "The app-devtools checkout's version, or nil (skipping app-devtools) when
  asked to or when it isn't checked out."
  [{:keys [skip-devtools?]}]
  (when-not skip-devtools?
    (or (registry/checkout-version (structure/repo-path "app-devtools"))
        (do (process/tee "app-devtools isn't checked out; skipping its generator" :warning)
            nil))))

(defn app!
  [app-name opts]
  (let [root    (structure/framework-root)
        app-dir (fs/path root app-name)]
    (prepare-dir! app-dir)
    (link/link!)
    (doseq [step (setup-steps app-name (devtools-version opts))]
      (let [exit (run-step! step root app-dir)]
        (when (= "generate app" (:label step))
          (when (fs/exists? app-dir) (spit (fs/file app-dir marker) "")))
        (when-not (zero? exit)
          (throw (ex-info (str (:label step) " failed (exit " exit "); stopping") {:babashka/exit 1})))))
    (let [results (mapv (fn [check] (assoc check :exit (run-step! check root app-dir)))
                        (checks (shadow-builds app-dir)))]
      (print-summary! results)
      results)))

(defn -main
  [& args]
  (let [[app-name] (remove #(str/starts-with? % "--") args)]
    (when-not app-name
      (throw (ex-info "usage: dough app <name> [--skip-devtools]" {:babashka/exit 1})))
    (let [results (app! app-name {:skip-devtools? (boolean (some #{"--skip-devtools"} args))})]
      (when (some (comp pos? :exit) results)
        (System/exit 1)))))
