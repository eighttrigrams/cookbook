(ns et.cb.read-attribution-db-test
  "Who read a Recipe, at the db layer: migration 013's two counters beside 008's
  total.

  > and break the reads down by human/machine as well

  `view-count-db-test` is the sibling and holds what a read must *not* touch; this
  holds what it now touches as well. The rule in one line: every read bumps the
  total, and the same read bumps exactly one of `human_reads` / `machine_reads` —
  the machine one only for a caller holding a machine token.

  **The three-into-two decision is what most of this file is about.** A write has
  two possible authors, because a visitor cannot write; a read has three sources,
  because 008 counts an anonymous stranger's read on purpose. So one of the three
  has to share a bucket, and the human's is the honest home — a person read it.
  Which means the attribution **cannot** come from `source-of`, whose silence means
  *machine*: the same absence means opposite things on the two paths, and a later
  reader who unified them would file every visitor's read under the agents. That is
  asserted here rather than only argued in a docstring, in
  `an-unattributed-read-is-a-humans-which-is-the-opposite-of-a-write`.

  The HTTP end — which request counts, and who the caller is taken to be — is
  `read-attribution-integration-test`'s."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.test-helpers :as h]))

(use-fixtures :each h/with-in-memory-db)

(defn- create! [title]
  (db.recipe/create-recipe h/*ds* h/*user-id*
                           {:title title :useful_when "when testing" :description "body v1"}))

(defn- counts [id]
  (select-keys (db.recipe/get-recipe h/*ds* h/*user-id* id)
               [:view_count :human_reads :machine_reads]))

(deftest a-new-recipe-starts-at-zero-in-all-three
  (let [{:keys [id]} (create! "Sourdough")]
    (is (= {:view_count 0 :human_reads 0 :machine_reads 0} (counts id))
        "013's DEFAULT 0 is an epoch, and a Recipe made after it starts level")))

(deftest a-human-read-and-a-machine-read-land-in-their-own-buckets
  (let [{:keys [id]} (create! "Sourdough")]
    (db.recipe/record-view! h/*ds* id false)
    (is (= {:view_count 1 :human_reads 1 :machine_reads 0} (counts id)))
    (db.recipe/record-view! h/*ds* id true)
    (is (= {:view_count 2 :human_reads 1 :machine_reads 1} (counts id)))
    (testing "and each further read moves exactly two numbers: the total and one
              bucket, never the other bucket"
      (db.recipe/record-view! h/*ds* id true)
      (db.recipe/record-view! h/*ds* id true)
      (is (= {:view_count 4 :human_reads 1 :machine_reads 3} (counts id))))))

(deftest the-total-is-the-sum-when-every-read-was-attributed
  ;; The invariant the badge leans on: with nothing predating the split, the two
  ;; buckets account for the total exactly — which is what lets the badge drop the
  ;; `of 212` and read as he asked for it.
  (let [{:keys [id]} (create! "Sourdough")]
    (dotimes [_ 5] (db.recipe/record-view! h/*ds* id false))
    (dotimes [_ 3] (db.recipe/record-view! h/*ds* id true))
    (let [{:keys [view_count human_reads machine_reads]} (counts id)]
      (is (= 8 view_count))
      (is (= view_count (+ human_reads machine_reads))))))

(deftest reads-counted-before-the-split-belong-to-neither-bucket
  ;; **The case the badge exists to be honest about.** 008 counts and 013
  ;; attributes, so a Recipe read before the second migration carries reads with no
  ;; reader recorded. Simulated the only way it can be — by moving the total on its
  ;; own, which is what the old one-column statement did — and the assertion is that
  ;; the remainder is *computable*, since that is what the UI renders as `of 212`.
  (let [{:keys [id]} (create! "Sourdough")]
    (h/bump-view-count-only! id 34)
    (is (= {:view_count 34 :human_reads 0 :machine_reads 0} (counts id))
        "the old reads are in the total and in neither bucket")
    (db.recipe/record-view! h/*ds* id false)
    (db.recipe/record-view! h/*ds* id true)
    (let [{:keys [view_count human_reads machine_reads]} (counts id)]
      (is (= 36 view_count))
      (is (= 1 human_reads))
      (is (= 1 machine_reads))
      (is (= 34 (- view_count human_reads machine_reads))
          "and the unattributed remainder is exactly the reads from before")
      (testing "which only ever shrinks *relative* to the total, since every new
                read lands in a bucket — it is never re-attributed and never grows"
        (dotimes [_ 10] (db.recipe/record-view! h/*ds* id false))
        (let [{:keys [view_count human_reads machine_reads]} (counts id)]
          (is (= 34 (- view_count human_reads machine_reads))))))))

(deftest an-unattributed-read-is-a-humans-which-is-the-opposite-of-a-write
  ;; **The inversion, pinned.** `source-of` reads the write paths' flag as
  ;; `(if human? "ui" "machine")` — silence is a machine. A read has no such
  ;; silence to interpret: the flag here says *is this a machine token*, and
  ;; everything else, the anonymous stranger included, is the human bucket. A
  ;; future unification of the two would flip this test.
  (let [{:keys [id]} (create! "Sourdough")]
    (testing "the visitor's read — no token, nobody signed in — is the human one"
      (db.recipe/record-view! h/*ds* id false)
      (is (= 1 (:human_reads (counts id))))
      (is (= 0 (:machine_reads (counts id)))))
    (testing "while an unattributed *write* is the machine's, on the same row, in
              the same test — the two live side by side so the difference cannot be
              read as an accident"
      (let [after (db.recipe/update-recipe h/*ds* h/*user-id* id
                                           {:description "written with nothing said"}
                                           nil {})]
        (is (= "machine" (:source after))
            "silence on a write is an agent; silence on a read is a person")))))

(deftest attribution-does-not-touch-modified-at-either
  ;; `recipe-views-do-not-touch-modified-at` is the sibling's and pins the total's
  ;; statement. This is the same assertion for the widened `:set` — the one edit
  ;; that could plausibly have dragged a `touch!` in with it — and it is here rather
  ;; than there because it is 013's risk and not 008's.
  (let [{:keys [id]} (create! "Baguette")]
    (h/backdate-modified-at! id "2020-01-01 00:00:00")
    (db.recipe/record-view! h/*ds* id true)
    (db.recipe/record-view! h/*ds* id false)
    (let [after (db.recipe/get-recipe h/*ds* h/*user-id* id)]
      (is (= 2 (:view_count after)) "both reads counted")
      (is (= 1 (:machine_reads after)))
      (is (= 1 (:human_reads after)))
      (is (= "2020-01-01 00:00:00" (:modified_at after))
          "and the stamp is byte-identical — a read still moves nothing a save
           guards on, however many columns it now bumps"))))

(deftest the-split-rides-on-the-lean-listing-row
  ;; The card that draws this badge is a collapsed card, which is a lean row: a
  ;; second fetch to say who read something would be a round trip per card.
  (let [{:keys [id]} (create! "Sourdough")]
    (db.recipe/record-view! h/*ds* id true)
    (db.recipe/record-view! h/*ds* id false)
    (let [row (first (db.recipe/list-recipes h/*ds* h/*user-id*))]
      (is (= id (:id row)))
      (is (= 2 (:view_count row)))
      (is (= 1 (:machine_reads row)))
      (is (= 1 (:human_reads row)))
      (is (not (contains? row :description)) "and still lean"))))

(deftest a-visitor-is-sent-the-total-and-not-the-split
  ;; **The audience decision, and it is a decision rather than an omission.** The
  ;; total is in a visitor's projection deliberately — it explains the order of the
  ;; shelf they are looking at. The split explains nothing about that order and
  ;; would instead say how much of the owner's traffic is his own agents, so it is
  ;; withheld the way `tags` is: the column is not named for them, so the key is
  ;; **absent** rather than zeroed. Absent and 0 are different answers, and a 0
  ;; would be a claim that nobody has read it.
  (let [{:keys [id]} (create! "Sourdough")
        visitor db.recipe/visitor-audience]
    (db.recipe/publish-recipe h/*ds* h/*user-id* id)
    (db.recipe/record-view! h/*ds* id false)
    (db.recipe/record-view! h/*ds* id true)
    (let [theirs (first (db.recipe/list-recipes h/*ds* visitor))
          mine (first (db.recipe/list-recipes h/*ds* h/*user-id*))]
      (is (= 2 (:view_count theirs)) "the total is theirs, as it always was")
      (is (false? (contains? theirs :human_reads)))
      (is (false? (contains? theirs :machine_reads)))
      (testing "and the owner does get both, so the absence above is the audience
                and not a projection that lost them for everybody"
        (is (= 1 (:human_reads mine)))
        (is (= 1 (:machine_reads mine)))))
    (testing "the same on the single-Recipe read, at ?detail=full — the widening
              axis and the audience axis are different, as they are for the tags"
      (let [theirs (db.recipe/get-recipe h/*ds* visitor id {:lean? false})]
        (is (= 2 (:view_count theirs)))
        (is (false? (contains? theirs :human_reads)))))))

(deftest the-ranking-is-on-the-total-and-the-split-does-not-move-it
  ;; **The trap the order names.** The shelf is `0.7 × view_count + 0.3 × version`,
  ;; on the owner's own weights, and he asked to *see* the breakdown rather than to
  ;; reorder his shelf. Two Recipes with the same total and opposite splits must
  ;; come back in the order the total and the version give them — a ranking that had
  ;; quietly moved onto `human_reads` would reverse this pair.
  (let [{mostly-machine :id} (create! "Read by the agents")
        {mostly-human :id} (create! "Read by hand")]
    (dotimes [_ 9] (db.recipe/record-view! h/*ds* mostly-machine true))
    (db.recipe/record-view! h/*ds* mostly-machine false)
    (dotimes [_ 8] (db.recipe/record-view! h/*ds* mostly-human false))
    (let [titles (mapv :title (db.recipe/list-recipes h/*ds* h/*user-id*))]
      (is (= ["Read by the agents" "Read by hand"] titles)
          "ten reads outrank eight whoever made them")
      (testing "and the loser is the one with more *human* reads, which is what
                makes this a test of the weights rather than of a coincidence"
        (let [rows (db.recipe/list-recipes h/*ds* h/*user-id*)]
          (is (= 1 (:human_reads (first rows))))
          (is (= 8 (:human_reads (second rows)))))))))
