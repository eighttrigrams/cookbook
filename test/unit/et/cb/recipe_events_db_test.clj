(ns et.cb.recipe-events-db-test
  "What makes an event, at the db layer: one per version **an agent** writes, and
  nothing else.

  The rule the owner gave when he was asked — *no my own ui edits should not land
  in the inbox* — is the one this file is mostly about, and it is asserted per write
  path rather than once: a test that only checked the save would pass with a stray
  event on the create path. The endpoint rules — who may read the queue, what
  marking one seen refuses — are HTTP facts and live in `inbox-integration-test`."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [et.cb.db.event :as db.event]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.db.scope :as db.scope]
            [et.cb.test-helpers :as h]))

(use-fixtures :each h/with-in-memory-db)

;; `:human? false` is a machine write and `:human? true` is his own. Both spelled
;; out at every call rather than defaulted, because which one a test means is the
;; whole subject of the file.

(defn- create!
  [title opts]
  (db.recipe/create-recipe h/*ds* h/*user-id*
                           {:title title :useful_when "when testing"
                            :description "body v1"}
                           opts))

(defn- machine-create! [title] (create! title {:human? false}))

(defn- scope!
  ([title] (scope! title ""))
  ([title description]
   (:id (db.scope/create-scope h/*ds* h/*user-id* {:title title
                                                   :description description}))))

(defn- machine-create-filed!
  "An agent's create, filed under Scopes on the way in — which is the only thing the
  queue's badges are about."
  [title scope-ids]
  (db.recipe/create-recipe h/*ds* h/*user-id*
                           {:title title :useful_when "when testing"
                            :description "body v1" :scope_ids scope-ids}
                           {:human? false}))

(defn- scope-titles-on [entry] (mapv :title (:scopes entry)))

(defn- save!
  [id fields opts]
  (db.recipe/update-recipe h/*ds* h/*user-id* id fields nil opts))

(defn- kinds-of [recipe-id]
  (mapv :kind (h/event-rows recipe-id)))

;; ---------------------------------------------------------------------------
;; a machine's writes

(deftest a-machine-create-makes-one-created-event-for-version-1
  (let [{:keys [id]} (machine-create! "Written by an agent")
        [event & more] (h/event-rows id)]
    (is (empty? more) "one create, one event")
    (is (= "created" (:kind event)))
    (is (= 1 (:version event)) "v1 is the version the create wrote")
    (is (= "Written by an agent" (:recipe_title event)) "with the title as it read then")
    (is (= id (:recipe_id event)))
    (is (= h/*user-id* (:user_id event)) "filed under the owner, not the writer")
    (is (= 0 (:seen event)) "unseen is where every event starts")
    (is (some? (:created_at event)) "and it is stamped when it happened")
    (is (nil? (:proposal_id event)) "nothing proposed anything here")
    (testing "and the row carries no source: every event here is a machine's, so a
              column for it would have one possible value"
      (is (false? (contains? event :source))))))

(deftest a-machine-save-that-changes-content-makes-one-modified-event-at-the-new-version
  (let [{:keys [id]} (machine-create! "Baguette")]
    (save! id {:description "body v2"} {:human? false})
    (let [[created modified] (h/event-rows id)]
      (is (= ["created" "modified"] [(:kind created) (:kind modified)])
          "the create first, the save after it — the queue is the append order")
      (is (= 1 (:version created)))
      (is (= 2 (:version modified))
          "the **new** version: the event is about the version the save wrote")
      (is (< (:id created) (:id modified)) "and the ids ascend with the appending"))))

(deftest a-machine-delete-makes-a-deleted-event-and-keeps-the-earlier-ones
  ;; The one kind he did not ask for by name. Without it an agent can create a
  ;; Recipe and delete it again, and an inbox whose promise is that changes show up
  ;; there would record the create and then erase the record of it.
  (let [{:keys [id]} (machine-create! "Doomed")]
    (save! id {:description "body v2"} {:human? false})
    (is (= {:success true} (db.recipe/delete-recipe h/*ds* h/*user-id* id {:human? false})))
    (let [rows (h/event-rows id)
          died (last rows)]
      (is (= ["created" "modified" "deleted"] (mapv :kind rows))
          "the delete is appended, and it takes nothing off the queue with it")
      (is (= 2 (:version died)) "at the version the Recipe died on")
      (is (= "Doomed" (:recipe_title died))
          "and the title, which is the only thing left that names it")
      (testing "the Recipe is off the shelf and still readable — since 012 a delete
                is a tombstone, so these entries are not orphans and the `deleted`
                one can be opened, which is what it was asked for"
        (is (nil? (db.recipe/get-recipe h/*ds* h/*user-id* id)))
        (is (= 1 (h/history-row-count id)))
        (is (= 2 (:total (db.recipe/list-versions h/*ds* h/*user-id* id))))))))

(deftest the-title-on-an-event-is-the-title-as-it-read-then
  ;; A snapshot and not a join: the event has to stay readable after the Recipe is
  ;; renamed, and after it is deleted altogether.
  (let [{:keys [id]} (machine-create! "The old name")]
    (save! id {:title "The new name"} {:human? false})
    (is (= ["The old name" "The new name"] (mapv :recipe_title (h/event-rows id))))))

;; ---------------------------------------------------------------------------
;; his own writes, which are the ones that make nothing

(deftest none-of-the-owners-own-writes-lands-in-the-inbox
  ;; *"no my own ui edits should not land in the inbox"* — the later word, and it
  ;; wins over "every recipe change". All four paths, because a test that only
  ;; covered the save would pass with a stray event on the create.
  (let [{:keys [id]} (create! "Typed by hand" {:human? true})]
    (is (empty? (h/event-rows)) "his create makes nothing")

    (save! id {:description "body v2"} {:human? true})
    (is (= 2 (:version (db.recipe/get-recipe h/*ds* h/*user-id* id)))
        "the save really did make a version")
    (is (empty? (h/event-rows)) "and still nothing in the inbox")

    (db.recipe/publish-recipe h/*ds* h/*user-id* id)
    (is (empty? (h/event-rows)) "publishing makes nothing")

    (db.recipe/delete-recipe h/*ds* h/*user-id* id {:human? true})
    (is (nil? (db.recipe/get-recipe h/*ds* h/*user-id* id)) "the delete really landed")
    (is (empty? (h/event-rows)) "and his delete makes nothing either")))

(deftest a-caller-that-says-nothing-about-itself-is-treated-as-an-agent
  ;; **This test asserted the opposite until migration 010**, and the reason it
  ;; flipped is worth keeping: `source-of` used to answer nil for a caller that
  ;; passed no `:human?` at all — the third bucket — and an event is written exactly
  ;; when the label would be `machine`, so silence made no event. 010 retired that
  ;; bucket, silence now labels the version `machine`, and the event rule follows it
  ;; without having a second opinion. That is the property being pinned here: the
  ;; queue and the labels are decided by one expression, so they cannot drift.
  ;;
  ;; Only the db layer can get here. Every write through a handler passes `:human?`
  ;; from the token, so no HTTP caller is ever unattributed.
  (let [{:keys [id]} (db.recipe/create-recipe h/*ds* h/*user-id* {:title "Unattributed"})]
    (is (= ["created"] (kinds-of id)))
    (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "body v2"} nil)
    (is (= ["created" "modified"] (kinds-of id)))
    (testing "and both of those versions are labelled `machine` — read while the
              Recipe still exists, because the labels are the thing the events are
              keyed off and after the delete there is no ladder left to read"
      (is (= ["machine" "machine"]
             (mapv :source (:versions (db.recipe/list-versions h/*ds* h/*user-id* id))))))
    (db.recipe/delete-recipe h/*ds* h/*user-id* id)
    (is (= ["created" "modified" "deleted"] (kinds-of id)))))

(deftest his-save-over-an-agents-Recipe-adds-nothing-to-the-queue
  ;; The mixed history, which is the interesting case: the agent's two writes are in
  ;; the queue and his edit between them is not, so the queue stays a record of what
  ;; the agents did rather than of what happened.
  (let [{:keys [id]} (machine-create! "Half his, half theirs")]
    (save! id {:description "his body"} {:human? true})
    (save! id {:description "the agent's body"} {:human? false})
    (is (= ["created" "modified"] (kinds-of id)))
    (is (= [1 3] (mapv :version (h/event-rows id)))
        "v2 is his and is absent — an event's version is the version it is about,
         and the numbers therefore have gaps in them by design")))

;; ---------------------------------------------------------------------------
;; the writes that make no version, and therefore no event

(deftest a-machine-save-that-changes-nothing-makes-no-event
  ;; For exactly the reason it makes no version: it returns before the write. An
  ;; inbox that filled up with re-saves of the same text would stop being read.
  (let [{:keys [id]} (machine-create! "Ciabatta")]
    (is (= ["created"] (kinds-of id)))
    (save! id {:title "Ciabatta"} {:human? false})
    (save! id {:description "body v1"} {:human? false})
    (save! id {} {:human? false})
    (is (= ["created"] (kinds-of id)) "three no-ops, no events")
    (is (= 1 (:version (db.recipe/get-recipe h/*ds* h/*user-id* id)))
        "and no versions either, which is the fact the events follow")))

(deftest a-machines-filing-only-save-makes-no-event
  ;; Tags and Scopes are not versioned — see the namespace docstring — and the
  ;; inbox sits on the content side of that one split. `update-recipe`'s third
  ;; branch is the one that has to stay quiet.
  (let [{:keys [id]} (machine-create! "Focaccia")
        scope (:id (db.scope/create-scope h/*ds* h/*user-id* {:title "Bread"
                                                             :description ""}))]
    (save! id {:tags "sourdough starter"} {:human? false})
    (is (= ["created"] (kinds-of id)) "a tags-only save makes no event")

    (save! id {:scope_ids [scope]} {:human? false})
    (is (= ["created"] (kinds-of id)) "and neither does a refile")

    (save! id {:tags "sourdough" :scope_ids []} {:human? false})
    (is (= ["created"] (kinds-of id)) "nor both at once")

    (testing "while a save that changes the filing *and* the content makes the one
              event the content change earns"
      (save! id {:tags "bread" :description "body v2"} {:human? false})
      (is (= ["created" "modified"] (kinds-of id)))
      (is (= 2 (:version (last (h/event-rows id))))))))

(deftest reading-a-recipe-makes-no-event
  (let [{:keys [id]} (machine-create! "Read but not written")]
    (db.recipe/get-recipe h/*ds* h/*user-id* id {:lean? false})
    (db.recipe/record-view! h/*ds* id)
    (db.recipe/list-versions h/*ds* h/*user-id* id)
    (db.recipe/list-recipes h/*ds* h/*user-id*)
    (is (= ["created"] (kinds-of id)))))

;; ---------------------------------------------------------------------------

(deftest an-event-and-the-write-it-records-are-one-transaction
  ;; The event goes in the same transaction as the thing it records: an event for a
  ;; save that then rolled back would be worse than no inbox. Asserted by breaking
  ;; the event write, which is the only direction a test can force — if the two
  ;; were separate statements the Recipe below would survive.
  (let [{:keys [id]} (machine-create! "Written before the break")]
    (with-redefs [db.event/record! (fn [& _] (throw (ex-info "no event for you" {})))]
      (is (thrown? Exception (machine-create! "Never happened")))
      (is (thrown? Exception (save! id {:description "body v2"} {:human? false})))
      (is (thrown? Exception (db.recipe/delete-recipe h/*ds* h/*user-id* id
                                                     {:human? false}))))
    (testing "so none of the three writes landed"
      (is (= ["Written before the break"]
             (mapv :title (db.recipe/list-recipes h/*ds* h/*user-id*)))
          "the create rolled back with its event")
      (let [recipe (db.recipe/get-recipe h/*ds* h/*user-id* id {:lean? false})]
        (is (some? recipe) "and the delete did too")
        (is (= 1 (:version recipe)) "the save rolled back with its event")
        (is (= "body v1" (:description recipe)))))
    (testing "and the one event that was written before the break is still the
              only one there"
      (is (= ["created"] (kinds-of id))))))

(deftest one-recipes-events-are-its-own
  (let [a (:id (machine-create! "This one"))
        b (:id (machine-create! "Not this one"))]
    (save! a {:description "body v2"} {:human? false})
    (is (= ["created" "modified"] (kinds-of a)))
    (is (= ["created"] (kinds-of b)))))

;; ---------------------------------------------------------------------------
;; reading the queue

(deftest every-entry-says-whether-its-recipe-is-still-there
  ;; Two flags since 012, because there are two questions: can this entry be opened,
  ;; and what is it about. A deleted Recipe answers yes to the first — the tombstone
  ;; keeps the text — and a purge is what finally makes the answer no.
  (let [alive (:id (machine-create! "Still here"))
        doomed (:id (machine-create! "Gone by the end"))]
    (is (= [1 1] (mapv :recipe_exists (db.event/list-unseen h/*ds* h/*user-id*))))
    (is (= [0 0] (mapv :recipe_tombstoned (db.event/list-unseen h/*ds* h/*user-id*))))
    (db.recipe/delete-recipe h/*ds* h/*user-id* doomed {:human? false})
    (let [by-recipe (group-by :recipe_id (db.event/list-unseen h/*ds* h/*user-id*))]
      (is (= [1] (mapv :recipe_exists (get by-recipe alive))))
      (is (= [0] (mapv :recipe_tombstoned (get by-recipe alive))))
      (is (= [1 1] (mapv :recipe_exists (get by-recipe doomed)))
          "both of the tombstoned Recipe's entries: there is text behind them")
      (is (= [1 1] (mapv :recipe_tombstoned (get by-recipe doomed)))
          "and both say which kind of existing it is, not only the `deleted` one —
           the `created` entry above it names the same deleted Recipe"))
    (testing "purging is what takes the text away, and then neither flag is set"
      (db.recipe/purge-recipe! h/*ds* h/*user-id* doomed)
      (let [by-recipe (group-by :recipe_id (db.event/list-unseen h/*ds* h/*user-id*))]
        (is (= [0 0] (mapv :recipe_exists (get by-recipe doomed))))
        (is (= [0 0] (mapv :recipe_tombstoned (get by-recipe doomed))))
        (is (= 2 (count (get by-recipe doomed)))
            "and the entries themselves survive it, as an event always does")))))

(deftest the-recipe-exists-flag-is-right-for-the-nil-owner-too
  ;; **The case that broke, and the reason this test is separate from the one
  ;; above.** Dev's owner has no `users` row, so his Recipes and his events both
  ;; carry `user_id NULL` — and the subquery correlates the two columns, where
  ;; `NULL = NULL` is NULL rather than true. Written with `=` it answered 'this
  ;; Recipe is gone' for every event on his own machine, and the page silently
  ;; stopped linking anything. Every fixture in this suite owns its rows under a
  ;; real id, so nothing else here can catch it.
  (let [{:keys [id]} (db.recipe/create-recipe h/*ds* nil {:title "The dev owner's"}
                                             {:human? false})]
    (is (= [1] (mapv :recipe_exists (db.event/list-unseen h/*ds* nil)))
        "his Recipe exists, and the flag has to say so")
    (db.recipe/delete-recipe h/*ds* nil id {:human? false})
    (is (= [1 1] (mapv :recipe_exists (db.event/list-unseen h/*ds* nil)))
        "deleted is not gone since 012: the row is still his and still readable")
    (is (= [1 1] (mapv :recipe_tombstoned (db.event/list-unseen h/*ds* nil)))
        "and the second flag has the same nil-owner correlation to get right")
    (db.recipe/purge-recipe! h/*ds* nil id)
    (is (= [0 0] (mapv :recipe_exists (db.event/list-unseen h/*ds* nil)))
        "and when it is really gone, so must the flag")
    (testing "and his queue is his: the other owner's events are not in it"
      (machine-create! "Somebody else's")
      (is (= 2 (count (db.event/list-unseen h/*ds* nil))))
      (is (= 1 (count (db.event/list-unseen h/*ds* h/*user-id*)))))))

;; ---------------------------------------------------------------------------
;; the Scopes on an entry — what area the change was in, which is most of
;; deciding whether it matters

(deftest an-entry-carries-the-scopes-its-recipe-is-filed-under
  (let [bread (scope! "Bread" "Anything with flour in it")
        ops (scope! "Ops" "Keeping the boxes running")
        {:keys [id]} (machine-create-filed! "Sourdough" [ops bread])
        [entry] (db.event/list-unseen h/*ds* h/*user-id*)]
    (is (= id (:recipe_id entry)))
    (is (= ["Bread" "Ops"] (scope-titles-on entry))
        "both of them, in title order — so the badges on a queue row read in the same
         order as the ones on a card and as the Scopes page's own list")
    (is (= [bread ops] (mapv :id (:scopes entry)))
        "with the Scope's own id, which is what a badge is keyed on")
    (is (= ["Anything with flour in it" "Keeping the boxes running"]
           (mapv :description (:scopes entry)))
        "and its description, which is the tooltip and the only place a reader meets
         it outside the Scopes page")
    (is (every? #(false? (contains? % :recipe_id)) (:scopes entry))
        "the grouping key is not part of what a Scope is")))

(deftest an-entry-for-an-unfiled-recipe-carries-an-empty-vector-and-not-a-missing-key
  ;; Absent and empty are different answers, and on the shelf the difference is the
  ;; whole privacy boundary — a visitor gets no `scopes` key rather than a claim that
  ;; the owner filed a Recipe under nothing. **Here there is no visitor**, so the only
  ;; thing an absent key could mean is that the attach never ran, which is a page
  ;; drawing no badges for a reason it cannot report. So it has to be there and empty.
  (let [_ (scope! "Bread")]
    (machine-create! "Filed under nothing")
    (let [[entry] (db.event/list-unseen h/*ds* h/*user-id*)]
      (is (true? (contains? entry :scopes)))
      (is (= [] (:scopes entry))))))

(deftest two-events-naming-one-recipe-both-get-its-scopes
  ;; The duplicate-id case: the queue hands `attach` one id per *entry*, so three
  ;; entries about one Recipe hand over the same id three times. `IN` does not care
  ;; and neither does the per-row lookup — but nothing else here would build that
  ;; shape, and it is the inbox's normal case rather than a corner.
  ;;
  ;; **It is also where keying on the wrong column shows**, which is why the ids are
  ;; deliberately misaligned. `attach` defaults to a row's `:id`, and an entry's `:id`
  ;; is the *event's*: with one Recipe and one event they are both 1 and a wrong key
  ;; would still answer right by coincidence. The `modified` entry below is event 2 on
  ;; Recipe 1 while Recipe 2 is a different Recipe filed under a different Scope, so
  ;; keying on `:id` attaches Ops to a Bread entry — silently, and plausibly.
  (let [bread (scope! "Bread")
        ops (scope! "Ops")
        {sourdough :id} (machine-create-filed! "Sourdough" [bread])]
    (save! sourdough {:description "body v2"} {:human? false})
    (let [{other :id} (machine-create-filed! "Restarting a stuck box" [ops])
          entries (db.event/list-unseen h/*ds* h/*user-id*)]
      (is (= [sourdough sourdough other] (mapv :recipe_id entries)))
      (is (not= (mapv :id entries) (mapv :recipe_id entries))
          "the event ids and the recipe ids disagree, or this test proves nothing
           about which of the two the Scopes were grouped by")
      (is (= [["Bread"] ["Bread"] ["Ops"]] (mapv scope-titles-on entries))
          "each entry gets its own Recipe's filing, including the two that name the
           same Recipe")
      (testing "and one more entry about the same Recipe joins them rather than
                taking a turn"
        (save! sourdough {:description "body v3"} {:human? false})
        (is (= [["Bread"] ["Bread"] ["Ops"] ["Bread"]]
               (mapv scope-titles-on (db.event/list-unseen h/*ds* h/*user-id*))))))))

(deftest an-entry-outlives-its-recipe-with-its-title-and-keeps-the-badges-until-a-purge
  ;; The row this whole snapshot design exists for, and it now has two stages. A
  ;; **delete** keeps the Recipe and its filing (012), so the entries keep their
  ;; badges and can be opened; a **purge** is where the associations go, and then the
  ;; snapshot title really is all that is left naming it. This test used to assert
  ;; the second stage's answer for the first one, which is what a tombstone changes.
  (let [bread (scope! "Bread")
        {:keys [id]} (machine-create-filed! "Doomed" [bread])]
    (save! id {:description "body v2"} {:human? false})
    (is (= [["Bread"] ["Bread"]]
           (mapv scope-titles-on (db.event/list-unseen h/*ds* h/*user-id*)))
        "filed while it lived")
    (is (= {:success true} (db.recipe/delete-recipe h/*ds* h/*user-id* id {:human? false})))
    (let [entries (db.event/list-unseen h/*ds* h/*user-id*)]
      (is (= ["created" "modified" "deleted"] (mapv :kind entries))
          "all three entries are still in the queue")
      (is (= [["Bread"] ["Bread"] ["Bread"]] (mapv scope-titles-on entries))
          "and they keep the badges, because a tombstone keeps its filing — which is
           most of what triaging a `deleted` row is")
      (is (= ["Doomed" "Doomed" "Doomed"] (mapv :recipe_title entries))
          "with the snapshot title intact, as it always was")
      (is (= [1 1 1] (mapv :recipe_exists entries)) "there is text behind them")
      (is (= 1 (h/scope-row-count id nil)) "the associations really are still there"))
    (testing "and the purge is what takes them"
      (db.recipe/purge-recipe! h/*ds* h/*user-id* id)
      (let [entries (db.event/list-unseen h/*ds* h/*user-id*)]
        (is (= ["created" "modified" "deleted"] (mapv :kind entries))
            "the entries survive a purge too — an event is not part of a Recipe")
        (is (= [[] [] []] (mapv scope-titles-on entries))
            "now with no Scopes, because the join rows went with the row")
        (is (= ["Doomed" "Doomed" "Doomed"] (mapv :recipe_title entries))
            "and the snapshot title is all that is left naming it")
        (is (= [0 0 0] (mapv :recipe_exists entries)))
        (is (= 0 (h/scope-row-count id nil)) "the associations really are gone")))))

(deftest the-scopes-on-an-entry-are-current-where-its-title-is-a-snapshot
  ;; The asymmetry, pinned so nobody later makes one half match the other. Refiling a
  ;; Recipe changes the badges on entries already in the queue and leaves their titles
  ;; alone — which is the pairing triage wants: what the entry is *about*, and what
  ;; area the Recipe belongs to *now*.
  (let [bread (scope! "Bread")
        ops (scope! "Ops")
        {:keys [id]} (machine-create-filed! "The old name" [bread])]
    (db.recipe/update-recipe h/*ds* h/*user-id* id {:title "The new name"
                                                    :scope_ids [ops]} nil {:human? true})
    (let [[entry] (db.event/list-unseen h/*ds* h/*user-id*)]
      (is (= "The old name" (:recipe_title entry))
          "the title is frozen at the moment the change happened")
      (is (= ["Ops"] (scope-titles-on entry))
          "while the badges are where it is filed now — read at query time, not
           written down when the event was"))))

(deftest the-queue-fetches-the-associations-once-for-the-whole-list
  ;; The shelf's property, met again: `the-listing-fetches-the-associations-once-for-
  ;; the-whole-page` in `scope-db-test` is the same assertion about `list-recipes`, and
  ;; it is the reason `attach` takes a collection at all. A queue he has not emptied in
  ;; a week must not cost a round trip per entry.
  (let [bread (scope! "Bread")]
    (doseq [title ["One" "Two" "Three" "Four"]]
      (let [{:keys [id]} (machine-create-filed! title [bread])]
        (save! id {:description "body v2"} {:human? false})))
    (let [real jdbc/execute!
          calls (atom 0)
          entries (with-redefs [jdbc/execute! (fn [& args] (swap! calls inc) (apply real args))]
                    (db.event/list-unseen h/*ds* h/*user-id*))]
      (is (= 8 (count entries)) "eight entries about four Recipes")
      (is (every? #(= ["Bread"] (scope-titles-on %)) entries))
      (is (= 2 @calls)
          "two statements — the queue and one for every association on it — and not
           one per entry, which is what the whole `attach` shape is for"))))
