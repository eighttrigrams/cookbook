(ns et.cb.machine-user-integration-test
  "The one machine user's credential, over HTTP: who may set it, what a login with
  it mints, and the audience that login resolves to.

  The bug this file exists to prevent is quiet: cookbook keys recipes by
  `user_id` and the machine has a `users` row of its own, so a token carrying the
  machine's own id would authenticate perfectly and then show an **empty shelf**.
  Nothing raises. So the central test logs in over HTTP with the real password and
  asserts the list it gets back is *the owner's*."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.cb.db :as db]
            [et.cb.db.user :as db.user]
            [et.cb.integration-helpers :as h :refer [POST-json]]))

(use-fixtures :each h/with-integration-db)

(defn- set-password!
  "The owner setting or resetting the machine password through the real route."
  [password & [opts]]
  (h/API :put "/api/machine-user/password" (merge {:body {:password password}} opts)))

(defn- machine-row []
  (jdbc/execute-one! (db/get-conn h/*ds*)
    (sql/format {:select [:id :username :is_machine_user :for_user_id :password_set_at]
                 :from [:users] :where [:= :is_machine_user 1]})
    db/jdbc-opts))

(defn- login [username password]
  (POST-json "/api/auth/login" {:username username :password password}))

(deftest the-machine-user-is-created-by-setting-its-password
  (testing "before anything, the owner is told it does not exist — with its fixed name"
    (let [resp (h/API :get "/api/machine-user" {})]
      (is (= 200 (:status resp)))
      (is (false? (:exists (:body resp))))
      (is (= "machine-user" (:username (:body resp))))
      (is (nil? (:password_set_at (:body resp))))))

  (testing "setting a password creates it — one operation, no separate add route"
    (let [resp (set-password! "machine-secret")]
      (is (= 200 (:status resp)))
      (is (true? (:exists (:body resp))))
      (is (= "machine-user" (:username (:body resp))))
      (is (some? (:password_set_at (:body resp))))))

  (testing "the row is flagged as the machine and points at the owner"
    (let [row (machine-row)]
      (is (= "machine-user" (:username row)))
      (is (= 1 (:is_machine_user row)))
      (is (= h/*user-id* (:for_user_id row))
          "for_user_id is the owner's id — this is what mint-time resolution reads")))

  (testing "a blank password is refused and changes nothing"
    (let [before (machine-row)]
      (is (= 400 (:status (set-password! ""))))
      (is (= 400 (:status (set-password! "   "))))
      (is (= before (machine-row)))))

  (testing "setting it again is a reset, not a second row"
    (let [before (machine-row)]
      (is (= 200 (:status (set-password! "a-new-secret"))))
      (is (= (:id before) (:id (machine-row))))
      (is (= 1 (:n (jdbc/execute-one! (db/get-conn h/*ds*)
                     (sql/format {:select [[[:count :*] :n]] :from [:users]
                                  :where [:= :is_machine_user 1]})
                     db/jdbc-opts))))
      (testing "and the old password stops working"
        (is (= 401 (:status (login "machine-user" "machine-secret"))))
        (is (= 200 (:status (login "machine-user" "a-new-secret"))))))))

(deftest a-machine-login-reads-the-owners-audience-not-its-own
  (let [{owned :id} (:body (POST-json "/api/recipes" {:title "The owner's recipe"}))]
    (set-password! "machine-secret")
    (let [resp (login "machine-user" "machine-secret")
          {:keys [token user]} (:body resp)]
      (testing "the login succeeds and says what the caller is"
        (is (= 200 (:status resp)))
        (is (some? token))
        (is (true? (:is-machine user)))
        (is (false? (:is-admin user))
            "a machine is not an admin — login used to hardcode this true"))

      (testing "the token's audience is the owner's id, not the machine row's"
        (is (= h/*user-id* (:id user)))
        (is (not= (:id (machine-row)) (:id user))))

      (testing "so the shelf it reads is the owner's, and not empty"
        (let [listed (h/API :get "/api/recipes" {:token token})]
          (is (= 200 (:status listed)))
          (is (seq (:body listed)) "an empty list here is the bug this test exists for")
          (is (contains? (set (map :id (:body listed))) owned))))

      (testing "and a recipe it writes belongs to the owner"
        (let [{created :id} (:body (h/API :post "/api/recipes"
                                         {:token token :body {:title "By the agent"}}))]
          (is (= h/*user-id*
                 (:user_id (jdbc/execute-one! (db/get-conn h/*ds*)
                             (sql/format {:select [:user_id] :from [:recipes]
                                          :where [:= :id created]})
                             db/jdbc-opts))))
          (testing "which the owner can see too — the audiences did not diverge"
            (is (contains? (set (map :id (:body (h/API :get "/api/recipes" {}))))
                           created)))))

      (testing "me reports the caller honestly"
        (let [me (h/API :get "/api/auth/me" {:token token})]
          (is (= 200 (:status me)))
          (is (= "machine-user" (:username (:body me))))
          (is (true? (:is-machine (:body me))))
          (is (false? (:is-admin (:body me))))
          (is (= h/*user-id* (:id (:body me)))))))))

(defn- sql-exec! [statement]
  (jdbc/execute-one! (db/get-conn h/*ds*) (sql/format statement)))

(deftest creating-the-machine-user-does-not-displace-the-dev-owner
  ;; Dev's actual shape, which no other test has: the owner has **no** `users`
  ;; row at all — so his recipes are NULL-owned and skip-logins resolves him by
  ;; taking a row out of the table — and the machine user is the only row there.
  ;; Resolving "the first row" rather than "the first human" would hand the dev
  ;; owner the machine's user-id and empty his shelf, which looks exactly like
  ;; the app losing his data.
  (let [{id :id} (:body (POST-json "/api/recipes" {:title "The dev owner's recipe"}))]
    (sql-exec! {:update :recipes :set {:user_id nil} :where [:= :id id]})
    (set-password! "machine-secret")
    (sql-exec! {:delete-from :users :where [:= :is_machine_user 0]})
    (testing "the machine row is now the only row in users"
      (is (= 1 (:n (jdbc/execute-one! (db/get-conn h/*ds*)
                     (sql/format {:select [[[:count :*] :n]] :from [:users]})
                     db/jdbc-opts)))))
    (testing "and a dev request with no token and no user header is still the owner"
      ;; skip-logins is on (no `with-real-auth`), so this is the dev owner, not a
      ;; visitor — the request simply carries nothing that names a user.
      (let [listed (h/API :get "/api/recipes" {:anonymous? true})]
        (is (= 200 (:status listed)))
        (is (= #{id} (set (map :id (:body listed))))
            "an empty shelf here means the machine row was mistaken for the owner")))))

(defn- recipe-user-id [id]
  (:user_id (jdbc/execute-one! (db/get-conn h/*ds*)
              (sql/format {:select [:user_id] :from [:recipes] :where [:= :id id]})
              db/jdbc-opts)))

(deftest a-machine-minted-with-no-owner-follows-the-owner-who-appears
  ;; The other direction of the same bug, and the one dev actually produces. The ⚙
  ;; panel stores the *caller's* id as `for_user_id`, and in dev the owner has no
  ;; `users` row, so it stores NULL — correct while he is the nil owner. Then a
  ;; human row appears (a local prod-mode run against the dev database seeds
  ;; `admin` at startup) and nothing repairs the machine's row: it would keep
  ;; acting as the nil owner, reading an empty shelf and writing rows owned by
  ;; nobody, which the owner is then the one person who cannot open.
  (let [{owned :id} (:body (POST-json "/api/recipes" {:title "The owner's recipe"}))]
    ;; dev's shape: no human row at all, so the panel has no owner id to store
    (sql-exec! {:delete-from :users :where [:= :is_machine_user 0]})
    (set-password! "machine-secret" {:anonymous? true})
    (testing "the row really is stored with no owner — this is what dev writes"
      (is (some? (machine-row)))
      (is (nil? (:for_user_id (machine-row)))))

    (let [human (:id (db.user/create-user h/*ds* "admin" "adminpass"))]
      (sql-exec! {:update :recipes :set {:user_id human} :where [:= :id owned]})
      (let [{:keys [token user]} (:body (login "machine-user" "machine-secret"))]
        (testing "the token is minted in that owner's audience rather than nobody's"
          (is (= human (:id user)))
          (is (true? (:is-machine user))))

        (testing "so the shelf it reads is his, and not empty"
          (let [listed (h/API :get "/api/recipes" {:token token})]
            (is (= 200 (:status listed)))
            (is (seq (:body listed)) "an empty shelf here is the bug this test exists for")
            (is (= #{owned} (set (map :id (:body listed)))))))

        (testing "and what it writes belongs to the owner, who can open it"
          (let [{created :id} (:body (h/API :post "/api/recipes"
                                            {:token token :body {:title "By the agent"}}))]
            (is (= human (recipe-user-id created))
                "a nil here is a row owned by nobody — invisible to the owner")
            (is (= 200 (:status (h/API :get (str "/api/recipes/" created)
                                       {:as-user human}))))))

        (testing "and the stored NULL is still NULL — the resolution follows the
                  owner, rather than the row being quietly rewritten"
          (is (nil? (:for_user_id (machine-row)))))))))

(deftest a-human-login-is-unchanged
  (let [resp (login "test-user" "testpass")
        {:keys [token user]} (:body resp)]
    (is (= 200 (:status resp)))
    (is (= h/*user-id* (:id user)))
    (is (true? (:is-admin user)))
    (is (false? (:is-machine user)))
    (testing "and that token is not a machine token"
      (let [me (h/API :get "/api/auth/me" {:token token})]
        (is (false? (:is-machine (:body me))))
        (is (true? (:is-admin (:body me))))))))

(deftest only-the-owner-may-manage-the-machine-user
  (set-password! "machine-secret")
  (let [machine-token (:token (:body (login "machine-user" "machine-secret")))]
    (testing "a machine may not read the credential's state"
      (is (= 403 (:status (h/API :get "/api/machine-user" {:token machine-token})))))
    (testing "nor rotate its own password — that is how an agent would lock the owner out"
      (let [before (machine-row)]
        (is (= 403 (:status (set-password! "chosen-by-the-agent" {:token machine-token}))))
        (is (= before (machine-row)))
        (is (= 200 (:status (login "machine-user" "machine-secret")))
            "the password the owner set still works")))
    (testing "and an anonymous caller may do neither"
      (h/with-real-auth
        (is (= 403 (:status (h/API :get "/api/machine-user" {:anonymous? true}))))
        (let [before (machine-row)]
          (is (= 403 (:status (set-password! "by-nobody" {:anonymous? true}))))
          (is (= before (machine-row))))))))

(deftest at-most-one-machine-user-and-sql-is-what-says-so
  (set-password! "machine-secret")
  (testing "a second machine row is refused by the database, not merely by a handler"
    (let [ex (try
               (jdbc/execute-one! (db/get-conn h/*ds*)
                 (sql/format {:insert-into :users
                              :values [{:username "second-machine"
                                        :password_hash "irrelevant"
                                        :is_machine_user 1
                                        :for_user_id h/*user-id*}]}))
               nil
               (catch Exception e e))]
      (is (some? ex) "the partial unique index has to reject this insert")
      (is (str/includes? (str (ex-message ex)) "UNIQUE"))))
  (testing "and there is still exactly one"
    (is (= 1 (:n (jdbc/execute-one! (db/get-conn h/*ds*)
                   (sql/format {:select [[[:count :*] :n]] :from [:users]
                                :where [:= :is_machine_user 1]})
                   db/jdbc-opts))))))

(deftest no-endpoint-ever-returns-a-password-hash
  (set-password! "machine-secret")
  (let [machine-token (:token (:body (login "machine-user" "machine-secret")))
        human-token (:token (:body (login "test-user" "testpass")))
        responses {"machine login"    (h/API-raw :post "/api/auth/login"
                                                 {:body {:username "machine-user"
                                                         :password "machine-secret"}})
                   "human login"      (h/API-raw :post "/api/auth/login"
                                                 {:body {:username "test-user"
                                                         :password "testpass"}})
                   "machine-user GET" (h/API-raw :get "/api/machine-user" {})
                   "password PUT"     (h/API-raw :put "/api/machine-user/password"
                                                 {:body {:password "machine-secret"}})
                   "me (machine)"     (h/API-raw :get "/api/auth/me" {:token machine-token})
                   "me (human)"       (h/API-raw :get "/api/auth/me" {:token human-token})}]
    ;; the raw body, so this catches a hash under any key name, not just the one
    ;; the handler happens to use today
    (doseq [[label resp] responses]
      (testing label
        (is (not (str/includes? (str (:body resp)) "password_hash")) label)
        (is (not (str/includes? (str (:body resp)) "bcrypt")) label)
        (is (not (str/includes? (str (:body resp)) "machine-secret")) label)))
    (testing "and the db layer's own machine-user read never selects the column"
      (is (not (contains? (db.user/get-machine-user h/*ds*) :password_hash))))))

(deftest describe-lists-every-route-and-nothing-else
  (let [routes (h/describe-endpoints)
        paths (set (map (juxt :method :path) routes))]
    (testing "the two new routes are published in the catalogue"
      (is (contains? paths ["GET" "/api/machine-user"]))
      (is (contains? paths ["PUT" "/api/machine-user/password"])))
    ;; This set is the "nothing else" half of the name, and it used to lock in a
    ;; catalogue that omitted `POST /api/test/reset` — a real route, and the only
    ;; destructive one — because its handler was private and its docstring did not
    ;; match `route-doc-re`. So the "every route" half was false while this
    ;; assertion looked like it proved otherwise, which is why nothing in this suite
    ;; ever mentioned that the route had no caller check. It is in the catalogue now
    ;; and in this set; an agent reading the list is exactly who should be told.
    (testing "every route is there and no non-route var leaked in"
      (is (= #{["GET" "/api/describe"]
               ["GET" "/api/auth/required"] ["GET" "/api/auth/me"] ["POST" "/api/auth/login"]
               ["GET" "/api/machine-user"] ["PUT" "/api/machine-user/password"]
               ["GET" "/api/recipes"] ["POST" "/api/recipes"]
               ["GET" "/api/recipes/:id"] ["PUT" "/api/recipes/:id"]
               ["DELETE" "/api/recipes/:id"]
               ["POST" "/api/recipes/:id/publish"] ["GET" "/api/recipes/:id/versions"]
               ["GET" "/api/scopes"] ["POST" "/api/scopes"]
               ["PUT" "/api/scopes/:id"] ["DELETE" "/api/scopes/:id"]
               ["POST" "/api/test/reset"]}
             paths)))
    (testing "and the destructive one says so, and says who may call it"
      (let [reset (first (filter #(= ["POST" "/api/test/reset"] ((juxt :method :path) %)) routes))]
        (is (some? reset))
        ;; \s+ rather than a space: these docstrings are wrapped, so a literal
        ;; " " would be asserting where the line breaks fall
        (is (re-find #"(?i)dev\s+only" (:doc reset)))
        (is (re-find #"(?i)owner's\s+alone" (:doc reset)))))
    (testing "no middleware or helper is advertised as callable"
      (is (empty? (filter #(str/includes? (:name %) "wrap-") routes))))))
