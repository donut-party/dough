(ns donut.dough.cli
  "Implements `dough cli <args>`: runs the donut CLI from its checkout, with
  party.donut/generate, party.donut/generators and the app template read
  from their checkouts too, so a change in any of them is live without an
  install, a push, or touching the Homebrew install. `donut generate --lib`
  resolves workspace libraries from their checkouts via DONUT_LOCAL_LIBS."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as ps]
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [donut.dough.registry :as registry]
   [donut.dough.structure :as structure]))

(def local-cli-libs
  "CLI deps replaced with their checkouts."
  ['party.donut/generate 'party.donut/generators registry/template-lib])

(defn cli-dir
  []
  (fs/path (structure/repo-path "donut-cli") "donut-cli"))

(defn local-bb-edn
  "The CLI's bb.edn (as data) with `local-cli-libs` pointed at their
  checkouts and :paths prefixed with `cli-dir`, the CLI's directory
  relative to where this bb.edn will be written (babashka requires
  relative :paths)."
  [bb-edn cli-dir libs]
  (-> bb-edn
      (update :paths (partial mapv #(str (fs/path cli-dir %))))
      (update :deps (fn [deps]
                      (reduce (fn [deps lib]
                                (if-let [dir (get libs lib)]
                                  (assoc deps lib {:local/root dir})
                                  deps))
                              deps
                              local-cli-libs)))))

(defn write-local-bb-edn!
  "Writes the generated bb.edn under dough's .cache and returns its path."
  [libs]
  (let [dir  (cli-dir)
        out  (fs/path (structure/dough-root) ".cache" "cli" "bb.edn")
        data (local-bb-edn (edn/read-string (slurp (fs/file dir "bb.edn")))
                           (str (fs/relativize (fs/parent out) dir))
                           libs)]
    (fs/create-dirs (fs/parent out))
    (spit (fs/file out) (with-out-str (pprint/pprint data)))
    (str out)))

(defn run-cli!
  "Runs the local CLI with `args` in `opts`' :dir (default: the current
  directory). Returns the process result; never throws on a non-zero exit."
  [args & [opts]]
  (let [libs (registry/local-libs)]
    (apply ps/shell (merge {:continue  true
                            :extra-env {"DONUT_LOCAL_LIBS" (pr-str libs)}}
                           opts)
           "bb" "--config" (write-local-bb-edn! libs) args)))

(defn -main
  [& args]
  (System/exit (:exit (run-cli! args))))
