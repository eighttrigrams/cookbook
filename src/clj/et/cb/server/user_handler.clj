(ns et.cb.server.user-handler
  (:require [clojure.string :as str]
            [et.cb.server.common :as common]
            [et.cb.db.user :as db.user]
            [et.cb.auth :as auth]))

(defn password-required-handler
  "GET /api/auth/required — whether the client must log in. False in dev with
  :dangerously-skip-logins?, true in production."
  [_req]
  {:status 200 :body {:required (not (common/allow-skip-logins?))}})

(defn login-handler
  "POST /api/auth/login — exchange {:username :password} for a JWT.
  200 {:token :user} on success, 401 otherwise.

  **The machine user's audience is resolved here, once, at mint time.** Its recipes
  are the owner's: cookbook keys rows by `user_id`, and the machine has a
  `users` row of its own, so a token carrying the machine's own id would show it
  an empty shelf — a bug that reads as 'the API is broken'. A machine row is
  therefore minted a *machine* token whose `:user-id` is its `for_user_id`, i.e.
  the owner's. `db/user-id-where-clause` is then already correct everywhere, and
  no future handler can forget a resolution step it never has to do.

  **A `for_user_id` of NULL falls back to the first human user**, which is the
  same rule dev's skip-logins already resolves the owner by. It is NULL for every
  machine user created in dev, because the ⚙ panel stores the caller's id and the
  dev owner has no `users` row to have an id — correct when it is written, and
  silently wrong the moment a human row appears (a local prod-mode run against the
  dev database seeds `admin` at startup, say). Without the fallback the machine
  would keep acting as the nil owner: an empty shelf again, and every row it wrote
  owned by nobody and 404 to the one person meant to read it. Resolving it here
  rather than repairing the row keeps the single resolution point — a stored value
  can go stale, and nothing prompts the password reset that would rewrite it.

  It also reads `is-admin` off the row instead of assuming it: the machine is not
  an admin, and saying otherwise would hand an unsupervised writer the owner's
  authority as well as his audience."
  [req]
  (let [ds (common/ensure-ds)
        {:keys [username password]} (:body req)
        user (db.user/verify-user ds username password)]
    (if user
      (let [machine? (= 1 (:is_machine_user user))
            ;; the audience the token reads in — the owner's rows, never the
            ;; machine's own, and never nobody's
            acting-id (if machine?
                        (or (:for_user_id user) (:id (db.user/first-human-user ds)))
                        (:id user))]
        {:status 200
         :body {:token (if machine?
                         (auth/create-machine-token acting-id (:username user))
                         (auth/create-token (:id user) (:username user) true))
                :user {:id acting-id
                       :username (:username user)
                       :is-admin (not machine?)
                       :is-machine machine?}}})
      {:status 401 :body {:error "Invalid credentials"}})))

(defn me-handler
  "GET /api/auth/me — the authenticated caller's {:id :username :is-admin
  :is-machine}. 401 when no valid token is presented.

  For a machine caller `:username` is the machine's own name while `:id` is the
  **owner's** id, because that is the audience its token reads in — see
  `login-handler`. `:is-machine` is what makes that pair readable rather than
  confusing."
  [req]
  (let [claims (some-> (auth/extract-token req) auth/verify-token)]
    (if claims
      {:status 200 :body {:id (:user-id claims)
                          :username (:username claims)
                          :is-admin (boolean (:is-admin claims))
                          :is-machine (boolean (:machine? claims))}}
      {:status 401 :body {:error "Not authenticated"}})))

;; ---------------------------------------------------------------------------
;; the one machine user
;;
;; Only the owner may look at this or change it. A machine rotating its own
;; credential is not part of writing unsupervised — it is how an agent would lock
;; the owner out of his own store — so a machine token is refused here even
;; though it is refused almost nowhere else in this app.

(defn- owner-caller
  "The signed-in owner, or nil for a machine caller and for an anonymous one.
  `common/owner-caller?` is the predicate, shared with the other routes that are
  the owner's alone, so they cannot come to disagree about who that is; this only
  adds the caller's claims, which is what the password route needs for its id."
  [req]
  (when (common/owner-caller? req)
    (common/get-user-from-request req)))

(defn- present-machine-user
  "What the client may know: that the row is there, its fixed name, and when the
  password was last set. Never the password, and never its hash."
  [row]
  (if row
    {:exists true
     :username (:username row)
     :password_set_at (:password_set_at row)}
    {:exists false
     :username db.user/machine-username}))

(defn machine-user-handler
  "GET /api/machine-user — whether the one machine user exists, its fixed
  username, and when its password was last set. Never the password or its hash.
  403 unless the caller is the owner: a machine may not inspect or rotate its own
  credential."
  [req]
  (if (owner-caller req)
    {:status 200 :body (present-machine-user
                         (db.user/get-machine-user (common/ensure-ds)))}
    {:status 403 :body {:error "Only the owner can manage the machine user"}}))

(defn set-machine-user-password-handler
  "PUT /api/machine-user/password — set or reset the machine user's password from
  {:password}. Creating the machine user and changing its password are the same
  operation on a fixed username, so there is no separate create route and no
  username field.

  The owner's id comes from the caller, never from the body, and is stored as the
  row's `for_user_id` — which is what `login-handler` mints the machine's token
  with. 200 with the same shape as the GET, 400 on a blank password, 403 unless
  the caller is the owner."
  [req]
  (if-let [{:keys [user-id]} (owner-caller req)]
    (let [{:keys [password]} (:body req)]
      (if (str/blank? (str password))
        {:status 400 :body {:error "password is required"}}
        {:status 200
         :body (present-machine-user
                 (db.user/set-machine-user-password! (common/ensure-ds) user-id password))}))
    {:status 403 :body {:error "Only the owner can manage the machine user"}}))
