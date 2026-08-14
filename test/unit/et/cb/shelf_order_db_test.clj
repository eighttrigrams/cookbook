(ns et.cb.shelf-order-db-test
  "The two orders the shelf can come back in, at the db layer.

  > i also need a switcher on the main page between the ranked order we have now, and
  > one order which is most recently added first

  `ranking-db-test` owns the ranking itself — the weights, and that they are *his*
  weights — and nothing here re-tests that. What is here is the second order and the
  choosing between them: that `:newest` is by when a Recipe was **added**, that it is
  **total**, that `:ranked` is what a caller gets without asking, and that adding the
  option moved nothing about the default.

  **The distinction this file exists to keep is added vs touched.** The shelf used to be
  ordered most-recently-*touched* first, `modified_at`, which is still the ranking's
  first tiebreaker. Most recently *added* is `created_at`, and the two disagree on
  exactly the case a reader cares about — an old Recipe edited this morning. There is a
  test for that disagreement rather than a docstring alone, because a `modified_at`
  ordering would satisfy every other assertion in this file."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.cb.db :as db]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.db.scope]
            [et.cb.test-helpers :as h]))

(use-fixtures :each h/with-in-memory-db)

(defn- create! [title]
  (:id (db.recipe/create-recipe h/*ds* h/*user-id*
                                {:title title :useful_when "when testing"
                                 :description "body v1"})))

(defn- backdate-created-at!
  "A Recipe written earlier than this test run. Straight at the column, for the reason
  `h/backdate-modified-at!` exists: `created_at` takes `datetime('now')` and no request
  can ask for a different one, so an order *by* it has nothing to order until a test
  can put rows at different moments."
  [id stamp]
  (jdbc/execute-one! (db/get-conn h/*ds*)
    (sql/format {:update :recipes :set {:created_at stamp} :where [:= :id id]})))

(defn- shelf
  ([] (shelf {}))
  ([opts] (mapv :title (db.recipe/list-recipes h/*ds* h/*user-id* opts))))

(deftest newest-is-most-recently-added
  (let [old (create! "Added first")
        mid (create! "Added second")
        new (create! "Added third")]
    (backdate-created-at! old "2020-01-01 00:00:00")
    (backdate-created-at! mid "2021-01-01 00:00:00")
    (backdate-created-at! new "2022-01-01 00:00:00")
    (is (= ["Added third" "Added second" "Added first"]
           (shelf {:order :newest})))
    (testing "and reads do not move it — which is the whole point of there being two
              orders rather than one that tries to be both"
      (dotimes [_ 20] (db.recipe/record-view! h/*ds* old false))
      (is (= ["Added third" "Added second" "Added first"]
             (shelf {:order :newest})))
      (testing "while the ranking does move for exactly that reason"
        (is (= "Added first" (first (shelf {:order :ranked}))))))))

(deftest added-is-not-touched
  ;; **The disagreement the docstrings are careful about.** A `modified_at` ordering
  ;; passes every other test in this file; only this one tells the two apart.
  (let [old (create! "Old but edited this morning")
        new (create! "Added yesterday")]
    (backdate-created-at! old "2020-01-01 00:00:00")
    (backdate-created-at! new "2026-01-01 00:00:00")
    (h/backdate-modified-at! new "2020-06-01 00:00:00")
    ;; and the old one is touched now, which is what `update-recipe` does
    (db.recipe/update-recipe h/*ds* h/*user-id* old {:description "body v2"} nil {})
    (testing "most recently **added** puts the newer creation first, however long ago
              it was last saved"
      (is (= ["Added yesterday" "Old but edited this morning"]
             (shelf {:order :newest}))))
    (testing "and most recently **touched** — the ranking's first tiebreaker — is the
              other way round on the same two rows"
      (let [rows (db.recipe/list-recipes h/*ds* h/*user-id*)]
        (is (= "Old but edited this morning" (:title (first rows)))
            "the edited one is first by the ranking, since its extra version outscores
             a tie of reads")))))

(deftest the-newest-order-is-total
  ;; `created_at` is `datetime('now')`, second-resolution, so rows written by one
  ;; script share a stamp — which is the normal case for a seed or an agent, not a
  ;; corner. Without the `id` tiebreaker SQLite may return them in any order it likes
  ;; and the shelf shuffles between two identical requests.
  (let [ids (doall (for [n (range 6)] (create! (str "Same second " n))))]
    (doseq [id ids] (backdate-created-at! id "2026-01-01 12:00:00"))
    (let [runs (repeatedly 5 #(shelf {:order :newest}))]
      (is (apply = runs) "five identical requests, five identical orders")
      (testing "and it is insertion order reversed, which is what the id gives"
        (is (= ["Same second 5" "Same second 4" "Same second 3"
                "Same second 2" "Same second 1" "Same second 0"]
               (first runs)))))))

(deftest ranked-is-the-default-and-nothing-else-changed-it
  (let [quiet (create! "Read once")
        loud (create! "Read often")]
    (backdate-created-at! loud "2020-01-01 00:00:00")   ; the oldest, and the most read
    (db.recipe/record-view! h/*ds* quiet false)
    (dotimes [_ 10] (db.recipe/record-view! h/*ds* loud false))
    (testing "no `:order` at all is the ranking"
      (is (= ["Read often" "Read once"] (shelf))))
    (testing "and so is asking for it by name — the same answer, so the default is the
              ranking and not merely *a* ranking"
      (is (= (shelf) (shelf {:order :ranked}))))
    (testing "an unknown order falls back to the ranking rather than to no order at
              all: a nil `:order-by` would leave the shelf in whatever order SQLite
              felt like, which is the untotal ordering both of these avoid"
      (is (= (shelf) (shelf {:order :sideways}))))
    (testing "and `:newest` really is a different answer on this fixture, so the three
              assertions above are about a default and not about an option that does
              nothing"
      (is (= ["Read once" "Read often"] (shelf {:order :newest}))))))

(deftest the-order-composes-with-every-narrowing
  ;; An order is not a narrowing, so the two are orthogonal — and the way that breaks
  ;; is an `:order-by` that replaces the `:where` rather than joining it. Three Recipes,
  ;; two narrowings and both orders.
  (let [bread (:id (et.cb.db.scope/create-scope h/*ds* h/*user-id*
                                                {:title "Bread" :description ""}))]
    (let [a (:id (db.recipe/create-recipe h/*ds* h/*user-id*
                                          {:title "Sourdough oldest" :useful_when ""
                                           :description "b" :scope_ids [bread]}
                                          {:human? true}))
          b (:id (db.recipe/create-recipe h/*ds* h/*user-id*
                                          {:title "Sourdough newest" :useful_when ""
                                           :description "b" :scope_ids [bread]}
                                          {:human? true}))]
      (db.recipe/create-recipe h/*ds* h/*user-id*
                               {:title "Deploying" :useful_when "" :description "b"}
                               {:human? true})
      (backdate-created-at! a "2020-01-01 00:00:00")
      (backdate-created-at! b "2026-01-01 00:00:00")
      ;; **The older one is read, and without that this test proved nothing.** Two
      ;; fresh Recipes tie on the score — same version, no reads — so the ranking
      ;; falls through to its tiebreakers and answers `modified_at`/`id` desc, which
      ;; is the *newest* first: both orders agreed, and the `:ranked` assertion below
      ;; passed for the wrong reason until this line existed. Found by watching it
      ;; fail, which is the only way that kind of vacuity announces itself.
      (dotimes [_ 5] (db.recipe/record-view! h/*ds* a false))
      (testing "the search narrows and the order orders, in both orders"
        (is (= ["Sourdough newest" "Sourdough oldest"]
               (shelf {:search-term "sourdough" :order :newest})))
        (is (= ["Sourdough oldest" "Sourdough newest"]
               (shelf {:search-term "sourdough" :order :ranked})))
        (is (= 2 (count (shelf {:search-term "sourdough" :order :newest})))
            "and the narrowing still narrows — an order that dropped the clause would
             answer with three"))
      (testing "and so does the Scope filter"
        (is (= ["Sourdough newest" "Sourdough oldest"]
               (shelf {:included-scope-ids [bread] :order :newest})))))))

(deftest a-visitor-may-ask-for-either-order
  ;; Unlike the Scope filters, an order is nobody's private view: the ranking already
  ;; explains the shelf a visitor is looking at, so choosing the other one is theirs to
  ;; ask for too. Asserted because the two parameters sit beside each other in the
  ;; handler and the refusal is easy to over-apply.
  (let [old (create! "Published first")
        new (create! "Published second")
        visitor db.recipe/visitor-audience]
    (db.recipe/publish-recipe h/*ds* h/*user-id* old)
    (db.recipe/publish-recipe h/*ds* h/*user-id* new)
    (backdate-created-at! old "2020-01-01 00:00:00")
    (backdate-created-at! new "2026-01-01 00:00:00")
    (dotimes [_ 5] (db.recipe/record-view! h/*ds* old false))
    (is (= ["Published first" "Published second"]
           (mapv :title (db.recipe/list-recipes h/*ds* visitor)))
        "their default is the ranking, and the much-read one leads it")
    (is (= ["Published second" "Published first"]
           (mapv :title (db.recipe/list-recipes h/*ds* visitor {:order :newest})))
        "and their `:newest` is honoured — an order is not a Scope")))
