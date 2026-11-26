(ns test-runner
  (:require
   [clojure.test :as t]
   [donut.dough.app-test]
   [donut.dough.brew-formula-test]
   [donut.dough.change-set-test]
   [donut.dough.cli-test]
   [donut.dough.cli-update-deps-test]
   [donut.dough.deps-test]
   [donut.dough.link-test]
   [donut.dough.registry-test]
   [donut.dough.release-test]
   [donut.dough.status-test]
   [donut.dough.structure-test]
   [donut.dough.sync-test]
   [donut.dough.versions-test]))

(def test-namespaces
  '[donut.dough.app-test
    donut.dough.brew-formula-test
    donut.dough.change-set-test
    donut.dough.cli-test
    donut.dough.cli-update-deps-test
    donut.dough.deps-test
    donut.dough.link-test
    donut.dough.registry-test
    donut.dough.release-test
    donut.dough.status-test
    donut.dough.structure-test
    donut.dough.sync-test
    donut.dough.versions-test])

(defn -main
  [& _]
  (let [{:keys [fail error]} (apply t/run-tests test-namespaces)]
    (when (pos? (+ fail error))
      (System/exit 1))))
