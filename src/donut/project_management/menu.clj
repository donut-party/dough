(ns donut.project-management.menu
  "Interactive task picker shown by `bin/run` when called with no arguments,
  built on gum (via bblgum) instead of babashka's plain `bb tasks` listing."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as ps]
   [bblgum.core :as gum]
   [bling.core :as bling]
   [clojure.edn :as edn]
   [donut.project-management.structure :as structure]))

(def ^:private unit-separator "")

(defn- bb-edn-path
  []
  (or (System/getProperty "babashka.config")
      (str (fs/path (structure/project-management-root) "bb.edn"))))

(defn read-tasks
  "Task names and docstrings from bb.edn's :tasks map, so the menu stays in
  sync with `bb tasks` without duplicating descriptions."
  []
  (let [{:keys [tasks]} (edn/read-string (slurp (bb-edn-path)))]
    (for [[task-name {:keys [doc]}] tasks]
      {:name (name task-name) :doc doc})))

(defn- choice
  "A gum `label:value` choice string: the padded name and doc as the label
  users see, the bare task name as the value gum returns on selection."
  [name-width {:keys [name doc]}]
  (str (format (str "%-" name-width "s") name) "   " doc unit-separator name))

(defn- print-plain-list
  [tasks]
  (println "Tasks:")
  (doseq [{:keys [name doc]} tasks]
    (println (format "  %-20s %s" name doc))))

(defn- run-task!
  [task-name]
  (bling/print-bling [:bold.positive (str "\n-> " task-name "\n")])
  (System/exit (:exit (ps/shell {:continue true} "bb" "--config" (bb-edn-path) task-name))))

(defn -main
  [& _]
  (let [tasks      (read-tasks)
        name-width (->> tasks (map (comp count :name)) (apply max))
        {:keys [status result]} (gum/gum :choose (mapv (partial choice name-width) tasks)
                                          :header "donut project-management"
                                          :label-delimiter unit-separator
                                          :height (+ (count tasks) 2)
                                          :cursor.foreground "212"
                                          :selected.foreground "212"
                                          :header.foreground "212")]
    (cond
      (seq result)
      (run-task! (first result))

      (zero? status)
      (bling/print-bling [:muted "\nNo task selected.\n"])

      :else
      (do (bling/print-bling [:warning "\nCouldn't open an interactive picker (no TTY?). Run bin/run <task-name> directly:\n"])
          (print-plain-list tasks)))))
