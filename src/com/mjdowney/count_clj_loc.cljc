(ns com.mjdowney.count-clj-loc
  (:require
    [clojure.java.io :as io]
    [rewrite-clj.zip :as z]))

(defrecord AnalyzerState
  [zloc        ; current zipper location
   fresh-line? ; true iff the line is just whitespace so far

   total-lines        ; total line count in the file
   blank-lines        ; just whitespace
   string-lines       ; exclusively occupied by strings (e.g. docstrings)
   rich-comment-lines ; count of lines spanned by (comment ...) forms
   comment-lines      ; count of lines exclusively occupied by ';...' comments
   ])

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
  (let [tag (z/tag zloc)]
    (cond
      (z/end? zloc) azs

      (z/linebreak? zloc)
      (let [start (first (z/position zloc))
            zloc (z/find-next zloc z/next* (complement z/whitespace?))
            end (if zloc
                  (first (z/position zloc))
                  (inc (:total-lines azs)))]
        (recur
          (if (= end start)
            (assoc azs :zloc zloc)
            (-> azs
                (assoc :zloc zloc :fresh-line? true)
                (update :blank-lines + (- end start (if fresh-line? 0 1)))))))

      (z/whitespace? zloc)
      (recur (next* azs))

      (= tag :comment)
      (if fresh-line? ; the comment is on a line by itself
        (recur (-> azs (update :comment-lines inc) next*))
        (recur (next* (assoc azs :fresh-line? true))))

      (= tag :multi-line)
      (let [[start end] (start+end-row zloc)
            lines (+ (- end start) (if fresh-line? 1 0))]
        (recur
          (-> azs
              (update :string-lines + lines)
              (assoc :fresh-line? false)
              next*)))

      (and fresh-line? (= tag :token) (z/sexpr-able? zloc) (string? (z/sexpr zloc)))
      ; It's a string, if the next thing (except comments/whitespace) is on the
      ; next line, then it's a string line
      (let [next-zloc (z/right zloc)]
        (if (or (z/end? next-zloc)
                (> (first (z/position next-zloc))
                   (first (z/position zloc))))
          (recur
            (-> azs
                (update :string-lines inc)
                (assoc :fresh-line? false)
                next*))
          (recur (next* azs))))

      (and (= tag :list) (= (first (z/sexpr zloc)) 'comment))
      (let [[start end] (start+end-row zloc)
            comment-lines (+ (- end start) (if fresh-line? 1 0))]
        (recur
          (-> azs
              (update :rich-comment-lines + comment-lines)
              (assoc :zloc (z/right* zloc) :fresh-line? false))))

      :else (recur (next* (assoc azs :fresh-line? false))))))

(defn analyze [zloc]
  (let [lines (-> zloc z/leftmost* z/up z/node meta :end-row)
        ret (analyze* (->AnalyzerState (z/leftmost* zloc) true lines 0 0 0 0))]
    (dissoc ret :zloc :fresh-line?)))

(defn analyze-str [s]
  (analyze (z/of-string s {:track-position? true})))

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
  ;=>> {:string-lines 5 :blank-lines 1 :total-lines 13 ...}

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
  ;=>> {:blank-lines 4 :comment-lines 5 :total-lines 12 ...}

  ;; Rich comment forms
  (analyze-str
    "(ns core)

    (defn add [x y] (+ x y))


    (comment
      ;; Demonstrating the addition fn
      (add 1 2) ;=> 3
      (add 2 5) ;=> 7
      )")
  ;=>> {:rich-comment-lines 5 :blank-lines 3 :total-lines 10 ...}

  ;; Single line comments
  (analyze-str
    "(ns core
       \"A namespace to do X\")

    (defn add \"this string is on the same line as code\" [x y] (+ x y))

    (defn subtract
      \"Subtract y from x\"
      [x y]
      (- x y))

    (defn multiply
      \"Multiply
        a
      and
        b\"
      [a b]
      (* a b))

    \"weird case of a string by itself on the last line\"")
  ;=>> {:total-lines 19,
  ;     :blank-lines 4,
  ;     :string-lines 7,
  ;     :rich-comment-lines 0,
  ;     :comment-lines 0}
  )

^:rct/test
(comment
  ;; Analysis of the source file at resources/example.clj
  (analyze
    (z/of-file
      (io/resource "example.clj")
      {:track-position? true}))
  ;=>> {:total-lines 29
  ;     :blank-lines 6
  ;     :string-lines 6
  ;     :rich-comment-lines 7
  ;     :comment-lines 2}
  )
