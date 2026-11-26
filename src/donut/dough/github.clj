(ns donut.dough.github
  "Wraps the `gh` CLI to list an org's repos -- the source of truth
  `dough sync` uses for which repos are \"donut\" repos. That naturally
  excludes GitHub wiki companion repos (the API doesn't list those as
  repos at all) and any local checkout whose remote was renamed or
  deleted upstream, without needing to special-case either."
  (:require
   [babashka.process :as ps]
   [cheshire.core :as json]))

(def org "donut-party")

(defn org-repos
  "Every non-archived repo in `org`, as {:name ... :ssh-url ...} maps.
  Requires `gh` to be authenticated (`gh auth status`)."
  ([] (org-repos org))
  ([org]
   (let [{:keys [exit out err]} (ps/sh "gh" "api" (str "orgs/" org "/repos") "--paginate" "--slurp")]
     (when-not (zero? exit)
       (throw (ex-info (str "gh api orgs/" org "/repos failed: " err) {:exit exit :err err})))
     (->> (json/parse-string out true)
          (apply concat)
          (remove :archived)
          (mapv (fn [{:keys [name ssh_url]}] {:name name :ssh-url ssh_url}))))))
