(ns donut.dough.clojars
  "Reads published versions from the Clojars API."
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]))

(defn artifact
  "Clojars' metadata for `lib` (a symbol like party.donut/generate), or nil
  if Clojars has no such artifact."
  [lib]
  (let [group    (or (namespace lib) (name lib))
        response (http/get (format "https://clojars.org/api/artifacts/%s/%s" group (name lib))
                           {:headers {"Accept" "application/json"}
                            :throw   false})]
    (case (:status response)
      200 (json/parse-string (:body response) true)
      404 nil
      (throw (ex-info (str "Clojars API returned " (:status response) " for " lib)
                      {:lib lib :status (:status response)})))))

(defn latest-release
  "The newest non-snapshot version of `lib` on Clojars. Throws if Clojars
  doesn't have the artifact."
  [lib]
  (or (:latest_release (artifact lib))
      (throw (ex-info (str "no release of " lib " on Clojars") {:lib lib}))))
