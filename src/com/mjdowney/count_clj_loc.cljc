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

(defmulti -analyze
  "Take the AnalyzerState and dispatch on the `rewrite-clj` tag for the node at
  the current :zloc."
  (fn [_azs zloc-tag] zloc-tag))

(defmethod -analyze :newline
  [{:keys [zloc fresh-line?] :as azs} _]
  (let [start (first (z/position zloc))
        zloc (z/find-next zloc z/next* (complement z/whitespace?))
        end (if zloc
              (first (z/position zloc))
              (inc (:total-lines azs)))]
      (if (= end start)
        (assoc azs :zloc zloc)
        (-> azs
            (assoc :zloc zloc :fresh-line? true)
            (update :blank-lines + (- end start (if fresh-line? 0 1)))))))

;; For non-newline whitespace and commas, just skip over, for unmatched forms
;; skip over, but tag the line with {:fresh-line? false}
(defmethod -analyze :whitespace [azs _] (next* azs))
(defmethod -analyze :comma      [azs _] (next* azs))
(defmethod -analyze :default    [azs _] (next* (assoc azs :fresh-line? false)))

(defmethod -analyze :comment
  [{:keys [fresh-line?] :as azs} _]
  (if fresh-line? ; the comment is on a line by itself
    (-> azs (update :comment-lines inc) next*)
    (next* (assoc azs :fresh-line? true))))

(defmethod -analyze :multi-line
  [{:keys [zloc fresh-line?] :as azs} _]
  (let [[start end] (start+end-row zloc)
        lines (+ (- end start) (if fresh-line? 1 0))]
    (-> azs
        (update :string-lines + lines)
        (assoc :fresh-line? false)
        next*)))

(defmethod -analyze :list
  [{:keys [zloc fresh-line?] :as azs} _]
  (if (= (first (z/sexpr zloc)) 'comment) ; rich comment form
    (let [[start end] (start+end-row zloc)
          comment-lines (+ (- end start) (if fresh-line? 1 0))]
      (-> azs
          (update :rich-comment-lines + comment-lines)
          (assoc :zloc (z/right* zloc) :fresh-line? false)))
    (-analyze azs :default)))

(defmacro spy [form] `(do #_(println ~(str form) "=>" ~form) ~form))

(defn start-row [zloc] (first (z/position zloc)))
(defn end-row [zloc] (:end-row (meta (z/node zloc))))
(defn first-on-line? [zloc]
  (let [prv (z/prev zloc)]
    (or (not prv) (< (start-row prv) (start-row zloc)))))

(defn last-on-line? [zloc]
  (let [nxt (z/next zloc)]
    (or (z/end? nxt) (< (start-row zloc) (start-row nxt)))))

(def alone-on-line? (every-pred first-on-line? last-on-line?))

(defmethod -analyze :token
  [{:keys [zloc] :as azs} _]
  (if (and (z/sexpr-able? zloc) (string? (z/sexpr zloc)) (alone-on-line? zloc))
    ; It's a string, if the next thing (except comments/whitespace) is on the
    ; next line, then it's a string line
    (let [next-zloc (z/right zloc)
          _ (spy (z/sexpr zloc))]
      (if (or (z/end? next-zloc)
              (> (first (z/position next-zloc))
                 (first (z/position zloc))))
        (-> azs
            (update :string-lines inc)
            (assoc :fresh-line? false)
            next*)
        (-analyze azs :default)))
    (-analyze azs :default)))

(defn analyze* [{:keys [zloc] :as azs}]
  (if (z/sexpr-able? zloc) (spy (z/sexpr zloc))) ;; TODO
  (if (z/end? zloc)
    azs
    (recur (-analyze azs (z/tag zloc)))))

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

(comment
  (z/node
    (z/next*
      (z/leftmost* (z/of-string "[\"str\"]" {:track-position? true}))))

  (analyze-str
    "
(defn x
\"docstring\"
[y]
[\"a string\"])")
  )

^:rct/test
(comment
  ;; Analysis of the source file at resources/example.clj
  (analyze
    (z/of-file
      (io/resource "example.clj")
      {:track-position? true}))
  ;=>>
  ^:matcho/strict
  {:total-lines 33
   :blank-lines 8
   :string-lines 6
   :rich-comment-lines 7
   :comment-lines 2}
  )
