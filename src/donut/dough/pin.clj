(ns donut.dough.pin
  "Implements `dough pin [--dry-run]`: points the app template's deps.edn
  at the newest Clojars release of every party.donut library it uses, and
  the donut CLI's bb.edn at the newest generate/generators and the
  template's main SHA (see donut.dough.cli-update-deps). The template's
  pins are the tested version set new apps start from, so this runs after
  every release. Libraries with no release on Clojars keep their pin."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [donut.dough.clojars :as clojars]
   [donut.dough.cli-update-deps :as cli-update-deps]
   [donut.dough.deps :as deps]
   [donut.dough.process :as process]
   [donut.dough.structure :as structure]))

(defn template-deps-path
  []
  (fs/path (structure/repo-path "single-page-app-template")
           "resources" "party" "donut" "single_page_app" "project-root" "deps.edn"))

(defn donut-mvn-deps
  "{lib version} of every party.donut :mvn/version dep in a deps.edn
  string, top level and in aliases."
  [deps-string]
  (let [data     (edn/read-string deps-string)
        dep-maps (cons (:deps data)
                       (mapcat (juxt :deps :extra-deps :override-deps) (vals (:aliases data))))]
    (into (sorted-map)
          (for [m dep-maps, [lib coord] m
                :when (and (= "party.donut" (namespace lib)) (:mvn/version coord))]
            [lib (:mvn/version coord)]))))

(defn pin-changes
  "[[lib old new] ...] for pins that differ from `latest` ({lib version};
  a nil version means unreleased, which keeps its pin)."
  [current latest]
  (vec (for [[lib old] current
             :let [new (get latest lib)]
             :when (and new (not= old new))]
         [lib old new])))

(defn apply-pins
  [deps-string changes]
  (reduce (fn [s [lib _ new]] (deps/update-mvn-version s lib new)) deps-string changes))

(defn pin-template!
  "Rewrites the template's pins; returns the changes."
  [{:keys [dry-run?]}]
  (let [path    (template-deps-path)
        s       (slurp (fs/file path))
        current (donut-mvn-deps s)
        latest  (into {} (map (fn [lib] [lib (:latest_release (clojars/artifact lib))])) (keys current))
        changes (pin-changes current latest)]
    (doseq [[lib v] current :when (nil? (get latest lib))]
      (process/tee (format "[template] %s %s: no release on Clojars, keeping it" lib v) :warning))
    (doseq [[lib old new] changes]
      (process/tee (format "[template] %s %s -> %s" lib old new) nil))
    (when (and (seq changes) (not dry-run?))
      (spit (fs/file path) (apply-pins s changes)))
    changes))

(defn -main
  [& args]
  (let [dry-run? (boolean (some #{"--dry-run"} args))]
    (when (empty? (pin-template! {:dry-run? dry-run?}))
      (process/tee "[template] every pin is already current" nil))
    (if dry-run?
      (process/tee "--dry-run: the CLI's pins weren't checked" :muted)
      (cli-update-deps/-main))))
