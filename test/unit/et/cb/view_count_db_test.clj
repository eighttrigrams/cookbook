(ns et.cb.view-count-db-test
  "`record-view!` at the db layer: what it writes, and everything it must leave
  alone.

  The endpoint rule — full read counts, lean read and listing do not — is an HTTP
  fact and lives in `view-count-integration-test`. What is here is the pair of
  properties that make the counter safe to have at all: it moves nothing but the
  count, and the write paths never call it."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.db.scope :as db.scope]
            [et.cb.test-helpers :as h]))

(use-fixtures :each h/with-in-memory-db)

(defn- create! [title]
  (db.recipe/create-recipe h/*ds* h/*user-id*
                           {:title title :useful_when "when testing" :description "body v1"}))

(defn- row [id]
  (db.recipe/get-recipe h/*ds* h/*user-id* id))

(defn- views [id]
  (:view_count (row id)))

(deftest a-new-recipe-starts-at-zero-and-the-counter-counts
  (let [{:keys [id]} (create! "Sourdough")]
    (is (= 0 (:view_count (row id))))
    (db.recipe/record-view! h/*ds* id false)
    (is (= 1 (views id)))
    (db.recipe/record-view! h/*ds* id false)
    (db.recipe/record-view! h/*ds* id false)
    (is (= 3 (views id)))
    (testing "the number rides on the lean projection — the card that shows it is
              a collapsed card, which is a lean row"
      (is (= 3 (:view_count (first (db.recipe/list-recipes h/*ds* h/*user-id*))))))))

(deftest recipe-views-do-not-touch-modified-at
  ;; The assertion this whole test exists for. `update-recipe` sends back the
  ;; `modified_at` its caller last read and 409s on a mismatch, so a read that
  ;; moved the stamp would make opening a card and then saving it fail against
  ;; yourself — and `modified_at` is the shelf's tiebreaker, so every read would
  ;; reshuffle the shelf. A well-meant `touch!` added to `record-view!` later is
  ;; what this is here to catch.
  (let [{:keys [id]} (create! "Baguette")
        before (row id)]
    (h/backdate-modified-at! id "2020-01-01 00:00:00")
    (let [stamped (row id)]
      (db.recipe/record-view! h/*ds* id false)
      (let [after (row id)]
        (is (= 1 (:view_count after)) "the read was counted")
        (is (= "2020-01-01 00:00:00" (:modified_at after))
            "and the stamp is byte-identical to what it was before the read")
        (testing "so a save holding the modified_at from before the read still
                  goes through, instead of 409ing against its own reader"
          (is (some? (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "v2"}
                                              (:modified_at stamped)))))
        (testing "and nothing else about the row moved either"
          (is (= (dissoc before :view_count :modified_at)
                 (dissoc stamped :view_count :modified_at)))
          (is (= 1 (:version stamped))))))))

(deftest the-write-paths-do-not-count-as-reads
  ;; The guard against the counter migrating into `db.recipe/get-recipe`, which
  ;; every write path calls to find out whether a row exists and what its text
  ;; is. Asserted *across* each write rather than only at the end, so it says
  ;; which write did the counting rather than that some write did.
  (let [{:keys [id]} (create! "Ciabatta")
        scope (:id (db.scope/create-scope h/*ds* h/*user-id*
                                          {:title "Bread" :description ""}))]
    (is (= 0 (views id)) "creating one is not reading it")

    (let [before (views id)]
      (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "body v2"} nil)
      (is (= before (views id)) "a save that makes a version does not count"))

    (let [before (views id)]
      (db.recipe/update-recipe h/*ds* h/*user-id* id {:tags "sourdough"} nil)
      (is (= before (views id)) "a tags-only save does not count"))

    (let [before (views id)]
      (db.recipe/update-recipe h/*ds* h/*user-id* id {:scope_ids [scope]} nil)
      (is (= before (views id)) "a refile does not count"))

    (let [before (views id)]
      (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "body v2"} nil)
      (is (= before (views id)) "and neither does a save that changes nothing"))

    (let [before (views id)]
      (db.recipe/publish-recipe h/*ds* h/*user-id* id)
      (is (= before (views id)) "publishing does not count"))

    (let [before (views id)]
      (db.recipe/list-versions h/*ds* h/*user-id* id)
      (is (= before (views id)) "and neither does reading the version history:
                                 a version list is not the Recipe's text as the
                                 shelf serves it"))

    (testing "so after five writes and a history read the Recipe has never been
              consumed, and one real read is the first thing to show up"
      (is (= 0 (views id)))
      (db.recipe/record-view! h/*ds* id false)
      (is (= 1 (views id))))))

(deftest a-read-of-one-recipe-counts-for-that-one-only
  (let [a (:id (create! "Read this one"))
        b (:id (create! "Not this one"))]
    (db.recipe/record-view! h/*ds* a false)
    (db.recipe/record-view! h/*ds* a false)
    (is (= 2 (views a)))
    (is (= 0 (views b)))))

(deftest the-count-survives-a-save-rather-than-being-archived
  ;; A read is a fact about the Recipe, not about the version that was current
  ;; when it happened — so a new version inherits the number rather than starting
  ;; over, and `recipe_history` has no column for it at all.
  (let [{:keys [id]} (create! "Focaccia")]
    (db.recipe/record-view! h/*ds* id false)
    (db.recipe/record-view! h/*ds* id false)
    (let [saved (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "body v2"} nil)]
      (is (= 2 (:version saved)))
      (is (= 2 (:view_count saved)))
      (is (= 2 (views id))))
    (testing "and no version entry claims to know how often it was read"
      (is (every? #(false? (contains? % :view_count))
                  (:versions (db.recipe/list-versions h/*ds* h/*user-id* id)))))))
