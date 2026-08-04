(ns et.cb.ui.state
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.cb.ui.api :as api]))

(defn- os-prefers-dark? []
  (.-matches (js/window.matchMedia "(prefers-color-scheme: dark)")))

(defn- initial-dark-mode
  "A remembered choice wins; failing that, follow the OS. base.css keys its dark
  palette on `html.dark-mode`, so this only decides whether that class is on."
  []
  (case (.getItem js/localStorage "cookbook-dark-mode")
    "true" true
    "false" false
    (os-prefers-dark?)))

(defonce *app-state
  (r/atom {:auth-required? nil   ;; nil = still loading
           :logged-in? false
           :token nil
           :current-user nil
           :error nil
           :show-login? false    ;; the sign-in form is only asked for
           :dark-mode (initial-dark-mode)
           :recipes []           ;; the lean rows — these never carry a body
           :details {}           ;; id -> the full row, fetched when a card opens
           :open #{}             ;; ids of the expanded cards
           :editing nil          ;; id of the recipe whose Edit modal is open
           :publishing nil       ;; id of the recipe awaiting a publish confirmation
           :deleting nil         ;; id of the recipe awaiting a delete confirmation
           :search ""
           :recipes-request 0    ;; only the newest listing request may land
           :settings-open? false ;; the owner's settings panel, asked for like the login form
           :machine-user nil}))  ;; {:exists :username :password_set_at} — never a password

;; ---------------------------------------------------------------------------
;; helpers

(defn auth-headers []
  (if-let [token (:token @*app-state)]
    {"Authorization" (str "Bearer " token)}
    {}))

(defn set-error [msg]
  (swap! *app-state assoc :error msg))

(defn clear-error []
  (swap! *app-state assoc :error nil))

(defn- err-handler [fallback]
  (fn [resp]
    (set-error (get-in resp [:response :error] fallback))))

;; ---------------------------------------------------------------------------
;; auth

(defn- save-token! [token user]
  (when token (.setItem js/localStorage "cookbook-token" token))
  (when user (.setItem js/localStorage "cookbook-user" (js/JSON.stringify (clj->js user)))))

(defn- clear-token! []
  (.removeItem js/localStorage "cookbook-token")
  (.removeItem js/localStorage "cookbook-user"))

(declare fetch-recipes)

(defn fetch-auth-required
  "Reading published Recipes is public, so the page renders either way.
  `required` only decides whether the owner's affordances show up."
  []
  (api/fetch-json "/api/auth/required" {}
    (fn [{:keys [required]}]
      (swap! *app-state assoc :auth-required? required)
      (if-not required
        (swap! *app-state assoc :logged-in? true)
        (let [token (.getItem js/localStorage "cookbook-token")
              user-str (.getItem js/localStorage "cookbook-user")]
          (when (and token user-str)
            (swap! *app-state assoc
                   :logged-in? true
                   :token token
                   :current-user (js->clj (js/JSON.parse user-str) :keywordize-keys true)))))
      (fetch-recipes))))

(defn login [username password on-success]
  (api/post-json "/api/auth/login" {:username username :password password} {}
    (fn [{:keys [token user]}]
      (swap! *app-state assoc :logged-in? true :token token :current-user user :error nil)
      (save-token! token user)
      (fetch-recipes)
      (when on-success (on-success)))
    (err-handler "Invalid credentials")))

(defn logout
  "Signing out has to drop what was fetched, not just hide it: the bodies
  already pulled into `:details` are the owner's."
  []
  (clear-token!)
  (swap! *app-state assoc
         :logged-in? false :token nil :current-user nil
         :recipes [] :details {} :open #{} :editing nil :publishing nil :deleting nil
         ;; the machine user's state is the owner's business too, and the panel
         ;; must not stay open over a signed-out shelf
         :settings-open? false :machine-user nil)
  (fetch-recipes))

;; ---------------------------------------------------------------------------
;; the machine user
;;
;; Owner-only on the server, so these two are only ever called from the settings
;; panel. Neither can carry a password back: no endpoint returns one.

(defn fetch-machine-user []
  (api/fetch-json "/api/machine-user" (auth-headers)
    (fn [m] (swap! *app-state assoc :machine-user m))))

(defn toggle-settings
  "Opening re-reads the state rather than trusting what was fetched before, since
  the password could have been reset from an API client in between."
  []
  (let [open? (not (:settings-open? @*app-state))]
    (swap! *app-state assoc :settings-open? open?)
    (when open? (fetch-machine-user))))

(defn set-machine-user-password
  "Creates the machine user the first time and resets its password afterwards —
  one operation on a fixed name, which is why there is one button."
  [password on-success]
  (api/put-json "/api/machine-user/password" {:password password} (auth-headers)
    (fn [m]
      (swap! *app-state assoc :machine-user m :error nil)
      (when on-success (on-success)))
    (err-handler "Could not set the machine user's password")))

;; ---------------------------------------------------------------------------
;; recipes
;;
;; The listing is lean by design — the rows in `:recipes` carry no body at all,
;; because the API does not send one. So a card cannot reveal a description it
;; is already holding: expanding one fetches `?detail=full` into `:details`.
;; Anything that changes a body writes the fresh full row back into that map, so
;; an open card never shows a stale one.

(defn- recipes-url []
  (let [{:keys [search]} @*app-state]
    (if (str/blank? search)
      "/api/recipes"
      (str "/api/recipes?search=" (js/encodeURIComponent search)))))

(defn fetch-recipes
  "The search changes faster than the list comes back and responses can arrive
  out of order, so each request takes a number and only the newest may write."
  []
  (let [request (:recipes-request (swap! *app-state update :recipes-request inc))]
    (api/fetch-json (recipes-url) (auth-headers)
      (fn [recipes]
        (when (= request (:recipes-request @*app-state))
          (swap! *app-state assoc :recipes (vec recipes)))))))

(defn- cache-detail! [recipe]
  (swap! *app-state assoc-in [:details (:id recipe)] recipe))

(defn fetch-detail
  "The body of one recipe. The only place the client ever asks for a
  description."
  [id on-done]
  (api/fetch-json (str "/api/recipes/" id "?detail=full") (auth-headers)
    (fn [recipe]
      (cache-detail! recipe)
      (when on-done (on-done recipe)))))

(defn toggle-open
  "Expanding is what fetches the body — the collapsed card never had it."
  [id]
  (let [open? (contains? (:open @*app-state) id)]
    (swap! *app-state update :open #(if open? (disj % id) (conj % id)))
    (when-not open? (fetch-detail id nil))))

(defn set-search [s]
  (swap! *app-state assoc :search s)
  (fetch-recipes))

(defn add-recipe [{:keys [title useful_when description]} on-success]
  (api/post-json "/api/recipes"
                 {:title title :useful_when (or useful_when "") :description (or description "")}
                 (auth-headers)
    (fn [_] (fetch-recipes) (when on-success (on-success)))
    (err-handler "Could not add that recipe")))

(defn update-recipe
  "Sends `modified_at` from the row we last read, so a save that raced somebody
  else comes back 409 instead of quietly winning."
  [id fields on-success]
  (let [known (get-in @*app-state [:details id])]
    (api/put-json (str "/api/recipes/" id)
                  (assoc fields :modified_at (:modified_at known))
                  (auth-headers)
      (fn [recipe]
        (cache-detail! recipe)
        (fetch-recipes)
        (when on-success (on-success)))
      (err-handler "Could not save"))))

(defn publish-recipe
  "One way: there is no unpublish call to pair with this one, on the server or
  here. The response is the full row, so an open card keeps a fresh body.

  `on-done` runs on failure too, because it is what closes the confirmation:
  the error banner renders under the modal's fixed overlay, so leaving the
  dialog open would put the banner's dismiss button out of reach."
  [id on-done]
  (let [done #(when on-done (on-done))]
    (api/post-json (str "/api/recipes/" id "/publish") {} (auth-headers)
      (fn [recipe]
        (cache-detail! recipe)
        (fetch-recipes)
        (done))
      (fn [resp]
        (done)
        ((err-handler "Could not publish") resp)))))

(defn delete-recipe
  "Takes the recipe and its whole version history with it, and nothing in the
  API brings any of it back — which is why a confirmation stands in front of
  this call.

  `on-done` runs on failure too, for the same reason it does in
  `publish-recipe`: it is what closes the confirmation, and the error banner
  renders under the modal's fixed overlay."
  [id on-done]
  (let [done #(when on-done (on-done))]
    (api/delete-simple (str "/api/recipes/" id) (auth-headers)
      (fn [_]
        (swap! *app-state (fn [s] (-> s
                                      (update :details dissoc id)
                                      (update :open disj id))))
        (fetch-recipes)
        (done))
      (fn [resp]
        (done)
        ((err-handler "Could not delete") resp)))))

;; ---------------------------------------------------------------------------
;; view state

(defn start-editing
  "The modal edits all three fields, so it needs the body — which the listing
  deliberately did not carry."
  [id]
  (if (get-in @*app-state [:details id])
    (swap! *app-state assoc :editing id)
    (fetch-detail id (fn [_] (swap! *app-state assoc :editing id)))))

(defn stop-editing []
  (swap! *app-state assoc :editing nil))

;; Publishing asks first. The latch is one-way — nothing in the API takes it
;; back off — so a misplaced click is not something an undo could repair.

(defn start-publishing [id]
  (swap! *app-state assoc :publishing id))

(defn stop-publishing []
  (swap! *app-state assoc :publishing nil))

;; Deleting asks first as well, and for a stronger reason: it removes the
;; recipe together with every version of it, and there is no undo call either.

(defn start-deleting [id]
  (swap! *app-state assoc :deleting id))

(defn stop-deleting []
  (swap! *app-state assoc :deleting nil))

;; ---------------------------------------------------------------------------
;; dark mode
;;
;; Same mechanism as tracker: the palette lives entirely in CSS, keyed on
;; `html.dark-mode`, and all this does is put that class on or off the root
;; element. The light `:root` values are never touched. The choice is
;; remembered, so a reload does not snap back.

(defn- apply-dark-mode! [dark?]
  (let [classes (.-classList (.-documentElement js/document))]
    (if dark?
      (.add classes "dark-mode")
      (.remove classes "dark-mode"))))

(defn toggle-dark-mode []
  (swap! *app-state update :dark-mode not))

(defn setup-dark-mode!
  "Apply the starting choice, then keep the root class in step with the atom."
  []
  (apply-dark-mode! (:dark-mode @*app-state))
  (add-watch *app-state :dark-mode-sync
    (fn [_ _ old-state new-state]
      (when (not= (:dark-mode old-state) (:dark-mode new-state))
        (apply-dark-mode! (:dark-mode new-state))
        (.setItem js/localStorage "cookbook-dark-mode" (str (:dark-mode new-state)))))))
