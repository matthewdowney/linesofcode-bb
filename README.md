[![bb compatible](https://raw.githubusercontent.com/babashka/babashka/master/logo/badge.svg)](https://babashka.org)

## linesofcode-bb

Babashka script to count lines of Clojure code, docs, comments, and more. 

Uses `rewrite-clj` to do a more granular analysis of what kinds of forms are 
present on each source file line.

### Coordinates

```clojure
io.github.matthewdowney/linesofcode-bb {:git/tag "v0.01" :git/sha ""}
```

### Example: as a one-liner

To analyze the lines of code under the `src` and `test` directories of the 
working directory, by file:

```bash
bb -Sdeps '{:deps {io.github.matthewdowney/linesofcode-bb {:git/tag "v0.01" :git/sha ""}}}' \
   -x com.mjdowney.loc/breakdown \ 
   --root "src/" "test/"
```

```
Analyzing files with options:
{:root ["src/" "test/"]}

|                              File | LOC | Docs | Comment Forms | Lines |
|-----------------------------------+-----+------+---------------+-------|
|         src/com/mjdowney/loc.cljc | 130 |   29 |            38 |   215 |
| src/com/mjdowney/loc/analyze.cljc |  46 |   49 |            58 |   187 |
|      src/com/mjdowney/loc/fs.cljc |  18 |    9 |             0 |    33 |
|                               SUM | 194 |   87 |            96 |   435 |
```

<details>
<summary>Or for a project-wide summary, without individual files</summary>

```bash
bb -Sdeps '{:deps {io.github.matthewdowney/linesofcode-bb {:git/tag "v0.01" :git/sha ""}}}' \
   -x com.mjdowney.loc/summarize \
   --root "src/" "test/"
```

```
Analyzing files with options:
{:root ["src/" "test/"]}

|               | Lines |     % |
|---------------+-------+-------|
|  Comments (;) |    26 |   6.0 |
|    Whitespace |    32 |   7.4 |
|    Docstrings |    87 |  20.0 |
| Comment Forms |    96 |  22.1 |
|           LOC |   194 |  44.6 |
|         Total |   435 | 100.0 |
```
</details>

### Example: as a babashka task

For example, in [rich-comment-tests]() I have the following in my bb.edn:
```clojure
{:tasks
 {loc {:extra-deps
       {io.github.matthewdowney/linesofcode-bb
        {:git/tag "v0.0.1" :git/sha ""}}
       :requires ([com.mjdowney.loc :as loc])
       :task     (loc/breakdown
                   {:root    ["src" "bb" "test"]
                    :exclude ["src/dev"]})}}}
```

<details>
<summary>Which can be invoked like...</summary>

```bash
$ bb loc
Analyzing files with options:
{:root ["src" "bb" "test"], :exclude ["src/dev"]}

|                                                 File | LOC | Docs | Comment Forms | Lines |
|------------------------------------------------------+-----+------+---------------+-------|
|             src/com/mjdowney/rich_comment_tests.cljc | 214 |   42 |           149 |   477 |
|  src/com/mjdowney/rich_comment_tests/emit_tests.cljc | 121 |   12 |            16 |   179 |
| src/com/mjdowney/rich_comment_tests/test_runner.cljc |  62 |   10 |             0 |    89 |
|        test/com/mjdowney/rich_comment_tests_test.clj |  48 |   29 |             0 |    83 |
|                             bb/test_rct_with_bb.cljc |  34 |    4 |            24 |    77 |
|                                 bb/test_helpers.cljc |  45 |    2 |             0 |    53 |
|                                                  SUM | 524 |   99 |           189 |   958 |
```
</details>

### Example: as a library

```clojure
(require '[babashka.deps :as deps])

(deps/add-deps 
  '{:deps 
    {io.github.matthewdowney/linesofcode-bb {:git/tag "v0.0.1" :git/sha ""}}})

(require '[com.mjdowney.loc :as loc])

(loc/breakdown {:root ["/home/some-project"] :exclude ["target" ".git"]})
```

## Pretty printing source files

You can also get descriptive output of how it is counting each line of a source
file by invoking `com.mjdowney.loc/pprint`.

```clojure 
(require '[com.mjdowney.loc :as loc])
(require '[clojure.java.io :as io])

(loc/pprint (io/resource "example.edn"))
```
<details>
<summary>Prints...</summary>

```clojure
COMMENT     1 ; Some namespace
CODE        2 (ns example
DOC         3   "Namespace
DOC         4   documentation
DOC         5   string."
CODE        6   (:require [foo.bar :as baz] #_[partially.commented :as not-a-whole-line]))
WHITESP     7 
COMMENT     8 ;; Some function definitions
WHITESP     9 
CODE       10 (defn a-fn
DOC        11   "Do something with the given
DOC        12   a and b args."
CODE       13   [a b]
COMMENTF   14   (comment ;; These three lines are a comment form
COMMENTF   15     (unchecked-add-int a b)
COMMENTF   16     )
CODE       17   (let [ret (+ a b)
CODE       18         _unused (comment "but this is not...")]
CODE       19     ret))
WHITESP    20 
CODE       21 (defn another-fn
DOC        22   "Terser, one-line doc string"
CODE       23   [x]
CODE       24   ["return string that isn't by itself" ; comment that isn't by itself
WHITESP    25 
CODE       26    "one string" "two strings"
WHITESP    27 
CODE       28    (dec x)])
WHITESP    29 
COMMENT    30 #_(defn this-is-commented-out []
COMMENT    31   (+ 1 1)
COMMENT    32   )
WHITESP    33 
COMMENTF   34 ^:rct/test
COMMENTF   35 (comment
COMMENTF   36   ;; For example, a rich comment form
COMMENTF   37   "Some comment
COMMENTF   38   string"
COMMENTF   39 
COMMENTF   40   (+ 2 2) ;=> 4
COMMENTF   41   )
WHITESP    42 
```
</details>
  
## Rationale

My goals were to 
- approximate the functionality of [lein-count](https://github.com/aiba/lein-count)
- break down lines of code vs rich comment forms, docstrings, etc.
- run without lein

because
- I've increasingly been using [rich-comment-tests](https://github.com/matthewdowney/rich-comment-tests) in my projects
- I'm also liking writing more descriptive namespace docs and examples inside of 
  comment forms, and then using babashka to pull them out and organize them in 
  markdown docs
