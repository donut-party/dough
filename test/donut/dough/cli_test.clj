(ns donut.dough.cli-test
  (:require
   [clojure.test :refer [deftest is]]
   [donut.dough.cli :as cli]))

(deftest local-bb-edn-test
  (is (= {:paths ["../../../donut-cli/donut-cli/src" "../../../donut-cli/donut-cli/resources"]
          :tasks {'new {:task 'donut.cli.new/-main}}
          :deps  {'io.github.paintparty/bling   {:mvn/version "0.10.0"}
                  'party.donut/generate         {:local/root "/src/generate"}
                  'party.donut/generators       {:mvn/version "0.0.50"}
                  'party.donut/single-page-app  {:local/root "/src/single-page-app-template"}}}
         (cli/local-bb-edn {:paths ["src" "resources"]
                            :tasks {'new {:task 'donut.cli.new/-main}}
                            :deps  {'io.github.paintparty/bling  {:mvn/version "0.10.0"}
                                    'party.donut/generate        {:mvn/version "1.0.77"}
                                    'party.donut/generators      {:mvn/version "0.0.50"}
                                    'party.donut/single-page-app {:git/url "https://github.com/donut-party/single-page-app-template"
                                                                  :git/sha "adb3d23"}}}
                           "../../../donut-cli/donut-cli"
                           ;; generators isn't checked out, so it keeps its Clojars version
                           {'party.donut/generate        "/src/generate"
                            'party.donut/single-page-app "/src/single-page-app-template"
                            'party.donut/system          "/src/system"}))))
