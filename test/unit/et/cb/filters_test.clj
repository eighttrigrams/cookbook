(ns et.cb.filters-test
  "The shelf's badge gestures, over every state its two filters can be in.

  Tracker's `et.tr.filters-test` is the model, and the shape is deliberately the
  same: named modifier maps, a `clean-slate` gate, an `all-gates` sequence, and two
  properties asserted over *all* of it rather than over the cases somebody thought
  of. What differs is the size of the matrix — two gestures and two flags here
  against three and three there — and one property that has no analogue over
  there, at the bottom.

  **Why the matrix is worth a test at all now.** Cookbook had one gesture and one
  predicate on `shiftKey`, and a test of that would have been a test of `if`. It has
  a gate since he asked for the positive filter — *ah ok yeah. but when no negative
  filter is selecgted, allow to select positively* — and a gate is a thing that can
  be wrong in ways nobody notices: the states that matter are the ones a reader is
  least likely to be in, and the failure is a click that silently does nothing."
  (:require [clojure.test :refer [deftest testing is]]
            [et.cb.filters :as filters]))

(def ^:private plain {})
(def ^:private shift {:shift? true})

(def ^:private clean-slate
  {:negative-active? false :positive-active? false})

;; All four combinations. None of them is unreachable — unlike tracker's, where two
;; of eight cannot happen — because cookbook's two flags are independent facts about
;; two independent sets, and **both being true at once is reachable**: the gate keeps
;; a *badge* from starting the second filter, and the chip row below the search is a
;; second control that the same rule has to cover. See `views/recipes`.
(def ^:private all-gates
  (for [negative-active? [false true]
        positive-active? [false true]]
    {:negative-active? negative-active? :positive-active? positive-active?}))

(deftest badge-gesture-test
  (testing "clean slate: both gestures are open"
    (is (= :toggle (filters/badge-gesture plain clean-slate)))
    (is (= :exclude (filters/badge-gesture shift clean-slate))))

  (testing "**his rule.** With an exclusion up the plain click is refused — and
            refused, not folded into the shift path, because a fall-through there
            would hide the Scope a plain click asked to select"
    (let [gate (assoc clean-slate :negative-active? true)]
      (is (nil? (filters/badge-gesture plain gate)))
      (is (= :exclude (filters/badge-gesture shift gate)))))

  (testing "and the half he did not state: with a positive selection up, shift
            stops excluding, so the first step into the other filter is closed
            from both sides rather than only from his"
    (let [gate (assoc clean-slate :positive-active? true)]
      (is (nil? (filters/badge-gesture shift gate)))
      (is (= :toggle (filters/badge-gesture plain gate))
          "while the plain click stays open — cookbook's positive filter is a set,
           so adding a second Scope is the point and not a slot being taken")))

  (testing "with both somehow up, the negative one wins the plain click and keeps
            the shift one — which is the state a reader gets out of, and the
            gestures that stay open are the ones that lead out of it"
    (let [gate {:negative-active? true :positive-active? true}]
      (is (nil? (filters/badge-gesture plain gate)))
      (is (= :exclude (filters/badge-gesture shift gate)))))

  (testing "an exclusion keeps shift open, so a second one can always be added —
            the gate closes the first step into a filter, never the way out of one"
    (is (= :exclude (filters/badge-gesture shift (assoc clean-slate
                                                        :negative-active? true)))))

  ;; What makes the unconditional pointer cursor honest: there is no gate state in
  ;; which a badge has nothing at all to offer. Narrow either branch and this is
  ;; what says the cursor now promises too much.
  (testing "every gate state leaves at least one gesture open"
    (doseq [gate all-gates]
      (is (some #(filters/badge-gesture % gate) [shift plain])
          (str "no gesture open for " gate)))))

(deftest badge-consumes-click?-test
  (testing "a click a gesture runs on stays on the badge"
    (is (true? (filters/badge-consumes-click? plain clean-slate)))
    (is (true? (filters/badge-consumes-click? shift clean-slate))))

  (testing "a shift-click refused because a positive selection is up stays on the
            badge too — a refusal that reached the card header would answer a
            filter gesture by expanding a card"
    (let [gate (assoc clean-slate :positive-active? true)]
      (is (nil? (filters/badge-gesture shift gate)))
      (is (true? (filters/badge-consumes-click? shift gate)))))

  (testing "and so does a plain click refused because an exclusion is up. **This
            is the consequence to know about**: in that state a plain badge click
            does nothing and the card does not expand, which is why the tooltip
            says which state the badge is in"
    (let [gate (assoc clean-slate :negative-active? true)]
      (is (nil? (filters/badge-gesture plain gate)))
      (is (true? (filters/badge-consumes-click? plain gate)))))

  (testing "over every gate state the badge keeps every click, because some gesture
            is always open — the property that would break first if a third state
            were ever added"
    (doseq [gate all-gates
            modifiers [plain shift]]
      (is (true? (filters/badge-consumes-click? modifiers gate))
          (str "gate " gate " modifiers " modifiers)))))

(deftest the-two-filters-are-never-both-startable
  ;; **The invariant the gate exists for, asserted as an invariant rather than as
  ;; four cases.** From a clean slate either gesture may start a filter; from a
  ;; state where one is running, no gesture may start the *other*. That is what
  ;; keeps `empty-message`'s sentences sayable and what makes his rule symmetrical.
  ;;
  ;; It is a property of the matrix and not of the app: the chip row below the
  ;; search is a second way in, and it is refused by reading the same gate. A
  ;; version of this that only tested the badge would go green on a UI that let the
  ;; chips do what the badges may not.
  (doseq [gate all-gates]
    (let [starts-positive? (= :toggle (filters/badge-gesture plain gate))
          starts-negative? (= :exclude (filters/badge-gesture shift gate))]
      (when (:negative-active? gate)
        (is (false? starts-positive?)
            (str "a badge may not start the positive filter from " gate)))
      (when (and (:positive-active? gate) (not (:negative-active? gate)))
        (is (false? starts-negative?)
            (str "a badge may not start the negative filter from " gate))))))

;; ---------------------------------------------------------------------------
;; the word rule, which is here for the reason the gestures are
;;
;; `?search=` is a SQL clause and the Scopes page's compose filter is a predicate
;; over a list already in hand, and they have to be the same rule. The clause's own
;; behaviour is pinned against SQLite in `et.cb.search-test`; what is pinned here is
;; the predicate, case for case against the same examples, so that a divergence
;; shows up as a failure in one of the two files rather than as a filter that
;; quietly disagrees with the search box above it.

(deftest a-term-is-a-prefix-of-a-word-and-not-a-substring
  (is (true? (filters/word-prefix-match? "cd" "abc cde")))
  (is (false? (filters/word-prefix-match? "cd" "abcd")))
  (testing "of any word, not only the first — the case the leading space buys"
    (is (true? (filters/word-prefix-match? "star" "Sourdough starter")))
    (is (true? (filters/word-prefix-match? "sour" "Sourdough starter"))))
  (testing "and a whole word still matches, while one character past it does not"
    (is (true? (filters/word-prefix-match? "abc" "abc cde")))
    (is (false? (filters/word-prefix-match? "abcx" "abc cde")))))

(deftest punctuation-ends-a-word-here-too
  (testing "the two the order named, and the one the Scopes page is full of"
    (is (true? (filters/word-prefix-match? "heating" "Re-heating pizza")))
    (is (true? (filters/word-prefix-match? "start" "make/start")))
    (is (true? (filters/word-prefix-match? "coordinator" "claude-coordinator"))))
  (testing "a term may carry a separator, and then it has to be that one"
    (is (true? (filters/word-prefix-match? "re-heat" "Re-heating pizza")))
    (is (false? (filters/word-prefix-match? "re/heat" "Re-heating pizza"))))
  (testing "while non-ASCII is a word character, so `se` does not find `Käse`"
    (is (false? (filters/word-prefix-match? "se" "Käse")))
    (is (true? (filters/word-prefix-match? "kä" "Käse")))))

(deftest wildcards-are-ordinary-characters
  ;; Neither side ever builds a pattern — the clause goes through `instr` and this
  ;; goes through `includes?` — so this is the same fact asserted twice.
  (is (true? (filters/word-prefix-match? "%" "100 % hydration")))
  (is (false? (filters/word-prefix-match? "%" "Plain title")))
  (is (true? (filters/word-prefix-match? "a_b" "a_b")))
  (is (false? (filters/word-prefix-match? "a_b" "axb"))))

(deftest terms-are-anded-and-values-are-ored
  (let [scope ["claude-coordinator" "agentic backend"]]
    (testing "each term may land in either value"
      (is (true? (filters/matches-word-prefix-search? "cla back" scope)))
      (is (true? (filters/matches-word-prefix-search? "back cla" scope)))
      (is (true? (filters/matches-word-prefix-search? "coordinator agentic" scope))))
    (testing "one term matching nothing fails the row, however well the others match"
      (is (false? (filters/matches-word-prefix-search? "cla zzz" scope)))))
  (testing "a blank search matches everything, the way the clause is nil for one"
    (is (true? (filters/matches-word-prefix-search? "" ["anything"])))
    (is (true? (filters/matches-word-prefix-search? "   \t " ["anything"])))
    (is (true? (filters/matches-word-prefix-search? nil ["anything"]))))
  (testing "and a nil value is a value with no words rather than an exception —
            a Scope may have no tags"
    (is (false? (filters/matches-word-prefix-search? "any" ["Title" nil])))
    (is (true? (filters/matches-word-prefix-search? "tit" ["Title" nil])))))

(deftest the-fold-is-case-insensitive-both-directions
  (is (true? (filters/word-prefix-match? "ab" "ABC CDE")))
  (is (true? (filters/matches-word-prefix-search? "AB CD" ["abc cde"])))
  (testing "and it is the host's Unicode fold, unlike SQLite's ASCII `lower()` —
            the one place the two evaluators of this rule differ, stated where a
            reader of either would meet it"
    (is (true? (filters/word-prefix-match? "kä" "KÄSE")))))

(deftest matching-scopes-narrows-a-list-of-scopes
  ;; The two surfaces that ask this ask it for opposite reasons — the Scopes page
  ;; while a name is being typed, a picker to find one chip among forty — so it is
  ;; one function and this is where it is pinned.
  (let [scopes [{:id 1 :title "claude-code" :tags "anthropic cli"}
                {:id 2 :title "claude-coordinator" :tags "orchestration"}
                {:id 3 :title "macos" :tags "apple laptop"}
                {:id 4 :title "taxes" :tags ""}]
        titles #(mapv :title (filters/matching-scopes scopes %))]
    (testing "a prefix of any word in the title, across the hyphen"
      (is (= ["claude-code" "claude-coordinator"] (titles "clau")))
      (is (= ["claude-coordinator"] (titles "coord")))
      (is (= ["claude-code"] (titles "code"))))
    (testing "**and a word that is only in the tags**, which is the half a
              title-only filter would lose: the Scope claiming that word is exactly
              the one you are looking for"
      (is (= ["macos"] (titles "apple")))
      (is (= ["claude-code"] (titles "cli"))))
    (testing "two terms, ANDed, each free to land in either field"
      (is (= ["claude-code"] (titles "clau cli")))
      (is (empty? (titles "clau apple"))))
    (testing "a blank filter leaves the list whole, which is what makes it a filter
              and not a mode"
      (is (= 4 (count (filters/matching-scopes scopes ""))))
      (is (= 4 (count (filters/matching-scopes scopes nil)))))
    (testing "a Scope with no tags is matched on its title alone rather than skipped"
      (is (= ["taxes"] (titles "tax"))))
    (testing "and nothing matching is empty rather than everything"
      (is (empty? (titles "zzz"))))))
