(ns et.cb.server
  (:require [ring.adapter.jetty9 :as jetty]
            [et.cb.db :as db]
            [et.cb.server.common :as common]
            [et.cb.server.user-handler :as user-handler]
            [et.cb.server.recipe-handler :as recipe-handler]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.auth :as auth]
            [et.cb.middleware.rate-limit :as rate-limit :refer [wrap-rate-limit]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [compojure.core :refer [defroutes routes wrap-routes
                                    GET POST PUT DELETE context]]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.cors :refer [wrap-cors]]
            [nrepl.server :as nrepl]
            [taoensso.telemere :as tel])
  (:gen-class))

(defn- env-int [name default]
  (if-let [v (System/getenv name)]
    (try (Integer/parseInt v) (catch Exception _ default))
    default))

(defn- reset-test-db-handler [_]
  (if (common/prod-mode?)
    {:status 403 :body {:error "Not available in production"}}
    (do (db/reset-all-data! (common/ensure-ds))
        (rate-limit/reset-rate-limit!)
        {:status 200 :body {:success true}})))

;; Prod uses a constant captured at JVM start; containers restart on each
;; deploy, so startup time doubles as a deploy-keyed cache buster.
(def ^:private prod-cache-bust (System/currentTimeMillis))

(defn- cache-bust []
  (if (common/prod-mode?)
    prod-cache-bust
    (let [js-file (io/file (io/resource "public/cookbook/js/main.js"))]
      (if (and js-file (.exists js-file))
        (.lastModified js-file)
        (System/currentTimeMillis)))))

(defn- serve-index [_]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (-> (io/resource "public/cookbook/index.html")
             slurp
             (str/replace "__CACHE_BUST__" (str (cache-bust))))})

(defn- serve-styles [_]
  {:status 200
   :headers {"Content-Type" "text/css"}
   :body (-> (io/resource "public/cookbook/styles.css")
             slurp
             (str/replace "__CACHE_BUST__" (str (cache-bust))))})

(def ^:private describe-namespaces
  "Namespaces whose public vars back HTTP routes. The /api/describe endpoint
  walks these to enumerate the API surface from var metadata, so the docstring
  on each handler *is* the API documentation. An agent is the primary reader of
  this API, so keeping the list current is not housekeeping."
  '[et.cb.server
    et.cb.server.user-handler
    et.cb.server.recipe-handler])

(def ^:private route-doc-re
  "Route handlers document themselves as `METHOD /path — explanation`. Matching
  on that keeps non-route helpers (build-app etc.) out of /api/describe, so the
  listing only ever advertises things you can actually call."
  #"(?s)^(GET|POST|PUT|DELETE|PATCH)\s+(\S+)\s")

(defn describe-handler
  "GET /api/describe — enumerate the API surface: every route handler with its
  method, path and docstring. Read-only and unauthenticated; lets an agent
  discover the endpoints before calling them."
  [_req]
  {:status 200
   :body (->> describe-namespaces
              (mapcat (fn [ns-sym] (when-let [n (find-ns ns-sym)] (ns-publics n))))
              (keep (fn [[sym v]]
                      (let [doc (:doc (meta v))]
                        (when-let [[_ method path] (some->> doc (re-find route-doc-re))]
                          {:name (str sym)
                           :ns (str (ns-name (.ns ^clojure.lang.Var v)))
                           :method method
                           :path path
                           :arglists (pr-str (:arglists (meta v)))
                           :doc doc}))))
              (sort-by (juxt :path :method))
              vec)})

(defn- mutating-request? [req]
  (#{:post :put :delete} (:request-method req)))

(def ^:private publish-route
  "The path of the one route that sets the latch, named once and used twice: the
  route table below is built from it and `publish-request?` compares against it, so
  the guard cannot come to disagree with the router about which request publishes.
  compojure accepts a symbol here — `compile-route` compiles a non-literal path at
  runtime — and records the route it matched in `:compojure/route` as
  `[method source]`, where `source` is the string the route was compiled from."
  "/:id/publish")

(defn- publish-request?
  "Whether compojure matched *the* publish route, asked of compojure rather than of
  the path. There is no regex here on purpose: `/11/publish`, `/%31%31/publish` and
  `/+11/publish` are all that route, and the router is the only thing that knows it
  in one answer. A path that matches no route never reaches this middleware at all,
  so a publish spelt in a way clout rejects is a 404 before it is a question."
  [req]
  (= [:post publish-route] (:compojure/route req)))

(defn- published-target?
  "Whether the named recipe is published, asked in the caller's own scope. A
  recipe the caller cannot see answers nil here, so what comes back is the
  handler's 404 rather than a 403 that would confirm the row exists."
  [req id]
  (= 1 (:published (db.recipe/get-recipe (common/ensure-ds)
                                         (common/get-user-id req) id))))

(defn- wrap-recipe-write-guard
  "Refuse a mutating recipe request from a caller nobody can identify.

  The read handlers and the write handlers ask two different questions about who
  is calling: a read asks `common/authenticated?` and falls back to
  `db.recipe/visitor-scope`, while a write asks `common/get-user-id`, which
  answers **nil** for an anonymous caller. The db layer reads a nil user-id as
  `user_id IS NULL` — a real owner in this schema, and in a dev database it is
  *every* row, because dev's admin has no user row. So without this, a caller the
  read path correctly refuses to show a private recipe to could still publish it,
  and publishing has no undo.

  This asks `common/authenticated?`, deliberately **not** whether a Bearer token
  is present: dev's `:dangerously-skip-logins?` makes `authenticated?` true while
  `get-user-id` is legitimately nil, and that is how the dev owner is
  represented. Gating on a token instead would refuse every dev write.

  **This half needs no recipe id, which is why it stays out here, in front of the
  router.** It therefore also answers for a mutating path that matches *no* route,
  and everything at or after this form inside the context is covered. The machine
  rules are the half that does need an id, and for that reason they cannot be
  answered here — see `wrap-machine-recipe-rules`.

  It sits inside `app-routes` rather than in the middleware chain, so every
  assembly of the app gets it — `app`, `build-app` for plurama, and the
  integration tests' own chain alike. That last one is the reason: the tests build
  their own chain without `wrap-auth`, so a guard in the chain would be invisible
  to them."
  [handler]
  (fn [req]
    (if (and (mutating-request? req) (not (common/authenticated? req)))
      {:status 401 :body {:error "Authentication required"}}
      (handler req))))

(defn- wrap-machine-recipe-rules
  "A published Recipe is the owner's: a machine caller may not change one, and may
  not publish at all. So, for a machine token only:

  - any mutation naming a **published** recipe → 403, which covers delete as well
    as edit, because deleting a published recipe is un-latching by demolition and
    leaving it open would be a hole in the same wall;
  - any **publish** → 403, published or not, because the latch is irreversible and
    a machine that could set it could make private content permanently public and
    freeze the recipe out of its own reach.

  Both read the token's `:machine?` claim, so a dev owner with no token — whom
  `authenticated?` deliberately accepts — is never mistaken for a machine. There is
  no switch that lifts either rule.

  It is **not** the machine-write gate this app must not have (see the comment above
  the middleware chain). That gate would refuse a *credentialled* agent whatever it
  was writing; these two rules refuse exactly two things, and an agent still writes
  everything else unsupervised.

  **Installed with `compojure.core/wrap-routes`, which runs it after the route has
  matched, and that is load-bearing rather than incidental.** Both rules have to
  know which recipe the request names, and in front of the router there is no
  reliable answer: `:path-info` is whatever the client wrote, undecoded, while the
  handler is given an `:id` clout captured and compojure url-decoded. A guard that
  parsed the raw path resolved `/11` and *not* `/%31%31`, `/1%31`, `/+11` or `/١١`,
  which are the same recipe to the handler — so a machine could edit, delete and
  publish any published Recipe by respelling the id. The fix is not a better parse:
  the id comes from `common/recipe-id`, the one function the handlers themselves
  call, so there is no second answer to disagree with.

  Wrapping the whole `(routes …)` form rather than each route keeps the property
  the pre-routing guard had: every route inside is covered, including one added
  later. A path that matches nothing never gets here, which is right — there is no
  recipe to protect — and `wrap-recipe-write-guard` outside still answers those
  with a 401."
  [handler]
  (fn [req]
    (let [machine? (and (mutating-request? req) (common/machine-caller? req))]
      (cond
        (and machine? (publish-request? req))
        {:status 403 :body {:error "Publishing is the owner's: a machine caller cannot set the publish latch"}}

        (and machine? (when-let [id (common/recipe-id req)] (published-target? req id)))
        {:status 403 :body {:error "This Recipe is published, and a published Recipe is the owner's: a machine caller cannot change it"}}

        :else
        (handler req)))))

(defroutes api-routes
  (context "/api" []
    (GET  "/describe" [] describe-handler)

    (context "/auth" []
      (GET  "/required" [] user-handler/password-required-handler)
      (GET  "/me"       [] user-handler/me-handler)
      (POST "/login"    [] user-handler/login-handler))

    ;; Owner-only, and guarded by the handlers themselves rather than by
    ;; `wrap-recipe-write-guard`, which is about recipes: a machine token gets a
    ;; 403 here, which is the one place in this app that it does.
    (context "/machine-user" []
      (GET "/"         [] user-handler/machine-user-handler)
      (PUT "/password" [] user-handler/set-machine-user-password-handler))

    ;; Two guards, because they answer two different questions. The 401 goes in
    ;; front of the router, where it needs nothing from the request but its
    ;; method. The machine rules go behind it via `wrap-routes`, because they need
    ;; the recipe id and only the router knows that. Put new recipe routes inside
    ;; the `(routes …)` form: that is what both guards cover.
    (context "/recipes" []
      (wrap-recipe-write-guard
        (wrap-routes
          (routes
            (GET    "/"             [] recipe-handler/list-recipes-handler)
            (POST   "/"             [] recipe-handler/add-recipe-handler)
            (GET    "/:id/versions" [] recipe-handler/recipe-versions-handler)
            (POST   publish-route   [] recipe-handler/publish-recipe-handler)
            (GET    "/:id"          [] recipe-handler/get-recipe-handler)
            (PUT    "/:id"          [] recipe-handler/update-recipe-handler)
            (DELETE "/:id"          [] recipe-handler/delete-recipe-handler))
          wrap-machine-recipe-rules)))

    (context "/test" []
      (POST "/reset" [] reset-test-db-handler))))

(defroutes app-routes
  api-routes
  (GET "/" [] serve-index)
  (GET "/styles.css" [] serve-styles)
  (route/resources "/" {:root "public/cookbook"})
  (route/not-found {:status 404 :body {:error "Not found"}}))

(defn- public-endpoint? [req]
  (= (:uri req) "/api/auth/login"))

(defn- wrap-auth [handler prod?]
  (fn [req]
    (if (and prod?
             (mutating-request? req)
             (str/starts-with? (or (:uri req) "") "/api")
             (not (public-endpoint? req)))
      (if-let [token (auth/extract-token req)]
        (if (auth/verify-token token)
          (handler req)
          {:status 401 :headers {"Content-Type" "application/json"} :body "{\"error\":\"Invalid token\"}"})
        {:status 401 :headers {"Content-Type" "application/json"} :body "{\"error\":\"Authentication required\"}"})
      (handler req))))

;; There is deliberately no machine-write gate in this chain. Cookbook is an
;; agentic memory store: a caller holding credentials writes unsupervised, with
;; no toggle to switch that off. Adding one back would remove the reason this
;; app exists. See README, "Unsupervised writes".
(defn- app [prod?]
  (-> app-routes
      (wrap-params)
      (wrap-json-body {:keywords? true})
      (wrap-auth prod?)
      (wrap-json-response)
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :post :put :delete])
      (wrap-rate-limit (env-int "RATE_LIMIT_MAX_REQUESTS" (if prod? 180 720))
                       (env-int "RATE_LIMIT_WINDOW_SECONDS" 60))))

(defn- run-server [port prod?]
  (let [host (or (System/getenv "HOST") "127.0.0.1")]
    (tel/log! :info (str "Binding to " host ":" port))
    (jetty/run-jetty (app prod?) {:port port :host host :join? false})))

(defn- setup-file-logging [path]
  (let [log-dir (.getParentFile (io/file path))]
    (.mkdirs log-dir)
    (tel/add-handler! :file (tel/handler:file {:path path}))))

(defn build-app
  "Initialise cookbook (config, datasource, optional file logging) and return a
  ring handler. Does not start jetty or nREPL — the caller (e.g. plurama) owns
  those."
  [config]
  (reset! common/*config config)
  (let [prod? (common/prod-mode?)]
    (when (and (true? (:dangerously-skip-logins? @common/*config)) prod?)
      (throw (ex-info "Cannot use :dangerously-skip-logins? in production mode" {})))
    (when-let [logfile (and (not prod?) (:logfile @common/*config))]
      (setup-file-logging logfile))
    (common/ensure-ds)
    (app prod?)))

(defn -main [& _args]
  (reset! common/*config (common/load-config))
  (let [prod? (common/prod-mode?)]
    (when-let [logfile (and (not prod?) (:logfile @common/*config))]
      (setup-file-logging logfile))
    (when (and (true? (:dangerously-skip-logins? @common/*config)) prod?)
      (throw (ex-info "Cannot use :dangerously-skip-logins? in production mode" {})))
    (tel/log! :info (str "Starting cookbook in " (if prod? "production" "development") " mode"))
    (common/ensure-ds)
    (when-not prod?
      (when-let [nrepl-port (:nrepl-port @common/*config)]
        (nrepl/start-server :port nrepl-port)
        (spit ".nrepl-port" nrepl-port)
        (tel/log! :info (str "nREPL server started on port " nrepl-port))))
    (if-let [port (:port @common/*config)]
      (do
        (tel/log! :info (str "Starting server on port " port))
        (run-server port prod?)
        @(promise))
      (throw (ex-info "No port defined" {})))))
