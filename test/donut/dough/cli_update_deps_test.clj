(ns donut.dough.cli-update-deps-test
  (:require
   [clojure.test :refer [deftest is]]
   [donut.dough.cli-update-deps :as cli-update-deps]))

(def bb-edn
  "{:deps {io.github.paintparty/bling       {:mvn/version \"0.10.0\"}
         party.donut/generate             {:mvn/version \"1.0.77\"}
         party.donut/generators           {:mvn/version \"0.0.50\"}
         party.donut/single-page-app      {:git/url \"https://github.com/donut-party/single-page-app-template\"
                                           :git/sha \"adb3d23\"}}}")

(deftest update-pins-test
  (is (= "{:deps {io.github.paintparty/bling       {:mvn/version \"0.10.0\"}
         party.donut/generate             {:mvn/version \"1.0.80\"}
         party.donut/generators           {:mvn/version \"0.0.51\"}
         party.donut/single-page-app      {:git/url \"https://github.com/donut-party/single-page-app-template\"
                                           :git/sha \"ffff000\"}}}"
         (cli-update-deps/update-pins bb-edn {:versions     '{party.donut/generate   "1.0.80"
                                                               party.donut/generators "0.0.51"}
                                              :template-sha "ffff000"}))))
