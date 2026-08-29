(ns et.cb.integration-helpers
  (:require [ring.mock.request :as mock]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
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

(defn clear-source!
  "Try to put a Recipe's current version back into the state migration 005 found
  everything in — `source` NULL, provenance never recorded — and **fail**, which is
  now the only thing this function is for.

  It used to work, and every test about the third bucket needed it: no request could
  produce that state, and yet it was the state every Recipe on the owner's shelf was
  in until he next saved one. Migration 010 wrote his answer for those versions down
  and made the column `NOT NULL CHECK (source IN ('ui','machine'))`, so this now
  raises `SQLiteException` — and it is kept, unaltered in what it attempts, because
  a test that asserts the category is gone should assert it against the statement
  that used to create it. Deleting the helper would leave that claim resting on
  nothing."
  [recipe-id]
  (jdbc/execute-one! (db/get-conn *ds*)
    (sql/format {:update :recipes :set {:source nil} :where [:= :id recipe-id]})))

(defn token-for [user-id]
  (auth/create-token user-id "test-user" true))

(defn machine-token-for
  "A machine token exactly as `login-handler` mints one: the token's `:user-id` is
  the **owner's**, not the machine row's, because that resolution happens once at
  mint time. Pass the owner's id — a test that passed the machine row's id would
  be testing a token the app never issues."
  [owner-id]
  (auth/create-machine-token owner-id "machine-user"))

(def machine-explanation
  "The `reason` and `context` every machine write to a Recipe route must carry
  since migration 015, as a test would send them.

  **Merged into a machine's write body by `build-request` rather than typed at
  sixty call sites**, and that is a decision about what these tests are *for*. Every
  one of them — the caution splits, the provenance labels, the proposal matrix — is
  about something else, and a machine `PUT` that omitted the pair would now be a 400
  in all of them: sixty tests failing for one reason none of them is about, and
  sixty places to edit again the next time a write gains a required field.

  A helper that made the tests exempt from the rule would be worse, so it does not:
  it sends what a real agent has to send. The rule itself is asserted where it
  belongs, in `et.cb.write-reason-integration-test`, which builds its bodies by hand
  precisely so that this default cannot satisfy the assertions about its absence."
  {:reason "why the test's agent made this change"
   :context "the test that was running when it did"})

(defn- machine-token?
  "Whether this token is a machine's, read off the claim rather than off how the
  test happened to obtain the string — the same question `common/machine-caller?`
  asks of a real request."
  [token]
  (boolean (:machine? (auth/verify-token token))))

(defn- build-request [method path {:keys [body token anonymous? as-user]}]
  (let [body (cond-> body
               ;; Only a machine's write, and only a write: a human token's body is
               ;; left exactly as the test wrote it, because the owner's saves carry
               ;; neither field and a helper adding them would be storing an
               ;; explanation the app says he never gives.
               (and body token (machine-token? token) (#{:post :put} method))
               (#(merge machine-explanation %)))]
    (cond-> (mock/request method path)
      token (mock/header "Authorization" (str "Bearer " token))
      (and (not token) (not anonymous?))
      (mock/header "X-User-Id" (str (or as-user *user-id*)))
      body (-> (mock/header "Content-Type" "application/json")
               (mock/body (json/generate-string body))))))

(defn API-raw
  "One request, body left exactly as the app produced it. For the routes that
  answer HTML or CSS rather than JSON."
  [method path opts]
  (*app* (build-request method path opts)))

(defn API
  "One request. Without :token the dev skip-logins header identifies the owner
  (:as-user to make that somebody else); with :anonymous? neither is sent.

  **`:anonymous?` alone does not make a visitor — wrap it in `with-real-auth`.**
  This fixture runs with `:dangerously-skip-logins? true`, and
  `common/get-user-from-request` falls through to the first human user whenever
  the token is missing *or* invalid: so a request with no credentials is served as
  the **owner**, and so is one carrying a bogus `Bearer`. Sending nothing is only
  half of being nobody; `with-real-auth` turns the flag off, which is what makes
  the other half true. A privacy assertion made on `:anonymous?` on its own is an
  assertion about the owner's view, and it will pass for the wrong reason."
  [method path opts]
  (update (API-raw method path opts) :body #(when (seq %) (json/parse-string % true))))

(defn describe-endpoints
  "The route catalogue out of GET /api/describe.

  It lives under `:endpoints` now: describe answers a **map with named sections**
  (`{:endpoints […] :scopes […]}`, tracker's shape) rather than the bare vector of
  routes it used to be, because the owner's Scopes had to be listed there too and
  a second section cannot be a member of a list of callable routes.

  Every test that reads the catalogue goes through this one function, so the next
  time that shape moves it is one edit and not eight. Takes the same opts as `API`,
  which is what lets a test ask as an anonymous caller."
  ([] (describe-endpoints {}))
  ([opts] (:endpoints (:body (API :get "/api/describe" opts)))))

(defn GET-json [path] (API :get path {}))
(defn POST-json [path body] (API :post path {:body body}))
(defn PUT-json [path body] (API :put path {:body body}))
(defn DELETE-json [path] (API :delete path {}))
