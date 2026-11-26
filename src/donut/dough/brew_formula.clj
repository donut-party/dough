(ns donut.dough.brew-formula
  "Renders ../homebrew-brew/Formula/donut.rb for the donut CLI's current
  version from Formula/donut.rb.template. The release zip must already
  exist: cli-publish pushes the tag GitHub archives it from."
  (:require
   [babashka.fs :as fs]
   [babashka.http-client :as http]
   [donut.dough.cli-bump :as cli-bump]
   [donut.dough.process :as process]
   [donut.dough.structure :as structure]
   [selmer.parser :as selmer])
  (:import
   [java.security MessageDigest]))

(def release-url-pattern
  "https://github.com/donut-party/donut-cli/archive/refs/tags/v%s.zip")

(defn formula-dir
  []
  (fs/path (structure/repo-path "homebrew-brew") "Formula"))

(defn sha256-hex
  [^bytes bs]
  (->> (.digest (MessageDigest/getInstance "SHA-256") bs)
       (map #(format "%02x" %))
       (apply str)))

(defn render
  [template {:keys [version sha256]}]
  (selmer/render template {:version version :sha256 sha256}))

(defn -main
  [& _]
  (let [version (cli-bump/version)
        zip     (:body (http/get (format release-url-pattern version) {:as :bytes}))
        out     (fs/path (formula-dir) "donut.rb")]
    (spit (fs/file out)
          (render (slurp (fs/file (formula-dir) "donut.rb.template"))
                  {:version version :sha256 (sha256-hex zip)}))
    (process/tee (format "[homebrew-brew] wrote %s for v%s; commit and push homebrew-brew to publish it"
                         out version))))
