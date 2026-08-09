(ns et.cb.caution-test
  "The adapter between a Recipe's version list and `us-vs-them`.

  None of the arithmetic is cookbook's, and none of it is tested here — the
  library has its own `caution_test.clj` and that is the only place the algorithm
  is pinned. What is cookbook's is the three statements it takes to ask the
  question: which text a Recipe's lines are, which order its versions go in, and
  which of the two `source` labels counts as us. Each of those can be wrong on its
  own while the arithmetic is perfect, and each is wrong silently — the ranges come
  back looking plausible either way.

  The fourth thing is the legend the API hands out with them, which is words rather
  than arithmetic and is wrong-able in the same quiet way: a legend that says the
  spectrum runs the other way is a correct answer with a lie attached to it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [et.cb.caution :as caution]))

(defn- version
  "A version as `GET /api/recipes/:id/versions` hands it over: newest first, so
  these are written newest first too."
  [n source description]
  {:version n :source source :description description
   :title "Sourdough" :useful_when "when baking"})

(deftest a-lone-version-is-whoever-wrote-it
  (testing "one version saved by hand — every line of it is his"
    (is (= [{:from 1 :to 2 :caution 1.0}]
           (caution/ranges [(version 1 "ui" "his first line\nhis second line")]))))
  (testing "and one written by an agent is up for grabs"
    (is (= [{:from 1 :to 2 :caution 0.0}]
           (caution/ranges [(version 1 "machine" "a line\nanother line")])))))

(deftest the-history-is-replayed-oldest-first
  ;; The one mistake this adapter can make that still returns a well-formed
  ;; answer. `/versions` is newest first, `assess` wants oldest first, and reading
  ;; the list in the order it arrives attributes every line to whoever wrote the
  ;; version *after* it — which for the common ladder (his text, an agent's
  ;; addition) hands the agent's line to him and vice versa.
  (let [ranges (caution/ranges [(version 2 "machine" "his line\nthe agent's line")
                                (version 1 "ui" "his line")])]
    (is (= [{:from 1 :to 1 :caution 1.0}
            {:from 2 :to 2 :caution 0.0}]
           ranges))))

(deftest us-is-the-ui-label
  ;; `:ours` is the whole of what cookbook contributes to the asymmetry, and the
  ;; two labels are the two this schema can hold. Getting it the wrong way round
  ;; is a total inversion that no shape check would catch.
  (let [ranges (caution/ranges [(version 2 "ui" "the agent's line\nhis line")
                                (version 1 "machine" "the agent's line")])]
    (is (= [{:from 1 :to 1 :caution 0.0}
            {:from 2 :to 2 :caution 1.0}]
           ranges))))

(deftest the-text-is-the-description
  ;; A version carries three content fields and only one of them has lines to be
  ;; careful in. Reading the title instead would answer about a single line and
  ;; look entirely reasonable doing it.
  (is (= [{:from 1 :to 3 :caution 1.0}]
         (caution/ranges [(version 1 "ui" "one\ntwo\nthree")]))))

(deftest an-empty-body-is-one-line-and-not-a-crash
  ;; A Recipe may be created with no description at all — the column defaults to
  ;; the empty string — and the split still has to answer something.
  (is (= [{:from 1 :to 1 :caution 1.0}]
         (caution/ranges [(version 1 "ui" "")])))
  (testing "and a nil, which is what a caller that selected the lean projection
            would hand over"
    (is (= [{:from 1 :to 1 :caution 0.0}]
           (caution/ranges [(version 1 "machine" nil)])))))

(deftest the-legend-pairs-each-end-with-the-right-author
  ;; The inversion guard on the words. `ours` is `#{"ui"}`, so the arithmetic puts
  ;; his end at 1.0 — `us-is-the-ui-label` above is what pins that — and a legend
  ;; that reads them the other way round would make every correct answer a lie to
  ;; the one reader who has nothing else to go by. Pinned as the two pairings rather
  ;; than as the whole string, so rewording the middle clause is free and swapping
  ;; the ends is not.
  (is (str/includes? caution/legend "1.00 saved here by hand"))
  (is (str/includes? caution/legend "0.00 written by an agent"))
  (testing "in the vocabulary the UI's tooltip already uses for the same fact"
    ;; `et.cb.ui.provenance/explanation` says "(ui) saved here by hand, (machine)
    ;; written by an agent" to the same person about the same two authors. That
    ;; namespace is cljs and nothing here can read it, so those two phrases are the
    ;; whole of what holds the two ends together — this reddens when one is reworded
    ;; and the other is left saying it differently.
    (is (= 2 (count (filter #(str/includes? caution/legend %)
                            ["saved here by hand" "written by an agent"])))))
  (testing "and it accounts for the middle, which the two-valued tooltip need not"
    (is (str/includes? caution/legend "in between"))))
