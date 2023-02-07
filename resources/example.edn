; Some namespace
(ns example
  "Namespace
  documentation
  string."
  (:require [foo.bar :as baz] #_[partially.commented :as not-a-whole-line]))

;; Some function definitions

(defn a-fn
  "Do something with the given
  a and b args."
  [a b]
  (comment ;; These three lines are a comment form
    (unchecked-add-int a b)
    )
  (let [ret (+ a b)
        _unused (comment "but this is not...")]
    ret))

(defn another-fn
  "Terser, one-line doc string"
  [x]
  ["return string that isn't by itself" ; comment that isn't by itself

   "one string" "two strings"

   (dec x)])

#_(defn this-is-commented-out []
  (+ 1 1)
  )

^:rct/test
(comment
  ;; For example, a rich comment form
  "Some comment
  string"

  (+ 2 2) ;=> 4
  )

