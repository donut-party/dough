(ns donut.dough.process
  "Convenience wrappers over babashka.process."
  (:require
   [babashka.process :as ps]
   [bling.core :as bling]
   [clojure.string :as str]))

(defn tee
  "Prints a string, styled with bling (default :info; nil for plain), and
  returns it."
  ([s]
   (tee s :info))
  ([s style]
   (if style
     (println (bling/bling [style s]))
     (println s))
   s))

(defn sh-out
  "Runs a command and returns its stdout. Throws, with stderr, on a non-zero
  exit."
  [& args]
  (let [{:keys [exit out err]} (apply ps/sh args)]
    (when-not (zero? exit)
      (throw (ex-info (str "command failed: " (str/join " " (remove map? args)) "\n" err)
                      {:args args :exit exit :err err})))
    out))
