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
           :versions {}          ;; id -> every version of it, fetched when the viewer opens
           :versions-request {}  ;; id -> the number of the newest /versions request for it
           :open #{}             ;; ids of the expanded cards
           :editing nil          ;; id of the recipe whose Edit modal is open
           :publishing nil       ;; id of the recipe awaiting a publish confirmation
           :deleting nil         ;; id of the recipe awaiting a delete confirmation
           :diffing nil          ;; id of the recipe whose version viewer is open
           :diff-version-idx 0   ;; which step of that history is on show
           :diff-unified? false  ;; the merge view's mode — split unless asked otherwise
           :search ""
           :human-only? false    ;; show only what a human has edited here
           :recipes-request 0    ;; only the newest listing request may land
           :page :shelf          ;; which page is on: :shelf, :settings or :scopes — see below
           :machine-user nil     ;; {:exists :username :password_set_at} — never a password
           :scopes []            ;; the owner's Scopes — [{:id :title :description :recipe_count}]
           :editing-scope nil    ;; id of the Scope being edited in place
           :deleting-scope nil})) ;; id of the Scope awaiting a delete confirmation

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
;; pages

(declare fetch-machine-user)
(declare fetch-scopes)

(defn go-to-page
  "Show one page: `:shelf`, `:settings` or `:scopes`.

  **One value and not three booleans, so 'both pages are open' is a state that
  cannot be reached** rather than one that has to be defended wherever the state
  is read. It used to be `:settings-open?` and `:scopes-open?`, each flipped by
  its own toggle, and the two panels stacked over the shelf when both were on.
  Whoever adds a fourth page inherits the invariant instead of the bug.

  Arriving re-reads what the page draws rather than trusting what was fetched
  before — an agent may have added a Scope through the API, and the machine
  user's password can be reset from any client — which is why this is a function
  and not an `assoc` at each call site.

  The Scopes page's two dialogs are dropped on every move, including a move
  *away* from it: the only buttons that open them are on that page, so one left
  latched would be a confirmation nobody could have asked for, waiting for the
  next visit."
  [page]
  (swap! *app-state assoc :page page :editing-scope nil :deleting-scope nil)
  (case page
    :settings (fetch-machine-user)
    :scopes (fetch-scopes)
    nil))

(defn- toggle-page
  "The top bar's buttons are toggles: pressing the one for the page you are on
  goes back to the shelf, and pressing the other one goes straight there without
  a stop in between."
  [page]
  (go-to-page (if (= page (:page @*app-state)) :shelf page)))

(defn toggle-settings [] (toggle-page :settings))

(defn toggle-scopes [] (toggle-page :scopes))

;; ---------------------------------------------------------------------------
;; auth

(defn- save-token! [token user]
  (when token (.setItem js/localStorage "cookbook-token" token))
  (when user (.setItem js/localStorage "cookbook-user" (js/JSON.stringify (clj->js user)))))

(defn- clear-token! []
  (.removeItem js/localStorage "cookbook-token")
  (.removeItem js/localStorage "cookbook-user"))

(declare fetch-recipes)
(declare fetch-scopes)

(defn- fetch-shelf!
  "Everything the shelf is drawn from. The Scopes go with the Recipes rather than
  waiting for the Scopes page to be opened, because the compose form's picker
  needs them before anybody has opened anything — and they are only fetched for a
  signed-in caller, since the endpoint answers 403 to anybody else and a 403 in
  the console reads as a bug."
  []
  (fetch-recipes)
  (when (:logged-in? @*app-state) (fetch-scopes)))

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
      (fetch-shelf!))))

(defn login [username password on-success]
  (api/post-json "/api/auth/login" {:username username :password password} {}
    (fn [{:keys [token user]}]
      (swap! *app-state assoc :logged-in? true :token token :current-user user :error nil)
      (save-token! token user)
      (fetch-shelf!)
      (when on-success (on-success)))
    (err-handler "Invalid credentials")))

(defn logout
  "Signing out has to drop what was fetched, not just hide it: the bodies
  already pulled into `:details` are the owner's, and so is every superseded
  draft in `:versions` — more so, since the API serves a history to nobody else
  at all.

  `:versions-request` goes with it, and that is the half that does the work: it
  is what a landing `/versions` response checks itself against, so clearing it
  makes an in-flight request from before the sign-out find no match and drop its
  body instead of writing a history into a signed-out shelf.

  **Back to the shelf**, in the same swap rather than through `go-to-page`: this
  is a reset and not a navigation — there is nothing to re-read on the way, and
  the machine-user fetch a move to `:settings` would make is the request a
  signed-out client must not send. Both owner-only pages are reached by a button
  only the owner has, so a visitor left on one would have no way back."
  []
  (clear-token!)
  (swap! *app-state assoc
         :logged-in? false :token nil :current-user nil
         :recipes [] :details {} :open #{} :editing nil :publishing nil :deleting nil
         :versions {} :versions-request {} :diffing nil
         :page :shelf
         ;; the machine user's state is the owner's business too, and the panel
         ;; must not stay open over a signed-out shelf
         :machine-user nil
         ;; and the Scopes more so: the server sends a signed-out client no
         ;; `scopes` key at all, so keeping the fetched list here would be the one
         ;; copy of the owner's filing left on a signed-out page
         :scopes [] :editing-scope nil :deleting-scope nil)
  (fetch-recipes))

;; ---------------------------------------------------------------------------
;; the machine user
;;
;; Owner-only on the server, so these two are only ever called from the settings
;; panel. Neither can carry a password back: no endpoint returns one.

(defn fetch-machine-user []
  (api/fetch-json "/api/machine-user" (auth-headers)
    (fn [m] (swap! *app-state assoc :machine-user m))))

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
;; Scopes
;;
;; The owner's categories: a title, a description, and 0 to n of them on any
;; Recipe. Owner-only on the server — a signed-out caller gets a 403 from
;; `/api/scopes` and no `scopes` key on any Recipe — so everything here is only
;; ever called while signed in, and `logout` drops the list rather than hiding it.
;;
;; Anything that changes a Scope refetches the **Recipes** too, not just the list:
;; the badges on the cards carry each Scope's title, so a rename that refreshed
;; only this list would leave the shelf showing the old word.

(defn fetch-scopes []
  (api/fetch-json "/api/scopes" (auth-headers)
    (fn [scopes] (swap! *app-state assoc :scopes (vec scopes)))))

(defn add-scope [{:keys [title description]} on-success]
  (api/post-json "/api/scopes" {:title title :description (or description "")}
                 (auth-headers)
    (fn [_]
      (fetch-scopes)
      (when on-success (on-success)))
    (err-handler "Could not add that Scope")))

(defn save-scope [id fields on-success]
  (api/put-json (str "/api/scopes/" id) fields (auth-headers)
    (fn [_]
      (fetch-scopes)
      ;; the cards carry the title, so a rename has to reach them
      (fetch-recipes)
      (when on-success (on-success)))
    (err-handler "Could not save that Scope")))

(defn delete-scope
  "Takes the Scope and every association to it. The Recipes survive untouched —
  each keeps all of its text and loses a badge — but the filing itself does not
  come back, which is why a confirmation stands in front of this call.

  `on-done` runs on failure too, for the reason it does in `publish-recipe`: it is
  what closes the confirmation, and the error banner renders under the modal's
  fixed overlay."
  [id on-done]
  (let [done #(when on-done (on-done))]
    (api/delete-simple (str "/api/scopes/" id) (auth-headers)
      (fn [_]
        (fetch-scopes)
        ;; every card filed under it is now showing a badge for a Scope that is
        ;; gone; the server has already unfiled them, so this is what catches up
        (fetch-recipes)
        (done))
      (fn [resp]
        (done)
        ((err-handler "Could not delete that Scope") resp)))))

(defn start-editing-scope [id]
  (swap! *app-state assoc :editing-scope id))

(defn stop-editing-scope []
  (swap! *app-state assoc :editing-scope nil))

(defn start-deleting-scope [id]
  (swap! *app-state assoc :deleting-scope id))

(defn stop-deleting-scope []
  (swap! *app-state assoc :deleting-scope nil))

;; ---------------------------------------------------------------------------
;; recipes
;;
;; The listing is lean by design — the rows in `:recipes` carry no body at all,
;; because the API does not send one. So a card cannot reveal a description it
;; is already holding: expanding one fetches `?detail=full` into `:details`.
;; Anything that changes a body writes the fresh full row back into that map, so
;; an open card never shows a stale one.

(defn- recipes-url
  "The listing URL carrying whichever narrowings are on. Assembled from a list
  rather than branched on, so search and the human filter compose — the endpoint
  applies both as `:where` clauses, and either of them winning here would have
  been this client's invention."
  []
  (let [{:keys [search human-only?]} @*app-state
        params (cond-> []
                 (not (str/blank? search))
                 (conj (str "search=" (js/encodeURIComponent search)))

                 human-only?
                 (conj "human=true"))]
    (if (empty? params)
      "/api/recipes"
      (str "/api/recipes?" (str/join "&" params)))))

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

;; ---------------------------------------------------------------------------
;; the version history
;;
;; Same on-demand, cached-by-id shape as `:details`, and for the same reason: a
;; shelf of thirteen cards must not pull thirteen histories nobody asked to read.
;; Only the viewer ever wants one, and only for the recipe it is open on. The
;; card's provenance badge is fed by counts the listing endpoint aggregates,
;; precisely so a collapsed card never has to come here.

(defn fetch-versions
  "Every version of one recipe, into `[:versions id]`.

  Numbered per id, the way the listing is numbered by `:recipes-request`: a save
  drops the cached list, so reopening the viewer can put a second request for the
  same id in flight behind the first, and the older response must not be the one
  that lands. The counter is per id rather than global so that a request for one
  recipe cannot invalidate another's, and it is only ever incremented — never
  cleared alongside the cache — because a reset would let two live requests share
  a number."
  [id]
  (let [request (-> (swap! *app-state update-in [:versions-request id] (fnil inc 0))
                    (get-in [:versions-request id]))]
    (api/fetch-json (str "/api/recipes/" id "/versions") (auth-headers)
      (fn [{:keys [versions]}]
        (when (= request (get-in @*app-state [:versions-request id]))
          (swap! *app-state assoc-in [:versions id] (vec versions)))))))

(defn- forget-versions!
  "Anything that makes a new version makes the cached list short by one, and a
  history one version behind the count on the card is exactly the contradiction
  the viewer exists not to show. Dropped rather than refetched: nothing is
  looking at it — the viewer covers the footer the Edit button sits in — so the
  next open pays for it.

  Publishing is not one of those things. It writes no history row and no version
  bump, and it touches none of the three fields a version is made of, so the
  cached list is still true after one."
  [id]
  (swap! *app-state update :versions dissoc id))

(defn start-diff
  "Open the version viewer on a recipe, at the newest step. Fetches only on a
  miss; a save is what drops the cache, so what is kept is what is current."
  [id]
  (swap! *app-state assoc :diffing id :diff-version-idx 0)
  (when-not (get-in @*app-state [:versions id])
    (fetch-versions id)))

(defn stop-diff
  "`:diff-unified?` deliberately survives this: which of the two layouts a reader
  can read is about the reader, not about the recipe they closed."
  []
  (swap! *app-state assoc :diffing nil :diff-version-idx 0))

(defn step-diff
  "+1 goes one version older, -1 one newer. The list is newest-first, so this is
  an index step; the component clamps it against how many versions there are,
  which is the only place that knows."
  [delta]
  (swap! *app-state update :diff-version-idx #(+ (or % 0) delta)))

(defn toggle-diff-unified []
  (swap! *app-state update :diff-unified? not))

(defn set-search [s]
  (swap! *app-state assoc :search s)
  (fetch-recipes))

(defn set-human-only
  "Narrow the shelf to the Recipes a human has edited here, or stop narrowing.
  Same shape as `set-search`, and the request numbering there covers this too:
  toggling while a search response is in flight cannot land the older listing."
  [on?]
  (swap! *app-state assoc :human-only? on?)
  (fetch-recipes))

(defn add-recipe [{:keys [title useful_when description tags scope_ids]} on-success]
  (api/post-json "/api/recipes"
                 {:title title :useful_when (or useful_when "") :description (or description "")
                  :tags (or tags "")
                  ;; a vector, always: the endpoint refuses anything that is not an
                  ;; array of ids, and an empty one is the honest 'filed under
                  ;; nothing' for a Recipe that did not exist a moment ago
                  :scope_ids (vec (or scope_ids []))}
                 (auth-headers)
    (fn [_]
      (fetch-recipes)
      ;; the counts on the Scopes page moved
      (fetch-scopes)
      (when on-success (on-success)))
    (err-handler "Could not add that recipe")))

(defn update-recipe
  "Sends `modified_at` from the row we last read, so a save that raced somebody
  else comes back 409 instead of quietly winning. That guard covers the Scope
  associations too — the server moves `modified_at` when the filing changes,
  precisely so this one field still speaks for everything the modal sends."
  [id fields on-success]
  (let [known (get-in @*app-state [:details id])]
    (api/put-json (str "/api/recipes/" id)
                  (assoc fields :modified_at (:modified_at known))
                  (auth-headers)
      (fn [recipe]
        (cache-detail! recipe)
        (forget-versions! id)
        (fetch-recipes)
        ;; a save may have refiled the Recipe, so the per-Scope counts moved
        (fetch-scopes)
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
        ;; The whole history went with it server-side, so the cached copy of it
        ;; goes too — and the viewer closes if it was the thing being read,
        ;; rather than stepping through versions of a recipe that is gone.
        (swap! *app-state (fn [s] (-> s
                                      (update :details dissoc id)
                                      (update :versions dissoc id)
                                      (update :open disj id)
                                      (cond-> (= id (:diffing s)) (assoc :diffing nil)))))
        (fetch-recipes)
        ;; its associations went with it server-side, so the counts moved here too
        (fetch-scopes)
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
