(ns et.cb.proposals-integration-test
  "Proposals over HTTP: what an agent gets told, what the owner may do about it, and
  what neither of them can reach.

  The gate itself and the store are covered at the db layer (`proposal-db-test`).
  What is only testable here is the conversation: which status an agent meets, how the
  two 409s are told apart, that `?overwrite=true` is read the way this app reads every
  such parameter, and that approving is the owner's alone.

  **A pending proposal must be invisible to every read**, which is the promise he
  asked for in as many words — *when another agent displays it, or lists recipes, it
  will continue to show the version before that* — so that is asserted across the
  whole read surface rather than on one endpoint."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.integration-helpers :as h :refer [GET-json POST-json PUT-json]]))

(use-fixtures :each h/with-integration-db)

(defn- machine-opts [] {:token (h/machine-token-for h/*user-id*)})

(defn- machine [method path & [body]]
  (h/API method path (cond-> (machine-opts) body (assoc :body body))))

(defn- his-recipe!
  "A Recipe with one of his own versions in it, which is what closes the gate."
  [title]
  (:body (POST-json "/api/recipes" {:title title :useful_when "when testing"
                                    :description "his body"})))

(defn- agents-recipe! [title]
  (:body (machine :post "/api/recipes" {:title title :useful_when "when testing"
                                        :description "the agent's body"})))

(defn- inbox [] (:body (GET-json "/api/inbox")))

(defn- latest-proposal-entry []
  (last (filter #(= "proposed" (:kind %)) (inbox))))

(defn- listed [id]
  (first (filter #(= id (:id %)) (:body (GET-json "/api/recipes")))))

;; ---------------------------------------------------------------------------
;; which writes become proposals

(deftest a-machine-edit-of-his-text-is-accepted-not-applied
  (let [{:keys [id]} (his-recipe! "His own")
        resp (machine :put (str "/api/recipes/" id)
                      {:title "The agent's title" :description "the agent's body"})]
    (testing "202, and the body says both things: what is pending and what the Recipe
              still is"
      (is (= 202 (:status resp)))
      (is (= "The agent's title" (:title (:pending (:body resp)))))
      (is (= "the agent's body" (:description (:pending (:body resp)))))
      (is (= 1 (:base_version (:pending (:body resp)))))
      (is (some? (:created_at (:pending (:body resp)))))
      (is (= "His own" (:title (:recipe (:body resp)))))
      (is (= "his body" (:description (:recipe (:body resp))))))
    (testing "and the Recipe really is untouched"
      (is (= "His own" (:title (listed id))))
      (is (= 1 (:version (listed id))))
      (is (= 1 (:total (:body (GET-json (str "/api/recipes/" id "/versions")))))))))

(deftest a-partial-proposal-keeps-the-fields-it-did-not-send
  ;; **A proposal is a proposed version, so it is always all three fields**, and which
  ;; three is decided by this route's first sentence: a field you leave out keeps its
  ;; current value. A PUT that renames a Recipe proposes the new name and *the Recipe's
  ;; own* useful-when and body, not two empty strings.
  ;;
  ;; Asserted over every partial shape and not just the one that broke, because the way
  ;; it broke was not about a field: the write path read the row with the **lean**
  ;; projection — the one that deliberately carries no `description` — and then merged
  ;; the payload by hand, so `description` was the field the merge could not see.
  ;; `db.recipe/merge-content` owns the absent-keeps rule and is what the handler asks
  ;; now; this is the test that says so from the outside.
  ;;
  ;; Both reasons a machine PUT becomes a proposal are covered, because they reach the
  ;; same code by different conditions: his writing in the history, and the latch.
  (doseq [state [:unpublished :published]
          [field value] [[:title "The agent's title"]
                         [:useful_when "when the agent says so"]
                         [:description "the agent's body"]]]
    (testing (str "a machine PUT carrying only " (name field) ", on " (name state)
                  " writing of his")
      (let [title (str "Partial " (name field) " " (name state))
            {:keys [id]} (his-recipe! title)
            _ (when (= :published state)
                (is (= 200 (:status (POST-json (str "/api/recipes/" id "/publish") {})))))
            expected (assoc {:title title :useful_when "when testing"
                             :description "his body"}
                            field value)
            resp (machine :put (str "/api/recipes/" id) {field value})]
        (is (= 202 (:status resp)))
        (testing "the 202 hands back a whole version, not one field and two holes"
          (is (= expected (select-keys (:pending (:body resp))
                                       [:title :useful_when :description]))))
        (testing "and so does the entry he will answer — read the proposal, not the row:
                  the row is untouched either way, which is what let this through"
          (let [p (:proposal (latest-proposal-entry))]
            (is (= expected (select-keys p [:title :useful_when :description])))
            (testing "with the Recipe's current text beside it for the diff"
              (is (= title (:current_title p)))
              (is (= "when testing" (:current_useful_when p)))
              (is (= "his body" (:current_description p))))))
        (testing "and approving it changes the one field and leaves the other two"
          (is (= 200 (:status (h/API :post (str "/api/inbox/"
                                                (:id (latest-proposal-entry)) "/approve")
                                     {}))))
          (is (= expected
                 (select-keys (:body (GET-json (str "/api/recipes/" id "?detail=full")))
                              [:title :useful_when :description])))))))
  (testing "and the title is trimmed on the way in, because the *merge rule* trims —
            which is the assertion that says the payload really goes through it and is
            not merged again next to it"
    (let [{:keys [id]} (his-recipe! "Trimmed")
          resp (machine :put (str "/api/recipes/" id) {:title "  The agent's title  "})]
      (is (= 202 (:status resp)))
      (is (= "The agent's title" (:title (:pending (:body resp))))))))

(deftest a-machine-edit-of-its-own-text-still-writes-straight-through
  ;; The property this app exists for, and the one the approval rule must not have
  ;; quietly turned into a workflow for everything.
  (let [{:keys [id]} (agents-recipe! "The agents' own")
        resp (machine :put (str "/api/recipes/" id) {:description "rewritten"})]
    (is (= 200 (:status resp)))
    (is (= 2 (:version (:body resp))))
    (is (= "rewritten" (:description (:body (GET-json (str "/api/recipes/" id "?detail=full"))))))
    (is (= 0 (:pending (listed id))) "and nothing is waiting")))

(deftest a-no-op-machine-save-on-his-text-proposes-nothing
  ;; The third condition of the gate. A PUT that sends the same text back has always
  ;; been a no-op and must stay one, rather than becoming a pending proposal of
  ;; nothing for him to answer.
  (let [{:keys [id title useful_when]} (his-recipe! "Unchanged")
        before (inbox)]
    (testing "the same fields back, one at a time and all together"
      (is (= 200 (:status (machine :put (str "/api/recipes/" id) {:title title}))))
      (is (= 200 (:status (machine :put (str "/api/recipes/" id) {:description "his body"}))))
      (is (= 200 (:status (machine :put (str "/api/recipes/" id) {}))))
      (is (= 200 (:status (machine :put (str "/api/recipes/" id)
                                   {:title title :useful_when useful_when
                                    :description "his body"})))))
    (is (= before (inbox)) "nothing was proposed and nothing was queued")
    (is (= 0 (:pending (listed id))))
    (is (= 1 (:version (listed id))))))

(deftest a-tags-only-machine-save-on-his-text-applies-directly
  (let [{:keys [id]} (his-recipe! "His own")
        scope (:id (:body (POST-json "/api/scopes" {:title "Bread" :description ""})))
        before (inbox)]
    (testing "filing is not the text he wrote, so it lands rather than waiting"
      (is (= 200 (:status (machine :put (str "/api/recipes/" id) {:tags "sourdough"}))))
      (is (= "sourdough" (:tags (listed id))))
      (is (= 200 (:status (machine :put (str "/api/recipes/" id) {:scope_ids [scope]}))))
      (is (= ["Bread"] (map :title (:scopes (listed id))))))
    (testing "and it makes no version, no proposal and no inbox entry"
      (is (= 1 (:version (listed id))))
      (is (= 0 (:pending (listed id))))
      (is (= before (inbox))))))

(deftest a-mixed-save-applies-the-filing-and-proposes-the-content
  (let [{:keys [id]} (his-recipe! "His own")
        resp (machine :put (str "/api/recipes/" id)
                      {:tags "sourdough bread" :description "the agent's body"})]
    (is (= 202 (:status resp)))
    (testing "the filing is on the Recipe already"
      (is (= "sourdough bread" (:tags (listed id))))
      (is (= "sourdough bread" (:tags (:recipe (:body resp))))
          "and the response's `:recipe` shows it, so the caller can see which half
           landed"))
    (testing "while the content is only proposed"
      (is (= "the agent's body" (:description (:pending (:body resp)))))
      (is (= "his body"
             (:description (:body (GET-json (str "/api/recipes/" id "?detail=full"))))))
      (is (= 1 (:version (listed id)))))
    (testing "and the half that was proposed is a whole version: this PUT named the
              description only, so the title and useful-when come off the Recipe"
      (is (= {:title "His own" :useful_when "when testing"
              :description "the agent's body"}
             (select-keys (:pending (:body resp))
                          [:title :useful_when :description]))))))

(deftest a-proposal-entry-is-titled-with-the-recipes-title-and-not-the-proposed-one
  ;; `recipe_title` is a snapshot of the **Recipe's** title — 009 says so, and the queue
  ;; heads each row with it. It used to be written from the proposal, so an agent
  ;; proposing a rename made the row name the Recipe something it had never been
  ;; called, which is precisely the case where the reader must not be guessing.
  (let [{:keys [id]} (his-recipe! "Sourdough starter")]
    (machine :put (str "/api/recipes/" id) {:title "How to keep a levain alive"})
    (let [entry (latest-proposal-entry)]
      (testing "the entry says what the Recipe is called"
        (is (= "Sourdough starter" (:recipe_title entry))))
      (testing "and the proposal beside it is a whole version, although the PUT named
                one field — see a-partial-proposal-keeps-the-fields-it-did-not-send"
        (is (= "his body" (:description (:proposal entry))))
        (is (= "when testing" (:useful_when (:proposal entry)))))
      (testing "while the proposal beside it carries both names, which is what the
                comparison is drawn from"
        (is (= "Sourdough starter" (:current_title (:proposal entry))))
        (is (= "How to keep a levain alive" (:title (:proposal entry))))))
    (testing "an overwrite does not leave the first proposal's title behind"
      (machine :put (str "/api/recipes/" id "?overwrite=true") {:title "Feeding a levain"})
      (let [entry (latest-proposal-entry)]
        (is (= "Sourdough starter" (:recipe_title entry)))
        (is (= "Feeding a levain" (:title (:proposal entry))))))
    (testing "and when he renames the Recipe himself, the next revision's entry follows
              the Recipe rather than staying on the name it had when it was filed"
      (is (= 200 (:status (PUT-json (str "/api/recipes/" id) {:title "Levain, kept alive"}))))
      (machine :put (str "/api/recipes/" id "?overwrite=true") {:title "Feeding a levain"})
      (let [entry (latest-proposal-entry)]
        (is (= "Levain, kept alive" (:recipe_title entry)))
        (is (= 2 (:base_version (:proposal entry))) "rebased, and the title moved with it")))
    (testing "one entry throughout — the title is the only thing that moved"
      (is (= 1 (count (filter #(= "proposed" (:kind %)) (inbox))))))))

;; ---------------------------------------------------------------------------
;; the two 409s

(deftest a-second-proposal-is-refused-with-the-pending-text
  (let [{:keys [id]} (his-recipe! "His own")
        _ (machine :put (str "/api/recipes/" id) {:description "take one"})
        resp (machine :put (str "/api/recipes/" id) {:description "take two"})]
    (is (= 409 (:status resp)))
    (is (= "proposal-pending" (:reason (:body resp)))
        "named, because this route has two 409s and guessing at the body shape is a trap")
    (testing "and it carries the text that is in the way, so the agent can see what
              it or another agent proposed"
      (is (= "take one" (:description (:pending (:body resp)))))
      (is (= 1 (:base_version (:pending (:body resp))))))
    (testing "the refused call wrote nothing at all — one proposal, still the first"
      (is (= 1 (count (filter #(= "proposed" (:kind %)) (inbox)))))
      (is (= "take one" (:description (:pending (:body (machine :put (str "/api/recipes/" id)
                                                               {:description "take three"})))))))))

(deftest overwrite-replaces-the-pending-proposal
  (let [{:keys [id]} (his-recipe! "His own")
        _ (machine :put (str "/api/recipes/" id) {:description "take one"})
        entry-before (latest-proposal-entry)
        resp (machine :put (str "/api/recipes/" id "?overwrite=true")
                      {:description "take two"})]
    (is (= 202 (:status resp)))
    (is (= "take two" (:description (:pending (:body resp)))))
    (testing "the queue entry keeps its id and its place — an agent revising three
              times must not ask him three times"
      (let [entry-after (latest-proposal-entry)]
        (is (= (:id entry-before) (:id entry-after)))
        (is (= (:created_at entry-before) (:created_at entry-after))))
      (is (= 1 (count (filter #(= "proposed" (:kind %)) (inbox))))))
    (testing "and only the exact string `true` counts, like ?detail and ?human"
      (doseq [query ["?overwrite=1" "?overwrite=yes" "?overwrite=TRUE" "?overwrite="
                     "?overwrite=false"]]
        (is (= 409 (:status (machine :put (str "/api/recipes/" id query)
                                     {:description "sneaking in"})))
            (str query " must not count as consent to replace"))))
    (testing "so the pending text is still the one the real overwrite wrote"
      (is (= "take two" (:description (:pending (:body (machine :put (str "/api/recipes/" id)
                                                               {:description "x"})))))))))

(deftest the-modified-at-guard-is-checked-before-the-proposal
  (let [{:keys [id]} (his-recipe! "His own")
        resp (machine :put (str "/api/recipes/" id)
                      {:modified_at "1999-01-01 00:00:00" :description "the agent's body"})]
    (is (= 409 (:status resp)))
    (is (= "modified-elsewhere" (:reason (:body resp)))
        "the other 409, and told apart by name rather than by shape")
    (is (= "His own" (:title (:current (:body resp)))) "carrying the row as it now is")
    (is (contains? (:current (:body resp)) :scopes)
        "with the filing, so a client redrawing from it does not blank the badges")
    (testing "and nothing was proposed: an agent writing against text that has moved
              is told that first"
      (is (empty? (filter #(= "proposed" (:kind %)) (inbox))))
      (is (= 0 (:pending (listed id)))))
    (testing "while the matching stamp proposes as usual"
      (let [current (:modified_at (:body (GET-json (str "/api/recipes/" id))))]
        (is (= 202 (:status (machine :put (str "/api/recipes/" id)
                                     {:modified_at current
                                      :description "the agent's body"}))))))))

;; ---------------------------------------------------------------------------
;; a pending proposal is invisible to every read

(deftest a-pending-proposal-is-invisible-to-every-read
  (let [{:keys [id]} (his-recipe! "His own")
        _ (machine :put (str "/api/recipes/" id) {:title "The agent's title"
                                                  :description "the agent's body"})]
    (doseq [[label opts] [["the owner" {}] ["a machine token" (machine-opts)]]]
      (testing (str "for " label)
        (testing "the listing shows the version before"
          (let [row (first (filter #(= id (:id %)) (:body (h/API :get "/api/recipes" opts))))]
            (is (= "His own" (:title row)))
            (is (= 1 (:version row)))
            (is (= 1 (:pending row)) "with `pending` as the one thing that changed")))
        (testing "and so does the single read, at both detail levels"
          (is (= "His own" (:title (:body (h/API :get (str "/api/recipes/" id) opts)))))
          (let [full (:body (h/API :get (str "/api/recipes/" id "?detail=full") opts))]
            (is (= "His own" (:title full)))
            (is (= "his body" (:description full)))))
        (testing "and the version history has nothing in it about the proposal"
          (let [versions (:body (h/API :get (str "/api/recipes/" id "/versions") opts))]
            (is (= 1 (:total versions)))
            (is (= ["His own"] (map :title (:versions versions))))))))
    (testing "a search cannot reach the proposed text either — the words in it are
              not on the Recipe"
      (is (empty? (:body (GET-json "/api/recipes?search=agent")))))
    (testing "and a visitor is not even told something is waiting"
      (h/API :post (str "/api/recipes/" id "/publish") {})
      (h/with-real-auth
        (let [row (first (:body (h/API :get "/api/recipes" {:anonymous? true})))]
          (is (= "His own" (:title row)))
          (is (false? (contains? row :pending))))))))

;; ---------------------------------------------------------------------------
;; a published Recipe: one door open

(deftest a-machine-may-propose-against-a-published-recipe-and-do-nothing-else-to-it
  (let [{:keys [id]} (his-recipe! "Signed and public")
        _ (POST-json (str "/api/recipes/" id "/publish") {})
        before (listed id)]
    (testing "a content PUT is accepted as a proposal — the owner opened this door,
              and his click is the gate"
      (let [resp (machine :put (str "/api/recipes/" id) {:description "the agent's body"})]
        (is (= 202 (:status resp)))
        (is (= "the agent's body" (:description (:pending (:body resp)))))))
    (testing "and the published Recipe is untouched while it waits"
      (is (= (dissoc before :pending) (dissoc (listed id) :pending)))
      (is (= 1 (:published (listed id))))
      (is (= 1 (:pending (listed id)))))
    (testing "filing it is not that door: refused, and refused whole"
      (doseq [body [{:tags "smuggled"}
                    {:scope_ids []}
                    {:tags "smuggled" :description "and a rewrite with it"}]]
        (is (= 403 (:status (machine :put (str "/api/recipes/" id) body)))
            (str body " must be refused on a published Recipe")))
      (is (= "" (:tags (listed id))) "nothing was filed")
      (is (= "the agent's body" (:description (:pending (:body (machine :put (str "/api/recipes/" id)
                                                                       {:description "x"})))))
          "and the mixed request did not replace the pending proposal either"))
    (testing "nor is deleting it"
      (is (= 403 (:status (machine :delete (str "/api/recipes/" id)))))
      (is (some? (listed id))))
    (testing "approving puts the agent's wording into the published text, which is what
              the inbox item warns about before the click"
      (let [entry (latest-proposal-entry)]
        (is (= 1 (:recipe_published (:proposal entry)))
            "and the entry says the Recipe is published, so he can see what he is doing")
        (let [resp (h/API :post (str "/api/inbox/" (:id entry) "/approve") {})]
          (is (= 200 (:status resp)))
          (is (= 2 (:version (:body resp))))
          (is (= "machine" (:source (:body resp))))
          (is (= 1 (:published (:body resp)))))))))

(deftest published-outranks-the-gate
  ;; The row that would slip through if the two rules were asked in the wrong order.
  ;; Every version of this Recipe is an agent's, so `machine_versions = version` and the
  ;; gate alone would let it write straight through — but it is published, and a
  ;; published Recipe is never the agents' to write. A 200 here would mean an agent had
  ;; rewritten public text unsupervised.
  (let [{:keys [id]} (agents-recipe! "Written by an agent, published by him")]
    (is (= 200 (:status (machine :put (str "/api/recipes/" id) {:description "v2, freely"})))
        "unpublished, the gate lets it through — which is what makes the next part a test")
    (is (= 200 (:status (POST-json (str "/api/recipes/" id "/publish") {}))))
    (let [row (listed id)]
      (is (= (:version row) (:machine_versions row)) "the gate still says yes")
      (is (= 1 (:published row)) "and the latch says no"))
    (let [resp (machine :put (str "/api/recipes/" id) {:description "v3, unsupervised?"})]
      (is (= 202 (:status resp)) "so it is a proposal, not a write")
      (is (= 2 (:version (listed id))) "and the public text is still what he published")
      (is (= "v2, freely"
             (:description (:body (GET-json (str "/api/recipes/" id "?detail=full")))))))))

;; ---------------------------------------------------------------------------
;; what a visitor sees

(deftest what-a-visitor-sees-is-the-last-approved-version
  ;; **The guarantee, in his words:** *if say the last version v3 was from a machine and
  ;; the human approved, and then the machine sends another request, on publish, what an
  ;; anon user sees is v3.*
  ;;
  ;; This is a guarantee and not a consequence. Publishing is allowed while a proposal
  ;; is pending, and a machine may propose against a published Recipe — so the
  ;; invisibility of a pending proposal to every read is the only thing standing between
  ;; an unapproved agent wording and an anonymous reader. Nothing else is in the way:
  ;; not the latch, not the gate, not the approval flow.
  (let [{:keys [id]} (his-recipe! "Restarting a stuck dev server")]
    ;; v1 his, v2 his, v3 the agent's — approved by him, which is the state his
    ;; sentence starts from.
    (PUT-json (str "/api/recipes/" id) {:description "his second draft"})
    (machine :put (str "/api/recipes/" id) {:title "Restarting a stuck dev server"
                                            :useful_when "when make start hangs"
                                            :description "the approved agent text"})
    (h/API :post (str "/api/inbox/" (:id (latest-proposal-entry)) "/approve") {})
    (is (= 200 (:status (POST-json (str "/api/recipes/" id "/publish") {}))))
    (let [row (listed id)]
      (is (= 3 (:version row)) "v3")
      (is (= "machine" (:source row)) "from a machine")
      (is (= 1 (:published row)) "and public"))
    ;; and then the machine sends another request.
    (is (= 202 (:status (machine :put (str "/api/recipes/" id)
                                 {:title "UNAPPROVED TITLE"
                                  :useful_when "UNAPPROVED USEFUL WHEN"
                                  :description "UNAPPROVED BODY"}))))
    (is (= 1 (:pending (listed id))) "so there really is one waiting")
    (h/with-real-auth
      (let [listing (:body (h/API :get "/api/recipes" {:anonymous? true}))
            row (first listing)
            full (:body (h/API :get (str "/api/recipes/" id "?detail=full") {:anonymous? true}))]
        (testing "an anonymous listing is v3"
          (is (= 1 (count listing)))
          (is (= id (:id row)))
          (is (= 3 (:version row)))
          (is (= "when make start hangs" (:useful_when row))))
        (testing "and so is an anonymous ?detail=full"
          (is (= "Restarting a stuck dev server" (:title full)))
          (is (= "the approved agent text" (:description full))))
        (testing "and no part of the proposal is in either of them"
          (is (not (re-find #"UNAPPROVED" (pr-str [listing full])))))
        (testing "nor is the history a way to it — a visitor has none at all"
          (is (= 404 (:status (h/API :get (str "/api/recipes/" id "/versions")
                                     {:anonymous? true})))))
        (testing "and a visitor is not even told something is waiting"
          (is (false? (contains? row :pending))))))
    (testing "while the owner's own reads are the approved version too — the proposal is
              a question, not a draft anybody is served"
      (is (= "the approved agent text"
             (:description (:body (GET-json (str "/api/recipes/" id "?detail=full"))))))
      (is (= 3 (:total (:body (GET-json (str "/api/recipes/" id "/versions")))))))))

;; ---------------------------------------------------------------------------
;; approving and dismissing

(deftest approving-writes-the-agents-text-and-clears-the-queue
  (let [{:keys [id]} (his-recipe! "His own")
        _ (machine :put (str "/api/recipes/" id) {:title "The agent's title"
                                                  :description "the agent's body"})
        entry (latest-proposal-entry)
        resp (h/API :post (str "/api/inbox/" (:id entry) "/approve") {})]
    (is (= 200 (:status resp)))
    (testing "the Recipe is on the agent's text at the next version"
      (is (= "The agent's title" (:title (:body resp))))
      (is (= 2 (:version (:body resp))))
      (is (= "the agent's body" (:description (:body resp))))
      (is (= "machine" (:source (:body resp))) "labelled as the agent's work"))
    (testing "the version before it is in the history with *his* label"
      (let [versions (:versions (:body (GET-json (str "/api/recipes/" id "/versions"))))]
        (is (= [2 1] (map :version versions)))
        (is (= ["machine" "ui"] (map :source versions)))
        (is (= "his body" (:description (second versions))))))
    (testing "the queue is empty and nothing is pending"
      (is (empty? (inbox)))
      (is (= 0 (:pending (listed id)))))
    (testing "and the mark stays on: approving text is not writing it"
      (is (= 1 (:has_human_edit (listed id)))))
    (testing "so the next agent edit still has to ask"
      (is (= 202 (:status (machine :put (str "/api/recipes/" id)
                                   {:description "and again"})))))))

(deftest dismissing-leaves-the-recipe-alone
  (let [{:keys [id]} (his-recipe! "His own")
        _ (machine :put (str "/api/recipes/" id) {:description "the agent's body"})
        entry (latest-proposal-entry)
        before (listed id)
        resp (h/API :post (str "/api/inbox/" (:id entry) "/dismiss") {})]
    (is (= 200 (:status resp)))
    (is (true? (:success (:body resp))))
    (testing "the Recipe is untouched — no version, no history row, nothing"
      ;; `:pending` is the one field that legitimately differs: it was 1 while the
      ;; proposal was waiting and is 0 now. Everything else has to be identical, and
      ;; the flag itself is asserted a few lines down.
      (is (= (dissoc before :pending) (dissoc (listed id) :pending)))
      (is (= 1 (:total (:body (GET-json (str "/api/recipes/" id "/versions")))))))
    (testing "the entry has left the queue and nothing is pending"
      (is (empty? (inbox)))
      (is (= 0 (:pending (listed id)))))
    (testing "and the agent may propose again, which is what makes a dismissal a
              decision about that text rather than about that agent"
      (is (= 202 (:status (machine :put (str "/api/recipes/" id)
                                   {:description "a better attempt"})))))))

(deftest resolving-twice-is-refused
  (let [{:keys [id]} (his-recipe! "His own")
        _ (machine :put (str "/api/recipes/" id) {:description "the agent's body"})
        entry (latest-proposal-entry)]
    (is (= 200 (:status (h/API :post (str "/api/inbox/" (:id entry) "/approve") {}))))
    (testing "a second approval says so rather than writing the text twice"
      (let [again (h/API :post (str "/api/inbox/" (:id entry) "/approve") {})]
        (is (= 409 (:status again)))
        (is (= "approved" (:resolution (:body again))))))
    (testing "and so does a dismissal of something already approved"
      (is (= 409 (:status (h/API :post (str "/api/inbox/" (:id entry) "/dismiss") {})))))
    (testing "the Recipe was written once, not twice"
      (is (= 2 (:version (listed id)))))))

(deftest approving-a-proposal-whose-recipe-is-gone-closes-it
  (let [{:keys [id]} (his-recipe! "Doomed")
        _ (machine :put (str "/api/recipes/" id) {:description "the agent's body"})
        entry (latest-proposal-entry)]
    (is (= 200 (:status (h/API :delete (str "/api/recipes/" id) {}))))
    (testing "his delete already closed it, so the entry is out of the queue"
      (is (empty? (filter #(= "proposed" (:kind %)) (inbox)))))
    (testing "and approving it now says the Recipe is gone rather than 500ing"
      (is (= 409 (:status (h/API :post (str "/api/inbox/" (:id entry) "/approve") {})))))))

(deftest seen-refuses-a-proposal
  (let [{:keys [id]} (his-recipe! "His own")
        _ (machine :put (str "/api/recipes/" id) {:description "the agent's body"})
        entry (latest-proposal-entry)
        resp (h/API :post (str "/api/inbox/" (:id entry) "/seen") {})]
    (is (= 400 (:status resp)))
    (is (re-find #"(?i)approve or dismiss" (:error (:body resp))))
    (testing "and it is still there to be answered — acknowledging it would have
              stranded the agent with nothing left to resolve it through"
      (is (= 1 (count (filter #(= "proposed" (:kind %)) (inbox)))))
      (is (= 1 (:pending (listed id)))))))

(deftest approving-and-dismissing-are-the-owners-alone
  (let [{:keys [id]} (his-recipe! "His own")
        _ (machine :put (str "/api/recipes/" id) {:description "the agent's body"})
        entry (latest-proposal-entry)]
    (testing "a machine cannot approve its own proposal — that would be the whole
              mechanism undone"
      (is (= 403 (:status (machine :post (str "/api/inbox/" (:id entry) "/approve")))))
      (is (= 403 (:status (machine :post (str "/api/inbox/" (:id entry) "/dismiss"))))))
    (testing "nor can a caller with no credentials"
      (h/with-real-auth
        (is (= 403 (:status (h/API :post (str "/api/inbox/" (:id entry) "/approve")
                                   {:anonymous? true}))))
        (is (= 403 (:status (h/API :post (str "/api/inbox/" (:id entry) "/dismiss")
                                   {:anonymous? true}))))))
    (testing "and neither refusal resolved or applied anything — the status alone
              would pass against a route that answered 403 after writing"
      (is (= 1 (:version (listed id))))
      (is (= "his body" (:description (:body (GET-json (str "/api/recipes/" id "?detail=full"))))))
      (is (= 1 (count (filter #(= "proposed" (:kind %)) (inbox))))))
    (testing "while the owner may, through the same chain"
      (is (= 200 (:status (h/API :post (str "/api/inbox/" (:id entry) "/approve") {})))))))

(deftest the-refusals-hold-through-the-production-chain
  (let [{:keys [id]} (his-recipe! "His own")
        _ (machine :put (str "/api/recipes/" id) {:description "the agent's body"})
        entry (latest-proposal-entry)]
    (h/with-prod-app
      (is (= 403 (:status (h/API :post (str "/api/inbox/" (:id entry) "/approve")
                                 (machine-opts)))))
      (is (= 403 (:status (h/API :post (str "/api/inbox/" (:id entry) "/dismiss")
                                 (machine-opts)))))
      (testing "and a machine's delete of his Recipe is refused there too"
        (is (= 403 (:status (h/API :delete (str "/api/recipes/" id) (machine-opts)))))))
    (is (= 1 (:version (listed id))) "nothing landed")
    (is (some? (listed id)) "and the Recipe is still there")))

;; ---------------------------------------------------------------------------
;; delete

(deftest a-machine-may-not-delete-a-recipe-he-has-written
  (let [{his :id} (his-recipe! "His own")
        {theirs :id} (agents-recipe! "The agents' own")]
    (testing "his text: refused, with somewhere to go instead"
      (let [resp (machine :delete (str "/api/recipes/" his))]
        (is (= 403 (:status resp)))
        (is (re-find #"(?i)propose" (:error (:body resp)))
            "the refusal names the alternative, since there is one for a PUT")))
    (is (some? (listed his)) "and the Recipe is still there")
    (testing "their own text: still theirs to remove, unsupervised"
      (is (= 200 (:status (machine :delete (str "/api/recipes/" theirs)))))
      (is (nil? (listed theirs))))
    (testing "**and a PUT on his text is not refused by that rule** — it has to reach
              the handler and become a proposal, which is what a delete-only rule
              written on every mutating method would have broken"
      (is (= 202 (:status (machine :put (str "/api/recipes/" his)
                                   {:description "the agent's body"})))))))

;; ---------------------------------------------------------------------------
;; documented where an agent will read it

(deftest the-rule-is-documented-in-describe
  (let [doc-for (fn [method path]
                  (:doc (first (filter #(and (= path (:path %)) (= method (:method %)))
                                       (h/describe-endpoints)))))]
    (testing "the write path says when a save becomes a proposal, and says the rule
              rather than pointing at a flag"
      (let [doc (doc-for "PUT" "/api/recipes/:id")]
        (is (re-find #"(?i)proposal" doc))
        (is (re-find #"machine_versions = version" doc))
        (is (re-find #"202" doc))
        (is (re-find #"overwrite=true" doc))
        (is (re-find #"proposal-pending" doc))
        (is (re-find #"modified-elsewhere" doc))))
    (testing "and the listing documents `pending`, and says in as many words that the
              rule itself is not a flag — an agent that went looking for one would
              otherwise be left guessing why there isn't one"
      (let [doc (doc-for "GET" "/api/recipes")]
        (is (re-find #"pending" doc))
        (is (re-find #"(?i)no `approval_required`" doc))))
    (testing "approve and dismiss are in the catalogue with who may call them"
      (is (re-find #"(?i)owner" (doc-for "POST" "/api/inbox/:id/approve")))
      (is (re-find #"(?i)machine" (doc-for "POST" "/api/inbox/:id/approve")))
      (is (re-find #"(?i)not touched|untouched"
                   (doc-for "POST" "/api/inbox/:id/dismiss"))))
    (testing "**a published Recipe's two answers are in it**, because an agent that
              read only the approval rule would expect a 403 for its proposal and a
              202 for its filing, and both would be wrong"
      (let [doc (doc-for "PUT" "/api/recipes/:id")]
        (is (re-find #"(?i)published" doc))
        (is (re-find #"(?i)outranks" doc) "that published beats the gate, in words")
        (is (re-find #"403 with nothing applied" doc)
            "and that a filing write on a published Recipe is refused whole")))
    (testing "and the visitor guarantee is said where a visitor's caller reads it,
              in his own terms"
      (doseq [path ["/api/recipes" "/api/recipes/:id"]]
        (is (re-find #"last approved version, always" (doc-for "GET" path))
            (str path " has to say what a visitor is shown")))
      (is (re-find #"last approved version, always"
                   (doc-for "POST" "/api/recipes/:id/publish"))
          "and so does the route that makes a Recipe public"))))
