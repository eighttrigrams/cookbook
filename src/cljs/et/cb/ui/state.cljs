(ns et.cb.ui.state
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.cb.ui.api :as api]
            [et.cb.ui.url :as url]))

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
           :diffing nil          ;; id of the recipe whose viewer is open — nil is closed
           ;; Which of the two readings that viewer is showing: nil for a step of
           ;; the recipe's history, and otherwise the **event** id of the proposal
           ;; being read against it. Never set without `:diffing` — both are
           ;; written by `open-viewer!` and by nothing else, so 'a proposal is open
           ;; and the viewer is not' is unreachable rather than merely avoided.
           :diffing-proposal nil
           :diff-version-idx 0   ;; which step of that history is on show
           :diff-unified? false  ;; the merge view's mode — split unless asked otherwise
           :search ""
           :human-only? false    ;; show only what a human has edited here
           ;; Scope ids whose Recipes are hidden from the shelf — a set, because
           ;; several can be on at once and each one takes more away. **Not
           ;; persisted**, deliberately: tracker keeps its `:shared/exclude-*` maps
           ;; in the client atom and writes them nowhere, so a reload starts
           ;; unfiltered, and a narrowing that outlived the session would be a
           ;; shelf silently missing rows for a reason nobody remembers setting.
           :excluded-scopes #{}
           :recipes-request 0    ;; only the newest listing request may land
           ;; which page is on: :shelf, :settings, :scopes, :inbox or :recipe
           :page :shelf
           ;; Which Recipe `:page :recipe` is showing, and how that fetch went.
           ;; Two keys rather than one, because a Recipe page has three states and
           ;; only one of them has a Recipe in it: the id is what the page *is*
           ;; about — it comes off the URL and is known before anything is fetched
           ;; — and the status is what came back. The body itself is not here: it
           ;; goes into `:details` like every other fetched body, so a Recipe read
           ;; on its own page and one read by expanding its card are one cached
           ;; row and not two that can disagree.
           :recipe-page-id nil
           :recipe-page-status nil ;; :loading, :found or :missing
           :inbox []             ;; the owner's unseen changes his agents made, oldest first
           :dismissing-proposal nil ;; event id of the proposal awaiting a dismiss confirmation
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
(declare fetch-inbox)
(declare fetch-recipe-page!)

(defn- show-page!
  "Move to `page` and re-read what it draws. **The address bar is not touched
  here**, and that is the whole reason this is a function of its own.

  Two callers want the state move and disagree about the URL. `go-to-page` below
  is a *navigation*: the reader asked to go somewhere, so the address has to follow
  and Back has to be able to return them. `sync-from-url!` is the opposite —
  the address moved first, either because the page was loaded at one or because the
  browser went Back — and pushing there would append an entry for the step the
  reader just took backwards, which is how a Back button comes to do nothing.

  So this writes the state, and each caller answers for the bar. Everything that
  makes a Recipe page — the id, the status, the fetch — is in one branch here for
  the same reason `open-viewer!` writes `:diffing` and `:diffing-proposal`
  together: 'the page says :recipe and there is no id' and 'the id is set and the
  page is the shelf' are then states nobody has to be careful about."
  [page recipe-id]
  (swap! *app-state assoc :page page :editing-scope nil :deleting-scope nil
         :recipe-page-id (when (= :recipe page) recipe-id)
         :recipe-page-status (when (= :recipe page) :loading))
  (case page
    :settings (fetch-machine-user)
    :scopes (fetch-scopes)
    ;; More than freshness here: the queue is the *only* place an agent's write
    ;; shows up, and it may have arrived while he was reading the shelf.
    :inbox (fetch-inbox)
    ;; And this one is not freshness at all: the page has nothing to draw until
    ;; the body arrives, because the listing never carried one.
    :recipe (fetch-recipe-page! recipe-id)
    nil))

(defn go-to-page
  "Show one page: `:shelf`, `:settings`, `:scopes`, `:inbox` or `:recipe` — the
  last one takes the Recipe's id as a second argument, and is the only one that
  needs anything beyond its own name.

  **One value and not four booleans, so 'both pages are open' is a state that
  cannot be reached** rather than one that has to be defended wherever the state
  is read. It used to be `:settings-open?` and `:scopes-open?`, each flipped by
  its own toggle, and the two panels stacked over the shelf when both were on.
  The Inbox is the fourth page and it inherited the invariant instead of the bug,
  which is what this shape is for. The Recipe page is the fifth, and the first that
  a reader can arrive at without pressing anything.

  Arriving re-reads what the page draws rather than trusting what was fetched
  before — an agent may have added a Scope through the API, and the machine
  user's password can be reset from any client — which is why this is a function
  and not an `assoc` at each call site.

  The Scopes page's two dialogs are dropped on every move, including a move
  *away* from it: the only buttons that open them are on that page, so one left
  latched would be a confirmation nobody could have asked for, waiting for the
  next visit.

  **And this is where the address bar is written, for the same reason the page
  is: it is the one chokepoint.** A Recipe page pushes `/recipe/<id>` and every
  other page pushes `/` — there is one addressable thing in this app and the rest
  is the app. Written at the call sites instead, the bar would be right for as long
  as every future writer of `:page` remembered it, which is the property this
  function exists to not depend on.

  Two callers deliberately do not come through here and each answers for the bar
  itself: `logout`, which is a reset rather than a navigation, and
  `sync-from-url!`, where the URL is already what it is."
  ([page] (go-to-page page nil))
  ([page recipe-id]
   (show-page! page recipe-id)
   (url/push-state! (if (= :recipe page) (url/recipe-path recipe-id) "/"))))

(defn open-recipe-page
  "Open one Recipe's own page — the card footer's fifth button, and the one gesture
  in this app that puts a thing's identity in the address bar."
  [id]
  (go-to-page :recipe id))

(defn sync-from-url!
  "Make the page match the address, without touching the address.

  Called twice: once when the client has finished working out who is calling — a
  Recipe of the owner's needs his token on the request, so this cannot run before
  that is known — and again on every `popstate`, which is Back and Forward.

  **It re-derives the whole view rather than undoing one thing**, which is
  personalist's shape (`ui/core.cljs`) and not tracker's. Tracker's `popstate`
  handler only closes a modal, and it is right to: its URL names a modal over a
  page that never moved. Here the URL names the *page*, so Back from a Recipe has
  to actually land on the shelf and Back-then-Forward has to land on the Recipe
  again — and a handler that only closed something would leave the address and the
  screen saying different things.

  An address this app has no page for — `/recipe/abc`, which the server serves the
  index for on purpose — puts the shelf up and **corrects the bar with
  `replace-state!`**. Corrected rather than left alone, because a bar naming a
  Recipe over a shelf is a lie the reader would copy; replaced rather than pushed,
  because there is no state to go Back to."
  []
  (if-let [id (url/parse-recipe-path (url/current-path))]
    (show-page! :recipe id)
    (do
      (show-page! :shelf nil)
      (when-not (= "/" (url/current-path))
        (url/replace-state! "/")))))

(defn- toggle-page
  "The top bar's buttons are toggles: pressing the one for the page you are on
  goes back to the shelf, and pressing the other one goes straight there without
  a stop in between."
  [page]
  (go-to-page (if (= page (:page @*app-state)) :shelf page)))

(defn toggle-settings [] (toggle-page :settings))

(defn toggle-scopes [] (toggle-page :scopes))

(defn toggle-inbox [] (toggle-page :inbox))

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
  the console reads as a bug.

  The inbox comes along for a different reason: its count is on a button in the
  top bar, which is visible from the shelf, so the number has to be there before
  anybody opens the page it belongs to. Same 403, same gate."
  []
  (fetch-recipes)
  (when (:logged-in? @*app-state)
    (fetch-scopes)
    (fetch-inbox)))

(defn fetch-auth-required
  "Reading published Recipes is public, so the page renders either way.
  `required` only decides whether the owner's affordances show up.

  **`sync-from-url!` is called from in here and not from `init`**, which is
  tracker's arrangement and for its reason: a Recipe page loaded at its own address
  fetches that Recipe, and whether the request carries the owner's token decides
  whether an unpublished one is a Recipe or a 404. Started in `init` the fetch
  would race the token out of localStorage, and the owner would meet his own
  Recipe's not-found page whenever he lost that race."
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
      (fetch-shelf!)
      (sync-from-url!))))

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
  only the owner has, so a visitor left on one would have no way back.

  **And the address bar with it**, which is the half the Recipe page added. The
  Recipe page is not owner-only, so signing out on one does not strand anybody —
  but `/recipe/7` for one of his unpublished Recipes reads perfectly well while he
  is signed in and is a 404 the moment he is not, so a client left standing at that
  address would reload into a not-found page for a Recipe that is sitting on his
  own shelf. `replace-state!` and not a push, for the reason the state reset is
  not a navigation either: there is no step here for Back to undo."
  []
  (clear-token!)
  (url/replace-state! "/")
  (swap! *app-state assoc
         :logged-in? false :token nil :current-user nil
         :recipes [] :details {} :open #{} :editing nil :publishing nil :deleting nil
         :versions {} :versions-request {} :diffing nil :diffing-proposal nil
         :page :shelf :recipe-page-id nil :recipe-page-status nil
         ;; and the inbox with them, for the strongest version of the same
         ;; reason: the server sends this queue to nobody but the owner — a
         ;; machine token is refused it — so a copy left here would be the one
         ;; place a signed-out page still said what his agents had been writing,
         ;; and a `proposed` entry carries the proposed text along with it
         :inbox [] :dismissing-proposal nil
         ;; the machine user's state is the owner's business too, and the panel
         ;; must not stay open over a signed-out shelf
         :machine-user nil
         ;; and the Scopes more so: the server sends a signed-out client no
         ;; `scopes` key at all, so keeping the fetched list here would be the one
         ;; copy of the owner's filing left on a signed-out page
         :scopes [] :editing-scope nil :deleting-scope nil
         ;; and the exclusions with them, for the same reason one step on: an
         ;; exclusion is a statement about the owner's filing, and the endpoint
         ;; ignores it for a signed-out caller anyway — so a set left here would
         ;; put ids on a URL that does nothing, under a strip naming Scopes this
         ;; client can no longer read the titles of
         :excluded-scopes #{})
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
;; the inbox
;;
;; The owner's queue of what his agents did to his shelf — oldest unseen first,
;; and his own edits are deliberately not in it. Owner-only on the server (a
;; machine token gets a 403, and so does a signed-out caller), so everything here
;; is only ever called while signed in, and `logout` drops the list rather than
;; hiding it.
;;
;; There is no unseen-count endpoint: the number on the top bar's button is the
;; length of this list, so the badge and the page can never disagree about what is
;; waiting.

(declare stop-diff)

(defn fetch-inbox
  "The queue.

  This used to close the viewer as well, when its proposal was no longer in the list
  that had just landed — a second guard beside `resolve-proposal`'s, for every other
  way an entry could leave the queue while he was reading it. **It could not fire and
  it is gone.** All eight call sites are gestures the viewer covers, and the one that
  is not — `resolve-proposal`'s own refetch — closes the viewer *synchronously first*,
  so `:diffing-proposal` was already nil by the time the response landed. Nothing
  polls, there is no websocket and nothing refetches on focus, so no other route
  reaches this while the viewer is up. Removing it left the check suite 10/10, which
  is the measurement that says nothing was covering it either.

  What holds the property the guard was written for is `views.diff/component`'s
  lookup, and the argument is written down there."
  []
  (api/fetch-json "/api/inbox" (auth-headers)
    (fn [entries] (swap! *app-state assoc :inbox (vec entries)))))

(defn unseen-count [] (count (:inbox @*app-state)))

(defn mark-seen
  "Acknowledge one entry, by its **event** id. The list is refetched rather than
  the entry removed here: the server decides what is in the queue, and something
  may have arrived since — which is the whole reason the queue exists."
  [event-id]
  (api/post-json (str "/api/inbox/" event-id "/seen") {} (auth-headers)
    (fn [_] (fetch-inbox))
    (err-handler "Could not mark that as seen")))

(declare fetch-recipes)

(defn- resolve-proposal
  "Approve or dismiss, by the entry's **event** id, and then refetch both lists.

  The Recipes come along because approving writes a version: the card's version
  badge, its provenance split and its `pending` flag all move, and a queue that
  emptied while the shelf behind it still said v1 would be the client contradicting
  itself. A dismissal changes no Recipe, and refetching anyway costs one request and
  keeps the two paths identical.

  **The viewer closes if it was open on this proposal**, and that is what makes the
  two entry points one resolution rather than two. There are two buttons for each
  answer now — one on the queue row, one in the viewer that row opens — and this is
  the one place both of them pass through, so neither can be the one that forgets.
  Answered from the viewer there is nothing left to look at; answered from the row
  with the viewer up, what would be left is a comparison against a proposal that no
  longer exists.

  `on-done` runs on failure too, for the reason it does in `publish-recipe`: it is
  what closes the confirmation, and the error banner renders under the modal's fixed
  overlay — so leaving the dialog open would put the banner's dismiss button out of
  reach. The viewer closes on failure for exactly that reason as well: it is a fixed
  full-screen surface over the same banner."
  [event-id action failure on-done]
  (let [done #(do (when (= event-id (:diffing-proposal @*app-state))
                    (stop-diff))
                  (when on-done (on-done)))]
    (api/post-json (str "/api/inbox/" event-id "/" action) {} (auth-headers)
      (fn [_]
        (fetch-inbox)
        (fetch-recipes)
        (done))
      (fn [resp]
        (done)
        ((err-handler failure) resp)))))

(defn approve-proposal [event-id on-done]
  (resolve-proposal event-id "approve" "Could not approve that proposal" on-done))

(defn dismiss-proposal
  "The agent's text is not served anywhere after this, and nothing brings it back —
  which is why the button that calls this has a confirmation in front of it, like
  Delete and Publish."
  [event-id on-done]
  (resolve-proposal event-id "dismiss" "Could not dismiss that proposal" on-done))

(defn start-dismissing-proposal [event-id]
  (swap! *app-state assoc :dismissing-proposal event-id))

(defn stop-dismissing-proposal []
  (swap! *app-state assoc :dismissing-proposal nil))

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

  **A Scope being hidden by is dropped from `:excluded-scopes` here**, and it has
  to happen before the refetch below rather than after it: the set is what builds
  the listing URL, so a deleted id left in it would narrow the very request meant
  to catch up, by a Scope that no longer exists — and the chips strip would be
  holding an id it has no title left to render.

  `on-done` runs on failure too, for the reason it does in `publish-recipe`: it is
  what closes the confirmation, and the error banner renders under the modal's
  fixed overlay."
  [id on-done]
  (let [done #(when on-done (on-done))]
    (api/delete-simple (str "/api/scopes/" id) (auth-headers)
      (fn [_]
        (swap! *app-state update :excluded-scopes disj id)
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
  rather than branched on, so the search, the human filter and the Scope exclusion
  compose — the endpoint applies all three as `:where` clauses, and any of them
  winning here would have been this client's invention."
  []
  (let [{:keys [search human-only? excluded-scopes]} @*app-state
        params (cond-> []
                 (not (str/blank? search))
                 (conj (str "search=" (js/encodeURIComponent search)))

                 human-only?
                 (conj "human=true")

                 ;; Sorted, so the same set of exclusions always spells the same
                 ;; URL: a cljs set has no order of its own, and two requests that
                 ;; mean the same thing reading differently is a nuisance in the
                 ;; network tab and in anything that caches by URL.
                 (seq excluded-scopes)
                 (conj (str "exclude-scopes=" (str/join "," (sort excluded-scopes)))))]
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

(defn fetch-recipe-page!
  "The one Recipe a `/recipe/<id>` page is about, into `:details` and a status
  beside it.

  **This is the first GET in the client with an error handler, and the 404 is an
  answer rather than a fault.** `/recipe/999999` is an id nobody wrote, and a
  visitor's `/recipe/<unpublished>` is a Recipe that exists and is not theirs to
  read; the server deliberately answers both the same way, and so does this — see
  `views.recipe/not-found` for why the page does not try to tell them apart.
  Without the handler the status would stay `:loading` and the page would spin
  forever on a question that had already been answered.

  **The fetch counts as a read**, because it is `?detail=full` and that is what
  `record-view!` counts. Opening a Recipe's page is a consumption in exactly the
  way expanding its card is, and the number it moves is the one that ranks the
  shelf — so this is consistent and intended, and not something to be 'fixed' by
  fetching lean and filling in the body later.

  Guarded on the id rather than on a request counter, the way
  `start-diff-at-version` guards on `:diffing`: `:recipe-page-id` is set before the
  request goes out and there is one page, so a response for a Recipe the reader has
  since navigated away from has nothing to write to. Reopening the *same* Recipe
  makes the two responses interchangeable."
  [id]
  (api/fetch-json (str "/api/recipes/" id "?detail=full") (auth-headers)
    (fn [recipe]
      (when (= id (:recipe-page-id @*app-state))
        (cache-detail! recipe)
        (swap! *app-state assoc :recipe-page-status :found)))
    (fn [_]
      (when (= id (:recipe-page-id @*app-state))
        (swap! *app-state assoc :recipe-page-status :missing)))))

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
  a number.

  `on-landed` is given the versions that just landed, and it is called from inside
  the same numbering guard — so a caller that wants to do something with the list
  cannot act on a response that was already stale. `start-diff-at-version` is the
  one caller: it has a version number and needs an index, which only the list can
  give it."
  ([id] (fetch-versions id nil))
  ([id on-landed]
   (let [request (-> (swap! *app-state update-in [:versions-request id] (fnil inc 0))
                     (get-in [:versions-request id]))]
     (api/fetch-json (str "/api/recipes/" id "/versions") (auth-headers)
       (fn [{:keys [versions]}]
         (when (= request (get-in @*app-state [:versions-request id]))
           (let [versions (vec versions)]
             (swap! *app-state assoc-in [:versions id] versions)
             (when on-landed (on-landed versions)))))))))

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

(defn- open-viewer!
  "The **only** writer of `:diffing` and `:diffing-proposal`, which is why they are
  written in one `assoc` and can never come apart. `[nil nil]` is closed.

  One overlay showing one of two comparisons — a step of a recipe's history, or a
  proposal against that recipe — is the same argument `go-to-page` makes about the
  pages: the state that must not exist is not defended at each reader, it is
  unreachable. Two `swap!`s at two call sites would have made 'the viewer is open on
  a proposal and on a history at once' a thing to be careful about."
  [recipe-id proposal-event-id]
  (swap! *app-state assoc
         :diffing recipe-id
         :diffing-proposal proposal-event-id
         :diff-version-idx 0))

(defn start-diff
  "Open the version viewer on a recipe, at the newest step. Fetches only on a
  miss; a save is what drops the cache, so what is kept is what is current."
  [id]
  (open-viewer! id nil)
  (when-not (get-in @*app-state [:versions id])
    (fetch-versions id)))

(defn start-proposal-diff
  "Open the viewer on a proposal instead of on a step of the history — the Recipe's
  current text against what an agent wants to make of it.

  Takes the **event** id, because that is what the queue row has and what the two
  answers are keyed by, and the recipe id, because that is what the viewer is open
  *on*. Nothing is fetched: both texts arrive on the inbox entry itself, so this
  opens without a round trip — see `db.proposal/attach-to-events` for why they are
  sent with the queue rather than asked for here."
  [event-id recipe-id]
  (open-viewer! recipe-id event-id))

(defn- step-that-produced
  "Which step of the version list *produced* version `v`, as an index into it.

  The list is newest-first and the step at index *i* shows entry *i+1* → entry
  *i* — so the step that produced version V is the index V sits at, and no
  arithmetic on the version number would do: the numbers are contiguous today, but
  an index computed as `total - v` would be a second way of answering a question the
  list already answers, and would go wrong the day a version is missing from it.

  0 when the version is not in the list at all, which is the newest step — the
  same place `start-diff` opens. That is the honest fallback for an entry whose
  version has since been ... nothing, today; it cannot happen, because versions are
  never removed. It is here so that a stale queue entry opens the viewer rather
  than a blank one."
  [entries v]
  (or (some (fn [[i entry]] (when (= v (:version entry)) i))
            (map-indexed vector entries))
      0))

(defn start-diff-at-version
  "Open the version viewer on the step that produced `version`, rather than on the
  newest one.

  This is what an inbox entry needs: it names one version, and the reader wants to
  see what *that* save changed — landing on the newest step would show them the
  wrong change and give no sign of it. The index cannot be worked out until the
  list has arrived, so on a cache miss it is set from inside `fetch-versions`'
  own numbering guard, and only if the viewer is still open on this recipe: a
  reader who closed it or moved on must not have the step yanked under them by a
  response from before."
  [id version]
  (open-viewer! id nil)
  (if-let [entries (get-in @*app-state [:versions id])]
    (swap! *app-state assoc :diff-version-idx (step-that-produced entries version))
    (fetch-versions id
      (fn [entries]
        (when (= id (:diffing @*app-state))
          (swap! *app-state assoc :diff-version-idx (step-that-produced entries version)))))))

(defn stop-diff
  "Close the viewer, whichever of the two it was showing — the ✕ is one button.

  `:diff-unified?` deliberately survives this: which of the two layouts a reader
  can read is about the reader, not about the recipe they closed."
  []
  (open-viewer! nil nil))

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

;; The Scope exclusion — the shelf's third narrowing, and the only negative one.
;; Every function here ends in a refetch for the reason `set-search` and
;; `set-human-only` do: the narrowing is the endpoint's `:where` clause, so the
;; only way to apply one is to ask again. The request numbering in `fetch-recipes`
;; already covers the race that creates.
;;
;; Nothing here checks that an id is one of the owner's. It cannot come from
;; anywhere else — the only way to add one is to shift+click a badge the server
;; put on a card — and an id the server does not recognise excludes nothing
;; anyway, so a check here would be a second opinion about a question the endpoint
;; already answers.

(defn toggle-excluded-scope
  "Hide the Recipes filed under this Scope, or stop hiding them.

  A toggle rather than an add — but **not** because a badge can be shift+clicked
  twice once the shelf has settled. It cannot: a Recipe carrying an excluded Scope
  is hidden badge and all, so every Recipe still on the shelf carries none of the
  excluded ones and no visible badge can name one. Filing a Recipe under a second
  Scope does not rescue its badges either; the Recipe goes with the first. That is
  exactly why `excluded-scopes-strip` is where the second click lives, calling
  `clear-excluded-scope`, and why that strip is not optional.

  What the `disj` branch is for is the window *before the refetch lands*: the rows
  on screen are still the unnarrowed ones, so the badge just clicked is still
  under the cursor. Toggling there lets a doubled click undo itself, where an add
  would swallow the second one and leave the Scope hidden with the only way back
  now in the strip."
  [id]
  (swap! *app-state update :excluded-scopes
         (fn [s] (if (contains? s id) (disj s id) (conj s id))))
  (fetch-recipes))

(defn clear-excluded-scope
  "Stop hiding one Scope's Recipes — the × on its chip.

  This is the affordance that makes the gesture safe rather than a nicety on top
  of it: an excluded Scope's badges leave the shelf with the Recipes carrying
  them, so without the chip there is nothing left to shift+click a second time."
  [id]
  (swap! *app-state update :excluded-scopes disj id)
  (fetch-recipes))

(defn clear-excluded-scopes
  "Stop hiding all of them at once. Only offered when more than one is on, which
  is when clearing them one at a time starts to be work."
  []
  (swap! *app-state assoc :excluded-scopes #{})
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
      ;; And the inbox, after this and after every other write below. **Not
      ;; because his own write made an entry** — it cannot, that is the rule the
      ;; queue is built on — but because his agents' writes land while he is
      ;; sitting on the shelf, and this is the moment the client is talking to the
      ;; server anyway. Without it the count on the top bar is as old as the page.
      (fetch-inbox)
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
        (fetch-inbox)
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
        (fetch-inbox)
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
        ;; rather than stepping through versions of a recipe that is gone. Both
        ;; readings, and the second one needs it as much: deleting a Recipe
        ;; resolves its pending proposal in the same transaction, so a viewer left
        ;; open on one would be showing a question that has just stopped existing.
        (swap! *app-state (fn [s] (-> s
                                      (update :details dissoc id)
                                      (update :versions dissoc id)
                                      (update :open disj id)
                                      (cond-> (= id (:diffing s))
                                        (assoc :diffing nil :diffing-proposal nil)))))
        (fetch-recipes)
        ;; its associations went with it server-side, so the counts moved here too
        (fetch-scopes)
        ;; the queue keeps every entry about a deleted Recipe — an event is the
        ;; record that something happened — so this is a refresh and not a cleanup
        (fetch-inbox)
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
