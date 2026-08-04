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

;; ---------------------------------------------------------------------------
;; the human-edit mark
;;
;; One monotonic bit on the row: set by a write the caller says is not a
;; machine's, never cleared by anything. These are the db-layer half — who counts
;; as a machine is decided from the token, which is the handler's half and lives
;; in the human-edit integration namespace.

(defn- flag-of [id]
  (:has_human_edit (db.recipe/get-recipe h/*ds* h/*user-id* id)))

(defn- create-as! [human? title]
  (db.recipe/create-recipe h/*ds* h/*user-id*
                           {:title title :useful_when "when testing" :description "body v1"}
                           {:human? human?}))

(deftest the-human-edit-mark-is-monotonic
  (testing "a machine's create leaves the row unmarked, and so does a caller who
            says nothing about itself — an unrecorded author is not a human one"
    (is (= 0 (:has_human_edit (create-as! false "Written by an agent"))))
    (is (= 0 (:has_human_edit (create! "Said nothing")))))

  (testing "a human's create marks it"
    (is (= 1 (:has_human_edit (create-as! true "Written by hand")))))

  (testing "a human edit of a machine's recipe earns the mark"
    (let [{:keys [id]} (create-as! false "Agent's draft")]
      (is (= 0 (flag-of id)))
      (is (= 1 (:has_human_edit (db.recipe/update-recipe h/*ds* h/*user-id* id
                                                         {:description "body v2"} nil
                                                         {:human? true}))))
      (is (= 1 (flag-of id)))))

  (testing "and a machine editing afterwards cannot take it back"
    (let [{:keys [id]} (create-as! true "The owner's")]
      (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "the agent's body"} nil
                               {:human? false})
      (is (= 1 (flag-of id)))
      (is (= "the agent's body"
             (:description (db.recipe/get-recipe h/*ds* h/*user-id* id {:lean? false})))
          "the machine's write still landed — only the mark is untouchable"))))

(deftest what-does-not-earn-the-mark
  (testing "a save that changes nothing: it returns before the write, so there is
            no edit to record"
    (let [{:keys [id]} (create-as! false "Unchanged")]
      (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "body v1"} nil
                               {:human? true})
      (is (= 0 (flag-of id)))
      (is (= 1 (:version (db.recipe/get-recipe h/*ds* h/*user-id* id))))))

  (testing "a refused save: a stale modified_at writes nothing at all"
    (let [{:keys [id]} (create-as! false "Raced")]
      (is (nil? (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "x"}
                                         "1999-01-01 00:00:00" {:human? true})))
      (is (= 0 (flag-of id)))))

  (testing "publishing: the latch says the owner put his name to the text, not
            that he wrote it"
    (let [{:keys [id]} (create-as! false "Signed but not written")]
      (is (= 1 (:published (db.recipe/publish-recipe h/*ds* h/*user-id* id))))
      (is (= 0 (:has_human_edit (db.recipe/publish-recipe h/*ds* h/*user-id* id))))
      (is (= 0 (flag-of id))))))

(deftest the-mark-is-in-the-lean-projection
  (let [{:keys [id]} (create-as! true "Readable at a glance")]
    (testing "a caller can see the bit the filter narrows by without asking for a
              body, the same way it can see `published`"
      (is (= 1 (:has_human_edit (db.recipe/get-recipe h/*ds* h/*user-id* id))))
      (is (every? #(contains? % :has_human_edit)
                  (db.recipe/list-recipes h/*ds* h/*user-id*))))
    (testing "and it is not part of the content — no version carries it"
      (is (every? #(false? (contains? % :has_human_edit))
                  (:versions (versions-of id)))))))

(deftest human-only-narrows-the-listing
  (let [{by-hand :id} (create-as! true "By hand")
        {by-agent :id} (create-as! false "By an agent")]
    (testing "on, the shelf is the marked rows only"
      (is (= [by-hand] (map :id (db.recipe/list-recipes h/*ds* h/*user-id*
                                                        {:human-only? true})))))
    (testing "off, or not asked for, it is everything"
      (is (= #{by-hand by-agent}
             (set (map :id (db.recipe/list-recipes h/*ds* h/*user-id* {:human-only? false})))))
      (is (= #{by-hand by-agent}
             (set (map :id (db.recipe/list-recipes h/*ds* h/*user-id*))))))
    (testing "it composes with the search rather than replacing it — both clauses
              apply, so a term that matches only the agent's recipe finds nothing"
      (is (= [by-hand] (map :id (db.recipe/list-recipes h/*ds* h/*user-id*
                                                        {:human-only? true :search-term "by"}))))
      (is (empty? (db.recipe/list-recipes h/*ds* h/*user-id*
                                          {:human-only? true :search-term "agent"}))))))

(deftest human-only-narrows-inside-the-visitor-scope
  ;; The clause has to be a `:where` beside the scope, not a filter over rows the
  ;; query already returned: a visitor filtering must get the human-edited ones
  ;; *among the published*, never a peek at an unpublished one that happens to
  ;; carry the mark.
  (let [{drafted :id} (create-as! true "Drafted by hand")
        {signed :id} (create-as! true "Signed and by hand")
        {agents :id} (create-as! false "Signed, by an agent")]
    (db.recipe/publish-recipe h/*ds* h/*user-id* signed)
    (db.recipe/publish-recipe h/*ds* h/*user-id* agents)
    (testing "the visitor's filtered shelf is the published human-edited row alone"
      (is (= [signed] (map :id (db.recipe/list-recipes h/*ds* db.recipe/visitor-scope
                                                       {:human-only? true})))))
    (testing "the human-edited draft stays outside it — the filter narrows the
              scope and cannot widen it"
      (let [ids (set (map :id (db.recipe/list-recipes h/*ds* db.recipe/visitor-scope
                                                      {:human-only? true})))]
        (is (false? (contains? ids drafted)))
        (is (false? (contains? ids agents)))))
    (testing "while the owner does see the draft under the same filter, so what
              the visitor is missing is the latch and not the mark"
      (is (= #{drafted signed} (set (map :id (db.recipe/list-recipes h/*ds* h/*user-id*
                                                                     {:human-only? true}))))))))

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
