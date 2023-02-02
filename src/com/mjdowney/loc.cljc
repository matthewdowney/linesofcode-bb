(ns com.mjdowney.loc
  "Count lines of Clojure code and the ratio of docs and comments vs code."
  (:require
    [clojure.java.io :as io]
    [clojure.set :as set]
    [com.mjdowney.loc.analyze :as a]
    [rewrite-clj.zip :as z]))

(defn analyze*
  "Like `analyze`, but returns sets of line numbers instead of counts."
  [file-or-resource]
  (let [zloc (z/of-file file-or-resource {:track-position? true})
        {:keys [lines exclusive inclusive]} (a/analyze zloc)
        single-line-strings (set/difference
                              (:string inclusive)
                              (:other inclusive)
                              (:comment inclusive))
        multi-line-strings (set/difference
                             (:multi-line exclusive)
                             (:other inclusive))
        docstrings (set/union single-line-strings multi-line-strings)

        ; Comments + unevals with nothing else on the same line comments
        comments
        (set/difference
          (set/union
            (:comment inclusive)
            (:uneval exclusive))
          (:string inclusive) (:multi-line exclusive) (:other inclusive))

        comment-forms
        (set/difference
          (:comment-form exclusive)
          ; i.e. don't count (let [x (comment "foo")] ...) as a
          ; comment form line or a line of "foo" (comment 'bar), for that matter
          (:other inclusive) (:string inclusive))

        loc (:other inclusive)
        whitespace (set/difference
                     (into #{} (range 1 (inc lines)))
                     comments comment-forms docstrings loc)]
    {:comments comments
     :comment-forms comment-forms
     :docstrings docstrings
     :loc loc
     :whitespace whitespace
     :lines lines}))

(defn analyze
  "Analyze a Clojure file and return a map counting lines of different types:

  Return keys:
    - :comments Lines exclusively composed of `;` comments or #_(...) forms

    - :comment-forms Lines exclusively composed of / spanned by `(comment ...)`
      forms. Lines contained by `(comment ...)` are not also counted elsewhere,
      e.g. blank lines in the comment form do not increase the
      :comments-and-whitespace count.

    - :docstrings Includes *all* multi-line strings and string literals which
      appear alone on a line. More of an approximation of things which are
      *likely* to be docstrings.

    - :loc Other non-whitespace / comment forms.

    - :lines Total lines in the file, including trailing newlines."
  [file-or-resource]
  (let [analysis (analyze* file-or-resource)]
    (-> analysis
        (dissoc :lines)
        (update-vals count)
        (assoc :lines (:lines analysis)))))

(defn pprint
  "Analyze the file `f` and pprint it to stdout, prefixing each line with the
  way the line is categorized."
  [f]
  (let [{:keys [comments comment-forms docstrings loc whitespace]} (analyze* f)]
    (letfn [(describe [line-number]
              (condp contains? line-number
                whitespace "WHITESP"
                comments "COMMENT"
                comment-forms "COMMENTF"
                docstrings "DOC"
                loc "CODE"
                ""))]
      (with-open [rdr (io/reader f)]
        (->> (line-seq rdr)
             (map-indexed vector)
             (run!
               (fn [[idx line]]
                 (let [idx (inc idx)]
                   (println
                     (format "%-8s %4s %s" (describe idx) idx line))))))))))

^:rct/test
(comment
  ;; For example...
  (def f (io/resource "example.clj"))

  ; You can also pprint the file to see how each line is being counted
  (pprint f)

  ; Every line is accounted for
  (= (->> (dissoc (analyze f) :lines) vals (reduce +))
     (:lines (analyze f)))
  ;=> true

  ; Example file has the correct number of each kind of line
  (analyze f)
  ;=>>
  ^:matcho/strict
  {:lines 43
   :whitespace 9
   :loc 12

   :comments 5
   :comment-forms 11
   :docstrings 6})
