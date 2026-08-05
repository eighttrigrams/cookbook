(ns et.cb.scope-privacy-test
  "Who is sent a Recipe's Scopes, at the db layer: the owner, a machine acting for
  him, and **nobody else, published or not**.

  > to logged in users only, no matter what

  The *no matter what* is the part with teeth, and it is what these tests are for:
  publishing is not an exception. A published Recipe is readable by anyone, and how
  the owner filed it is still his — the latch decides who may read the Recipe and
  this decides who may see the shelf it sits on. Those two questions come apart
  here and in exactly one other place, the tags.

  **Absent, not empty.** The assertion throughout is `contains?` and not `=
  []`, because an empty vector is still an answer — it would tell a visitor 'this
  Recipe is filed under nothing', which is a claim about the owner's filing that
  the owner did not make. The mechanism is that the join is **not run**, so there
  is no key rather than a key somebody remembered to blank; `db.recipe/with-scopes`
  is the one place that decides it and it decides from the audience, which is a
  value no caller can forge into meaning 'a visitor'.

  A `=`-based test would pass against a `dissoc` afterwards and against a `scopes:
  []`, which are the two implementations this shape exists to rule out."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.db.scope :as db.scope]
            [et.cb.test-helpers :as h]))

(use-fixtures :each h/with-in-memory-db)

(defn- setup!
  "A published Recipe filed under one Scope. Published, because that is the case
  the owner's *no matter what* rules out."
  []
  (let [{bread :id} (db.scope/create-scope h/*ds* h/*user-id* {:title "Bread"
                                                              :description "Loaves"})
        {:keys [id]} (db.recipe/create-recipe h/*ds* h/*user-id*
                                              {:title "Sourdough starter"
                                               :useful_when "when baking"
                                               :description "body v1"
                                               :scope_ids [bread]})]
    (db.recipe/publish-recipe h/*ds* h/*user-id* id)
    id))

(deftest a-visitor-is-sent-no-scopes-key-at-all
  (let [id (setup!)
        visitor db.recipe/visitor-audience]
    (testing "not on the listing"
      (is (false? (contains? (first (db.recipe/list-recipes h/*ds* visitor)) :scopes))))
    (testing "not on the single read, and not when it asks for everything —
              ?detail=full is about verbosity and this is not"
      (is (false? (contains? (db.recipe/get-recipe h/*ds* visitor id) :scopes)))
      (is (false? (contains? (db.recipe/get-recipe h/*ds* visitor id {:lean? false})
                             :scopes))))
    (testing "and not when the caller asks for them outright: `scopes?` is a
              request and the audience is the answer"
      (is (false? (contains? (db.recipe/get-recipe h/*ds* visitor id {:scopes? true})
                             :scopes)))
      (is (false? (contains? (db.recipe/get-recipe h/*ds* visitor id {:lean? false
                                                                     :scopes? true})
                             :scopes))))
    (testing "while the Recipe itself is fully readable to them — this is a
              boundary around the filing, not around the Recipe"
      (is (= "Sourdough starter" (:title (db.recipe/get-recipe h/*ds* visitor id))))
      (is (= "body v1" (:description (db.recipe/get-recipe h/*ds* visitor id
                                                           {:lean? false})))))))

(deftest the-owner-is-sent-them-on-the-listing
  (let [id (setup!)
        row (first (db.recipe/list-recipes h/*ds* h/*user-id*))]
    (testing "with title and description, since a badge needs the first and its
              tooltip the second"
      (is (= [{:id (:id (first (db.scope/list-scopes h/*ds* h/*user-id*)))
               :title "Bread"
               :description "Loaves"}]
             (:scopes row))))
    (testing "and an unfiled Recipe of his carries the key, empty — for a caller
              who may see the filing, 'filed under nothing' is a true and useful
              answer, which is exactly what it is not for a visitor"
      (db.recipe/create-recipe h/*ds* h/*user-id* {:title "Unfiled"})
      (let [unfiled (first (filter #(= "Unfiled" (:title %))
                                   (db.recipe/list-recipes h/*ds* h/*user-id*)))]
        (is (contains? unfiled :scopes))
        (is (= [] (:scopes unfiled)))))
    (testing "a single read gives them only when asked, because it is a second
              statement and the guards and write paths that call it have no use
              for the filing"
      (is (false? (contains? (db.recipe/get-recipe h/*ds* h/*user-id* id) :scopes)))
      (is (= ["Bread"] (mapv :title (:scopes (db.recipe/get-recipe h/*ds* h/*user-id* id
                                                                  {:scopes? true}))))))))

(deftest one-owners-scopes-never-reach-anothers-recipe
  ;; Not a leak the join could produce today — `set-recipe-scopes!` drops ids the
  ;; caller does not own, so the row cannot exist. Written straight into the table
  ;; instead, so the *read* is what is under test: if a row like this ever gets in
  ;; by another route, the reader still narrows on the Scope's owner.
  (let [{bread :id} (db.scope/create-scope h/*ds* h/*user-id* {:title "Bread"})
        stranger (inc h/*user-id*)
        {theirs :id} (db.recipe/create-recipe h/*ds* stranger {:title "Theirs"})]
    (h/insert-scope-row! theirs bread)
    (testing "the stranger's own listing does not show them a Scope of the
              owner's, because the join narrows on `scopes.user_id`"
      (is (= [] (:scopes (first (db.recipe/list-recipes h/*ds* stranger))))))
    (testing "and the owner's shelf is unaffected either way"
      (is (empty? (db.recipe/list-recipes h/*ds* h/*user-id*))))))
