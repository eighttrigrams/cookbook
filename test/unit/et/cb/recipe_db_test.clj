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

;; ---------------------------------------------------------------------------
;; per-version provenance
;;
;; `source` on the row for the current version and on each history row for the
;; superseded ones. Same `:human?` the mark above is set from — there is one way
;; of deciding who the caller is, and the token is the handler's half of it.

(defn- source-of [id]
  (:source (db.recipe/get-recipe h/*ds* h/*user-id* id)))

(defn- sources-by-version
  "version -> source over the whole ladder, newest and oldest alike, which is what
  makes 'this version was relabelled' visible at all."
  [id]
  (into {} (map (juxt :version :source) (:versions (versions-of id)))))

(defn- split-of
  "The badge's three counts as the listing serves them — from the listing, because
  that is the only read that carries them and the card is a collapsed row."
  [id]
  (-> (first (filter #(= id (:id %)) (db.recipe/list-recipes h/*ds* h/*user-id*)))
      (select-keys [:version :machine_versions :ui_versions :unrecorded_versions])))

(deftest archive-order-is-the-whole-design
  ;; The one bug this schema invites. `archive!` must write the *outgoing* row's
  ;; own source, not the source of the save displacing it. Backwards, an agent's
  ;; edit would retroactively relabel the owner's previous version as machine
  ;; work — plausible-looking in the UI and wrong everywhere.
  (let [{:keys [id]} (create-as! true "Written by hand, then by an agent")]
    (is (= "ui" (source-of id)) "v1 is the human's")
    (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "the agent's body"} nil
                             {:human? false})
    (testing "v1 still reads ui and v2 reads machine — each version keeps the label
              it was saved under"
      (is (= {1 "ui" 2 "machine"} (sources-by-version id))))
    (testing "and the other way round too: a human saving over a machine's version
              does not relabel it"
      (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "the owner's body"} nil
                               {:human? true})
      (is (= {1 "ui" 2 "machine" 3 "ui"} (sources-by-version id))))
    (testing "so a version's label never changes once written — the whole point of
              recording it per version"
      (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "and again"} nil
                               {:human? false})
      (is (= {1 "ui" 2 "machine" 3 "ui" 4 "machine"} (sources-by-version id))))))

(deftest a-machine-create-then-a-human-edit
  (let [{:keys [id]} (create-as! false "The agent's draft")]
    (is (= "machine" (source-of id)))
    (let [saved (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "body v2"} nil
                                         {:human? true})]
      (testing "the new version is the human's and the old one stays the agent's"
        (is (= "ui" (:source saved)))
        (is (= {1 "machine" 2 "ui"} (sources-by-version id))))
      (testing "which is one machine version and one ui version on the card"
        (is (= {:version 2 :machine_versions 1 :ui_versions 1 :unrecorded_versions 0}
               (split-of id)))))))

(deftest a-caller-that-says-nothing-leaves-the-version-unrecorded
  ;; The third bucket, reachable from the db layer only: every write through a
  ;; handler carries `:human?`, because the token always answers it. Stamping
  ;; 'machine' here would turn 'nobody said' into a claim about an agent.
  (let [{:keys [id]} (create! "Said nothing")]
    (is (nil? (source-of id)))
    (is (= {:version 1 :machine_versions 0 :ui_versions 0 :unrecorded_versions 1}
           (split-of id)))
    (testing "and a labelled save afterwards leaves the unrecorded version
              unrecorded rather than backfilling it"
      (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "body v2"} nil {:human? true})
      (is (= {1 nil 2 "ui"} (sources-by-version id)))
      (is (= {:version 2 :machine_versions 0 :ui_versions 1 :unrecorded_versions 1}
             (split-of id))))))

(deftest what-does-not-touch-the-source
  (testing "publishing: not a version at all, so there is no provenance in it to
            record — and relabelling the row would be relabelling somebody else's
            work"
    (let [{:keys [id]} (create-as! false "Signed but not written")]
      (db.recipe/publish-recipe h/*ds* h/*user-id* id)
      (is (= "machine" (source-of id)))
      (is (= 1 (:version (db.recipe/get-recipe h/*ds* h/*user-id* id))))
      (is (= {:version 1 :machine_versions 1 :ui_versions 0 :unrecorded_versions 0}
             (split-of id)))
      (testing "publishing twice is no different"
        (db.recipe/publish-recipe h/*ds* h/*user-id* id)
        (is (= "machine" (source-of id))))))

  (testing "a save that changes nothing: no history row, so no version — and the
            label would otherwise be the one thing a no-op changed"
    (let [{:keys [id]} (create-as! false "Unchanged")]
      (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "body v1"} nil {:human? true})
      (is (= "machine" (source-of id)))
      (is (= 0 (h/history-row-count id)))
      (is (= {1 "machine"} (sources-by-version id)))))

  (testing "a refused save: a stale modified_at writes nothing at all"
    (let [{:keys [id]} (create-as! true "Raced")]
      (is (nil? (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "x"}
                                         "1999-01-01 00:00:00" {:human? false})))
      (is (= "ui" (source-of id))))))

(deftest the-three-counts-sum-to-the-version
  ;; A real invariant and not a coincidence: history holds versions 1..N-1, one row
  ;; each, and the row itself is N. A split that stopped summing would mean the
  ;; badge was counting something other than versions.
  (let [{:keys [id]} (create-as! true "Much revised")]
    (doseq [[i human?] (map-indexed vector [false true false false true nil])]
      (if (nil? human?)
        (db.recipe/update-recipe h/*ds* h/*user-id* id {:description (str "body " i)} nil)
        (db.recipe/update-recipe h/*ds* h/*user-id* id {:description (str "body " i)} nil
                                 {:human? human?}))
      (let [{:keys [version machine_versions ui_versions unrecorded_versions]} (split-of id)]
        (is (= version (+ machine_versions ui_versions unrecorded_versions))
            (str "the split must sum to the version at v" version))))
    (testing "and the tally matches the ladder the version list shows, one entry
              per version — the counts are aggregated from the same columns"
      (is (= {:version 7 :machine_versions 3 :ui_versions 3 :unrecorded_versions 1}
             (split-of id)))
      (is (= {1 "ui" 2 "machine" 3 "ui" 4 "machine" 5 "machine" 6 "ui" 7 nil}
             (sources-by-version id))))))

(deftest a-recipe-with-no-history-counts-its-one-version-once
  ;; The LEFT JOIN's phantom row: with no history rows at all the join still yields
  ;; one all-NULL row per recipe, which an unguarded `source IS NULL` count would
  ;; read as an extra unrecorded version.
  (let [{by-hand :id} (create-as! true "Fresh, by hand")
        {said-nothing :id} (create! "Fresh, unlabelled")]
    (is (= {:version 1 :machine_versions 0 :ui_versions 1 :unrecorded_versions 0}
           (split-of by-hand)))
    (is (= {:version 1 :machine_versions 0 :ui_versions 0 :unrecorded_versions 1}
           (split-of said-nothing)))))

(deftest the-split-does-not-widen-the-lean-projection
  ;; The counts come from a join on `recipe_history`, which has a `description`
  ;; column of its own — so this is the read that would have leaked a body through
  ;; the back door.
  (let [{:keys [id]} (create-as! true "Brioche")]
    (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "body v2"} nil {:human? true})
    (testing "a lean listing still has no description key at all"
      (is (every? #(false? (contains? % :description))
                  (db.recipe/list-recipes h/*ds* h/*user-id*))))
    (testing "and the join does not duplicate the row it counted history for"
      (is (= 1 (count (db.recipe/list-recipes h/*ds* h/*user-id*)))))
    (testing "the full projection is still the recipe's own body, not a history row's"
      (is (= "body v2" (:description (first (db.recipe/list-recipes h/*ds* h/*user-id*
                                                                   {:lean? false}))))))
    (testing "and the counts are there either way — both versions here are the
              owner's, the create and the save"
      (is (= 2 (:ui_versions (first (db.recipe/list-recipes h/*ds* h/*user-id*
                                                            {:lean? false}))))))))

(deftest the-mark-and-the-labels-cannot-disagree
  ;; 004's bit is kept rather than derived — a listing filter that had to aggregate
  ;; over history is what putting the version on the row avoided — so the two have
  ;; to be written by the same save. `has_human_edit` is true exactly when some
  ;; version reads 'ui'.
  (let [agrees? (fn [id]
                  (let [ui? (some #{"ui"} (vals (sources-by-version id)))]
                    (= (= 1 (:has_human_edit (db.recipe/get-recipe h/*ds* h/*user-id* id)))
                       (boolean ui?))))]
    (testing "a machine's create: no ui version, no mark"
      (let [{:keys [id]} (create-as! false "The agent's")]
        (is (agrees? id))
        (is (= 0 (:ui_versions (split-of id))))))

    (testing "a human's create: a ui version and the mark"
      (let [{:keys [id]} (create-as! true "The owner's")]
        (is (agrees? id))
        (is (= 1 (:ui_versions (split-of id))))))

    (testing "a caller that said nothing: neither, which is why NULL is not
              'machine' — it is the same silence the 0 in the bit is"
      (let [{:keys [id]} (create! "Unlabelled")]
        (is (agrees? id))
        (is (= 0 (:ui_versions (split-of id))))))

    (testing "and they stay in step across a whole ladder of saves, including the
              machine save after the human one — where the mark latches and the
              label does not"
      (let [{:keys [id]} (create-as! false "Much revised")]
        (doseq [human? [false true false true false]]
          (db.recipe/update-recipe h/*ds* h/*user-id* id
                                   {:description (str "body " (rand-int 1000000) human?)}
                                   nil {:human? human?})
          (is (agrees? id)))
        (testing "the last save was a machine's, so the current label is machine
                  while the mark stays 1 — the bit is about the Recipe, the label
                  about the version"
          (is (= "machine" (source-of id)))
          (is (= 1 (:has_human_edit (db.recipe/get-recipe h/*ds* h/*user-id* id))))
          (is (pos? (:ui_versions (split-of id)))))))))

(deftest every-version-entry-carries-a-source-key
  ;; The version list is what part 2's diff viewer reads, so the key has to be
  ;; there on every entry — including the ones whose value is nil, and including
  ;; the current row, whose label comes off the row rather than off a history row.
  (let [{:keys [id]} (create! "Unlabelled first")]
    (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "body v2"} nil {:human? false})
    (db.recipe/update-recipe h/*ds* h/*user-id* id {:description "body v3"} nil {:human? true})
    (let [versions (:versions (versions-of id))]
      (is (= 3 (count versions)))
      (is (every? #(contains? % :source) versions))
      (testing "the current entry's label is the row's"
        (is (= "ui" (:source (first versions))))
        (is (true? (:current (first versions)))))
      (testing "the history entries' labels are their own, oldest included"
        (is (= "machine" (:source (second versions))))
        (is (nil? (:source (last versions))))
        (is (contains? (last versions) :source))))))

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
