(ns donut.dough.graph
  "The dependency graph between checked-out donut repos, read from each
  library's top-level :deps. Releases happen per repo (datapotato's six
  modules share one repo and one release branch), so the graph is between
  repos: a repo depends on another if any of its libraries depends on one
  of the other's."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [donut.dough.registry :as registry]))

(defn- top-level-deps
  [lib-dir]
  (try
    (set (keys (:deps (edn/read-string {:default tagged-literal} (slurp (fs/file lib-dir "deps.edn"))))))
    (catch Exception _ #{})))

(defn repo-name
  "The repo (top-level directory under the framework root) a library
  directory belongs to."
  [framework-root lib-dir]
  (str (first (fs/components (fs/relativize framework-root lib-dir)))))

(defn lib-graph
  "{lib #{donut-lib-it-depends-on}} for `libs` ({lib dir})."
  [libs]
  (into {}
        (map (fn [[lib dir]] [lib (into #{} (filter libs) (top-level-deps dir))]))
        libs))

(defn repo-graph
  "{repo #{repo-it-depends-on}}, from a lib graph and {lib repo}."
  [lib-graph lib->repo]
  (reduce-kv (fn [g lib deps]
               (let [repo (lib->repo lib)]
                 (update g repo (fnil into #{})
                         (comp (map lib->repo) (remove #{repo}))
                         deps)))
             {}
             lib-graph))

(defn topo-sort
  "Nodes of `graph` ({node #{dependency}}) with every node after its
  dependencies, ties broken alphabetically. Throws on a cycle."
  [graph]
  (let [nodes (into (set (keys graph)) (mapcat val) graph)]
    (loop [order [], done #{}, remaining (sort nodes)]
      (if (empty? remaining)
        order
        (let [ready (filter #(every? done (get graph % #{})) remaining)]
          (when (empty? ready)
            (throw (ex-info "dependency cycle" {:remaining remaining})))
          (recur (into order ready)
                 (into done ready)
                 (remove (set ready) remaining)))))))

(defn local-repos
  "{:libs {lib dir} :lib->repo {lib repo} :repo-graph {...} :order [repo ...]}
  for every library checked out under `framework-root`, excluding the app
  template (it isn't released through Clojars)."
  [framework-root]
  (let [libs      (dissoc (registry/local-libs framework-root) registry/template-lib)
        lib->repo (into {} (map (fn [[lib dir]] [lib (repo-name framework-root dir)])) libs)
        graph     (repo-graph (lib-graph libs) lib->repo)]
    {:libs       libs
     :lib->repo  lib->repo
     :repo-graph graph
     :order      (topo-sort graph)}))

(defn repo-libs
  "{lib dir} of the libraries in `repo`."
  [{:keys [libs lib->repo]} repo]
  (into (sorted-map) (filter (fn [[lib _]] (= repo (lib->repo lib)))) libs))
