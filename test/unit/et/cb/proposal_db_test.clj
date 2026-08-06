(ns et.cb.proposal-db-test
  "The proposal store and the gate that decides who needs one, at the db layer.

  The HTTP half — which status an agent gets, how `?overwrite=true` is read, who may
  approve — is in `proposals-integration-test`. What is here is the two things the
  routes are built on: **the gate is `machine_versions = version`**, computed from the
  same expression the card's badge uses, and **a proposal's lifecycle keeps its inbox
  entry in step with it** at every step."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.db.event :as db.event]
            [et.cb.db.proposal :as db.proposal]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.test-helpers :as h]))

(use-fixtures :each h/with-in-memory-db)

(defn- create!
  [title human?]
  (db.recipe/create-recipe h/*ds* h/*user-id*
                           {:title title :useful_when "when testing"
                            :description "body v1"}
                           {:human? human?}))

(defn- save!
  [id fields human?]
  (db.recipe/update-recipe h/*ds* h/*user-id* id fields nil {:human? human?}))

(defn- machine-only? [id]
  (db.recipe/machine-only? h/*ds* h/*user-id* id))

(defn- events-of [recipe-id]
  (h/event-rows recipe-id))

;; ---------------------------------------------------------------------------
;; the gate

(deftest the-gate-is-machine-versions-equals-version
  (testing "a Recipe the agents wrote throughout is theirs to write"
    (let [{:keys [id]} (create! "All theirs" false)]
      (is (true? (machine-only? id)))
      (save! id {:description "body v2"} false)
      (is (true? (machine-only? id)) "and stays theirs however many times")))

  (testing "one save of his own closes it"
    (let [{:keys [id]} (create! "Theirs, then his" false)]
      (save! id {:description "his correction"} true)
      (is (false? (machine-only? id)))))

  (testing "**and it stays closed once his version is superseded**, which is the
            case a predicate reading the row's own `source` would get wrong: the
            agents write again, the row says `machine`, and his version is two back
            in the history where the gate still has to see it"
    (let [{:keys [id]} (create! "His, in the middle" false)]
      (save! id {:description "his correction"} true)
      (save! id {:description "the agent's again"} false)
      (is (= "machine" (:source (db.recipe/get-recipe h/*ds* h/*user-id* id)))
          "the row's own label is the agent's")
      (is (false? (machine-only? id))
          "and the gate is still closed, because the whole history is what it reads")))

  (testing "a Recipe he wrote is his from the start"
    (let [{:keys [id]} (create! "His" true)]
      (is (false? (machine-only? id)))))

  (testing "and an id nobody can see is not writable either"
    (is (nil? (machine-only? 999999)))))

(deftest the-gate-and-the-card-are-computed-from-one-expression
  ;; The order's requirement: reuse `source-split-columns` rather than write a second
  ;; count. This is what would catch the two drifting — the numbers the owner reads on
  ;; the card and the number the gate acts on have to be the same numbers.
  (let [{:keys [id]} (create! "Much revised" false)]
    (save! id {:description "v2 theirs"} false)
    (save! id {:description "v3 his"} true)
    (save! id {:description "v4 theirs"} false)
    (let [listed (first (filter #(= id (:id %))
                                (db.recipe/list-recipes h/*ds* h/*user-id*)))
          split (db.recipe/version-split h/*ds* h/*user-id* id)]
      (is (= (select-keys listed [:version :machine_versions :ui_versions])
             (select-keys split [:version :machine_versions :ui_versions]))
          "the single-row split and the listing's are the same three numbers")
      (is (= {:version 4 :machine_versions 3 :ui_versions 1}
             (select-keys split [:version :machine_versions :ui_versions])))
      (is (false? (machine-only? id))
          "which is what the gate reads: 3 of 4 are machine, so it is not theirs"))))

(deftest the-gate-is-not-has-human-edit
  ;; `has_human_edit` and the gate agree today, because migration 010 brought the bit
  ;; up wherever a version reads `ui`. They are still different questions, and the
  ;; test that keeps them from being collapsed is this one: approving an agent's
  ;; proposal writes a `machine` version and leaves the bit alone, so a Recipe can
  ;; carry the bit while its newest version is an agent's — and the gate has to stay
  ;; closed for it.
  (let [{:keys [id]} (create! "His, then approved agent text" true)]
    (is (= 1 (:has_human_edit (db.recipe/get-recipe h/*ds* h/*user-id* id))))
    (is (false? (machine-only? id)))
    (let [proposal (db.proposal/propose! h/*ds* h/*user-id* id 1
                                         {:title "His, then approved agent text"
                                          :useful_when "when testing"
                                          :description "the agent's body"})]
      (db.recipe/approve-proposal! h/*ds* h/*user-id*
                                   (db.proposal/by-event h/*ds* h/*user-id*
                                                         (:id (first (events-of id))))))
    (let [recipe (db.recipe/get-recipe h/*ds* h/*user-id* id)]
      (is (= 2 (:version recipe)))
      (is (= "machine" (:source recipe)) "the approved version is the agent's text")
      (is (= 1 (:has_human_edit recipe))
          "the bit is untouched — approving text is not writing it")
      (is (false? (machine-only? id))
          "and the gate is still closed, so the next agent edit still has to ask"))))

;; ---------------------------------------------------------------------------
;; content-would-change?

(deftest content-would-change-reads-the-one-merge-rule
  (let [{:keys [id]} (create! "Sourdough" false)
        would? (fn [fields] (db.recipe/content-would-change? h/*ds* h/*user-id* id fields))]
    (is (false? (would? {})) "an empty save changes nothing")
    (is (false? (would? {:title "Sourdough"})) "the same title back is a no-op")
    (is (false? (would? {:description "body v1"})))
    (is (false? (would? {:title "Sourdough" :useful_when "when testing"
                         :description "body v1"}))
        "all three, unchanged, is still a no-op — which is what stops a machine PUT
         becoming a pending proposal of nothing")
    (is (true? (would? {:description "different"})))
    (is (true? (would? {:title "Sourdough starter"})))
    (testing "the trim is the merge rule's, not this function's"
      (is (false? (would? {:title "  Sourdough  "}))))
    (testing "filing is not content, so it does not count as a change here"
      (is (false? (would? {:tags "bread"})))
      (is (false? (would? {:scope_ids []}))))
    (testing "and an id nobody can see answers nil rather than true"
      (is (nil? (db.recipe/content-would-change? h/*ds* h/*user-id* 999999 {:title "x"}))))))

;; ---------------------------------------------------------------------------
;; the store, and the inbox entry that goes with it

(deftest a-proposal-writes-one-inbox-entry
  (let [{:keys [id]} (create! "Half his" false)
        _ (save! id {:description "his correction"} true)
        before (count (events-of id))
        proposal (db.proposal/propose! h/*ds* h/*user-id* id 2
                                       {:title "Half his" :description "proposed body"})]
    (is (some? (:id proposal)))
    (is (= 2 (:base_version proposal)) "written against the version it read")
    (is (= "proposed body" (:description proposal)))
    (let [entry (last (events-of id))]
      (is (= (inc before) (count (events-of id))) "exactly one new entry")
      (is (= "proposed" (:kind entry)))
      (is (= (:id proposal) (:proposal_id entry)) "and it names the proposal")
      (is (= 2 (:version entry)) "at the base version")
      (is (= 0 (:seen entry)) "unseen, which is what puts it in his queue"))
    (testing "and it is the pending proposal for that Recipe"
      (is (= (:id proposal) (:id (db.proposal/pending-for h/*ds* h/*user-id* id)))))))

(deftest an-overwrite-keeps-the-entry-and-its-place-in-the-queue
  ;; The property he would notice if it broke: an agent revising three times must be
  ;; one thing to answer, and must not walk to the bottom of a queue worked through
  ;; oldest-first.
  (let [{:keys [id]} (create! "Half his" false)
        _ (save! id {:description "his correction"} true)
        first-proposal (db.proposal/propose! h/*ds* h/*user-id* id 2
                                             {:title "Half his" :description "take one"})
        entry-before (last (events-of id))
        ;; a second Recipe writes an entry *after* it, so "kept its place" means
        ;; something rather than being true of a one-entry queue
        {other :id} (create! "Something else" false)
        queue-before (mapv :id (db.event/list-unseen h/*ds* h/*user-id*))]
    (dotimes [n 2]
      (db.proposal/propose! h/*ds* h/*user-id* id 2
                            {:title "Half his" :description (str "take " (+ n 2))}))
    (testing "still one proposal, updated in place"
      (is (= 1 (count (filter #(= "proposed" (:kind %)) (events-of id)))))
      (let [pending (db.proposal/pending-for h/*ds* h/*user-id* id)]
        (is (= (:id first-proposal) (:id pending)) "the same row, not a new one")
        (is (= "take 3" (:description pending)) "carrying the newest text")
        (is (= (:created_at first-proposal) (:created_at pending))
            "`created_at` does not move — it is this entry's place in the queue")))
    (testing "and the entry kept its id, so the queue reads exactly as it did"
      (let [entry-after (first (filter #(= "proposed" (:kind %)) (events-of id)))]
        (is (= (:id entry-before) (:id entry-after)))
        (is (= (:created_at entry-before) (:created_at entry-after))))
      (is (= queue-before (mapv :id (db.event/list-unseen h/*ds* h/*user-id*)))
          "including the entry for the other Recipe, which is still behind it"))
    (testing "the base version moves with the text, though: three revisions against a
              Recipe that has meanwhile moved on are proposals against the new
              version, and saying otherwise would overstate how stale they are"
      (save! id {:description "his second correction"} true)
      (db.proposal/propose! h/*ds* h/*user-id* id 3 {:title "Half his"
                                                    :description "take four"})
      (is (= 3 (:base_version (db.proposal/pending-for h/*ds* h/*user-id* id))))
      (is (= 3 (:version (first (filter #(= "proposed" (:kind %)) (events-of id)))))
          "and the entry says the same, having kept its id while doing so"))
    ;; the other Recipe is only here to give the queue a second member
    (is (some? other))))

(deftest resolving-a-proposal-marks-its-entry-seen-and-frees-the-recipe
  (let [{:keys [id]} (create! "Half his" false)
        _ (save! id {:description "his correction"} true)
        _ (db.proposal/propose! h/*ds* h/*user-id* id 2 {:title "Half his"
                                                         :description "proposed"})
        entry (first (filter #(= "proposed" (:kind %)) (events-of id)))
        proposal (db.proposal/by-event h/*ds* h/*user-id* (:id entry))]
    (is (= (:id entry) (:event_id proposal)) "by-event finds the pair in one read")
    (is (some? (db.proposal/pending-for h/*ds* h/*user-id* id)))
    (is (= 1 (count (filter #(= "proposed" (:kind %))
                            (db.event/list-unseen h/*ds* h/*user-id*)))))

    (h/in-transaction (fn [tx] (db.proposal/resolve! tx h/*user-id* proposal "dismissed")))

    (testing "the entry leaves the queue in the same breath as the proposal closes —
              the invariant that a `proposed` entry is unseen exactly while its
              proposal is unresolved"
      (is (empty? (filter #(= "proposed" (:kind %))
                          (db.event/list-unseen h/*ds* h/*user-id*))))
      (is (= 1 (:seen (db.event/get-event h/*ds* h/*user-id* (:id entry))))))
    (testing "the proposal is resolved and stays on the table — a dismissal is a fact
              about what an agent tried"
      (is (nil? (db.proposal/pending-for h/*ds* h/*user-id* id)))
      (let [resolved (db.proposal/by-event h/*ds* h/*user-id* (:id entry))]
        (is (= "dismissed" (:resolution resolved)))
        (is (some? (:resolved_at resolved)))))
    (testing "and the Recipe is free for the next proposal, which the partial index
              would have refused a moment ago"
      (is (some? (db.proposal/propose! h/*ds* h/*user-id* id 2
                                       {:title "Half his" :description "second attempt"})))
      (is (= 2 (count (filter #(= "proposed" (:kind %)) (events-of id))))
          "and that one is a new entry, because the old one was answered"))))

(deftest approving-writes-the-agents-text-as-the-next-version
  (let [{:keys [id]} (create! "Half his" false)
        _ (save! id {:title "Half his" :description "his correction"} true)
        outgoing (db.recipe/get-recipe h/*ds* h/*user-id* id {:lean? false})
        _ (db.proposal/propose! h/*ds* h/*user-id* id 2
                                {:title "The agent's title"
                                 :useful_when "when the agent says"
                                 :description "the agent's body"})
        entry (first (filter #(= "proposed" (:kind %)) (events-of id)))
        proposal (db.proposal/by-event h/*ds* h/*user-id* (:id entry))
        result (db.recipe/approve-proposal! h/*ds* h/*user-id* proposal)]
    (testing "the three fields land as the new version"
      (is (= 3 (:version result)))
      (is (= "The agent's title" (:title result)))
      (is (= "when the agent says" (:useful_when result)))
      (is (= "the agent's body" (:description result))))
    (testing "labelled `machine`, because the agent wrote it"
      (is (= "machine" (:source result))))
    (testing "and `has_human_edit` is untouched: approving text is not writing it,
              which is `publish-recipe`'s argument met a second time"
      (is (= (:has_human_edit outgoing) (:has_human_edit result)))
      (is (= 1 (:has_human_edit result))))
    (testing "the outgoing version is archived with **its own** source, not with the
              approval's — the `archive-order-is-the-whole-design` property, which a
              second write path could break on its own"
      (is (= {1 "machine" 2 "ui" 3 "machine"}
             (into {} (map (juxt :version :source)
                           (:versions (db.recipe/list-versions h/*ds* h/*user-id* id))))))
      (is (= "his correction"
             (:description (second (:versions (db.recipe/list-versions h/*ds* h/*user-id* id)))))))
    (testing "no `modified` event is written: the proposal's own entry is the record,
              and it has just been resolved. The queue holds the agent's `created`
              entry and the `proposed` one and nothing else — his own save in between
              never made one, and neither did the approval"
      (is (= ["created" "proposed"] (mapv :kind (events-of id))))
      (is (= ["created"] (mapv :kind (db.event/list-unseen h/*ds* h/*user-id*)))
          "and the `proposed` entry is the one that left the queue"))
    (testing "and the proposal is resolved `approved`"
      (is (nil? (db.proposal/pending-for h/*ds* h/*user-id* id)))
      (is (= "approved" (:resolution (db.proposal/by-event h/*ds* h/*user-id* (:id entry))))))))

(deftest approving-a-proposal-whose-recipe-is-gone-answers-nil
  (let [{:keys [id]} (create! "Doomed" false)
        _ (save! id {:description "his correction"} true)
        _ (db.proposal/propose! h/*ds* h/*user-id* id 2 {:title "Doomed"
                                                         :description "proposed"})
        entry (first (filter #(= "proposed" (:kind %)) (events-of id)))
        proposal (db.proposal/by-event h/*ds* h/*user-id* (:id entry))]
    (db.recipe/delete-recipe h/*ds* h/*user-id* id {:human? true})
    (is (nil? (db.recipe/approve-proposal! h/*ds* h/*user-id* proposal))
        "there is nothing to write it onto")))

(deftest deleting-a-recipe-closes-its-pending-proposal
  ;; The opposite call from the events, which are left behind: an event records that
  ;; something happened, a proposal is a question, and a question about a Recipe that
  ;; no longer exists cannot be answered. Left pending it would sit at the top of his
  ;; queue unanswerable and go on blocking the agent that filed it.
  (let [{:keys [id]} (create! "Doomed" false)
        _ (save! id {:description "his correction"} true)
        _ (db.proposal/propose! h/*ds* h/*user-id* id 2 {:title "Doomed"
                                                         :description "proposed"})
        entry (first (filter #(= "proposed" (:kind %)) (events-of id)))]
    (is (= 1 (count (filter #(= "proposed" (:kind %))
                            (db.event/list-unseen h/*ds* h/*user-id*)))))

    (db.recipe/delete-recipe h/*ds* h/*user-id* id {:human? true})

    (testing "the proposal is closed and its entry has left the queue"
      (is (nil? (db.proposal/pending-for h/*ds* h/*user-id* id)))
      (is (= 1 (:seen (db.event/get-event h/*ds* h/*user-id* (:id entry)))))
      (is (empty? (filter #(= "proposed" (:kind %))
                          (db.event/list-unseen h/*ds* h/*user-id*)))))
    (testing "with no resolution word, because he decided nothing — the Recipe went"
      (is (nil? (:resolution (db.proposal/by-event h/*ds* h/*user-id* (:id entry)))))
      (is (some? (:resolved_at (db.proposal/by-event h/*ds* h/*user-id* (:id entry))))))
    (testing "and the row is kept: what an agent tried is a fact even now"
      (is (some? (db.proposal/by-event h/*ds* h/*user-id* (:id entry)))))))

;; ---------------------------------------------------------------------------
;; what the reads say

(deftest a-pending-proposal-shows-as-pending-on-the-reads-and-changes-nothing-else
  (let [{:keys [id]} (create! "Half his" false)
        _ (save! id {:description "his correction"} true)
        before (db.recipe/get-recipe h/*ds* h/*user-id* id {:lean? false})
        listed-before (first (filter #(= id (:id %))
                                     (db.recipe/list-recipes h/*ds* h/*user-id*)))]
    ;; 0 and 1 rather than false and true, like `published` and every other flag this
    ;; schema serves: the JSON carries what SQLite holds, and the client compares
    ;; against 1 — see the comment on `card` in `views/recipes.cljs`, where 0 being
    ;; truthy in cljs is exactly why the comparison is spelled out.
    (is (= 0 (:pending listed-before)) "nothing waiting yet")

    (db.proposal/propose! h/*ds* h/*user-id* id 2 {:title "Rewritten"
                                                   :description "the agent's body"})

    (testing "the flag turns on, on the listing and on the single read"
      (is (= 1 (:pending (first (filter #(= id (:id %))
                                        (db.recipe/list-recipes h/*ds* h/*user-id*))))))
      (is (= 1 (:pending (db.recipe/get-recipe h/*ds* h/*user-id* id)))))
    (testing "**and nothing else about the Recipe moved**: the row is untouched by a
              proposal, which is exactly what he asked for — it keeps showing the
              version before"
      (is (= (dissoc before :pending)
             (dissoc (db.recipe/get-recipe h/*ds* h/*user-id* id {:lean? false}) :pending)))
      (is (= 2 (:version (db.recipe/get-recipe h/*ds* h/*user-id* id))))
      (is (= "his correction"
             (:description (db.recipe/get-recipe h/*ds* h/*user-id* id {:lean? false})))))
    (testing "and the version history says nothing about it either — a proposal is
              not a version"
      (is (= 2 (:total (db.recipe/list-versions h/*ds* h/*user-id* id)))))
    (testing "the provenance counts are not multiplied by the second table, which is
              what a LEFT JOIN instead of an EXISTS would have done"
      (let [row (first (filter #(= id (:id %)) (db.recipe/list-recipes h/*ds* h/*user-id*)))]
        (is (= 2 (:version row)))
        (is (= 1 (:machine_versions row)))
        (is (= 1 (:ui_versions row)))))))

(deftest a-visitors-projection-does-not-name-pending
  ;; Same rule as the tags and the Scopes: absent rather than false. Whether an agent
  ;; is waiting to rewrite something is the owner's business, and an empty answer
  ;; would still be an answer.
  (let [{:keys [id]} (create! "Published, and proposed against" false)]
    (save! id {:description "his correction"} true)
    (db.recipe/publish-recipe h/*ds* h/*user-id* id)
    (db.proposal/propose! h/*ds* h/*user-id* id 2 {:title "Rewritten"
                                                   :description "the agent's"})
    (let [rows (db.recipe/list-recipes h/*ds* db.recipe/visitor-audience)]
      (is (= 1 (count rows)) "the row is there to ask about")
      (is (false? (contains? (first rows) :pending))))
    (is (false? (contains? (db.recipe/get-recipe h/*ds* db.recipe/visitor-audience id)
                           :pending)))
    (testing "while the owner sees it"
      (is (= 1 (:pending (db.recipe/get-recipe h/*ds* h/*user-id* id)))))))

(deftest attaching-proposals-to-a-queue-reads-both-texts
  (let [{:keys [id]} (create! "Sourdough" false)
        _ (save! id {:title "Sourdough starter" :description "his body"} true)
        _ (db.proposal/propose! h/*ds* h/*user-id* id 2
                                {:title "Sourdough, revised"
                                 :useful_when "when the agent says"
                                 :description "the agent's body"})
        entries (db.proposal/attach-to-events h/*ds* h/*user-id*
                                              (db.event/list-unseen h/*ds* h/*user-id*))
        proposed (first (filter #(= "proposed" (:kind %)) entries))
        {:keys [proposal]} proposed]
    (testing "the proposed text and the Recipe's current text are both on the entry,
              because reviewing a proposal means reading a diff against what the
              Recipe says now"
      (is (= "Sourdough, revised" (:title proposal)))
      (is (= "the agent's body" (:description proposal)))
      (is (= "Sourdough starter" (:current_title proposal)))
      (is (= "his body" (:current_description proposal))))
    (testing "with both version numbers, which is what says the two are not the same
              thing"
      (is (= 2 (:base_version proposal)))
      (is (= 2 (:recipe_version proposal))))
    (testing "and reading the queue counts no consumption: a proposal review is not
              somebody using the Recipe, and `view_count` ranks the shelf"
      (is (= 0 (:view_count (db.recipe/get-recipe h/*ds* h/*user-id* id)))))
    (testing "entries that are not proposals are handed back untouched"
      (is (every? #(false? (contains? % :proposal))
                  (filter #(not= "proposed" (:kind %)) entries))))))

(deftest a-proposal-whose-recipe-is-gone-still-renders
  ;; The LEFT JOIN in `attach-to-events`: the entry has to come back and be able to
  ;; say why it cannot be approved, rather than disappearing from the queue.
  (let [{:keys [id]} (create! "Doomed" false)
        _ (save! id {:description "his correction"} true)
        _ (db.proposal/propose! h/*ds* h/*user-id* id 2 {:title "Rewritten"
                                                         :description "the agent's"})
        entry-id (:id (first (filter #(= "proposed" (:kind %)) (events-of id))))]
    ;; reach past `delete-recipe`, which resolves the proposal — this is the state a
    ;; queue would be in if a Recipe went missing any other way
    (h/delete-recipe-row! id)
    (let [entries (db.proposal/attach-to-events h/*ds* h/*user-id*
                                                (db.event/list-unseen h/*ds* h/*user-id*))
          proposed (first (filter #(= entry-id (:id %)) entries))]
      (is (some? (:proposal proposed)) "the entry still carries its proposed text")
      (is (= "Rewritten" (:title (:proposal proposed))))
      (is (nil? (:recipe_version (:proposal proposed)))
          "and says there is no current version to diff against")
      (is (nil? (:current_title (:proposal proposed)))))))

(deftest one-owners-proposals-are-their-own
  (let [{:keys [id]} (create! "Mine" false)]
    (save! id {:description "his correction"} true)
    (db.proposal/propose! h/*ds* h/*user-id* id 2 {:title "Mine" :description "proposed"})
    (is (some? (db.proposal/pending-for h/*ds* h/*user-id* id)))
    (is (nil? (db.proposal/pending-for h/*ds* (inc h/*user-id*) id))
        "another owner cannot see it")
    (let [entry (first (filter #(= "proposed" (:kind %)) (events-of id)))]
      (is (nil? (db.proposal/by-event h/*ds* (inc h/*user-id*) (:id entry)))
          "nor reach it through the entry"))))
