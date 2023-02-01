; Some namespace
(ns example
  "Namespace
  documentation
  string."
  (:require [foo.bar :as baz]))

;; Main API

(defn some-fn
  "Do something with the given
  a and b args."
  [a b]
  (+ a b))

(defn another-fn
  "Terser, one-line doc string"
  [x]
  (dec x))

(comment
  ;; For example, a rich comment form
  "Some comment
  string"

  (+ 2 2) ;=> 4
  )

