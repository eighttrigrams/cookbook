(ns et.cb.scope-inclusion-db-test
  "Narrowing the shelf **to** the Recipes filed under given Scopes, at the db layer:
  the listing's fourth narrowing and the positive one.

  > and on the main page, below the searchbar, list all scopes and have them be an
  > OR filter for scopes, i.e. it filters when one or more are selected for all
  > recipes which match one or more selectd scopes

  The rule, in one line: a Recipe is listed if it carries **at least one** of the
  named Scopes. `et.cb.scope-exclusion-db-test` is the same sentence negated, and
  these tests are deliberately its mirror image — same fixtures, same shape, so that
  the two files can be read side by side and the inversions stand out rather than
  having to be hunted for.

  **Three of them invert, and each has a test of its own here:**

  - a Recipe filed under **no** Scope falls out, where the exclusion always kept it;
  - an id the caller does not own narrows to **nothing**, where an unowned exclusion
    took nothing away — the failure mode of a stale id is an empty shelf rather than
    a full one, which is the inversion with a consequence in the client;
  - several ids take **less** away and never more, where several exclusions took more
    and never less. That is the OR he asked for, and the one thing about the pair
    that reads backwards until it is said out loud.

  **The visitor test is the privacy one**, and it carries more weight here than in
  the sibling: an exclusion lets an anonymous caller *infer* which published Recipes
  carry a Scope by diffing two listings, and an inclusion hands the answer over in
  one response. It is built the same way — the published Recipe does carry the Scope
  being named, and the owner asking the identical question is shown being narrowed —
  so it cannot pass because the clause has quietly stopped working.

  What is **not** here: how a caller spells the ids, which is the HTTP layer's
  (`et.cb.scope-inclusion-integration-test`)."
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

(defn- including [& ids]
  (shelf {:included-scope-ids (vec ids)}))

(deftest including-one-scope-keeps-exactly-what-carries-it
  (let [bread (scope! "Bread")]
    (recipe! "Sourdough" [bread])
    (recipe! "Unfiled")
    (recipe! "Deploying" [(scope! "Deployment")])
    (is (= #{"Sourdough" "Unfiled" "Deploying"} (set (shelf)))
        "the shelf before, so the narrowing below has something to take away")
    (testing "the Recipe filed under it stays and the one filed elsewhere goes"
      (is (= ["Sourdough"] (including bread))))
    (testing "and a Recipe filed under no Scope at all goes too — the inversion of
              the sibling's most-asked-about case, and the wanted answer: asked for
              the Recipes in Bread, nobody is asking for the unfiled ones as well"
      (is (not (contains? (set (including bread)) "Unfiled")))
      (is (contains? (set (shelf)) "Unfiled")
          "and it is on the shelf when nothing is selected, so its absence above is
           the clause and not the fixture"))))

(deftest two-selections-keep-more-and-never-less
  (let [bread (scope! "Bread")
        deployment (scope! "Deployment")]
    (recipe! "Sourdough" [bread])
    (recipe! "Deploying" [deployment])
    (recipe! "Neither" [(scope! "Elsewhere")])
    (testing "**the OR.** A Recipe carrying either is kept; one carrying neither is
              not — which is the assertion that would fail against an AND, since no
              Recipe here carries both"
      (is (= #{"Sourdough" "Deploying"} (set (including bread deployment)))))
    (testing "and each one on its own keeps only its own, so the union above is a
              union and not a filter that stopped working"
      (is (= ["Sourdough"] (including bread)))
      (is (= ["Deploying"] (including deployment))))))

(deftest carrying-a-selected-scope-and-another-is-kept
  ;; The mirror of `carrying-an-excluded-scope-and-a-kept-one-is-still-gone`, and
  ;; the same misreading in the other direction: "show the Recipes of that Scope"
  ;; is about carrying *any* of them, not about carrying only them. A Recipe filed
  ;; under Bread and Favourites is in Bread.
  (let [bread (scope! "Bread")
        favourites (scope! "Favourites")]
    (recipe! "Sourdough" [bread favourites])
    (recipe! "Just a favourite" [favourites])
    (recipe! "Just bread" [bread])
    (testing "a Recipe filed under the selected Scope and another one is kept"
      (is (= #{"Sourdough" "Just bread"} (set (including bread)))))
    (testing "and selecting the Scope they share keeps both of the ones that carry it"
      (is (= #{"Sourdough" "Just a favourite"} (set (including favourites)))))))

(deftest an-id-the-caller-does-not-own-keeps-nothing-and-does-not-error
  ;; **The inversion with teeth.** The sibling's unowned id is harmless — it
  ;; excludes nothing and the shelf is unchanged. Here the same silence empties the
  ;; shelf, because a clause that matches no association keeps no row. No error
  ;; either way, for the same reason: an error would say which ids exist.
  (let [stranger (inc h/*user-id*)
        {theirs :id} (db.scope/create-scope h/*ds* stranger {:title "Theirs"})
        mine (scope! "Mine")
        sourdough (recipe! "Sourdough" [mine])]
    ;; Straight at the join table: no request can file the owner's Recipe under
    ;; somebody else's Scope, so the *read* is what is under test — a clause that
    ;; skipped the join through `scopes` would honour this id and return the row.
    (h/insert-scope-row! sourdough theirs)
    (testing "the stranger's Scope keeps nothing, silently — and note what that
              means for a reader: an empty shelf, not a full one"
      (is (= [] (including theirs))))
    (testing "and neither does an id that names nothing at all"
      (is (= [] (including 99999))))
    (testing "while the caller's own id still works, so the two answers above are a
              refusal rather than a clause that has stopped working"
      (is (= ["Sourdough"] (including mine))))
    (testing "and an unowned id **alongside** an owned one still keeps the owned
              one's Recipes — the OR means a stale id costs nothing when it is not
              the only one selected, which is the case a client is likeliest to be in"
      (is (= ["Sourdough"] (including theirs mine))))))

(deftest a-visitor-is-refused-the-narrowing-outright
  ;; **The privacy test, and the more urgent of the pair.** An exclusion lets an
  ;; anonymous caller *infer* which published Recipes carry Scope 4 — diff two
  ;; listings, one id at a time. This one answers it: the rows that come back are
  ;; the ones carrying it. Same refusal, in the same line of `list-recipes`, off the
  ;; audience.
  ;;
  ;; Built so that honouring it would change the answer. The published Recipe below
  ;; carries the Scope being named, and the owner's assertion at the end keeps that
  ;; same Recipe with that same id — so this cannot pass because the clause has
  ;; quietly stopped working, only because the visitor is refused it.
  (let [bread (scope! "Bread")
        sourdough (recipe! "Sourdough" [bread])
        deploying (recipe! "Deploying" [(scope! "Deployment")])
        visitor db.recipe/visitor-audience]
    (db.recipe/publish-recipe h/*ds* h/*user-id* sourdough)
    (db.recipe/publish-recipe h/*ds* h/*user-id* deploying)
    (let [unnarrowed (set (mapv :title (db.recipe/list-recipes h/*ds* visitor)))]
      (is (= #{"Sourdough" "Deploying"} unnarrowed)
          "both published, and one of them carries the Scope named below")
      (testing "their selection changes nothing at all — the Recipe carrying Bread
                is not singled out for them"
        (is (= unnarrowed
               (set (mapv :title (db.recipe/list-recipes
                                  h/*ds* visitor {:included-scope-ids [bread]}))))))
      (testing "and an id they do not own does not empty their shelf either, which
                is the way this refusal fails if it is written as 'narrow by less'
                rather than 'do not narrow'"
        (is (= unnarrowed
               (set (mapv :title (db.recipe/list-recipes
                                  h/*ds* visitor {:included-scope-ids [99999]})))))
        (is (= unnarrowed
               (set (mapv :title (db.recipe/list-recipes
                                  h/*ds* visitor {:included-scope-ids (vec (range 1 20))})))))))
    (testing "and the owner, asking the same thing about the same Recipe, is
              narrowed — which is what makes the assertions above a refusal"
      (is (= #{"Sourdough" "Deploying"} (set (shelf))))
      (is (= ["Sourdough"] (including bread))))))

(deftest the-narrowing-composes-with-the-other-three
  (let [bread (scope! "Bread")]
    (recipe! "Sourdough starter" [bread] {:human? true})
    (recipe! "Sourdough by machine" [bread])
    (recipe! "Starter culture" [] {:human? true})
    (recipe! "Deploying" [])
    (testing "with the search, both directions: each takes rows the other left"
      (is (= #{"Sourdough starter" "Sourdough by machine" "Starter culture"}
             (set (shelf {:search-term "s"}))))
      (is (= #{"Sourdough starter" "Sourdough by machine"}
             (set (shelf {:search-term "s" :included-scope-ids [bread]}))))
      (is (= ["Sourdough starter"]
             (shelf {:included-scope-ids [bread] :search-term "starter"}))))
    (testing "with the human filter, the same way"
      (is (= #{"Sourdough starter" "Starter culture"} (set (shelf {:human-only? true}))))
      (is (= ["Sourdough starter"]
             (shelf {:human-only? true :included-scope-ids [bread]}))))
    (testing "and with the exclusion, which is the pair the UI never puts up at
              once and the db layer deliberately does not refuse: *in these Scopes
              and not in those* is a coherent question, and this is its answer"
      (let [favourites (scope! "Favourites")]
        (recipe! "Favourite bread" [bread favourites])
        (is (= #{"Sourdough starter" "Sourdough by machine" "Favourite bread"}
               (set (including bread))))
        (is (= #{"Sourdough starter" "Sourdough by machine"}
               (set (shelf {:included-scope-ids [bread]
                            :excluded-scope-ids [favourites]}))))))
    (testing "and all four at once, since they are four clauses on one query"
      (is (= ["Sourdough starter"]
             (shelf {:search-term "start" :human-only? true
                     :included-scope-ids [bread]
                     :excluded-scope-ids [(scope! "Unused")]}))))))

(deftest the-clause-is-not-a-join-and-the-survivors-counts-say-so
  ;; The `EXISTS`-not-a-`LEFT JOIN` property, pinned rather than assumed, and it
  ;; matters more on this side than on the sibling's: the row this clause keeps is
  ;; by definition one *with* associations, so a join would multiply the history
  ;; rows of every surviving Recipe rather than of an incidental one. Two Scopes and
  ;; two versions on the survivor, on purpose — under the listing's `GROUP BY` a
  ;; second multi-row join counts each history row once per association, so the
  ;; provenance split would read double.
  (let [bread (scope! "Bread")
        favourites (scope! "Favourites")
        keeper (recipe! "Kept" [bread favourites] {:human? true})]
    (db.recipe/update-recipe h/*ds* h/*user-id* keeper {:title "Kept"
                                                        :description "body v2"}
                             nil {:human? true})
    (db.recipe/record-view! h/*ds* keeper)
    (db.recipe/record-view! h/*ds* keeper)
    (recipe! "Dropped" [(scope! "Elsewhere")])
    (let [before (first (db.recipe/list-recipes h/*ds* h/*user-id*))
          narrowed (db.recipe/list-recipes h/*ds* h/*user-id*
                                           {:included-scope-ids [bread]})
          after (first narrowed)]
      (is (= "Kept" (:title before)) "the read it is read from is the same row")
      (is (= 1 (count narrowed)) "and the selection did take the other one away")
      (testing "the provenance split, the version and the view count are what they
                were before the clause was added"
        (is (= (select-keys before [:version :machine_versions :ui_versions :view_count])
               (select-keys after [:version :machine_versions :ui_versions :view_count])))
        (is (= {:version 2 :machine_versions 0 :ui_versions 2 :view_count 2}
               (select-keys after [:version :machine_versions :ui_versions :view_count]))
            "spelled out, so a doubling that happened to be equal on both sides
             cannot pass this"))
      (testing "and selecting **both** of the survivor's Scopes keeps it once and
                not twice, which is the other way a join would show"
        (is (= ["Kept"] (including bread favourites))))
      (testing "and the Scopes still come back attached to the survivor"
        (is (= ["Bread" "Favourites"] (mapv :title (:scopes after))))))))

(deftest the-survivors-keep-the-order-they-had
  ;; The shelf is ranked and sliced by the server, and this is a clause inside that
  ;; query rather than a step after it — so the survivors come back in the order
  ;; they were already in, with the rest simply not there.
  (let [bread (scope! "Bread")
        most-read (recipe! "Most read" [bread])
        middle (recipe! "Read most of all")
        least (recipe! "Least read" [bread])]
    (dotimes [_ 5] (db.recipe/record-view! h/*ds* middle))
    (dotimes [_ 3] (db.recipe/record-view! h/*ds* most-read))
    (dotimes [_ 1] (db.recipe/record-view! h/*ds* least))
    (is (= ["Read most of all" "Most read" "Least read"] (shelf))
        "the ranked order, with the Recipe about to be dropped at the top of it")
    (testing "taking the top row away leaves the rest in the order they had"
      (is (= ["Most read" "Least read"] (including bread))))))
