(ns com.mjdowney.v2
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as string]
            [rewrite-clj.zip :as z]
            [rewrite-clj.node :as n]))

(def newline-node (n/newline-node "\n"))
(def line (comp first z/position))

(defn strip-comment [zloc]
  (let [trailing? (when-let [prv (z/left zloc)] (>= (line prv) (line zloc)))]
    (if trailing? ; comment is on the same line as a form, just replace with nl
      (z/replace zloc newline-node)
      (z/remove* zloc))))

(defn rich-comment? [zloc]
  (when-let [s (when (z/sexpr-able? zloc) (z/sexpr zloc))]
    (and (seqable? s) (= (first s) 'comment))))

(defn strip [zloc]
  (cond
    (z/linebreak? zloc)          (z/replace zloc newline-node)
    (= (z/tag zloc) :comment)    (strip-comment zloc)
    (= (z/tag zloc) :multi-line) (z/remove zloc)
    (rich-comment? zloc)         (z/remove zloc)
    :else                        zloc))

(defn lines-of-code [zloc]
  (if (z/end? zloc) ; count the root node lines if we're at the end
    (let [lines (string/split-lines (z/root-string zloc))
          remove-blank (remove (comp empty? string/trim))
          counting (completing (fn [n _] (inc n)))]
      (transduce remove-blank counting 0 lines))
    (recur (z/next* (strip zloc)))))

(defn analyze [f]
  (let [^java.io.File f (io/file f)
        zloc (z/of-file f {:track-position? true})]
    {"File" (.getPath f)
     "LOC"  (lines-of-code (z/leftmost* zloc))
     "Lines" (-> zloc z/root meta :end-row)}))

(defn path [^java.io.File f] (.getPath f))
(defn dir? [^java.io.File f] (.isDirectory f))
(defn ls   [^java.io.File f] (.listFiles f))

(defn analyze-file-tree [root file-pred]
  (->> (tree-seq dir? ls (io/file root))
       (remove dir?)
       (filter file-pred)
       (pmap analyze)
       (sort-by (comp - #(get % "LOC")))))

(defn extension? [ext]  (fn [f] (string/ends-with? (path f) ext)))
(defn file-mask  [exts] (apply some-fn (map extension? exts)))

(comment
  (let [root (io/file "/home/matthew/the-system/src/the_system")
        data (analyze-file-tree root (file-mask #{".clj"}))
        dirs (filter #(and (dir? %) (> (count (ls %)) 1))
               (tree-seq dir? ls root))]
    (pprint/print-table
      (sort-by #(get % "Dir")
        (for [dir dirs
              :when (some (file-mask #{"clj"}) (ls dir))
              :let [path (path dir)
                    data (filter #(string/starts-with? (get % "File") path) data)]
              :when (seq data)]
          (let [totals (apply merge-with + (map #(dissoc % "File") data))]
            (into {"Dir" path} totals))))))

  (let [root (io/file "/home/matthew/the-system/")
        data (analyze-file-tree root (file-mask #{".clj"}))
        totals (apply merge-with + (map #(dissoc % "File") data))]
    (pprint/print-table
      (concat data [(assoc totals "File" "SUM")]))))
