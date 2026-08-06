(ns et.cb.ranking-db-test
  "How the shelf is ordered: `0.7 × view_count + 0.3 × version` descending, then
  `modified_at` descending, then `id` descending.

  Two things are worth pinning and only one of them is obvious. The obvious one
  is that consumption outranks recency — that is the change. The other is that
  **these** weights are applied and not merely some weights: a test whose
  expected order comes out the same under 50/50 says nothing about the 70/30 the
  owner asked for, so the shelf below is built so that several plausible
  weightings each produce a different order."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.test-helpers :as h]))

(use-fixtures :each h/with-in-memory-db)

(defn- create! [title]
  (:id (db.recipe/create-recipe h/*ds* h/*user-id*
                                {:title title :useful_when "" :description "v1"})))

(defn- edit-to-version!
  "Save until the Recipe is on version `n`. A new Recipe is already v1, and every
  save has to change something or `update-recipe` treats it as the no-op it is."
  [id n]
  (doseq [v (range 2 (inc n))]
    (db.recipe/update-recipe h/*ds* h/*user-id* id {:description (str "body v" v)} nil)))

(defn- read-times! [id n]
  (dotimes [_ n] (db.recipe/record-view! h/*ds* id)))

(defn- shelf []
  (map :title (db.recipe/list-recipes h/*ds* h/*user-id*)))

(defn- make!
  "A Recipe with `reads` reads and `versions` versions, in that state."
  [title reads versions]
  (let [id (create! title)]
    (edit-to-version! id versions)
    (read-times! id reads)
    id))

(deftest reads-outrank-recency
  ;; The headline, and the smallest case that shows it: same version count, so
  ;; the only thing separating them is that one has been read and the other was
  ;; touched more recently.
  (let [old (create! "Read four times, and old")
        recent (create! "Written since, and never read")]
    (h/backdate-modified-at! old "2020-01-01 00:00:00")
    (h/backdate-modified-at! recent "2026-01-01 00:00:00")
    (testing "before anybody reads it, the newer one leads — the old order,
              which is now the tiebreaker"
      (is (= ["Written since, and never read" "Read four times, and old"] (shelf))))
    (read-times! old 4)
    (testing "four reads put it in front of a Recipe touched six years later"
      (is (= ["Read four times, and old" "Written since, and never read"] (shelf))))))

(deftest edits-count-too-but-less
  (let [read-once (create! "Read once")
        edited (create! "Edited to v4")]
    (edit-to-version! edited 4)
    (read-times! read-once 1)
    (testing "editing is not worth nothing: four versions (1.2) still lead one
              read of a v1 (0.7 + 0.3 = 1.0)"
      (is (= ["Edited to v4" "Read once"] (shelf))))
    (read-times! read-once 1)
    (testing "and a second read (1.4 + 0.3 = 1.7) passes it — which is the shape
              of the whole ranking: it takes four edits to hold off two reads"
      (is (= ["Read once" "Edited to v4"] (shelf))))))

(deftest the-weights-are-the-owners-and-not-just-any-weights
  ;; Four Recipes chosen so that the *ratio* of the two weights decides the
  ;; order, and 0.7/0.3 lands in a band no other plausible pair does.
  ;;
  ;;                   reads  versions   0.7/0.3   0.5/0.5   0.8/0.2
  ;;   more-versions        1         6      2.5       3.5       2.0
  ;;   more-reads           3         1      2.4       2.0       2.6
  ;;   middling             2         3      2.3       2.5       2.2
  ;;   fewest-reads         1         5      2.2       3.0       1.8
  ;;
  ;; Under the owner's weights: more-versions, more-reads, middling,
  ;; fewest-reads. Under 50/50 the order is more-versions, fewest-reads,
  ;; middling, more-reads; under 80/20 it is more-reads, middling,
  ;; more-versions, fewest-reads. So this assertion fails if anybody moves the
  ;; constants in either direction — which is the point of writing it out rather
  ;; than asserting "the most-read one is first".
  (make! "more-versions" 1 6)
  (make! "more-reads" 3 1)
  (make! "middling" 2 3)
  (make! "fewest-reads" 1 5)
  (is (= ["more-versions" "more-reads" "middling" "fewest-reads"] (shelf)))
  (testing "the scores really are that close together — the order above is not
            an accident of some other column"
    (is (= [2.5 2.4 2.3 2.2]
           (->> (db.recipe/list-recipes h/*ds* h/*user-id*)
                (map (fn [{:keys [view_count version]}]
                       (-> (+ (* 0.7 view_count) (* 0.3 version))
                           (* 10) Math/round (/ 10.0)))))))))

(deftest ties-fall-back-to-recency-and-then-to-the-id
  ;; Ties are the normal case, not a corner: every Recipe starts at 0.3 × 1, so a
  ;; fresh shelf is entirely ties. Without the two fallbacks SQLite may hand them
  ;; back in any order it likes and the shelf would shuffle between reloads.
  (let [a (create! "A")
        b (create! "B")
        c (create! "C")]
    (h/backdate-modified-at! a "2026-01-03 00:00:00")
    (h/backdate-modified-at! b "2026-01-02 00:00:00")
    (h/backdate-modified-at! c "2026-01-01 00:00:00")
    (testing "same score, so most recently touched first"
      (is (= ["A" "B" "C"] (shelf))))
    (testing "a read breaks the tie ahead of recency"
      (read-times! c 1)
      (is (= ["C" "A" "B"] (shelf))))
    (testing "and with the stamps equal too — datetime('now') is
              second-resolution, so this is a real case — the id decides, newest
              first, which is at least an order that does not change"
      (read-times! a 1)
      (read-times! b 1)
      (doseq [id [a b c]] (h/backdate-modified-at! id "2026-01-01 00:00:00"))
      (is (= ["C" "B" "A"] (shelf))))))

(deftest the-order-is-the-same-one-for-a-narrowed-shelf
  ;; The search and the human filter are `:where` clauses on the same query, so
  ;; they narrow inside the ranking rather than reordering what is left.
  (let [_ (make! "bread starter" 5 1)
        _ (make! "bread rolls" 0 4)
        _ (make! "risotto" 9 1)]
    (is (= ["risotto" "bread starter" "bread rolls"] (shelf)))
    (is (= ["bread starter" "bread rolls"]
           (map :title (db.recipe/list-recipes h/*ds* h/*user-id* {:search-term "bread"}))))))

(deftest a-full-projection-is-ranked-the-same-way
  ;; `?detail=full` widens the columns and must not touch the order.
  (make! "read more" 3 1)
  (make! "read less" 1 1)
  (is (= ["read more" "read less"]
         (map :title (db.recipe/list-recipes h/*ds* h/*user-id* {:lean? false})))))
