(ns donut.dough.link
  "Implements `dough link`: writes a `:donut/local` alias into the user
  deps.edn (~/.clojure/deps.edn, or $CLJ_CONFIG/deps.edn) that overrides
  every donut library with its local checkout. Any app then runs against
  the checkouts with `clojure -M:dev:donut/local`, and `dough checkout`
  decides which branch each checkout is on, so the alias only changes when
  repos are added. Only the :donut/local entry is touched; the rest of the
  file, comments included, is preserved."
  (:require
   [babashka.fs :as fs]
   [clojure.pprint :as pprint]
   [donut.dough.process :as process]
   [donut.dough.registry :as registry]
   [rewrite-clj.zip :as z]))

(def alias-key :donut/local)

(defn user-deps-path
  []
  (let [config-dir (or (System/getenv "CLJ_CONFIG")
                       (some-> (System/getenv "XDG_CONFIG_HOME") (fs/path "clojure") str)
                       (str (fs/path (fs/home) ".clojure")))]
    (fs/path config-dir "deps.edn")))

(defn alias-value
  [libs]
  {:override-deps (into (sorted-map)
                        (map (fn [[lib dir]] [lib {:local/root dir}]))
                        libs)})

(defn- pretty-node
  "`value` as a pretty-printed, multi-line rewrite-clj node."
  [value]
  (z/node (z/of-string (binding [*print-namespace-maps* false]
                         (with-out-str (pprint/pprint value))))))

(defn with-alias
  "deps-string with [:aliases alias-key] set to `value`."
  [deps-string value]
  (let [root    (z/of-string deps-string)
        aliases (some-> root z/down (z/find-value z/right :aliases) z/right)]
    (z/root-string
     (if aliases
       (z/assoc aliases alias-key (pretty-node value))
       (z/assoc root :aliases (pretty-node {alias-key value}))))))

(defn link!
  ([] (link! (user-deps-path) (registry/local-libs)))
  ([deps-path libs]
   (let [file (fs/file deps-path)]
     (fs/create-dirs (fs/parent deps-path))
     (spit file (with-alias (if (fs/exists? file) (slurp file) "{}") (alias-value libs)))
     (process/tee (format "wrote %s %s with %d libraries" deps-path alias-key (count libs))))))

(defn -main
  [& _]
  (link!))
