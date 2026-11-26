(ns donut.dough.registry
  "Which donut libraries are checked out locally, and where: read from the
  `:donut/lib` key of every deps.edn under the framework root (a repo root,
  or one level down for multi-module repos like datapotato), plus the app
  template, which is consumed as a dep but has no :donut/lib."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as ps]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [donut.dough.structure :as structure]))

(def template-lib 'party.donut/single-page-app)
(def template-repo "single-page-app-template")

(defn- donut-lib
  [deps-edn-file]
  (try
    (:donut/lib (edn/read-string {:default tagged-literal} (slurp (fs/file deps-edn-file))))
    (catch Exception _ nil)))

(defn- lib-name
  [deps-edn-file]
  (:name (donut-lib deps-edn-file)))

(defn checkout-version
  "The version `clojure -T:build install` would give the checkout at `dir`:
  `<version-prefix>.<commit count>`, the donut.build scheme. nil if `dir`
  isn't a donut.build library."
  [dir]
  (when-let [{:keys [version-prefix]} (and (fs/exists? dir) (donut-lib (fs/path dir "deps.edn")))]
    (let [{:keys [exit out]} (ps/sh {:dir (str dir)} "git" "rev-list" "--count" "HEAD")]
      (when (and version-prefix (zero? exit))
        (str version-prefix "." (str/trim out))))))

(defn local-libs
  "{lib-symbol absolute-dir} for every library checked out under
  `framework-root`."
  ([] (local-libs (structure/framework-root)))
  ([framework-root]
   (let [candidates (concat (fs/glob framework-root "*/deps.edn")
                            (fs/glob framework-root "*/*/deps.edn"))
         libs       (into (sorted-map)
                          (keep (fn [f]
                                  (when-let [lib (lib-name f)]
                                    [lib (str (fs/canonicalize (fs/parent f)))])))
                          candidates)
         template   (fs/path framework-root template-repo)]
     (cond-> libs
       (fs/exists? (fs/path template "deps.edn"))
       (assoc template-lib (str (fs/canonicalize template)))))))
