(ns donut.project-management.regenerate-legacy
  (:require
   [babashka.fs :as fs]
   [babashka.process :as ps]
   [donut.bb-utils.file :as dbbf]
   [donut.project-management.deps :as deps]
   [donut.project-management.rebuild-test-app :as rebuild-test-app]
   [donut.project-management.structure :as structure]))

(defn kill-js
  []
  (let [pid (-> (ps/pipeline (ps/pb "ps" "ax")
                             (ps/pb "grep" "pnpm run dev")
                             (ps/pb "tail" "-n" "1")
                             (ps/pb "cut" "-d" " " "-f1"))
                last
                deref
                :out
                slurp
                clojure.string/trim)]
    (ps/sh "kill" "-9" pid)))

(defn regenerate-template-test
  []
  (let [tt-dir     (structure/repo-path "template-test")
        parent-dir (fs/parent tt-dir)]
    (ps/shell {:dir parent-dir} "rm -rf template-test")
    (ps/shell {:dir parent-dir} "donut new donut-template-test/test-app -t template-test")
    (ps/shell {:dir tt-dir} "npm i")
    (ps/shell {:dir tt-dir} "git init")
    (ps/shell {:dir tt-dir} "git add .")
    (ps/shell {:dir tt-dir} "git commit -m \"initial commit\"")
    (ps/shell {:dir tt-dir} "donut generate entity-scaffold user")))

;; TODO update brew installation

(defn -main
  []
  (let [version          (rebuild-test-app/install-lib! "generators")
        donut-cli-bb-edn (fs/path (structure/repo-path "donut-cli") "donut-cli/bb.edn")]

    ;; update donut cli deps to use latest generators
    (dbbf/update-file donut-cli-bb-edn
                      deps/update-mvn-version
                      'party.donut/generators
                      version)

    (ps/shell {:dir (fs/path (structure/repo-path "donut-cli") "develop/bin")} "./develop copy-local")

    (kill-js)

    (regenerate-template-test)))
