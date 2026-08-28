(ns et.cb.scope-exclusion-db-test
  "Hiding Recipes by the Scopes they are filed under, at the db layer: the
  listing's third narrowing, and the only **negative** one.

  > once that is activated it wont show items of these Scopes

  The shape is tracker's, whose `db.category-exclusion` emits one `NOT EXISTS` per
  category type into the `WHERE` — **not** the in-memory `apply-exclusion-filter`
  that also lives in that codebase and that its task lists do not use. So these
  tests are about a query and not about a step after one, and two of them are here
  only to hold that distinction: the survivors' aggregates and the survivors' order
  are both things a filter applied afterwards would get right by accident and a
  second `LEFT JOIN` would get wrong.

  The rule, in one line: a Recipe survives unless it carries at least one of the
  excluded Scopes. Everything else follows from that, including the two cases a
  reader always asks about — a Recipe filed under nothing survives, and a Recipe
  filed under an excluded Scope *and* another one does not.

  **The visitor test is the privacy one** and it is deliberately built so that
  honouring the parameter would change its answer: the published Recipe in it does
  carry the excluded Scope, and the owner's own half of the same test watches that
  same id take that same Recipe away. A test whose fixture could not be narrowed
  would pass against an implementation that had simply stopped working.

  What is **not** here: how a caller spells the ids, which is the HTTP layer's
  (`et.cb.scope-exclusion-integration-test`)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.db.scope :as db.scope]
            [et.cb.test-helpers :as h]))

(use-fixtures :each h/with-in-memory-db)

(defn- scope! [title]
  (:id (db.scope/create-scope h/*ds* h/*user-id* {:title title :description ""})))

(defn- recipe!
  ([title] (recipe! title [] {}))
  ([title scope-ids] (recipe! title scope-ids {}))
  ([title scope-ids opts]
   (:id (db.recipe/create-recipe h/*ds* h/*user-id*
                                 {:title title :useful_when "when testing"
                                  :description "body v1"
                                  :scope_ids scope-ids}
                                 opts))))

(defn- shelf
  "The owner's shelf as titles, optionally narrowed."
  ([] (shelf {}))
  ([opts] (mapv :title (db.recipe/list-recipes h/*ds* h/*user-id* opts))))

(defn- excluding [& ids]
  (shelf {:excluded-scope-ids (vec ids)}))

(deftest excluding-one-scope-drops-exactly-what-carries-it
  (let [bread (scope! "Bread")]
    (recipe! "Sourdough" [bread])
    (recipe! "Unfiled")
    (recipe! "Deploying" [(scope! "Deployment")])
    (is (= #{"Sourdough" "Unfiled" "Deploying"} (set (shelf)))
        "the shelf before, so the narrowing below has something to take away")
    (testing "the Recipe filed under it is gone and the one filed elsewhere stays"
      (is (= #{"Unfiled" "Deploying"} (set (excluding bread)))))
    (testing "and a Recipe filed under no Scope at all is never excluded — it is
              the case a reader asks about, and it falls out of NOT EXISTS rather
              than being defended anywhere"
      (is (contains? (set (excluding bread)) "Unfiled")))))

(deftest two-exclusions-take-away-more-and-never-less
  (let [bread (scope! "Bread")
        deployment (scope! "Deployment")]
    (recipe! "Sourdough" [bread])
    (recipe! "Deploying" [deployment])
    (recipe! "Neither" [(scope! "Elsewhere")])
    (testing "a Recipe carrying either is gone; one carrying neither stays"
      (is (= ["Neither"] (excluding bread deployment))))
    (testing "and each one on its own takes only its own"
      (is (= #{"Deploying" "Neither"} (set (excluding bread))))
      (is (= #{"Sourdough" "Neither"} (set (excluding deployment)))))))

(deftest carrying-an-excluded-scope-and-a-kept-one-is-still-gone
  ;; The rule is about carrying *any* excluded Scope, not about carrying only
  ;; them — which is the reading somebody implementing this from the sentence
  ;; "hide the Recipes of that Scope" could plausibly get backwards.
  (let [bread (scope! "Bread")
        favourites (scope! "Favourites")]
    (recipe! "Sourdough" [bread favourites])
    (recipe! "Just a favourite" [favourites])
    (is (= ["Sourdough" "Just a favourite"] (sort-by #(if (= "Sourdough" %) 0 1) (shelf))))
    (is (= ["Just a favourite"] (excluding bread))
        "the Recipe filed under both is gone; the other Scope does not rescue it")
    (testing "and excluding the Scope they share takes both"
      (is (= [] (excluding favourites))))))

(deftest an-id-the-caller-does-not-own-excludes-nothing-and-does-not-error
  (let [stranger (inc h/*user-id*)
        {theirs :id} (db.scope/create-scope h/*ds* stranger {:title "Theirs"})
        mine (scope! "Mine")
        sourdough (recipe! "Sourdough" [mine])]
    ;; Straight at the join table: no request can file the owner's Recipe under
    ;; somebody else's Scope, so the *read* is what is under test here — a clause
    ;; that skipped the join through `scopes` would honour this id.
    (h/insert-scope-row! sourdough theirs)
    (testing "the stranger's Scope excludes nothing, silently — no error, and no
              way to learn from the answer that the id exists"
      (is (= ["Sourdough"] (excluding theirs))))
    (testing "and neither does an id that names nothing at all"
      (is (= ["Sourdough"] (excluding 99999))))
    (testing "while the caller's own id still works, so the two answers above are
              a refusal rather than a feature that does nothing"
      (is (= [] (excluding mine))))
    (testing "and an unowned id alongside an owned one leaves the owned one's work
              intact rather than poisoning the clause"
      (is (= [] (excluding theirs mine))))))

(deftest a-visitor-is-refused-the-narrowing-outright
  ;; **The privacy test.** `with-scopes` refuses a visitor the Scopes by not
  ;; running the join, so their presence is not testable — and a visitor's search
  ;; does not reach the filing either, which is the same refusal made a third time
  ;; (`et.cb.search-test/a-visitors-search-does-not-reach-the-filing`).
  ;; Honouring an exclusion would hand that back: a caller could binary-search
  ;; which published Recipes carry Scope 4 by watching rows vanish.
  ;;
  ;; Built so that honouring it *would* change the answer. The published Recipe
  ;; below carries the excluded Scope, and the owner's assertion at the end takes
  ;; that same Recipe away with that same id — so this cannot pass because the
  ;; exclusion has quietly stopped working, only because the visitor is refused it.
  (let [bread (scope! "Bread")
        sourdough (recipe! "Sourdough" [bread])
        _ (recipe! "Deploying" [(scope! "Deployment")])
        visitor db.recipe/visitor-audience]
    (db.recipe/publish-recipe h/*ds* h/*user-id* sourdough)
    (let [unnarrowed (mapv :title (db.recipe/list-recipes h/*ds* visitor))]
      (is (= ["Sourdough"] unnarrowed)
          "the published Recipe, and it is the one carrying the Scope")
      (testing "their exclusion changes nothing at all"
        (is (= unnarrowed
               (mapv :title (db.recipe/list-recipes
                             h/*ds* visitor {:excluded-scope-ids [bread]})))))
      (testing "not even when they name every id there is"
        (is (= unnarrowed
               (mapv :title (db.recipe/list-recipes
                             h/*ds* visitor {:excluded-scope-ids (vec (range 1 20))}))))))
    (testing "and the owner, asking the same thing about the same Recipe, is
              narrowed — which is what makes the three assertions above a refusal"
      (is (= #{"Sourdough" "Deploying"} (set (shelf))))
      (is (= ["Deploying"] (excluding bread))))))

(deftest the-narrowing-composes-with-the-other-two
  (let [bread (scope! "Bread")]
    (recipe! "Sourdough starter" [bread] {:human? true})
    (recipe! "Sourdough by machine" [bread])
    (recipe! "Starter culture" [] {:human? true})
    (recipe! "Deploying" [])
    (testing "with the search, both directions: each takes rows the other left"
      (is (= #{"Sourdough starter" "Sourdough by machine" "Starter culture"}
             (set (shelf {:search-term "s"}))))
      (is (= ["Starter culture"]
             (shelf {:search-term "s" :excluded-scope-ids [bread]})))
      (is (= ["Starter culture"]
             (shelf {:excluded-scope-ids [bread] :search-term "starter"}))))
    (testing "with the human filter, the same way"
      (is (= #{"Sourdough starter" "Starter culture"} (set (shelf {:human-only? true}))))
      (is (= ["Starter culture"]
             (shelf {:human-only? true :excluded-scope-ids [bread]}))))
    (testing "and all three at once, since they are three clauses on one query"
      (is (= ["Starter culture"]
             (shelf {:search-term "cult" :human-only? true
                     :excluded-scope-ids [bread]}))))))

(deftest the-clause-is-not-a-join-and-the-survivors-counts-say-so
  ;; The `NOT EXISTS`-not-a-`LEFT JOIN` property, pinned rather than assumed. The
  ;; survivor here is filed under **two** Scopes and has **two** versions on
  ;; purpose: a second multi-row join under the listing's `GROUP BY` would count
  ;; each history row once per association, so the provenance split would read
  ;; double. A correlated `EXISTS` answers per row and multiplies nothing.
  (let [bread (scope! "Bread")
        favourites (scope! "Favourites")
        excluded (scope! "Excluded")
        keeper (recipe! "Kept" [bread favourites] {:human? true})]
    (db.recipe/update-recipe h/*ds* h/*user-id* keeper {:title "Kept"
                                                        :description "body v2"}
                             nil {:human? true})
    (db.recipe/record-view! h/*ds* keeper false)
    (db.recipe/record-view! h/*ds* keeper false)
    (recipe! "Dropped" [excluded])
    (let [before (first (db.recipe/list-recipes h/*ds* h/*user-id*))
          after (first (db.recipe/list-recipes h/*ds* h/*user-id*
                                               {:excluded-scope-ids [excluded]}))]
      (is (= "Kept" (:title before)) "the read it is read from is the same row")
      (is (= 1 (count (db.recipe/list-recipes h/*ds* h/*user-id*
                                              {:excluded-scope-ids [excluded]})))
          "and the exclusion did take the other one away")
      (testing "the provenance split, the version and the view count are what they
                were before the clause was added"
        (is (= (select-keys before [:version :machine_versions :ui_versions :view_count])
               (select-keys after [:version :machine_versions :ui_versions :view_count])))
        (is (= {:version 2 :machine_versions 0 :ui_versions 2 :view_count 2}
               (select-keys after [:version :machine_versions :ui_versions :view_count]))
            "spelled out, so a doubling that happened to be equal on both sides
             cannot pass this"))
      (testing "and the Scopes still come back attached to the survivor"
        (is (= ["Bread" "Favourites"] (mapv :title (:scopes after))))))))

(deftest the-survivors-keep-the-order-they-had
  ;; The shelf is ranked and sliced by the server, and this is a clause inside that
  ;; query rather than a step after it — so the survivors come back in the order
  ;; they were already in, with the excluded rows simply not there.
  (let [bread (scope! "Bread")
        most-read (recipe! "Most read")
        middle (recipe! "Filed and read most of all" [bread])
        least (recipe! "Least read")]
    (dotimes [_ 5] (db.recipe/record-view! h/*ds* middle false))
    (dotimes [_ 3] (db.recipe/record-view! h/*ds* most-read false))
    (dotimes [_ 1] (db.recipe/record-view! h/*ds* least false))
    (is (= ["Filed and read most of all" "Most read" "Least read"] (shelf))
        "the ranked order, with the Recipe about to be excluded at the top of it")
    (testing "taking the top row away leaves the rest in the order they had"
      (is (= ["Most read" "Least read"] (excluding bread))))))
