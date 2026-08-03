(ns et.cb.server
  (:require [ring.adapter.jetty9 :as jetty]
            [et.cb.db :as db]
            [et.cb.server.common :as common]
            [et.cb.server.user-handler :as user-handler]
            [et.cb.server.recipe-handler :as recipe-handler]
            [et.cb.auth :as auth]
            [et.cb.middleware.rate-limit :as rate-limit :refer [wrap-rate-limit]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [compojure.core :refer [defroutes routes GET POST PUT DELETE context]]
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

  It is **not** the machine-write gate this app must not have (see below). That
  gate would refuse a *credentialled* agent; this refuses a caller with no
  credentials at all. An agent holding a token still writes unsupervised.

  It wraps the whole `/api/recipes` context rather than each handler, so a route
  added there later is covered by construction, and it sits inside `app-routes`
  rather than in the middleware chain, so every assembly of the app gets it —
  `app`, `build-app` for plurama, and the integration tests' own chain alike."
  [handler]
  (fn [req]
    (if (and (mutating-request? req)
             (not (common/authenticated? req)))
      {:status 401 :body {:error "Authentication required"}}
      (handler req))))

(defroutes api-routes
  (context "/api" []
    (GET  "/describe" [] describe-handler)

    (context "/auth" []
      (GET  "/required" [] user-handler/password-required-handler)
      (GET  "/me"       [] user-handler/me-handler)
      (POST "/login"    [] user-handler/login-handler))

    (context "/recipes" []
      (wrap-recipe-write-guard
        (routes
          (GET    "/"             [] recipe-handler/list-recipes-handler)
          (POST   "/"             [] recipe-handler/add-recipe-handler)
          (GET    "/:id/versions" [] recipe-handler/recipe-versions-handler)
          (POST   "/:id/publish"  [] recipe-handler/publish-recipe-handler)
          (GET    "/:id"          [] recipe-handler/get-recipe-handler)
          (PUT    "/:id"          [] recipe-handler/update-recipe-handler)
          (DELETE "/:id"          [] recipe-handler/delete-recipe-handler))))

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
