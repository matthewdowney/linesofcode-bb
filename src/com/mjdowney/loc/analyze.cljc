(ns com.mjdowney.loc.analyze
  "Analyze a source file with `rewrite-clj` and return sets of line numbers.

  Sets are for a series of code elements (comments, comment forms, multi-line
  strings, etc.) and the line numbers on which these forms appear or which these
  forms span.

  To get useful output, you then have to combine and count the sets (e.g. to
  find every line number occupied only by (comment ) forms).

  See `analyze`."
  (:require [rewrite-clj.zip :as z]))

(defn conj-span
  "Update `lines-set` to include each line in `[row end-row]` inclusive."
  [lines-set row end-row]
  (loop [lines-set (or lines-set #{}) i row]
    (if (<= i end-row)
      (recur (conj lines-set i) (inc i))
      lines-set)))

(defn comment-form?
  "Is `zloc` a `(comment ...)` form?"
  [zloc]
  (if (= (z/tag zloc) :meta)
    (recur (-> zloc z/down z/right))
    (and (= (z/tag zloc) :list) (= (z/string (z/down zloc)) "comment"))))

(defn string-token?
  "Is `zloc` a string literal?"
  [zloc]
  (and (= (z/tag zloc) :token) (z/sexpr-able? zloc) (string? (z/sexpr zloc))))

(defn skip
  "Skip with right* unless `zloc` is already the rightmost point, in which
  case navigate upwards and try again."
  [zloc]
  (when-not (z/end? zloc)
    (or (z/right* zloc) (skip (z/up* zloc)))))

(defn analyze
  "Analyze the zipper `zl` starting from the left-most position, returning a
  map shaped

    {:lines     <total number of lines in the file>
     :exclusive {...}
     :inclusive {...}}

  where :exclusive and :inclusive are maps of form type -> set of line numbers
  on which the form occurs.

  The :exclusive map contains:
    - :comment-form Line numbers spanned by `(comment ...)` forms
    - :uneval Lines spanned by `#_( ... )` forms
    - :multi-line Lines spanned by multi-line strings (usually docstrings)

  The :inclusive map contains:
    - :comment Line numbers on which a `;` comment appears
    - :string Line numbers on which *exactly one* string literal appears
    - :other Line numbers on which any other non-whitespace form starts and ends

  The analysis skips of :exclusive forms instead of traversing them deeper, so
  e.g. a (comment \"string\") form is *just* counted as a :comment-form, and
  does not affect the :inclusive :string count.

  The :inclusive forms, on the other hand, are traversed to full depth, so you
  might find a :comment, :string, and :other form all on the same line.

  The return sets can be used to e.g. find all the lines which contain a string
  literal and *only* comments or whitespace, which are likely to be one-line
  docstrings, and sum them with :multi-line strings to get an approximation of
  how much documentation a namespace includes.

  The :inclusive set of :other line numbers is the most straightforward way to
  count 'lines of code'."
  [zl]
  (let [span  (fn [xs id s e]  (update-in xs [:exclusive id] conj-span s e))
        pos   (fn [xs id & ps] (update-in xs [:inclusive id] into ps))
        tally (fn [xs id s]    (update-in xs [:inclusive id s] (fnil inc 0)))]
    (loop [zl (z/leftmost* zl)
           xs {:lines (or (-> zl z/root meta :end-row) 0)
               :exclusive {:comment-form #{} :uneval #{} :multi-line #{}}
               :inclusive {:comment #{} :string {} :other #{}}}]
      (if (z/end? zl)
        ; Before returning, turn the string mapping into a set of one-string
        ; lines, and categorize multi-string lines as :other
        (reduce-kv
          (fn [xs line-num n-strings]
            (if (= n-strings 1)
              (update-in xs [:inclusive :string] conj line-num)
              (update-in xs [:inclusive :other] conj line-num)))
          (assoc-in xs [:inclusive :string] #{})
          (get-in xs [:inclusive :string]))

        (let [tag (z/tag zl)
              {s :row e :end-row} (-> zl z/node meta)]
          (cond
            ; For these three kinds, record the whole span and then skip over
            ; them without searching deeper
            (comment-form? zl)  (recur (skip zl)    (span xs :comment-form s e))
            (= tag :uneval)     (recur (skip zl)    (span xs :uneval s e))
            (= tag :multi-line) (recur (z/next* zl) (span xs :multi-line s e))

            ; Record only the start lines for comments (a comment is by
            ; definition one line)
            (= tag :comment)    (recur (z/next* zl) (pos xs :comment s))

            ; Count the number of strings per line, in order to later exclude
            ; lines with multiple strings
            (string-token? zl)  (recur (z/next* zl) (tally xs :string s))

            ; Ignore whitespace + the top-level :forms zipper
            (or (z/whitespace? zl) (= tag :forms)) (recur (z/next* zl) xs)

            ; Tally start / end lines for all other forms under :other
            :else               (recur (z/next* zl) (pos xs :other s e))))))))

(comment
  ;; For example, to analyze this file
  (def zloc
    (z/of-file
      "src/com/mjdowney/loc/analyze.cljc"
      {:track-position? true}))

  ;; ... just look at the line count of each type, because the sets are large
  (-> (analyze zloc)
      (update :inclusive update-vals count)
      (update :exclusive update-vals count))
  )

; So that this NS has an :uneval form to look at, also helps make sense of
; which lines are counted as :other vs something specific though
#_(let [analysis (analyze zloc)]
    (dotimes [i (:lines analysis)]
      (let [i (inc i)]
        (println
          (format "%3s: %s" i
            (if (contains? (get-in analysis [:inclusive :other]) i)
              "CODE"
              "----"))))))

^:rct/test
(comment
  (defn astr [s] (analyze (z/of-string s {:track-position? true})))

  ; Tracks `;` comments, string literals, and sexprs
  (astr
    "; Some namespace
    (ns example
      \"Ns docstring\"
      (:require [foo.bar :as baz]
                [\"cljs-import-style\" :as x]))")
  ;=>>
  {:lines 5
   :exclusive {:comment-form #{} :uneval #{} :multi-line #{}}
   :inclusive {:comment #{1} :string #{3 5} :other #{2 4 5}}}

  ; Handles #_ reader macro and weird (comment) cases
  (astr
    "(ns example
      (:require [foo.bar :as baz] #_[\"cljs-import-style\" :as x]))

    ; Fn defs

    (defn f
      \"Docstring for
       f\"
      [x]
      (let [ret (inc #_y x)
            _unused (comment \"todo\")]
        ret))

    ^:rct/test
    (comment
      (f 1) ;=> 2
      (f 2) ;=> 3
    )")
  ;=>>
  {:lines 18,
   :exclusive {:comment-form #{11 ; comment form inside the let
                               14 15 16 17 18} ; last comment form
               :uneval #{2 10}
               :multi-line #{7 8}}
   :inclusive {:comment #{4}
               :string #{}
               :other #{1 2 6 9 10 11 12}}}
  )
