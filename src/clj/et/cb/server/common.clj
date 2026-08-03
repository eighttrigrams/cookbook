(ns et.cb.server.common
  (:require [et.cb.db :as db]
            [et.cb.db.user :as db.user]
            [et.cb.auth :as auth]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [aero.core :as aero]
            [taoensso.telemere :as tel]))

(defonce ds (atom nil))
(defonce *config (atom nil))

(defn load-config []
  (let [config-file (io/file "config.edn")]
    (if (.exists config-file)
      (do
        (tel/log! :info "Loading configuration from config.edn")
        (aero/read-config config-file))
      (do
        (tel/log! :info "config.edn not found, using defaults")
        {}))))

(defn ensure-ds []
  (when (nil? @ds)
    (when (nil? @*config)
      (reset! *config (load-config)))
    (let [conn (db/init-conn (get @*config :db {:type :sqlite-memory}))]
      (reset! ds conn)))
  @ds)

(defn prod-mode? []
  (let [on-fly? (some? (System/getenv "FLY_APP_NAME"))
        dev-mode? (= "true" (System/getenv "DEV"))
        admin-pw (System/getenv "ADMIN_PASSWORD")]
    (cond
      (or on-fly? (not dev-mode?))
      (do (when-not admin-pw
            (throw (ex-info "ADMIN_PASSWORD required in production" {})))
          true)
      admin-pw
      true
      :else
      false)))

(defn allow-skip-logins? []
  (and (true? (:dangerously-skip-logins? @*config))
       (not (prod-mode?))))

(defn get-user-from-request
  "Resolve the acting user. In prod, from the verified JWT. In dev with
  skip-logins, defaults to the first *human* user (or the nil-owner admin when
  there are none), optionally overridden by an x-user-id header.

  Human, not merely first: the machine user is a row in this table too, and in dev
  it is usually the only one — the owner has no row at all. Taking the first row
  of any kind would hand the dev owner the machine's user-id and empty his shelf,
  which is the same class of bug as minting a machine token scoped to the
  machine's own id."
  [req]
  (or (some-> (auth/extract-token req) auth/verify-token)
      (when (allow-skip-logins?)
        (let [user-id-str (get-in req [:headers "x-user-id"])]
          (if (or (nil? user-id-str) (= user-id-str "null"))
            {:user-id (:id (db.user/first-human-user (ensure-ds))) :is-admin true}
            {:user-id (Integer/parseInt user-id-str) :is-admin false})))))

(defn machine-caller?
  "Whether this request carries a *machine* token. Only a token can say so — the
  claim is put there by `auth/create-machine-token` at login — so a dev owner with
  no token, whom `authenticated?` deliberately accepts, is never mistaken for
  one."
  [req]
  (true? (:machine? (get-user-from-request req))))

(defn get-user-id [req]
  (:user-id (get-user-from-request req)))

;; `wrap-auth` only gates mutating requests, so a read has to decide for itself
;; whether anybody is signed in — a valid Bearer token or dev skip-logins counts
;; as the owner, anybody else is an anonymous visitor. What that decides here is
;; which Recipes exist at all for the caller: a visitor sees the published ones
;; and is not told that the private ones are there.
(defn authenticated? [req]
  (some? (get-user-from-request req)))

(defn admin-password []
  (or (System/getenv "ADMIN_PASSWORD")
      (when (= "true" (System/getenv "DEV")) "admin")
      (throw (ex-info "ADMIN_PASSWORD env var is required" {}))))

(defn is-admin? [req]
  (:is-admin (get-user-from-request req)))

(defn query-param
  "One query param's value. A repeated param (`?a=1&a=2`) reaches us from
  wrap-params as a vector of every value it was given; the last one wins, so a
  caller always gets a plain string or nil."
  [req name]
  (let [value (get-in req [:query-params name])]
    (if (sequential? value) (last value) value)))

(defn parse-int-opt [s]
  (when (and s (not (str/blank? (str s))))
    (try (Integer/parseInt (str/trim (str s)))
         (catch NumberFormatException _ nil))))

(defn recipe-id
  "The recipe a request names, or nil when it names none.

  **The one place that question is answered.** Every recipe handler calls this and
  so does `wrap-machine-recipe-rules`, deliberately: a single row has many
  spellings in the path, and the two must not resolve them differently.

  Two things make hand-rolling a second answer wrong. compojure captures `:id`
  with clout's `[^/,;?]+` and url-decodes it *after* matching, so `/11`, `/%31%31`
  and `/1%31` are one recipe to the router. And `Integer/parseInt` then accepts
  more than `\\d+` does — a leading `+`, and non-ASCII decimal digits via
  `Character/digit` — so `+11` and `١١` are that recipe too. A guard that
  disagreed with its handler about which row a path named was a guard that could be
  walked past by respelling the id."
  [req]
  (parse-int-opt (get-in req [:params :id])))
