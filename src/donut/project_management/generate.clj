(ns donut.project-management.generate
  (:require
   [babashka.fs :as fs]
   [babashka.process :as ps]
   [clojure.string :as str]
   [donut.bb-utils.file :as dbbf]
   [donut.bb-utils.process :as dbbps]
   [donut.bb-utils.project :as dbbproj]
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
  (let [tt-dir     (structure/project-root "template-test") 
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
  (let [version          (->> "clj -T:build install"
                              (dbbps/sh-out {:dir (structure/project-root "generators")})
                              str/split-lines
                              last)
        donut-cli-bb-edn (fs/path (structure/project-root "donut-cli") "donut-cli/bb.edn")]

    ;; update donut cli deps to use latest generators
    (dbbf/update-file donut-cli-bb-edn
                      dbbproj/update-dependency-version
                      'party.donut/generators
                      version)

    (ps/shell {:dir (fs/path (structure/project-root "donut-cli") "develop/bin")} "./develop copy-local")

    (kill-js)

    (regenerate-template-test)))
