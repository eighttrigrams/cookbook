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

    (testing "his save on the agent's Recipe adds nothing either — the queue is
              whose work it was, not which Recipe it was"
      (is (= 200 (:status (PUT-json (str "/api/recipes/" agents) {:description "his body"}))))
      (is (= ["created"] (kinds))))

    (testing "the agent's save does, at the new version"
      (is (= 200 (:status (machine :put (str "/api/recipes/" agents)
                                   {:description "the agent's body"}))))
      (is (= ["created" "modified"] (kinds)))
      (is (= 3 (:version (last (inbox))))
          "v3: the agent's own two writes are entries and his in between is not, so
           the version numbers have gaps by design"))

    (testing "his publish and his delete add nothing"
      (is (= 200 (:status (h/API :post (str "/api/recipes/" his "/publish") {}))))
      (is (= 200 (:status (h/API :delete (str "/api/recipes/" his) {}))))
      (is (= ["created" "modified"] (kinds))))

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

;; ---------------------------------------------------------------------------
;; the order

(deftest the-queue-is-append-order-and-survives-two-entries-in-one-second
  ;; `created_at` is second-resolution, so a queue ordered on it would be ordered
  ;; on a tie — and two entries in one second is the normal case here, not a
  ;; corner. This writes several in one go and then makes the tie explicit by
  ;; stamping every row with the same second.
  (let [a (:id (machine-create! "First"))
        b (:id (machine-create! "Second"))]
    (machine :put (str "/api/recipes/" a) {:description "body v2"})
    (machine :put (str "/api/recipes/" b) {:description "body v2"})
    (jdbc/execute-one! (db/get-conn h/*ds*)
      (sql/format {:update :recipe_events :set {:created_at "2026-01-01 00:00:00"}}))
    (let [entries (inbox)]
      (is (= 4 (count entries)))
      (is (apply < (map :id entries)) "ascending by id, which is the append order")
      (is (= [["First" "created"] ["Second" "created"]
              ["First" "modified"] ["Second" "modified"]]
             (mapv (juxt :recipe_title :kind) entries))
          "so the oldest change is at the top and the newest at the bottom, with
           every row sharing one timestamp — which is exactly the case an ordering
           on `created_at` gets wrong")
      (is (= 1 (count (set (map :created_at entries))))
          "and the timestamps really are all the same, or the assertion above
           proves nothing"))))

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

;; ---------------------------------------------------------------------------
;; what reading it must not touch

(deftest reading-the-inbox-moves-no-view-count-and-no-modified-at
  ;; Reviewing what an agent wrote is not consuming a Recipe. `view_count` ranks the
  ;; shelf, so an inbox that counted reads would quietly reorder his Cookbook every
  ;; time he went through the queue; and `modified_at` is the optimistic-concurrency
  ;; guard, so moving it would 409 a save he had in flight.
  (let [{:keys [id]} (machine-create! "Sourdough")
        before (first (filter #(= id (:id %)) (:body (GET-json "/api/recipes"))))]
    (dotimes [_ 3] (GET-json "/api/inbox"))
    (h/API :post (str "/api/inbox/" (:id (first (inbox))) "/seen") {})
    (let [after (first (filter #(= id (:id %)) (:body (GET-json "/api/recipes"))))]
      (is (= 0 (:view_count after)) "no read was counted")
      (is (= (:modified_at before) (:modified_at after)) "and the stamp did not move")
      (is (= before after) "nothing about the row moved at all"))))

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

(deftest resetting-the-database-empties-the-inbox
  ;; `delete-recipe` deliberately leaves a Recipe's events behind; a reset must not.
  ;; A queue naming Recipes that no longer exist at all is a record of nothing, and
  ;; a fixture that half-resets is one a later test can pass because of the half
  ;; that stayed.
  (let [{:keys [id]} (machine-create! "Written by an agent")]
    (machine :put (str "/api/recipes/" id) {:description "body v2"})
    (is (= 2 (count (inbox))))
    (is (= 200 (:status (h/API :post "/api/test/reset" {}))))
    (is (empty? (inbox)))
    (is (empty? (:body (GET-json "/api/recipes"))))))
