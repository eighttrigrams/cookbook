(ns et.cb.server.user-handler
  (:require [et.cb.server.common :as common]
            [et.cb.db.user :as db.user]
            [et.cb.auth :as auth]))

(defn password-required-handler
  "GET /api/auth/required — whether the client must log in. False in dev with
  :dangerously-skip-logins?, true in production."
  [_req]
  {:status 200 :body {:required (not (common/allow-skip-logins?))}})

(defn login-handler
  "POST /api/auth/login — exchange {:username :password} for a JWT.
  200 {:token :user} on success, 401 otherwise."
  [req]
  (let [{:keys [username password]} (:body req)
        user (db.user/verify-user (common/ensure-ds) username password)]
    (if user
      {:status 200
       :body {:token (auth/create-token (:id user) (:username user) true)
              :user {:id (:id user) :username (:username user) :is-admin true}}}
      {:status 401 :body {:error "Invalid credentials"}})))

(defn me-handler
  "GET /api/auth/me — the authenticated user's {:id :username :is-admin}.
  401 when no valid token is presented."
  [req]
  (let [claims (some-> (auth/extract-token req) auth/verify-token)]
    (if claims
      {:status 200 :body {:id (:user-id claims)
                          :username (:username claims)
                          :is-admin (:is-admin claims)}}
      {:status 401 :body {:error "Not authenticated"}})))
