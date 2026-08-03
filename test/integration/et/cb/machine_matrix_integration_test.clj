(ns et.cb.machine-matrix-integration-test
  "Who may do what to a Recipe. This is the whole security surface of the app, so
  it is written out as an exhaustive table of cases rather than illustrated with a
  few examples.

  The two rows that *are* the feature:

  | caller  | recipe      | create | edit | delete | publish | read   |
  |---------|-------------|--------|------|--------|---------|--------|
  | owner   | unpublished | 201    | 200  | 200    | 200     | 200    |
  | owner   | published   | –      | 200  | 200    | 200 no-op | 200  |
  | machine | unpublished | 201    | 200  | 200    | **403** | 200    |
  | machine | published   | –      | **403** | **403** | **403** | 200 |
  | anon    | unpublished | 401    | 401  | 401    | 401     | absent |
  | anon    | published   | 401    | 401  | 401    | 401     | 200    |

  A machine writes unsupervised — that is what cookbook is for — and the one wall
  it meets is the publish latch. Every ✓ case therefore asserts the write actually
  **landed in the row**, and every refusal asserts the row did **not** change: a
  status code alone would pass against a gate that swallows writes and answers
  200 anyway, which is exactly the regression this file has to catch."
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
;; no scoped GET left to ask.

(defn- row [id]
  (jdbc/execute-one! (db/get-conn h/*ds*)
    (sql/format {:select [:id :title :version :published :published_at :user_id]
                 :from [:recipes] :where [:= :id id]})
    db/jdbc-opts))

(defn- row-count []
  (:n (jdbc/execute-one! (db/get-conn h/*ds*)
        (sql/format {:select [[[:count :*] :n]] :from [:recipes]}) db/jdbc-opts)))

(defn- owner-recipe!
  "A recipe belonging to the owner, published or not. Created as the owner, since
  a machine may not publish and an anonymous caller may not write at all."
  [state title]
  (let [{:keys [id]} (:body (POST-json "/api/recipes" {:title title
                                                       :useful_when "when testing"
                                                       :description "the body"}))]
    (when (= state :published)
      (is (= 200 (:status (h/API :post (str "/api/recipes/" id "/publish") {})))))
    id))

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

   ;; --- the machine: unsupervised, until it meets the latch ---------------
   {:caller :machine :state :unpublished :op :create  :expect 201 :lands? true}
   {:caller :machine :state :unpublished :op :edit    :expect 200 :lands? true}
   {:caller :machine :state :unpublished :op :delete  :expect 200 :lands? true}
   {:caller :machine :state :unpublished :op :publish :expect 403 :lands? false}
   {:caller :machine :state :unpublished :op :read    :expect 200 :visible? true}
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
  (doseq [{:keys [caller state op expect lands?] :as spec} matrix]
    (testing (str (name caller) " / " (name state) " recipe / " (name op))
      (let [id (owner-recipe! state (str (name caller) "-" (name state) "-" (name op)))
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
;; the gate that must not come back

(deftest a-machine-write-lands-because-there-is-no-recording-gate
  (let [id (owner-recipe! :unpublished "Written by an agent")
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
