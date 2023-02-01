(ns com.mjdowney.count-clj-loc
  (:require
    [clojure.java.io :as io]
    [rewrite-clj.zip :as z]))

(defrecord AnalyzerState [zloc fresh-line? lines whitespace multi-strs single-strs rich-comments comments])

(defn next* [{:keys [zloc] :as azs}]
  (let [line (comp first z/position)
        zloc' (z/next* zloc)
        prev-end (:end-row (meta (z/node zloc)))
        this-line (line zloc')]
    (if-not (= prev-end this-line)
      (assoc azs :zloc zloc' :fresh-line? true)
      (assoc azs :zloc zloc'))))

(defn start+end-row [zloc]
  (let [{:keys [row end-row]} (meta (z/node zloc))]
    [row end-row]))

(defn analyze* [{:keys [zloc fresh-line?] :as azs}]
  (let [tag (z/tag zloc)
        azs (if-not (z/end? zloc)
              (update azs :lines max (-> zloc z/node meta :end-row))
              azs)]
    (cond
      (z/end? zloc) azs

      (z/linebreak? zloc)
      (let [start (first (z/position zloc))
            zloc' (z/find-next zloc z/next* (complement z/whitespace?))
            end (first (z/position (or zloc' (z/rightmost* zloc))))]
        (recur
          (if (= end start)
            (assoc azs :zloc zloc')
            (-> azs
                (assoc :zloc zloc' :fresh-line? true)
                (update :whitespace + (- end start (if fresh-line? 0 1)))))))

      (z/whitespace? zloc)
      (recur (next* azs))

      (= tag :comment)
      (if fresh-line?                                       ; the comment is on a line by itself
        (recur (-> azs (update :comments inc) next*))
        (recur (next* (assoc azs :fresh-line? true))))

      (= tag :multi-line)
      (let [[start end] (start+end-row zloc)
            lines (+ (- end start) (if fresh-line? 1 0))]
        (recur
          (-> azs
              (update :multi-strs + lines)
              (assoc :fresh-line? false)
              next*)))

      (and (= tag :list) (= (first (z/sexpr zloc)) 'comment))
      (let [[start end] (start+end-row zloc)
            comment-lines (+ (- end start) (if fresh-line? 1 0))]
        (recur
          (-> azs
              (update :rich-comments + comment-lines)
              (assoc :zloc (z/right* zloc) :fresh-line? false))))

      :else (recur (next* (assoc azs :fresh-line? false))))))

(defn analyze [zloc]
  (let [ret (analyze* (->AnalyzerState zloc true 0 0 0 0 0 0))]
    (dissoc ret :zloc :fresh-line?)))

(defn analyze-str [s]
  (analyze (z/leftmost* (z/of-string s {:track-position? true}))))

^:rct/test
(comment
  ;; Multi-line strs: three in the namespace doc, two in the function doc
  (analyze-str
    "(ns core
      \"This comment will span
      three
      lines\"
      (:require
        [foo.bar :as baz]
        [abc.def :as whatever]))

    (defn some-fn
      \"This comment also spans
      a couple lines\"
      [x]
      (+ x x))")
  ;=>> {:multi-strs 5 :whitespace 1 :lines 13 ...}

  ; With five lines that are just comments, and four which are just whitespace
  (analyze-str
    "; Some comment on the ns
    (#_weird-way-to-declar-an-ns ns core
      (:require [x.y :as z])) ; this comment does not count as its own line


    ;;; Some stuff follows after another double newline
     , ;; (Whitespace comes before this comment)
    ;;; After another double newline


    ;; And a comment
    (+ 1 2 3)")
  ;=>> {:whitespace 4 :comments 5 :lines 12 ...}

  ;; Rich comment forms
  (analyze-str
    "(ns core)

    (defn add [x y] (+ x y))


    (comment
      ;; Demonstrating the addition fn
      (add 1 2) ;=> 3
      (add 2 5) ;=> 7
      )")
  ;=>> {:rich-comments 5 :whitespace 3 :lines 10 ...}
  )


^:rct/test
(comment
  ;; Analysis of the source file at resources/example.clj
  (analyze
    (z/of-file
      (io/resource "example.clj")
      {:track-position? true}))
  ;=>> {:lines 29
  ;     :whitespace 6
  ;     :multi-strs 5
  ;     :rich-comments 7
  ;     :comments 2}
  )
