(ns et.cb.auth
  (:require [buddy.sign.jwt :as jwt]
            [clojure.string :as str]))

(defn jwt-secret []
  (or (System/getenv "ADMIN_PASSWORD") "dev-secret"))

(defn create-token
  ([user-id username is-admin]
   (create-token {:user-id user-id :username username :is-admin is-admin}))
  ([claims]
   (jwt/sign claims (jwt-secret))))

(defn create-machine-token
  "Token for a non-human API client (agent, script). Marked `:machine? true`.

  Unlike its siblings, cookbook does **not** hold such a token read-only: this
  is an agentic memory store, so it writes unsupervised, with no gate and no
  toggle. The one thing it cannot cross is the publish latch — a published
  Recipe is the owner's and a machine caller may not change it."
  [user-id username]
  (create-token {:user-id user-id :username username :is-admin false
                 :machine? true :machine-username username}))

(defn verify-token [token]
  (try
    (jwt/unsign token (jwt-secret))
    (catch Exception _ nil)))

(defn extract-token [req]
  (when-let [auth-header (get-in req [:headers "authorization"])]
    (when (str/starts-with? auth-header "Bearer ")
      (subs auth-header 7))))
