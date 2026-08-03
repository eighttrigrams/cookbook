(ns et.cb.integration-helpers
  (:require [ring.mock.request :as mock]
            [et.cb.db :as db]
            [et.cb.db.user :as db.user]
            [et.cb.server :as server]
            [et.cb.server.common :as common]
            [et.cb.auth :as auth]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.params :refer [wrap-params]]
            [cheshire.core :as json]
            [taoensso.telemere :as tel]))

(tel/remove-handler! :default/console)

(def ^:dynamic *app* nil)
(def ^:dynamic *ds* nil)
(def ^:dynamic *user-id* nil)

(defn make-app []
  (-> server/app-routes
      (wrap-params)
      (wrap-json-body {:keywords? true})
      (wrap-json-response)))

(defn with-integration-db [f]
  (let [conn (db/init-conn {:type :sqlite-memory})]
    (try
      (reset! common/ds conn)
      (reset! common/*config {:dangerously-skip-logins? true})
      (let [user (db.user/create-user conn "test-user" "testpass")]
        (with-redefs [common/prod-mode? (constantly false)]
          (binding [*app* (make-app)
                    *ds* conn
                    *user-id* (:id user)]
            (f))))
      (finally
        (reset! common/ds nil)
        (reset! common/*config nil)
        (when-let [pc (:persistent-conn conn)]
          (.close pc))))))

(defn with-real-auth* [f]
  (let [config @common/*config]
    (try
      (swap! common/*config assoc :dangerously-skip-logins? false)
      (f)
      (finally (reset! common/*config config)))))

(defmacro with-real-auth
  "Run the body with `:dangerously-skip-logins?` false in the config, so a request
  without a Bearer token is a genuinely anonymous one. The config value rather
  than a redef of `allow-skip-logins?`, so the real code path is the one under
  test."
  [& body]
  `(with-real-auth* (fn [] ~@body)))

(defn with-prod-app* [f]
  (let [config @common/*config]
    (try
      (with-redefs [common/prod-mode? (constantly true)]
        (binding [*app* (server/build-app {})]
          (f)))
      (finally (reset! common/*config config)))))

(defmacro with-prod-app
  "Run the body against the app as production assembles it — `wrap-auth` in the
  chain, so a mutating request without a valid token never reaches a handler.
  `build-app` decides that from `prod-mode?`, hence the redef; skip-logins is off
  either way once prod-mode? says yes."
  [& body]
  `(with-prod-app* (fn [] ~@body)))

(defn token-for [user-id]
  (auth/create-token user-id "test-user" true))

(defn machine-token-for
  "A machine token exactly as `login-handler` mints one: the token's `:user-id` is
  the **owner's**, not the machine row's, because that resolution happens once at
  mint time. Pass the owner's id — a test that passed the machine row's id would
  be testing a token the app never issues."
  [owner-id]
  (auth/create-machine-token owner-id "machine-user"))

(defn- build-request [method path {:keys [body token anonymous? as-user]}]
  (cond-> (mock/request method path)
    token (mock/header "Authorization" (str "Bearer " token))
    (and (not token) (not anonymous?))
    (mock/header "X-User-Id" (str (or as-user *user-id*)))
    body (-> (mock/header "Content-Type" "application/json")
             (mock/body (json/generate-string body)))))

(defn API-raw
  "One request, body left exactly as the app produced it. For the routes that
  answer HTML or CSS rather than JSON."
  [method path opts]
  (*app* (build-request method path opts)))

(defn API
  "One request. Without :token the dev skip-logins header identifies the owner
  (:as-user to make that somebody else); with :anonymous? neither is sent, which
  is the only way to see what a visitor sees."
  [method path opts]
  (update (API-raw method path opts) :body #(when (seq %) (json/parse-string % true))))

(defn GET-json [path] (API :get path {}))
(defn POST-json [path body] (API :post path {:body body}))
(defn PUT-json [path body] (API :put path {:body body}))
(defn DELETE-json [path] (API :delete path {}))
