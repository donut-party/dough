(ns test-runner
  (:require
   [clojure.test :as t]
   [donut.dough.brew-formula-test]
   [donut.dough.brew-test]
   [donut.dough.change-set-test]
   [donut.dough.cli-update-deps-test]
   [donut.dough.deps-test]
   [donut.dough.rebuild-test-app-test]
   [donut.dough.status-test]
   [donut.dough.structure-test]
   [donut.dough.sync-test]
   [donut.dough.versions-test]))

(def test-namespaces
  '[donut.dough.brew-formula-test
    donut.dough.brew-test
    donut.dough.change-set-test
    donut.dough.cli-update-deps-test
    donut.dough.deps-test
    donut.dough.rebuild-test-app-test
    donut.dough.status-test
    donut.dough.structure-test
    donut.dough.sync-test
    donut.dough.versions-test])

(defn -main
  [& _]
  (let [{:keys [fail error]} (apply t/run-tests test-namespaces)]
    (when (pos? (+ fail error))
      (System/exit 1))))
