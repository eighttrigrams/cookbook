(ns et.cb.inbox-integration-test
  "The inbox over HTTP: who may read the queue, what order it comes in, and the
  one entry that cannot be acknowledged.

  The queue is the record of what the **agents** did — his own writes are not in
  it, which is asserted here as well as at the db layer, because the two answer
  different questions: there, that `update-recipe` writes no row; here, that no
  request of his produces one, whichever route he goes through.

  Owner-only means two refusals and not one: a machine token gets 403 and so does
  a caller with no credentials. A machine reading this list would learn what he
  has not looked at yet, and a machine *marking* it would empty the review he has
  not done."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.cb.db :as db]
            [et.cb.integration-helpers :as h :refer [GET-json POST-json PUT-json]]))

(use-fixtures :each h/with-integration-db)

(defn- machine-opts [] {:token (h/machine-token-for h/*user-id*)})

(defn- machine
  "One request as an agent — a real machine token, which is the only thing that
  makes `machine-caller?` true."
  [method path & [body]]
  (h/API method path (cond-> (machine-opts) body (assoc :body body))))

(defn- machine-create! [title]
  (:body (machine :post "/api/recipes" {:title title :useful_when "when testing"
                                        :description "body v1"})))

(defn- inbox
  "The owner's queue, as the page fetches it."
  []
  (:body (GET-json "/api/inbox")))

(defn- kinds [] (mapv :kind (inbox)))

;; ---------------------------------------------------------------------------
;; what shows up

(deftest an-agents-writes-show-up-and-his-own-do-not
  (let [agents (:id (machine-create! "Written by an agent"))
        his (:id (:body (POST-json "/api/recipes" {:title "Typed by hand"
                                                  :description "body v1"})))]
    (testing "the agent's create is in the queue and his is not"
      (is (= ["created"] (kinds)))
      (is (= agents (:recipe_id (first (inbox))))))

    (testing "his save on his own Recipe adds nothing"
      (is (= 200 (:status (PUT-json (str "/api/recipes/" his) {:description "body v2"}))))
      (is (= ["created"] (kinds))))

    (testing "the agent's own save on its own Recipe does, at the new version"
      (is (= 200 (:status (machine :put (str "/api/recipes/" agents)
                                   {:description "the agent's second body"}))))
      (is (= ["created" "modified"] (kinds)))
      (is (= 2 (:version (last (inbox))))))

    (testing "his save on the agent's Recipe adds nothing — the queue is whose work
              it was, not which Recipe it was"
      (is (= 200 (:status (PUT-json (str "/api/recipes/" agents) {:description "his body"}))))
      (is (= ["created" "modified"] (kinds))))

    (testing "and the agent's *next* save is a proposal rather than a modification,
              because his save closed the gate — so the queue gains a `proposed`
              entry and not a `modified` one. The version numbers therefore have gaps
              in them by design: v3 was his and is not here."
      (is (= 202 (:status (machine :put (str "/api/recipes/" agents)
                                   {:description "the agent's third body"}))))
      (is (= ["created" "modified" "proposed"] (kinds)))
      (is (= [1 2 3] (mapv :version (inbox)))
          "the proposal is against v3 — the version it read, which is his"))

    (testing "his publish and his delete add nothing"
      (is (= 200 (:status (h/API :post (str "/api/recipes/" his "/publish") {}))))
      (is (= 200 (:status (h/API :delete (str "/api/recipes/" his) {}))))
      (is (= ["created" "modified" "proposed"] (kinds))
          "the queue is exactly what it was before those two requests"))

    (testing "and an entry carries no source: every one of them is an agent's"
      (is (every? #(false? (contains? % :source)) (inbox))))))

(deftest an-entry-says-which-recipe-at-which-version-and-when
  (let [{:keys [id]} (machine-create! "Sourdough")
        [entry] (inbox)]
    (is (= id (:recipe_id entry)))
    (is (= "Sourdough" (:recipe_title entry)))
    (is (= "created" (:kind entry)))
    (is (= 1 (:version entry)))
    (is (some? (:created_at entry)))
    (is (some? (:id entry)) "and its own id, which is what the seen route takes")
    (is (nil? (:proposal_id entry)))))

(deftest a-machine-save-that-changes-nothing-or-only-the-filing-adds-no-entry
  (let [{:keys [id]} (machine-create! "Ciabatta")
        scope (:id (:body (POST-json "/api/scopes" {:title "Bread" :description ""})))]
    (is (= 1 (count (inbox))))
    (machine :put (str "/api/recipes/" id) {:title "Ciabatta"})
    (machine :put (str "/api/recipes/" id) {:tags "sourdough"})
    (machine :put (str "/api/recipes/" id) {:scope_ids [scope]})
    (is (= 1 (count (inbox)))
        "a no-op, a retag and a refile: three 200s and no new entries")
    (testing "while a content change adds one"
      (machine :put (str "/api/recipes/" id) {:description "body v2"})
      (is (= 2 (count (inbox)))))))

(deftest an-agents-delete-is-an-entry-and-the-earlier-ones-survive-it
  (let [{:keys [id]} (machine-create! "Doomed")]
    (machine :put (str "/api/recipes/" id) {:description "body v2"})
    (is (= 200 (:status (machine :delete (str "/api/recipes/" id)))))
    (is (= ["created" "modified" "deleted"] (kinds)))
    (testing "and the entries still name the Recipe readably, which is all that is
              left of it"
      (is (every? #(= "Doomed" (:recipe_title %)) (inbox)))
      (is (= 404 (:status (GET-json (str "/api/recipes/" id))))))))

(deftest every-entry-says-whether-its-recipe-is-still-there
  ;; Not the same question as `kind`, which is the point: after a create and a
  ;; delete, the `created` entry names a Recipe that is just as gone as the
  ;; `deleted` one does — and it can still be unseen after the `deleted` one has
  ;; been acknowledged, which is exactly when a page would offer a link into a 404.
  (let [alive (:id (machine-create! "Still here"))
        doomed (:id (machine-create! "Gone by the end"))]
    (is (every? #(= 1 (:recipe_exists %)) (inbox)) "both are there to begin with")

    (is (= 200 (:status (machine :delete (str "/api/recipes/" doomed)))))
    (let [by-recipe (group-by :recipe_id (inbox))]
      (is (= [1] (distinct (map :recipe_exists (get by-recipe alive))))
          "the surviving Recipe's entry still says so")
      (is (= [0 0] (mapv :recipe_exists (get by-recipe doomed)))
          "and *both* of the dead Recipe's entries say it is gone — the `created`
           one as well as the `deleted` one, which is the half a client cannot
           work out for itself")
      (testing "and the flag is the truth: those ids really are 404s now"
        (is (= 404 (:status (GET-json (str "/api/recipes/" doomed)))))
        (is (= 200 (:status (GET-json (str "/api/recipes/" alive)))))))

    (testing "acknowledging the `deleted` entry leaves the dead `created` one
              behind, still flagged — the case the flag exists for"
      (let [dead-delete (first (filter #(= "deleted" (:kind %)) (inbox)))]
        (is (= 200 (:status (h/API :post (str "/api/inbox/" (:id dead-delete) "/seen") {}))))
        (let [left (first (filter #(= doomed (:recipe_id %)) (inbox)))]
          (is (= "created" (:kind left)))
          (is (= 0 (:recipe_exists left))))))))

;; ---------------------------------------------------------------------------
;; the order

(deftest the-queue-is-append-order-and-not-timestamp-order
  ;; `created_at` is second-resolution, so a queue ordered on it would be ordered on
  ;; a tie — and two entries in one second is the normal case here, not a corner.
  ;;
  ;; **The first version of this test stamped every row with the same second and
  ;; asserted the order held.** A mutation run showed it green against a `list-unseen`
  ;; that ordered by `created_at`: with every stamp equal, SQLite returns the rows in
  ;; rowid order anyway, so the two orderings were indistinguishable and the test
  ;; proved nothing about which column was used. So it now makes the stamps
  ;; **disagree** with the append order — the last entry gets the oldest timestamp —
  ;; and only an ordering on `id` gives the right answer.
  (let [a (:id (machine-create! "First"))
        b (:id (machine-create! "Second"))]
    (machine :put (str "/api/recipes/" a) {:description "body v2"})
    (machine :put (str "/api/recipes/" b) {:description "body v2"})
    (let [ids (mapv :id (inbox))]
      (is (= 4 (count ids)))
      ;; The newest entry gets 2020; the rest share 2026. Ordering on the stamp would
      ;; put the newest first and the other three in a tie behind it.
      (jdbc/execute-one! (db/get-conn h/*ds*)
        (sql/format {:update :recipe_events :set {:created_at "2026-01-01 00:00:00"}}))
      (jdbc/execute-one! (db/get-conn h/*ds*)
        (sql/format {:update :recipe_events :set {:created_at "2020-01-01 00:00:00"}
                     :where [:= :id (last ids)]}))
      (let [entries (inbox)]
        (is (= ids (mapv :id entries))
            "the queue is still the append order, though the timestamps now say
             otherwise — which is the whole claim")
        (is (= [["First" "created"] ["Second" "created"]
                ["First" "modified"] ["Second" "modified"]]
               (mapv (juxt :recipe_title :kind) entries)))
        (is (= "2020-01-01 00:00:00" (:created_at (last entries)))
            "and the last entry really does carry the oldest stamp, or the assertion
             above is back to proving nothing")
        (is (= 2 (count (set (map :created_at entries))))
            "with the other three tied on one second, which is the case that made the
             old version of this test vacuous")))))

;; ---------------------------------------------------------------------------
;; marking one seen

(deftest marking-one-seen-takes-it-out-of-the-queue-and-leaves-the-rest
  (let [{:keys [id]} (machine-create! "Sourdough")
        _ (machine :put (str "/api/recipes/" id) {:description "body v2"})
        [oldest newest] (inbox)
        resp (h/API :post (str "/api/inbox/" (:id oldest) "/seen") {})]
    (is (= 200 (:status resp)))
    (is (= 1 (:seen (:body resp))) "the acknowledged entry comes back marked")
    (is (= [(:id newest)] (mapv :id (inbox)))
        "and the queue is what is left, oldest first as before")
    (testing "acknowledging the second one empties the queue"
      (is (= 200 (:status (h/API :post (str "/api/inbox/" (:id newest) "/seen") {}))))
      (is (empty? (inbox))))

    (testing "and doing it again is an idempotent 200, the way publishing an
              already-published Recipe is: `seen` is a latch, the first
              acknowledgement is the fact recorded, and a client that lost the
              response to it must not be told the entry never existed"
      (let [again (h/API :post (str "/api/inbox/" (:id newest) "/seen") {})]
        (is (= 200 (:status again)))
        (is (= 1 (:seen (:body again))))
        (is (empty? (inbox)))))))

(deftest a-seen-entry-does-not-come-back-when-the-recipe-changes-again
  ;; The queue is per change and not per Recipe: acknowledging v2 does not
  ;; acknowledge v3, and v3 arriving does not un-acknowledge v2.
  (let [{:keys [id]} (machine-create! "Baguette")]
    (h/API :post (str "/api/inbox/" (:id (first (inbox))) "/seen") {})
    (is (empty? (inbox)))
    (machine :put (str "/api/recipes/" id) {:description "body v2"})
    (is (= ["modified"] (kinds)) "one new entry, and the acknowledged one stays gone")
    (is (= 2 (:version (first (inbox)))))))

(deftest an-entry-that-is-not-yours-is-a-404
  (let [{:keys [id]} (machine-create! "Sourdough")
        entry (:id (first (inbox)))]
    (is (= 404 (:status (h/API :post (str "/api/inbox/" (+ entry 1000) "/seen") {}))))
    (is (= 404 (:status (h/API :post "/api/inbox/0/seen" {}))))
    (is (= 404 (:status (h/API :post "/api/inbox/not-a-number/seen" {}))))
    (testing "and none of that acknowledged the entry that does exist"
      (is (= 1 (count (inbox))))
      (is (= id (:recipe_id (first (inbox))))))))

;; ---------------------------------------------------------------------------
;; whose it is

(deftest the-inbox-is-the-owners-alone
  (let [{:keys [id]} (machine-create! "Written by an agent")
        entry (:id (first (inbox)))]
    (testing "a machine token is refused both routes — it would be reading the
              review he has not done, and emptying it"
      (is (= 403 (:status (machine :get "/api/inbox"))))
      (is (= 403 (:status (machine :post (str "/api/inbox/" entry "/seen"))))))

    (testing "and so is a caller with no credentials"
      (h/with-real-auth
        (is (= 403 (:status (h/API :get "/api/inbox" {:anonymous? true}))))
        (is (= 403 (:status (h/API :post (str "/api/inbox/" entry "/seen")
                                   {:anonymous? true}))))))

    (testing "neither refusal said anything about what is in there, and neither
              acknowledged anything — the status alone would pass against a route
              that answered 403 after writing"
      (is (= 1 (count (inbox))))
      (is (= id (:recipe_id (first (inbox))))))

    (testing "while the owner reads it through the same chain, so those 403s are
              about the caller and not about the route being switched off"
      (is (= 200 (:status (GET-json "/api/inbox")))))))

(deftest the-inbox-refusals-hold-through-the-production-chain
  ;; A machine token is a *valid* token, so `wrap-auth` passes it straight through
  ;; and `owner-caller?` is the only wall left standing. Asserted where it is the
  ;; only wall — the rest of this file runs a chain with no `wrap-auth` at all.
  (let [_ (machine-create! "Written by an agent")
        entry (:id (first (inbox)))]
    (h/with-prod-app
      (is (= 403 (:status (h/API :get "/api/inbox" (machine-opts)))))
      (is (= 403 (:status (h/API :post (str "/api/inbox/" entry "/seen") (machine-opts)))))
      (testing "and the owner's own token goes through that same chain"
        (is (= 200 (:status (h/API :get "/api/inbox"
                                   {:token (h/token-for h/*user-id*)}))))))
    (testing "nothing was acknowledged by either refusal"
      (is (= 1 (count (inbox)))))))

(deftest a-proposed-entry-says-whether-the-recipe-is-published
  ;; The one thing about approving *this* proposal that cannot be left to be discovered
  ;; afterwards. An agent may propose against a published Recipe — the owner's call —
  ;; so approving one replaces text that is already public and that he has put his name
  ;; to, and there is no unpublish. The client says so on the item, and it can only say
  ;; it because the entry carries the flag.
  (let [{:keys [id]} (:body (POST-json "/api/recipes" {:title "Signed and public"
                                                       :description "his body"}))
        {other :id} (:body (POST-json "/api/recipes" {:title "Still a draft"
                                                      :description "his body"}))]
    (machine :put (str "/api/recipes/" other) {:description "the agent's body"})
    (is (= 0 (:recipe_published (:proposal (last (inbox)))))
        "0 while it is his own private draft")
    (POST-json (str "/api/recipes/" id "/publish") {})
    (machine :put (str "/api/recipes/" id) {:description "the agent's body"})
    (let [by-recipe (into {} (map (juxt :recipe_id identity)) (inbox))]
      (is (= 1 (:recipe_published (:proposal (get by-recipe id)))))
      (is (= 0 (:recipe_published (:proposal (get by-recipe other))))
          "and the two entries in one queue do not borrow each other's answer"))))

;; ---------------------------------------------------------------------------
;; what reading it must not touch

(deftest reading-the-inbox-moves-no-view-count-and-no-modified-at
  ;; Reviewing what an agent wrote is not consuming a Recipe. `view_count` ranks the
  ;; shelf, so an inbox that counted reads would quietly reorder his Cookbook every
  ;; time he went through the queue; and `modified_at` is the optimistic-concurrency
  ;; guard, so moving it would 409 a save he had in flight.
  ;;
  ;; **The queue has to hold a `proposed` entry for this to be capable of failing**,
  ;; and the first version of this test did not put one there. A `proposed` entry is
  ;; the only kind whose rendering needs the Recipe's *text*, so
  ;; `db.proposal/attach-to-events` is the only thing here that reads a Recipe at all —
  ;; and it returns before reading anything when no entry is a proposal. With a queue
  ;; of notifications only, an implementation that fetched each proposal through
  ;; `GET /api/recipes/:id?detail=full` — the one the order forbids, because that
  ;; endpoint counts a consumption — passed this test untouched.
  ;;
  ;; The count starts at 1 rather than 0 for the same reason: `(= 0 …)` cannot tell
  ;; "nothing was counted" from "the column does not exist yet", while a real read
  ;; already recorded is a number an accidental increment moves.
  (let [{:keys [id]} (:body (POST-json "/api/recipes" {:title "Sourdough"
                                                       :description "his body"}))]
    (GET-json (str "/api/recipes/" id "?detail=full"))
    (machine-create! "Written by an agent")
    (machine :put (str "/api/recipes/" id) {:description "the agent's body"})
    (let [entries (inbox)
          before (first (filter #(= id (:id %)) (:body (GET-json "/api/recipes"))))]
      (is (= #{"created" "proposed"} (set (map :kind entries)))
          "a notification and a question, so both paths through the listing are read")
      (is (some? (:proposal (first (filter #(= "proposed" (:kind %)) entries))))
          "and the proposal really did come back with its text, which is the read
           that could have gone through the counting endpoint")
      (dotimes [_ 3] (inbox))
      (h/API :post (str "/api/inbox/" (:id (first (filter #(= "created" (:kind %)) entries)))
                        "/seen")
             {})
      (let [after (first (filter #(= id (:id %)) (:body (GET-json "/api/recipes"))))]
        (is (= 1 (:view_count after)) "no read was counted")
        (is (= (:modified_at before) (:modified_at after)) "and the stamp did not move")
        (is (= before after) "nothing about the row moved at all")))))

;; ---------------------------------------------------------------------------
;; the catalogue and the reset

(deftest the-inbox-routes-are-in-the-catalogue
  ;; `/api/describe` is assembled from `server/describe-namespaces`, and an agent
  ;; reads it to find out what exists. A handler in a namespace missing from that
  ;; list is invisible there however well it works.
  (let [endpoints (h/describe-endpoints)
        by-path (group-by (juxt :method :path) endpoints)]
    (is (contains? by-path ["GET" "/api/inbox"]))
    (is (contains? by-path ["POST" "/api/inbox/:id/seen"]))
    (testing "and each entry carries the docstring, which is the documentation"
      (is (re-find #"oldest first"
                   (:doc (first (get by-path ["GET" "/api/inbox"]))))))
    (testing "the catalogue is public, so an anonymous caller can still discover
              that the route exists — what it cannot do is read it"
      (is (contains? (set (map :path (h/describe-endpoints {:anonymous? true})))
                     "/api/inbox")))))

(defn- table-count
  "Rows in a table, read straight from it. The queue alone cannot answer what a reset
  destroyed: `recipe_events` is cleared either way, so a `recipe_proposals` row left
  behind is invisible to every read this app has — which is exactly how it went
  unnoticed."
  [table]
  (:n (jdbc/execute-one! (db/get-conn h/*ds*)
        (sql/format {:select [[[:count :*] :n]] :from [table]}) db/jdbc-opts)))

(deftest resetting-the-database-empties-the-inbox
  ;; `delete-recipe` deliberately leaves a Recipe's events behind; a reset must not.
  ;; A queue naming Recipes that no longer exist at all is a record of nothing, and
  ;; a fixture that half-resets is one a later test can pass because of the half
  ;; that stayed.
  (let [{:keys [id]} (machine-create! "Written by an agent")
        his (:body (POST-json "/api/recipes" {:title "His own" :description "his body"}))]
    (machine :put (str "/api/recipes/" id) {:description "body v2"})
    ;; And a **pending proposal**, which is the half of this that the queue cannot
    ;; see. A reset that stopped clearing `recipe_proposals` passed every assertion
    ;; below, because the `proposed` entry goes with `recipe_events` and the row it
    ;; points at is unreachable afterwards: the Recipe is gone, so no read consults
    ;; it. What is left is a question nobody can answer, still holding the partial
    ;; unique index against a recipe id the next agent may be given.
    (is (= 202 (:status (machine :put (str "/api/recipes/" (:id his))
                                 {:description "the agent's body"}))))
    (is (= 3 (count (inbox))))
    (is (= 1 (table-count :recipe_proposals)))
    (is (= 200 (:status (h/API :post "/api/test/reset" {}))))
    (is (empty? (inbox)))
    (is (empty? (:body (GET-json "/api/recipes"))))
    (testing "and the tables behind them, including the one no read would show"
      (is (zero? (table-count :recipe_events)))
      (is (zero? (table-count :recipe_proposals))))
    (testing "so the next agent's write on a reused id is a write and not a 409 about
              a proposal from a database that no longer exists"
      (let [{:keys [id]} (machine-create! "Written after the reset")]
        (is (= 200 (:status (machine :put (str "/api/recipes/" id)
                                     {:description "body v2"}))))))))
