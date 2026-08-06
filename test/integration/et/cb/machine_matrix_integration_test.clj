(ns et.cb.machine-matrix-integration-test
  "Who may do what to a Recipe. This is the whole security surface of the app, so
  it is written out as an exhaustive table of cases rather than illustrated with a
  few examples.

  The two rows that *are* the feature:

  | caller  | recipe      | text     | create | edit | delete | publish | read |
  |---------|-------------|----------|--------|------|--------|---------|------|
  | owner   | unpublished | either   | 201    | 200  | 200    | 200     | 200  |
  | owner   | published   | either   | –      | 200  | 200    | 200 no-op | 200 |
  | machine | unpublished | agents'  | 201    | 200  | 200    | **403** | 200  |
  | machine | unpublished | **his**  | –      | **202** | **403** | **403** | 200 |
  | machine | published   | either   | –      | **403** | **403** | **403** | 200 |
  | anon    | unpublished | either   | 401    | 401  | 401    | 401     | absent |
  | anon    | published   | either   | 401    | 401  | 401    | 401     | 200  |

  A machine writes unsupervised — that is what cookbook is for — and it meets two
  walls. The publish latch, which was the only one; and, since proposals, **whose
  text it is**: a Recipe every version of which an agent wrote is still the agents'
  to rewrite and delete at will, while one the owner has written any part of is
  neither. An edit of his text is not refused but *deferred* — 202, filed as a
  proposal, the row unchanged until he approves — and a delete of it is refused
  outright, because there is no such thing as proposing a deletion.

  So `text` is an axis of this table now, and it is the one a reader is most likely
  to get wrong: the guard rule is `DELETE`-only, and writing it on every mutating
  method would refuse the very PUTs the proposal path exists for.

  Every ✓ case asserts the write actually **landed in the row**, and every refusal
  *and every deferral* asserts the row did **not** change: a status code alone would
  pass against a gate that swallows writes and answers 200 anyway, which is exactly
  the regression this file has to catch."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.cb.db :as db]
            [et.cb.integration-helpers :as h :refer [POST-json]]))

(use-fixtures :each h/with-integration-db)

;; ---------------------------------------------------------------------------
;; the row, read straight from the table
;;
;; Not through the API: a case that refuses a machine has to be checked by
;; something the guard cannot answer for, and after a successful delete there is
;; no GET in the owner's audience left to ask.

(defn- row [id]
  (jdbc/execute-one! (db/get-conn h/*ds*)
    (sql/format {:select [:id :title :version :published :published_at :user_id]
                 :from [:recipes] :where [:= :id id]})
    db/jdbc-opts))

(defn- row-count []
  (:n (jdbc/execute-one! (db/get-conn h/*ds*)
        (sql/format {:select [[[:count :*] :n]] :from [:recipes]}) db/jdbc-opts)))

(defn- table-count
  "Rows in any table, for the same reason `row-count` reads the table rather than a
  listing: what `/api/test/reset` clears is the thing under test, and a count taken
  through a handler could be narrowed by an audience."
  [table]
  (:n (jdbc/execute-one! (db/get-conn h/*ds*)
        (sql/format {:select [[[:count :*] :n]] :from [table]}) db/jdbc-opts)))

(defn- owner-recipe!
  "A recipe on the owner's shelf, published or not, and written either by him or by
  an agent — `text` decides which, and it decides whether a machine may write to it
  directly.

  `:agents` text is created with a machine token, so every version of it is stamped
  `machine` and the Recipe is machine-only. `:his` is created as the owner, so v1 is
  `ui` and it needs approval from then on. Publishing is always done as the owner: a
  machine may not publish at all."
  ([state title] (owner-recipe! state title :his))
  ([state title text]
   (let [opts (cond-> {:body {:title title :useful_when "when testing"
                              :description "the body"}}
                (= text :agents) (assoc :token (h/machine-token-for h/*user-id*)))
         {:keys [id]} (:body (h/API :post "/api/recipes" opts))]
     (when (= state :published)
       (is (= 200 (:status (h/API :post (str "/api/recipes/" id "/publish") {})))))
     id)))

;; ---------------------------------------------------------------------------
;; the three callers

(defn- caller-opts
  "The request options that make a request come from `caller`. The owner uses dev
  skip-logins (no token, the x-user-id header), the machine a real machine token,
  and anonymous neither — which is the only way to be a visitor."
  [caller]
  (case caller
    :owner   {}
    :machine {:token (h/machine-token-for h/*user-id*)}
    :anon    {:anonymous? true}))

(defn- request
  "One request as `caller`. Anonymous cases run with `:dangerously-skip-logins?`
  off, because with it on there is no such thing as an anonymous caller."
  [caller method path & [body]]
  (let [opts (cond-> (caller-opts caller) body (assoc :body body))]
    (if (= caller :anon)
      (h/with-real-auth (h/API method path opts))
      (h/API method path opts))))

;; ---------------------------------------------------------------------------
;; the table
;;
;; :expect is the status. :lands? says whether the row must have changed
;; afterwards — the half of each case that a status assertion cannot cover.
;;
;; :text is whose writing the Recipe holds, and it defaults to :his. It only changes
;; an answer for a machine caller, but it is spelled out on the machine rows rather
;; than left implicit, because "which Recipe is this" is exactly what a reader has to
;; know to read those rows at all.

(def ^:private matrix
  [;; --- the owner: everything, on both states -----------------------------
   {:caller :owner   :state :unpublished :op :create  :expect 201 :lands? true}
   {:caller :owner   :state :unpublished :op :edit    :expect 200 :lands? true}
   {:caller :owner   :state :unpublished :op :delete  :expect 200 :lands? true}
   {:caller :owner   :state :unpublished :op :publish :expect 200 :lands? true}
   {:caller :owner   :state :unpublished :op :read    :expect 200 :visible? true}
   {:caller :owner   :state :published   :op :edit    :expect 200 :lands? true}
   {:caller :owner   :state :published   :op :delete  :expect 200 :lands? true}
   {:caller :owner   :state :published   :op :publish :expect 200 :lands? false} ;; idempotent no-op
   {:caller :owner   :state :published   :op :read    :expect 200 :visible? true}

   ;; --- the machine on its own text: unsupervised, until it meets the latch
   {:caller :machine :state :unpublished :op :create  :expect 201 :lands? true}
   {:caller :machine :text :agents :state :unpublished :op :edit    :expect 200 :lands? true}
   {:caller :machine :text :agents :state :unpublished :op :delete  :expect 200 :lands? true}
   {:caller :machine :text :agents :state :unpublished :op :publish :expect 403 :lands? false}
   {:caller :machine :text :agents :state :unpublished :op :read    :expect 200 :visible? true}

   ;; --- the machine on *his* text: deferred, not refused, except the delete
   ;; 202 is "accepted, not applied": the proposal is filed and the row is untouched,
   ;; which is why this row asserts `:lands? false` rather than being a refusal.
   {:caller :machine :text :his :state :unpublished :op :edit    :expect 202 :lands? false}
   {:caller :machine :text :his :state :unpublished :op :delete  :expect 403 :lands? false}
   {:caller :machine :text :his :state :unpublished :op :publish :expect 403 :lands? false}
   {:caller :machine :text :his :state :unpublished :op :read    :expect 200 :visible? true}
   {:caller :machine :state :published   :op :edit    :expect 403 :lands? false}
   {:caller :machine :state :published   :op :delete  :expect 403 :lands? false}
   {:caller :machine :state :published   :op :publish :expect 403 :lands? false}
   {:caller :machine :state :published   :op :read    :expect 200 :visible? true}

   ;; --- anonymous: refused every write, and shown only what is published --
   {:caller :anon    :state :unpublished :op :create  :expect 401 :lands? false}
   {:caller :anon    :state :unpublished :op :edit    :expect 401 :lands? false}
   {:caller :anon    :state :unpublished :op :delete  :expect 401 :lands? false}
   {:caller :anon    :state :unpublished :op :publish :expect 401 :lands? false}
   {:caller :anon    :state :unpublished :op :read    :expect 404 :visible? false}
   {:caller :anon    :state :published   :op :create  :expect 401 :lands? false}
   {:caller :anon    :state :published   :op :edit    :expect 401 :lands? false}
   {:caller :anon    :state :published   :op :delete  :expect 401 :lands? false}
   {:caller :anon    :state :published   :op :publish :expect 401 :lands? false}
   {:caller :anon    :state :published   :op :read    :expect 200 :visible? true}])

(defn- run-create [caller]
  (let [before (row-count)
        resp (request caller :post "/api/recipes" {:title "Made by the caller"})
        created (:id (:body resp))]
    {:resp resp
     ;; a create that landed leaves a new row — and, for a machine, one owned by
     ;; the *owner*, which is what mint-time resolution is for
     :landed? (and (some? created)
                   (= (inc before) (row-count))
                   (= h/*user-id* (:user_id (row created))))
     :unchanged? (= before (row-count))}))

(defn- run-edit [caller id]
  (let [before (row id)
        resp (request caller :put (str "/api/recipes/" id) {:title "Renamed by the caller"})
        after (row id)]
    {:resp resp
     :landed? (and (= "Renamed by the caller" (:title after))
                   (= (inc (:version before)) (:version after)))
     :unchanged? (= before after)}))

(defn- run-delete [caller id]
  (let [resp (request caller :delete (str "/api/recipes/" id))]
    {:resp resp
     :landed? (nil? (row id))
     :unchanged? (some? (row id))}))

(defn- run-publish [caller id]
  (let [before (row id)
        resp (request caller :post (str "/api/recipes/" id "/publish"))
        after (row id)]
    {:resp resp
     :landed? (and (= 1 (:published after)) (zero? (:published before)))
     :unchanged? (= before after)}))

(defn- run-read [caller id]
  (let [resp (request caller :get (str "/api/recipes/" id))
        listed (->> (:body (request caller :get "/api/recipes")) (map :id) set)]
    {:resp resp
     :visible? (and (= 200 (:status resp)) (contains? listed id))
     :invisible? (and (= 404 (:status resp)) (not (contains? listed id)))}))

(deftest the-machine-matrix
  (doseq [{:keys [caller state op expect lands? text] :or {text :his} :as spec} matrix]
    (testing (str (name caller) " / " (name state) " " (name text) " recipe / " (name op))
      (let [id (owner-recipe! state
                              (str (name caller) "-" (name state) "-" (name text)
                                   "-" (name op))
                              text)
            {:keys [resp landed? unchanged? visible? invisible?]}
            (case op
              :create  (run-create caller)
              :edit    (run-edit caller id)
              :delete  (run-delete caller id)
              :publish (run-publish caller id)
              :read    (run-read caller id))]
        (is (= expect (:status resp))
            (str "status for " (pr-str (dissoc spec :expect))))
        (if (= :read op)
          (if (:visible? spec)
            (is (true? visible?) "the recipe must be readable and listed")
            (is (true? invisible?) "the recipe must be a 404 and absent from the listing"))
          (if (true? lands?)
            (is (true? landed?) "the write had to land in the row, not merely answer 200")
            (is (true? unchanged?) "nothing may have changed in the table")))))))

;; ---------------------------------------------------------------------------
;; how the id is spelt
;;
;; The table above is exhaustive over caller × state × operation and spells the id
;; `(str id)` in every cell — and that was the one spelling the guard and the
;; handler agreed on. The guard parsed the id off the raw `:path-info` with `\d+`
;; while clout captures `[^/,;?]+` and compojure url-decodes it after matching, so
;; `/%31%31` was no recipe at all to the guard and recipe 11 to the handler. All
;; three machine refusals could be walked past by respelling the id, including the
;; publish latch, which nothing in this app can undo.
;;
;; So the spelling of the id is an axis of the security surface, and it belongs in
;; the table. The plain spelling stays in the list as a control: it is what makes
;; these tests about the *disagreement* rather than about whether a machine can
;; write at all.

(defn- pct
  "Every ASCII digit percent-encoded — `11` becomes `%31%31`. Built from the digits
  rather than hardcoded, so each case is provably the recipe it just created."
  [s]
  (apply str (map #(str "%3" %) s)))

(defn- arabic-indic
  "The same digits as U+0660…U+0669. `Integer/parseInt` accepts these through
  `Character/digit` and Java's `\\d` does not, so this spelling comes from the
  handler's parse being lenient rather than from the path being encoded — a fix
  that only url-decodes closes half of the hole and leaves this open."
  [s]
  (apply str (map #(char (+ 0x0660 (- (int %) (int \0)))) s)))

(defn- spellings
  "Every way of writing this recipe's id that still routes to this recipe. Keyed by
  what makes each one different, because a failure naming `%2011` is unreadable."
  [id]
  (let [s (str id)]
    (array-map
      "plain"                  s
      "fully encoded"          (pct s)
      ;; the last digit only, not the first: encoding everything *after* the first
      ;; digit is the plain id again for a one-digit id, and a spelling that
      ;; silently collapses into the control tests nothing
      "half encoded"           (let [cut (dec (count s))]
                                 (str (subs s 0 cut) (pct (subs s cut))))
      "leading plus"           (str "+" s)
      "encoded leading plus"   (str "%2B" s)
      "trailing encoded space" (str s "%20")
      "leading encoded space"  (str "%20" s)
      "Arabic-Indic digits"    (arabic-indic s)
      "encoded leading zero"   (str (pct "0") (pct s)))))

(def ^:private spelling-labels (vec (keys (spellings 11))))

;; Edit and delete are asked of a **published** recipe, which is the state the rule
;; is about. Publish is asked of an unpublished one as well, and that is the case
;; that matters most: publishing an already-published recipe is a 200 no-op that
;; changes no row, so only the unpublished case can tell a refusal from a
;; permitted write.
(def ^:private spelling-cases
  [{:op :edit    :state :published}
   {:op :delete  :state :published}
   {:op :publish :state :unpublished}
   {:op :publish :state :published}])

(defn- machine-mutation
  "One machine mutation of recipe `id`, naming it as `spelling`."
  [op id spelling]
  (let [path (str "/api/recipes/" spelling)]
    (case op
      :edit    (request :machine :put path {:title "Rewritten by a machine"})
      :delete  (request :machine :delete path)
      :publish (request :machine :post (str path "/publish")))))

(deftest a-machine-is-refused-however-the-recipe-id-is-spelt
  (doseq [{:keys [op state]} spelling-cases
          label spelling-labels]
    (testing (str "machine / " (name state) " recipe / " (name op)
                  " / id spelt: " label)
      (let [id (owner-recipe! state (str (name op) " " label))
            spelling (get (spellings id) label)
            before (row id)
            resp (machine-mutation op id spelling)]
        (is (= 403 (:status resp))
            (str "a machine may not " (name op) " this recipe, however it is named"))
        ;; the row, not the status: this is the half that catches a guard which
        ;; answers 403 for the plain id and lets the encoded one through
        (is (= before (row id))
            "nothing may have changed in the table")))))

(deftest every-spelling-names-the-same-recipe
  ;; Without this the refusals above would also pass if a "fix" made the *router*
  ;; reject the encoded spellings — a 403 and a 404 are both not-a-write. What has
  ;; to hold is that the router still resolves every spelling to this row and the
  ;; guard refuses it anyway: the two agreeing, rather than both failing.
  (let [id (owner-recipe! :published "Spelt every way")]
    (doseq [label spelling-labels]
      (testing (str "the owner reads recipe " id " as " label)
        (let [resp (request :owner :get (str "/api/recipes/" (get (spellings id) label)))]
          (is (= 200 (:status resp)) "the router has to resolve this spelling")
          (is (= id (:id (:body resp)))
              "and resolve it to the same row, or the refusal above proves nothing"))))))

(deftest the-machine-rules-hold-through-the-production-chain
  ;; The bypass was not dev-only, and this is why: the prod chain's one addition is
  ;; `wrap-auth`, a machine token is a *valid* token, so it passes straight through
  ;; and the recipe rules are the only wall left standing. Assert them where they
  ;; are the only wall — the rest of this file runs a chain with no `wrap-auth` at
  ;; all, which is the same reason the guard lives in `app-routes`.
  (let [id (owner-recipe! :published "Signed, and reachable from production")
        before (row id)]
    (h/with-prod-app
      (doseq [label ["plain" "fully encoded" "Arabic-Indic digits"]]
        (let [path (str "/api/recipes/" (get (spellings id) label))]
          (testing (str "a machine gets nowhere with the id spelt: " label)
            (is (= 403 (:status (request :machine :put path {:title "Edited through prod"}))))
            (is (= 403 (:status (request :machine :post (str path "/publish")))))
            (is (= 403 (:status (request :machine :delete path)))))))
      (testing "and none of that landed"
        (is (= before (row id))))
      (testing "while the owner's own token goes through the same chain — so those
                403s are the recipe rules answering, not wrap-auth refusing everyone"
        (is (= 200 (:status (h/API :put (str "/api/recipes/" id)
                                   {:token (h/token-for h/*user-id*)
                                    :body {:title "Edited by the owner"}}))))))))

;; ---------------------------------------------------------------------------
;; the destructive route that is not a recipe route
;;
;; `POST /api/test/reset` drops every recipe and all of its history. It is a
;; sibling of `/api/recipes`, so it sits outside both recipe guards and had no
;; caller check of its own: a machine token refused an edit and a delete on a
;; published Recipe seconds earlier could wipe the whole table with one call, and
;; so could a caller carrying nothing at all.
;;
;; It is asserted in this file, which is otherwise about Recipes, because this file
;; claims to be the whole security surface — and a route that deletes every Recipe
;; is part of that surface wherever it happens to be mounted.

(deftest resetting-the-database-is-the-owners-alone
  (let [signed (owner-recipe! :published "The owner's signed recipe")
        drafted (owner-recipe! :unpublished "The owner's draft")
        ;; A Scope, and a Recipe filed under it, because this route drops those
        ;; too and the Recipe count alone cannot see it: a reset that stopped
        ;; clearing `recipe_scopes` would leave join rows pointing at Recipes that
        ;; no longer exist — ghosts waiting for an id to be reused — and every
        ;; assertion below would still have passed.
        scope (:id (:body (POST-json "/api/scopes" {:title "Bread"
                                                    :description "Loaves"})))
        _ (is (= 200 (:status (h/PUT-json (str "/api/recipes/" drafted)
                                          {:scope_ids [scope]}))))
        before (row-count)]
    (is (= 1 (table-count :scopes)))
    (is (= 1 (table-count :recipe_scopes)))
    (testing "a machine token is refused — the same caller the guard refuses an
              edit and a delete on the published one"
      (is (= 403 (:status (request :machine :put (str "/api/recipes/" signed)
                                   {:title "Rewritten by a machine"}))))
      (is (= 403 (:status (request :machine :delete (str "/api/recipes/" signed)))))
      (is (= 403 (:status (request :machine :post "/api/test/reset")))))

    (testing "and so is a caller with no credentials"
      (is (= 403 (:status (request :anon :post "/api/test/reset")))))

    ;; the table, not the status: a reset that ran and answered 403 anyway would
    ;; pass on the codes alone, and this is the assertion that cannot
    (testing "nothing was dropped by either refusal"
      (is (= before (row-count)))
      (is (= 1 (table-count :scopes)))
      (is (= 1 (table-count :recipe_scopes)))
      (is (some? (row signed)))
      (is (some? (row drafted))))

    (testing "while the owner may still use it — the 403s are about the caller,
              not the route being switched off"
      (is (= 200 (:status (request :owner :post "/api/test/reset"))))
      (is (zero? (row-count))))

    (testing "and it takes the Scopes and the filing with it: a fixture that
              half-resets is one a later test can pass because of the half that
              stayed"
      (is (zero? (table-count :recipe_scopes)))
      (is (zero? (table-count :scopes))))))

;; ---------------------------------------------------------------------------
;; the gate that must not come back

(deftest a-machine-write-lands-because-there-is-no-recording-gate
  ;; Cookbook's defining property: a caller holding credentials writes unsupervised.
  ;; Asserted on the Recipes that property is *unconditional* for — the ones every
  ;; version of which an agent wrote, which is what an agentic memory store is mostly
  ;; made of.
  (let [id (owner-recipe! :unpublished "Written by an agent" :agents)
        before (row id)
        resp (request :machine :put (str "/api/recipes/" id)
                      {:title "The agent's title" :description "the agent's body"})
        after (row id)]
    (testing "the response is a 200 with the new content"
      (is (= 200 (:status resp)))
      (is (= "The agent's title" (:title (:body resp)))))
    ;; The point of this test. A recording-mode gate answers a swallowed write
    ;; with `200 {"dropped":true}`, so asserting the status would pass against the
    ;; very regression this exists to catch. Read the row.
    (testing "and the row really changed — a dropped write is served with a 200"
      (is (= "The agent's title" (:title after)))
      (is (= (inc (:version before)) (:version after)))
      (is (not= (:title before) (:title after)))
      (is (nil? (:dropped (:body resp)))))
    (testing "a machine create lands too, owned by the owner"
      (let [created (:body (request :machine :post "/api/recipes" {:title "Agent's own"}))]
        (is (= "Agent's own" (:title (row (:id created)))))
        (is (= h/*user-id* (:user_id (row (:id created)))))))))

(deftest a-proposal-is-not-a-recording-gate-in-disguise
  ;; **The distinction this whole feature rests on, and the one a future reader is
  ;; most likely to blur.** A recording gate silently drops a credentialled agent's
  ;; write and answers as though it had worked. A proposal drops nothing: it says
  ;; `202 accepted, not applied`, hands back both texts, and leaves a visible artefact
  ;; the owner has to answer. If this ever starts looking like the gate cookbook
  ;; deliberately lacks, it is this test that should go red.
  (let [id (owner-recipe! :unpublished "His own writing")
        before (row id)
        resp (request :machine :put (str "/api/recipes/" id)
                      {:title "The agent's title" :description "the agent's body"})]
    (testing "the status is not 200: an agent that treated it as one would be wrong,
              and no other write in this API lies about that"
      (is (= 202 (:status resp)))
      (is (nil? (:dropped (:body resp)))))
    (testing "the row is untouched — this is the deferral, not a swallowed write"
      (is (= before (row id))))
    (testing "and the response says both what was proposed and what the Recipe still
              says, so the caller can tell exactly what happened"
      (is (= "The agent's title" (:title (:pending (:body resp)))))
      (is (= "the agent's body" (:description (:pending (:body resp)))))
      (is (= 1 (:base_version (:pending (:body resp)))))
      (is (= "His own writing" (:title (:recipe (:body resp)))))
      (is (= 1 (:version (:recipe (:body resp))))))
    (testing "the owner is told, in the one place that exists for it"
      (let [entry (last (:body (h/API :get "/api/inbox" {})))]
        (is (= "proposed" (:kind entry)))
        (is (= id (:recipe_id entry)))
        (is (some? (:proposal_id entry)))))
    (testing "and it is not a refusal either: he can turn it into a version, which is
              what makes 202 the honest answer rather than a polite 403"
      (let [entry (last (:body (h/API :get "/api/inbox" {})))
            approved (h/API :post (str "/api/inbox/" (:id entry) "/approve") {})]
        (is (= 200 (:status approved)))
        (is (= "The agent's title" (:title (row id))))
        (is (= 2 (:version (row id))))))))

(deftest there-is-no-recording-mode-anything
  (testing "no recording-mode routes, for the owner or for a machine"
    (doseq [[method path] [[:get "/api/recording-mode"]
                           [:post "/api/recording-mode/toggle"]
                           [:put "/api/recording-mode"]
                           [:get "/api/recording_mode"]]]
      (is (= 404 (:status (h/API method path {})))
          (str method " " path " must not exist"))))
  (testing "no recording-mode namespace and no machine-write gate in the chain"
    (is (nil? (io/resource "et/cb/server/recording_mode.clj")))
    (is (nil? (io/resource "et/cb/middleware/recording_mode.clj")))
    (is (nil? (ns-resolve 'et.cb.server 'wrap-machine-write-guard))))
  (testing "and rate limiting is still there — a different concern entirely"
    (is (some? (io/resource "et/cb/middleware/rate_limit.clj")))))
