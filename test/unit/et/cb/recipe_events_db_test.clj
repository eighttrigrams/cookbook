(ns et.cb.recipe-events-db-test
  "What makes an event, at the db layer: one per version **an agent** writes, and
  nothing else.

  The rule the owner gave when he was asked — *no my own ui edits should not land
  in the inbox* — is the one this file is mostly about, and it is asserted per write
  path rather than once: a test that only checked the save would pass with a stray
  event on the create path. The endpoint rules — who may read the queue, what
  marking one seen refuses — are HTTP facts and live in `inbox-integration-test`."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
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
      (testing "the Recipe itself really is gone, so these are orphans by design"
        (is (nil? (db.recipe/get-recipe h/*ds* h/*user-id* id)))
        (is (zero? (h/history-row-count id)))))))

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

(deftest a-caller-that-says-nothing-about-itself-makes-no-event
  ;; `source-of` is nil for a caller that passed no `:human?` at all, which is the
  ;; third bucket 005 keeps. Unknown provenance is not machine provenance, so it
  ;; writes no event — the same direction 004 and 005 both round in.
  (let [{:keys [id]} (db.recipe/create-recipe h/*ds* h/*user-id* {:title "Unattributed"})]
    (is (empty? (h/event-rows)))
    (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "body v2"} nil)
    (is (empty? (h/event-rows)))
    (db.recipe/delete-recipe h/*ds* h/*user-id* id)
    (is (empty? (h/event-rows)))))

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
  (let [alive (:id (machine-create! "Still here"))
        doomed (:id (machine-create! "Gone by the end"))]
    (is (= [1 1] (mapv :recipe_exists (db.event/list-unseen h/*ds* h/*user-id*))))
    (db.recipe/delete-recipe h/*ds* h/*user-id* doomed {:human? false})
    (let [by-recipe (group-by :recipe_id (db.event/list-unseen h/*ds* h/*user-id*))]
      (is (= [1] (mapv :recipe_exists (get by-recipe alive))))
      (is (= [0 0] (mapv :recipe_exists (get by-recipe doomed)))
          "both of the dead Recipe's entries, not only the `deleted` one"))))

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
    (is (= [0 0] (mapv :recipe_exists (db.event/list-unseen h/*ds* nil)))
        "and when it is really gone, so must the flag")
    (testing "and his queue is his: the other owner's events are not in it"
      (machine-create! "Somebody else's")
      (is (= 2 (count (db.event/list-unseen h/*ds* nil))))
      (is (= 1 (count (db.event/list-unseen h/*ds* h/*user-id*)))))))
