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
