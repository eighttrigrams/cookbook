(ns et.cb.recipe-db-test
  "The version ladder and the lean projection, at the db layer."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.test-helpers :as h]))

(use-fixtures :each h/with-in-memory-db)

(defn- create! [title]
  (db.recipe/create-recipe h/*ds* h/*user-id*
                           {:title title :useful_when "when testing" :description "body v1"}))

(defn- versions-of [id]
  (db.recipe/list-versions h/*ds* h/*user-id* id))

(deftest version-ladder
  (let [{:keys [id]} (create! "Sourdough")]
    (testing "a new recipe is version 1 with no history"
      (is (= 1 (:version (db.recipe/get-recipe h/*ds* h/*user-id* id))))
      (is (= 1 (:total (versions-of id))))
      (is (= [1] (map :version (:versions (versions-of id))))))

    (testing "an edit makes v2, and history holds v1 with the *old* content"
      (let [saved (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "body v2"} nil)
            {:keys [versions total]} (versions-of id)]
        (is (= 2 (:version saved)))
        (is (= "body v2" (:description saved)))
        (is (= 2 total))
        (is (= [2 1] (map :version versions)))
        (is (= "body v1" (:description (second versions))))
        (is (true? (:current (first versions))))
        (is (nil? (:current (second versions))))))

    (testing "editing again makes v3"
      (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "body v3"} nil)
      (is (= 3 (:version (db.recipe/get-recipe h/*ds* h/*user-id* id))))
      (is (= [3 2 1] (map :version (:versions (versions-of id))))))

    (testing "a save that changes nothing is a no-op — no bump, no history row"
      (let [before (versions-of id)
            saved (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "body v3"} nil)]
        (is (= 3 (:version saved)))
        (is (= 3 (:total (versions-of id))))
        (is (= (map :version (:versions before))
               (map :version (:versions (versions-of id)))))))

    (testing "the newest version carries the current row's content"
      (let [newest (first (:versions (versions-of id)))]
        (is (= 3 (:version newest)))
        (is (= "body v3" (:description newest)))
        (is (= "Sourdough" (:title newest)))))))

(deftest an-omitted-field-keeps-its-value
  (let [{:keys [id]} (create! "Focaccia")
        saved (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "new body"} nil)]
    (is (= "Focaccia" (:title saved)))
    (is (= "when testing" (:useful_when saved)))
    (is (= "new body" (:description saved)))))

(deftest lean-projection-omits-the-key-entirely
  (let [{:keys [id]} (create! "Brioche")]
    (testing "a lean get has no :description key at all — not a nil one"
      (let [lean (db.recipe/get-recipe h/*ds* h/*user-id* id)]
        (is (false? (contains? lean :description)))
        (is (contains? lean :title))
        (is (contains? lean :useful_when))
        (is (contains? lean :version))))
    (testing "a lean listing likewise"
      (is (every? #(false? (contains? % :description))
                  (db.recipe/list-recipes h/*ds* h/*user-id*))))
    (testing "the full projection carries it"
      (is (= "body v1" (:description (db.recipe/get-recipe h/*ds* h/*user-id* id {:lean? false}))))
      (is (every? #(contains? % :description)
                  (db.recipe/list-recipes h/*ds* h/*user-id* {:lean? false}))))))

(deftest optimistic-concurrency
  (let [{:keys [id]} (create! "Baguette")
        current (db.recipe/get-recipe h/*ds* h/*user-id* id)]
    (testing "a stale modified_at refuses the save"
      (is (nil? (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "x"}
                                         "1999-01-01 00:00:00")))
      (is (= 1 (:version (db.recipe/get-recipe h/*ds* h/*user-id* id))))
      (is (= "body v1" (:description (db.recipe/get-recipe h/*ds* h/*user-id* id {:lean? false})))))
    (testing "the matching one goes through"
      (is (= 2 (:version (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "x"}
                                                  (:modified_at current))))))))

(deftest publishing-is-not-part-of-the-content
  (testing "a new recipe is private, and version/history know nothing about it"
    (let [{:keys [id published published_at]} (create! "Pretzel")]
      (is (= 0 published))
      (is (nil? published_at))
      (is (every? #(false? (contains? % :published))
                  (:versions (versions-of id)))))))

(deftest the-publish-latch
  (let [{:keys [id]} (create! "Pretzel")]
    (testing "publishing sets the latch and stamps when it happened"
      (let [published (db.recipe/publish-recipe h/*ds* h/*user-id* id)]
        (is (= 1 (:published published)))
        (is (some? (:published_at published)))
        (is (= 1 (:published (db.recipe/get-recipe h/*ds* h/*user-id* id))))))

    (testing "it is not a content change — no version bump, no history row"
      (is (= 1 (:version (db.recipe/get-recipe h/*ds* h/*user-id* id))))
      (is (= 0 (h/history-row-count id)))
      (is (= 1 (:total (versions-of id))))
      (is (every? #(false? (contains? % :published)) (:versions (versions-of id)))))

    (h/backdate-published-at! id "2020-01-01 00:00:00")

    (testing "publishing again is a no-op — the first publish is the fact
              recorded, so the stamp does not move"
      (let [again (db.recipe/publish-recipe h/*ds* h/*user-id* id)]
        (is (= 1 (:published again)))
        (is (= "2020-01-01 00:00:00" (:published_at again)))
        (is (= "2020-01-01 00:00:00"
               (:published_at (db.recipe/get-recipe h/*ds* h/*user-id* id))))
        (is (= 1 (:version again)))
        (is (= 0 (h/history-row-count id)))))

    (testing "an edit afterwards moves the version and leaves the latch alone"
      (let [saved (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "body v2"} nil)]
        (is (= 2 (:version saved)))
        (is (= 1 (:published saved)))
        (is (= "2020-01-01 00:00:00" (:published_at saved)))))

    (testing "the latch is the owner's to set"
      (let [{stranger-recipe :id} (create! "Somebody else's")]
        (is (nil? (db.recipe/publish-recipe h/*ds* (inc h/*user-id*) stranger-recipe)))
        (is (= 0 (:published (db.recipe/get-recipe h/*ds* h/*user-id* stranger-recipe))))))))

(deftest a-visitor-sees-published-recipes-only
  (let [{drafted :id} (create! "Draft")
        {signed :id} (create! "Signed")]
    (db.recipe/publish-recipe h/*ds* h/*user-id* signed)
    (testing "the draft is outside the visitor's listing, not redacted in it"
      (let [ids (set (map :id (db.recipe/list-recipes h/*ds* db.recipe/visitor-scope)))]
        (is (contains? ids signed))
        (is (false? (contains? ids drafted)))))
    (testing "and outside a get"
      (is (nil? (db.recipe/get-recipe h/*ds* db.recipe/visitor-scope drafted)))
      (is (nil? (db.recipe/get-recipe h/*ds* db.recipe/visitor-scope drafted {:lean? false})))
      (is (some? (db.recipe/get-recipe h/*ds* db.recipe/visitor-scope signed))))
    (testing "a visitor is lean by default and gets the body on request"
      (is (false? (contains? (db.recipe/get-recipe h/*ds* db.recipe/visitor-scope signed)
                             :description)))
      (is (= "body v1" (:description (db.recipe/get-recipe h/*ds* db.recipe/visitor-scope signed
                                                           {:lean? false})))))
    (testing "a search cannot widen the scope"
      (is (empty? (db.recipe/list-recipes h/*ds* db.recipe/visitor-scope {:search-term "Draft"}))))))

(deftest a-visitor-is-not-the-nil-owner
  (let [drafted (db.recipe/create-recipe h/*ds* nil {:title "Nil-owner draft"})
        signed (db.recipe/create-recipe h/*ds* nil {:title "Nil-owner signed"})]
    (db.recipe/publish-recipe h/*ds* nil (:id signed))
    (testing "a nil user-id selects the nil-owner's rows rather than nothing —
              this is the trap a visitor must not fall into"
      (is (= #{(:id drafted) (:id signed)}
             (set (map :id (db.recipe/list-recipes h/*ds* nil))))))
    (testing "the visitor scope is not that: it keeps the unpublished nil-owner
              row out and lets the published one through"
      (let [ids (set (map :id (db.recipe/list-recipes h/*ds* db.recipe/visitor-scope)))]
        (is (false? (contains? ids (:id drafted))))
        (is (contains? ids (:id signed))))
      (is (nil? (db.recipe/get-recipe h/*ds* db.recipe/visitor-scope (:id drafted))))
      (is (some? (db.recipe/get-recipe h/*ds* db.recipe/visitor-scope (:id signed)))))))

(deftest delete-takes-the-history-with-it
  (let [{:keys [id]} (create! "Ciabatta")]
    (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "v2"} nil)
    (is (= 1 (h/history-row-count id)))
    (is (= {:success true} (db.recipe/delete-recipe h/*ds* h/*user-id* id)))
    (is (nil? (db.recipe/get-recipe h/*ds* h/*user-id* id)))
    (is (nil? (versions-of id)))
    (testing "the history rows are gone rather than orphaned — nothing enforces
              the foreign key on this connection"
      (is (= 0 (h/history-row-count id))))))

(deftest scoped-to-its-owner
  (let [{:keys [id]} (create! "Private")
        stranger (inc h/*user-id*)]
    (is (nil? (db.recipe/get-recipe h/*ds* stranger id)))
    (is (empty? (db.recipe/list-recipes h/*ds* stranger)))
    (is (nil? (db.recipe/delete-recipe h/*ds* stranger id)))
    (is (some? (db.recipe/get-recipe h/*ds* h/*user-id* id)))))
