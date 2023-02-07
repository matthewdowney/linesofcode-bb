(ns com.mjdowney.loc.fs
  "File tree utils."
  (:require [clojure.java.io :as io]))

;; File helpers
(defn cpath [^java.io.File f] (.getCanonicalPath f))
(defn path  [^java.io.File f] (.getPath f))
(defn dir?  [^java.io.File f] (.isDirectory f))
(defn ls    [^java.io.File f] (.listFiles f))

(defn pkeep
  "Map the function `f` in parallel over all files under :root, with `keep`
  semantics.

  Options:
    :root - A file, directory, or collection of the same.
    :exclude - A collection of files or directories to exclude from the search.
    :regex - A regex to match files against. Defaults to .+\\.(clj|cljs|cljc).
    :pred - A predicate to match files against. Defaults to the :regex."
  [f {:keys [root exclude pred regex] :or {regex #".+\.(clj|cljs|cljc)"}}]
  (let [root (if (coll? root) root [root])

        exclude (into #{} (map (comp cpath io/file)) exclude)
        search-recursive? (fn [f] (and (dir? f) (not (exclude (cpath f)))))

        pred (or pred #(and (not (dir? %)) (re-matches regex (path %))))
        pred (fn [f] (and (pred f) (not (exclude (cpath f)))))]
    (->> root
         (mapcat #(tree-seq search-recursive? ls (io/file %)))
         (filter pred)
         (pmap f)
         (filter some?))))
