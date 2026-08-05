(ns et.cb.tags-db-test
  "Tags at the db layer: what a tags-only save does and does not do, and which
  callers the projection carries them to.

  The two halves are separate questions on purpose. *Not versioned* is about the
  history — a tag change writes no version, so `update-recipe` has a third branch
  between its no-op and its archive-and-bump. *Not shown to a visitor* is about
  the projection — `select-columns` names the column only for an owner, so the key
  is absent rather than empty, and no dissoc anywhere could be forgotten.

  What is deliberately **not** here: that a visitor's *search* still matches tags.
  That is the owner's decision and it is pinned in `et.cb.search-test` beside the
  rest of the search semantics, and over HTTP in the integration namespace."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.test-helpers :as h]))

(use-fixtures :each h/with-in-memory-db)

(defn- create!
  ([title] (create! title nil))
  ([title tags]
   (db.recipe/create-recipe h/*ds* h/*user-id*
                            (cond-> {:title title :useful_when "when testing"
                                     :description "body v1"}
                              tags (assoc :tags tags)))))

(defn- row [id] (db.recipe/get-recipe h/*ds* h/*user-id* id {:lean? false}))

(defn- save! [id fields] (db.recipe/update-recipe h/*ds* h/*user-id* id fields nil))

;; ---------------------------------------------------------------------------
;; the column itself

(deftest tags-default-to-empty-and-are-written-on-create
  (testing "a create that says nothing about tags leaves the column at its
            default, so every Recipe reads as untagged rather than as null"
    (is (= "" (:tags (create! "Untagged")))))
  (testing "and one that does carries them straight through"
    (is (= "bread baking" (:tags (create! "Sourdough" "bread baking"))))))

(deftest an-omitted-tags-key-keeps-what-is-there
  (let [{:keys [id]} (create! "Sourdough" "bread baking")]
    (testing "an edit meant for the body cannot silently clear the filing"
      (is (= "bread baking" (:tags (save! id {:description "body v2"})))))
    (testing "while an empty string is a real value and does clear it"
      (is (= "" (:tags (save! id {:tags ""})))))))

;; ---------------------------------------------------------------------------
;; a tags-only save: the third branch

(deftest a-tags-only-save-writes-no-version
  (let [{:keys [id]} (create! "Sourdough")
        before (db.recipe/list-versions h/*ds* h/*user-id* id)
        saved (save! id {:tags "bread baking"})]
    (testing "the tags are persisted"
      (is (= "bread baking" (:tags saved)))
      (is (= "bread baking" (:tags (row id)))))
    (testing "the version does not move"
      (is (= 1 (:version saved)))
      (is (= 1 (:version (row id)))))
    (testing "and no history row is written — the ladder is exactly as it was"
      (is (= 0 (h/history-row-count id)))
      (is (= (:total before) (:total (db.recipe/list-versions h/*ds* h/*user-id* id))))
      (is (= 1 (:total (db.recipe/list-versions h/*ds* h/*user-id* id)))))
    (testing "changing them again is still no version"
      (save! id {:tags "bread baking starter"})
      (is (= 1 (:version (row id))))
      (is (= 0 (h/history-row-count id))))
    (testing "no version carries tags either, the current one included: there is
              no answer to what its tags were at v1"
      (is (every? #(false? (contains? % :tags))
                  (:versions (db.recipe/list-versions h/*ds* h/*user-id* id)))))))

(deftest a-tags-only-save-does-move-modified-at
  ;; The decision this branch had to make. `datetime('now')` is
  ;; second-resolution, so the stamp has to be backdated first or a save in the
  ;; same second would look untouched either way.
  (let [{:keys [id]} (create! "Sourdough")]
    (h/backdate-modified-at! id "2020-01-01 00:00:00")
    (let [saved (save! id {:tags "bread"})]
      (is (not= "2020-01-01 00:00:00" (:modified_at saved)))
      (is (= (:modified_at saved) (:modified_at (row id)))))
    (testing "so the optimistic-concurrency guard covers a tag write like any
              other: a client holding the pre-tag stamp is refused rather than
              quietly carrying the old tags back over the new ones"
      (h/backdate-modified-at! id "2020-01-01 00:00:00")
      (let [stale "2020-01-01 00:00:00"]
        (save! id {:tags "bread baking"})
        (is (nil? (db.recipe/update-recipe h/*ds* h/*user-id* id
                                           {:tags "something else"} stale)))
        (is (= "bread baking" (:tags (row id))))))))

(deftest a-tags-only-save-leaves-the-provenance-alone
  ;; A machine wrote v1, so both marks have a value that a careless tags branch
  ;; would overwrite: the bit would flip to 1 and the label to "ui".
  (let [{:keys [id]} (db.recipe/create-recipe h/*ds* h/*user-id*
                                              {:title "Written by an agent"}
                                              {:human? false})]
    (is (= 0 (:has_human_edit (row id))))
    (is (= "machine" (:source (row id))))
    (db.recipe/update-recipe h/*ds* h/*user-id* id {:tags "filed by the owner"} nil
                             {:human? true})
    (testing "filing a Recipe under a word is not writing it — the same call
              `publish-recipe` makes"
      (is (= 0 (:has_human_edit (row id)))))
    (testing "and there is no new version for a label to be about, so the one
              that is there keeps saying who wrote it"
      (is (= "machine" (:source (row id))))
      (is (= 1 (:version (row id)))))
    (testing "while an edit to the content in the same breath does earn both"
      (db.recipe/update-recipe h/*ds* h/*user-id* id
                               {:description "rewritten by hand" :tags "filed again"} nil
                               {:human? true})
      (is (= 1 (:has_human_edit (row id))))
      (is (= "ui" (:source (row id))))
      (is (= 2 (:version (row id))))
      (is (= "filed again" (:tags (row id)))))))

(deftest a-save-that-changes-neither-is-still-a-no-op
  (let [{:keys [id]} (create! "Sourdough" "bread")]
    (h/backdate-modified-at! id "2020-01-01 00:00:00")
    (let [saved (save! id {:description "body v1" :tags "bread"})]
      (testing "identical content and identical tags return before every write —
                including the stamp, which is what tells the two branches apart"
        (is (= 1 (:version saved)))
        (is (= 0 (h/history-row-count id)))
        (is (= "2020-01-01 00:00:00" (:modified_at saved)))
        (is (= "2020-01-01 00:00:00" (:modified_at (row id))))))))

(deftest content-and-tags-in-one-save-make-one-version
  (let [{:keys [id]} (create! "Sourdough" "bread")
        saved (save! id {:description "body v2" :tags "bread baking"})]
    (is (= 2 (:version saved)))
    (is (= "bread baking" (:tags saved)))
    (is (= 1 (h/history-row-count id)))
    (testing "the archived version is the outgoing *content*, and it has no tags
              on it to be right or wrong about"
      (let [v1 (second (:versions (db.recipe/list-versions h/*ds* h/*user-id* id)))]
        (is (= "body v1" (:description v1)))
        (is (false? (contains? v1 :tags)))))))

;; ---------------------------------------------------------------------------
;; the projection

(deftest a-visitor-is-served-no-tags-key-at-all
  (let [{:keys [id]} (create! "Signed" "the owner's own filing")]
    (db.recipe/publish-recipe h/*ds* h/*user-id* id)
    (testing "the owner gets the key, lean and full alike"
      (is (= "the owner's own filing" (:tags (db.recipe/get-recipe h/*ds* h/*user-id* id))))
      (is (= "the owner's own filing"
             (:tags (db.recipe/get-recipe h/*ds* h/*user-id* id {:lean? false}))))
      (is (= "the owner's own filing" (:tags (first (db.recipe/list-recipes h/*ds* h/*user-id*))))))
    (testing "a visitor gets no key — absent, not empty: an empty string would
              tell them this Recipe is untagged, which is itself the owner's
              business"
      (doseq [recipe [(db.recipe/get-recipe h/*ds* db.recipe/visitor-scope id)
                      (db.recipe/get-recipe h/*ds* db.recipe/visitor-scope id {:lean? false})
                      (first (db.recipe/list-recipes h/*ds* db.recipe/visitor-scope))
                      (first (db.recipe/list-recipes h/*ds* db.recipe/visitor-scope
                                                     {:lean? false}))]]
        (is (some? recipe) "the published recipe is visible to a visitor")
        (is (false? (contains? recipe :tags)))
        (is (some? (:title recipe)) "and the rest of it is served as before")))
    (testing "?detail=full is about verbosity and not about the boundary: it
              gives a visitor the description and still no tags"
      (let [full (db.recipe/get-recipe h/*ds* db.recipe/visitor-scope id {:lean? false})]
        (is (= "body v1" (:description full)))
        (is (false? (contains? full :tags)))))
    (testing "and the nil owner is an owner, not a visitor — the marker is what
              distinguishes them, never a missing user-id"
      (let [{other :id} (db.recipe/create-recipe h/*ds* nil {:title "Nobody's" :tags "still shown"})]
        (is (= "still shown" (:tags (db.recipe/get-recipe h/*ds* nil other))))))))
