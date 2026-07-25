(ns test-runner
  (:require
   [clojure.test :as t]
   [donut.project-management.brew-test]
   [donut.project-management.deps-test]
   [donut.project-management.rebuild-test-app-test]
   [donut.project-management.repo-status-test]
   [donut.project-management.structure-test]
   [donut.project-management.sync-repos-test]
   [donut.project-management.versions-test]))

(defn -main
  [& _]
  (let [{:keys [fail error]} (t/run-tests 'donut.project-management.brew-test
                                          'donut.project-management.deps-test
                                          'donut.project-management.rebuild-test-app-test
                                          'donut.project-management.repo-status-test
                                          'donut.project-management.structure-test
                                          'donut.project-management.sync-repos-test
                                          'donut.project-management.versions-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
