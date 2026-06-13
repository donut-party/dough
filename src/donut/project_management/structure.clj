(ns donut.project-management.structure
  (:require
   [babashka.fs :as fs]
   [donut.bb-utils.project :as project]))

(def framework-root
  (-> (project/project-root-git)
      fs/path
      fs/parent
      str))

(defn project-root
  [project-name]
  (fs/path framework-root project-name))
