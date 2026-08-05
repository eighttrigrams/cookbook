(ns et.cb.scope-db-test
  "Scopes at the db layer: the entity, the associations, and the two deletes that
  have to clean up after themselves.

  Three questions are kept apart here because they fail independently.

  *The entity* — a title and a description, unique per owner, an omitted field
  keeping its value on a save. Ordinary CRUD, and the only surprise in it is that
  a duplicate title comes back as nil rather than as a SQLite exception.

  *The associations* — absent keeps, present replaces, empty clears, and an id the
  caller does not own is dropped. Plus the thing that makes them filing rather than
  content: changing them writes no version.

  *The deletes* — and this is the half that would pass while being broken.
  `PRAGMA foreign_keys` is 0 on this connection, so nothing cascades and nothing
  refuses an orphan: a `delete-recipe` that forgot the join rows would still make
  the Recipe vanish from every read, and a test that only asked a handler whether
  the Recipe was gone would agree. So both deletes are checked by counting rows in
  `recipe_scopes` afterwards (`h/scope-row-count`), which is the only place the
  difference shows.

  What is deliberately **not** here: that a visitor is sent no `scopes` key. That
  is the projection's job and it is pinned in `et.cb.scope-privacy-test` beside the
  rest of the boundary, and over HTTP in the integration namespace."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.db.scope :as db.scope]
            [et.cb.test-helpers :as h]))

(use-fixtures :each h/with-in-memory-db)

(defn- scope!
  ([title] (scope! title ""))
  ([title description]
   (db.scope/create-scope h/*ds* h/*user-id* {:title title :description description})))

(defn- scopes [] (db.scope/list-scopes h/*ds* h/*user-id*))

(defn- recipe!
  ([title] (recipe! title nil))
  ([title scope-ids]
   (db.recipe/create-recipe h/*ds* h/*user-id*
                            (cond-> {:title title :useful_when "when testing"
                                     :description "body v1"}
                              scope-ids (assoc :scope_ids scope-ids)))))

(defn- save! [id fields] (db.recipe/update-recipe h/*ds* h/*user-id* id fields nil))

(defn- row [id] (db.recipe/get-recipe h/*ds* h/*user-id* id {:lean? false :scopes? true}))

(defn- titles-on [recipe] (mapv :title (:scopes recipe)))

;; ---------------------------------------------------------------------------
;; the entity

(deftest a-scope-is-a-title-and-a-description
  (let [created (scope! "  Bread  " "Anything with flour in it")]
    (testing "the title is trimmed like a Recipe's, since it is what the thing is
              called and not a line of prose"
      (is (= "Bread" (:title created))))
    (is (= "Anything with flour in it" (:description created)))
    (testing "and a Scope with nothing said about its description gets the empty
              string rather than null — undescribed is a true state, not a
              withheld one"
      (is (= "" (:description (scope! "Deployment")))))))

(deftest scopes-are-listed-by-title-with-their-recipe-counts
  (let [{deploy :id} (scope! "Deployment")
        {bread :id} (scope! "Bread")
        _unused (scope! "Zymurgy")]
    (recipe! "Sourdough" [bread])
    (recipe! "Rye" [bread deploy])
    (testing "by title, so the page and a card's badges agree on an order"
      (is (= ["Bread" "Deployment" "Zymurgy"] (mapv :title (scopes)))))
    (testing "each with how many Recipes are filed under it, aggregated in the
              query rather than counted from whatever the client happens to hold"
      (is (= {"Bread" 2 "Deployment" 1 "Zymurgy" 0}
             (into {} (map (juxt :title :recipe_count)) (scopes)))))))

(deftest a-title-is-unique-per-owner
  (scope! "Bread")
  (testing "a second Scope by the same name is refused, as nil rather than as a
            constraint violation surfacing from the driver"
    (is (nil? (scope! "Bread"))))
  (testing "and the trim happens before the comparison, so ` Bread ` is the same
            title and not a near-duplicate nobody can tell apart in a list"
    (is (nil? (scope! " Bread "))))
  (testing "a different name is fine, and the refusal wrote nothing"
    (is (some? (scope! "Deployment")))
    (is (= ["Bread" "Deployment"] (mapv :title (scopes))))))

(deftest an-omitted-field-keeps-its-value-on-a-save
  (let [{:keys [id]} (scope! "Bread" "Anything with flour in it")]
    (testing "an edit meant for the description cannot silently blank the title"
      (let [saved (db.scope/update-scope h/*ds* h/*user-id* id {:description "Loaves"})]
        (is (= "Bread" (:title saved)))
        (is (= "Loaves" (:description saved)))))
    (testing "and the other way round"
      (let [saved (db.scope/update-scope h/*ds* h/*user-id* id {:title "Baking"})]
        (is (= "Baking" (:title saved)))
        (is (= "Loaves" (:description saved)))))
    (testing "an empty description is a real value and does clear it"
      (is (= "" (:description (db.scope/update-scope h/*ds* h/*user-id* id
                                                     {:description ""})))))))

(deftest a-rename-onto-another-title-is-refused-but-onto-its-own-is-not
  (let [{bread :id} (scope! "Bread")
        {deploy :id} (scope! "Deployment")]
    (is (nil? (db.scope/update-scope h/*ds* h/*user-id* deploy {:title "Bread"})))
    (testing "and the refused save left the row alone"
      (is (= "Deployment" (:title (db.scope/get-scope h/*ds* h/*user-id* deploy)))))
    (testing "saving a Scope's own title back is not a clash with itself"
      (is (= "Bread" (:title (db.scope/update-scope h/*ds* h/*user-id* bread
                                                    {:title "Bread"
                                                     :description "Loaves"})))))))

(deftest a-scope-belongs-to-its-owner-alone
  (let [{:keys [id]} (scope! "Bread")
        stranger (inc h/*user-id*)]
    (is (nil? (db.scope/get-scope h/*ds* stranger id)))
    (is (empty? (db.scope/list-scopes h/*ds* stranger)))
    (is (nil? (db.scope/update-scope h/*ds* stranger id {:title "Theirs"})))
    (is (nil? (db.scope/delete-scope h/*ds* stranger id)))
    (testing "and all that refusing changed nothing"
      (is (= "Bread" (:title (db.scope/get-scope h/*ds* h/*user-id* id)))))))

;; ---------------------------------------------------------------------------
;; the associations

(deftest scope-ids-file-a-recipe-on-create
  (let [{bread :id} (scope! "Bread")
        {deploy :id} (scope! "Deployment")]
    (testing "the created Recipe comes back filed, in title order"
      (is (= ["Bread" "Deployment"] (titles-on (recipe! "Rye" [deploy bread])))))
    (testing "and a create that says nothing about Scopes is filed under none —
              the key is there and empty, which for a row that did not exist a
              statement ago is the only thing an absent key could mean"
      (is (= [] (:scopes (recipe! "Unfiled")))))))

(deftest absent-keeps-present-replaces-empty-clears
  (let [{bread :id} (scope! "Bread")
        {deploy :id} (scope! "Deployment")
        {:keys [id]} (recipe! "Sourdough" [bread])]
    (testing "a save meant for the body cannot silently unfile the Recipe"
      (is (= ["Bread"] (titles-on (save! id {:description "body v2"})))))
    (testing "a present array replaces the set rather than adding to it"
      (is (= ["Deployment"] (titles-on (save! id {:scope_ids [deploy]})))))
    (testing "and an empty one clears it, which is why it had to mean something
              rather than reading as 'no opinion'"
      (is (= [] (titles-on (save! id {:scope_ids []})))))
    (is (= 0 (h/scope-row-count id nil)))))

(deftest an-id-the-caller-does-not-own-is-dropped
  (let [{bread :id} (scope! "Bread")
        stranger-scope (:id (db.scope/create-scope h/*ds* (inc h/*user-id*)
                                                  {:title "Theirs"}))
        created (recipe! "Sourdough" [bread stranger-scope 99999])]
    (testing "the Recipe is filed under what the caller owns and nothing else, so
              cross-user filing is impossible by construction rather than by a
              check somebody has to remember — and the returned `:scopes` is the
              receipt for exactly what happened"
      (is (= ["Bread"] (titles-on created)))
      (is (= 1 (h/scope-row-count (:id created) nil))))
    (testing "the same on a save, and an array of nothing but ids the caller does
              not own clears the filing rather than keeping it"
      (is (= [] (titles-on (save! (:id created) {:scope_ids [stranger-scope]})))))))

(deftest changing-the-filing-writes-no-version
  (let [{bread :id} (scope! "Bread")
        {:keys [id]} (recipe! "Sourdough")
        before (db.recipe/list-versions h/*ds* h/*user-id* id)
        saved (save! id {:scope_ids [bread]})]
    (testing "the association is persisted"
      (is (= ["Bread"] (titles-on saved)))
      (is (= 1 (h/scope-row-count id bread))))
    (testing "the version does not move and no history row is written — a Scope is
              a way back to a Recipe, not part of it"
      (is (= 1 (:version saved)))
      (is (= 0 (h/history-row-count id)))
      (is (= (:total before) (:total (db.recipe/list-versions h/*ds* h/*user-id* id)))))
    (testing "and `source` is left alone, since there is no new version for a
              label to be about"
      (is (nil? (:source saved))))))

(deftest a-scopes-only-save-moves-modified-at
  (let [{bread :id} (scope! "Bread")
        {:keys [id]} (recipe! "Sourdough")]
    (h/backdate-modified-at! id "2020-01-01 00:00:00")
    (let [saved (save! id {:scope_ids [bread]})]
      (testing "so the one optimistic-concurrency guard still covers everything a
                save can send: a client holding a pre-filing read is told 409
                rather than carrying its stale associations back over"
        (is (not= "2020-01-01 00:00:00" (:modified_at saved)))))))

(deftest a-save-that-refiles-nothing-is-still-a-no-op
  (let [{bread :id} (scope! "Bread")
        {:keys [id]} (recipe! "Sourdough" [bread])]
    (h/backdate-modified-at! id "2020-01-01 00:00:00")
    (let [saved (save! id {:scope_ids [bread]})]
      (testing "sending the same set of ids back is not a change, so it does not
                earn a touch any more than resaving the same body does"
        (is (= "2020-01-01 00:00:00" (:modified_at saved)))
        (is (= 1 (:version saved)))))))

(deftest the-modified-at-guard-runs-before-the-associations-are-written
  (let [{bread :id} (scope! "Bread")
        {:keys [id]} (recipe! "Sourdough")]
    (testing "a save that loses the race writes nothing at all — not the row, and
              not the filing, which is the half that is not on the row and could
              have been written before the guard was consulted"
      (is (nil? (db.recipe/update-recipe h/*ds* h/*user-id* id {:scope_ids [bread]}
                                         "1999-01-01 00:00:00")))
      (is (= 0 (h/scope-row-count id nil))))))

(deftest the-listing-fetches-the-associations-once-for-the-whole-page
  (let [{bread :id} (scope! "Bread")]
    (doseq [title ["One" "Two" "Three"]]
      (recipe! title [bread]))
    (let [real jdbc/execute!
          calls (atom 0)
          rows (with-redefs [jdbc/execute! (fn [& args] (swap! calls inc) (apply real args))]
                 (db.recipe/list-recipes h/*ds* h/*user-id*))]
      (testing "every row is filed"
        (is (= 3 (count rows)))
        (is (every? #(= ["Bread"] (mapv :title (:scopes %))) rows)))
      (testing "and it cost two statements — the listing and one for all of the
                associations — rather than one per row, which is the whole reason
                `attach` takes a collection"
        (is (= 2 @calls))))))

;; ---------------------------------------------------------------------------
;; the deletes, and the orphans nothing else would notice

(deftest deleting-a-scope-takes-its-associations-with-it
  (let [{bread :id} (scope! "Bread")
        {deploy :id} (scope! "Deployment")
        {sourdough :id} (recipe! "Sourdough" [bread deploy])
        {rye :id} (recipe! "Rye" [bread])]
    (is (= 3 (h/scope-row-count)))
    (is (= {:success true} (db.scope/delete-scope h/*ds* h/*user-id* bread)))
    (testing "the join rows naming it are gone from the table, not merely
              unreachable — nothing enforces the foreign key here, so an orphan
              would survive every read and come back as somebody else's badge the
              day the id is reused"
      (is (= 0 (h/scope-row-count nil bread)))
      (is (= 1 (h/scope-row-count))))
    (testing "the Recipes survive and keep every other Scope: a Recipe loses a
              badge, not its text"
      (is (= ["Deployment"] (titles-on (row sourdough))))
      (is (= [] (titles-on (row rye))))
      (is (= "body v1" (:description (row rye)))))))

(deftest deleting-a-recipe-takes-its-associations-with-it
  (let [{bread :id} (scope! "Bread")
        {:keys [id]} (recipe! "Sourdough" [bread])]
    (is (= 1 (h/scope-row-count id nil)))
    (is (= {:success true} (db.recipe/delete-recipe h/*ds* h/*user-id* id)))
    (testing "the join rows are gone rather than orphaned, the same fact
              `recipe-loses-its-history-rows` pins for the history table"
      (is (= 0 (h/scope-row-count id nil)))
      (is (= 0 (h/scope-row-count))))
    (testing "and the Scope itself is untouched — deleting a Recipe is not
              deleting the shelf it was filed on"
      (is (= "Bread" (:title (db.scope/get-scope h/*ds* h/*user-id* bread))))
      (is (= 0 (:recipe_count (first (scopes))))))))

(deftest a-refused-delete-leaves-the-associations-alone
  (let [{bread :id} (scope! "Bread")
        {:keys [id]} (recipe! "Sourdough" [bread])
        stranger (inc h/*user-id*)]
    (is (nil? (db.scope/delete-scope h/*ds* stranger bread)))
    (is (nil? (db.recipe/delete-recipe h/*ds* stranger id)))
    (testing "neither refusal deleted the join row on its way to saying no"
      (is (= 1 (h/scope-row-count id bread))))))
