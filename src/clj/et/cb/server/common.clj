(ns et.cb.server.common
  (:require [et.cb.db :as db]
            [et.cb.auth :as auth]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [aero.core :as aero]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
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
  skip-logins, defaults to the first user (or the nil-owner admin when there
  are none), optionally overridden by an x-user-id header."
  [req]
  (or (some-> (auth/extract-token req) auth/verify-token)
      (when (allow-skip-logins?)
        (let [user-id-str (get-in req [:headers "x-user-id"])]
          (if (or (nil? user-id-str) (= user-id-str "null"))
            (let [first-user (jdbc/execute-one! (db/get-conn (ensure-ds))
                               (sql/format {:select [:id] :from [:users]
                                            :order-by [[:id :asc]] :limit 1})
                               db/jdbc-opts)]
              {:user-id (:id first-user) :is-admin true})
            {:user-id (Integer/parseInt user-id-str) :is-admin false})))))

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
