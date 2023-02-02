; Some namespace
(ns example
  "Namespace
  documentation
  string."
  (:require [foo.bar :as baz]))

;; Some function definitions

(defn some-fn
  "Do something with the given
  a and b args."
  [a b]
  (+ a b))

(defn another-fn
  "Terser, one-line doc string"
  [x]
  ["return string that isn't by itself" ; comment that isn't by itself

   "one string" "two strings"

   (dec x)])

(comment
  ;; For example, a rich comment form
  "Some comment
  string"

  (+ 2 2) ;=> 4
  )

