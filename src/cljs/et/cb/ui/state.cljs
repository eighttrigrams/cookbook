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
           ;; Which of that page's two modes is on: the reading, or the editor at
           ;; `?edit=true`. Written in the same `swap!` as the two above and by the
           ;; same one function, so 'the editor is open and the page is the shelf'
           ;; is unreachable rather than defended. It is **derived from the
           ;; address**, never toggled: `sync-from-url!` is what reads it, which is
           ;; what makes Back and Forward move between the two modes.
           :recipe-page-edit? false
           ;; What the owner has **changed** in the editor, and only that: a map of
           ;; the content fields he has touched, `{}` when he has touched none. The
           ;; four fields used to be component-local `r/atom`s, which a Save button in
           ;; the top bar cannot see — so the draft is here, where `:publishing`,
           ;; `:diffing` and `:showing-provenance?` already are.
           ;;
           ;; **Changes and not a copy**, which is `recipe-edit-fields`' whole point:
           ;; an untouched field is not in here at all and resolves to what is stored,
           ;; so there is no seeding step to run at the wrong moment. Cleared on every
           ;; page move by `show-page!`, beside the Scopes page's two dialogs.
           :recipe-draft {}
           ;; A filing save that is out on the wire, and what the owner has asked
           ;; for since — `nil`, or `{:id :wanted :sent}`. One value and not three
           ;; keys, for the reason `:diffing`/`:diffing-proposal` are written
           ;; together: 'a set is queued and nothing is in flight' is then a state
           ;; that cannot be reached rather than one every reader has to check.
           ;; `toggle-recipe-scope` is the only writer. See it for why a queue
           ;; and not a disabled row.
           :filing nil
           ;; Whether that page is showing the body's source with its provenance
           ;; instead of the rendered markdown. **Not persisted and dropped on every
           ;; move**, by `show-page!`, for the reason the Scopes page's dialogs are:
           ;; the only button that turns it on is on that page, so one left latched
           ;; would be a view nobody asked for, waiting for the next Recipe opened.
           :showing-provenance? false
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
(declare fetch-deleted)
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
  makes a Recipe page — the id, the mode, the status, the fetch — is in one branch
  here for the same reason `open-viewer!` writes `:diffing` and `:diffing-proposal`
  together: 'the page says :recipe and there is no id', 'the id is set and the page
  is the shelf' and 'the editor is open over no Recipe' are then states nobody has
  to be careful about. `edit?` joined that list rather than getting a `swap!` of its
  own.

  **Switching between the two modes of a Recipe already on screen does not re-read
  it, and that is the one condition in here.** Everywhere else this fetches
  unconditionally, deliberately — `views.recipe`'s docstring argues that opening a
  Recipe's page *is* a read and that the count it moves is the one ranking the shelf.
  Pressing Edit is not opening it a second time. Left unconditional, one edit would
  have counted three reads — the page, the editor, and the page again after Save —
  and inflated the number that decides the shelf's order by an amount that has
  nothing to do with anybody reading anything. So: same Recipe, already `:found`,
  keep the row and the status and fetch nothing. Every other arrival — a cold load
  at either address, a different Recipe, a return from the shelf — fetches as before,
  because `:recipe-page-id` is not this one or the status is not `:found`."
  [page recipe-id edit?]
  (let [{:keys [recipe-page-id recipe-page-status]} @*app-state
        same-recipe? (and (= :recipe page)
                          (= recipe-id recipe-page-id)
                          (= :found recipe-page-status))]
    (swap! *app-state assoc :page page :editing-scope nil :deleting-scope nil
           ;; **And the editor's draft, for the Scopes dialogs' reason exactly.** The
           ;; only thing that fills it is a form on one page, so a draft that outlived
           ;; a move would be an edit nobody could see waiting to reappear — and this
           ;; is *every* way edit mode can end, in one place: Cancel, a successful
           ;; Save, a top-bar button, Back, Forward and a fresh load all come through
           ;; here. Three handlers remembering to clear it would have been three
           ;; chances not to.
           :recipe-draft {}
           :showing-provenance? false
           :recipe-page-id (when (= :recipe page) recipe-id)
           :recipe-page-edit? (and (= :recipe page) (boolean edit?))
           :recipe-page-status (when (= :recipe page)
                                 (if same-recipe? :found :loading)))
    (case page
      :settings (fetch-machine-user)
      :scopes (fetch-scopes)
      ;; More than freshness here: the queue is the *only* place an agent's write
      ;; shows up, and it may have arrived while he was reading the shelf.
      ;;
      ;; **The Scope list comes too, for the reason the `:recipe` branch below
      ;; says at length**: the version viewer opens over this page and carries the
      ;; same picker, and the picker draws from `:scopes` — the owner's whole list.
      ;; That was fetched by the Scopes page, by `fetch-shelf!` and by a Recipe's
      ;; own page, so an owner who came straight to `/inbox` by its address had
      ;; none, and the picker would have rendered as nothing at all: a control
      ;; silently absent on exactly the path this feature is for. Signed in only,
      ;; because /api/scopes answers anybody else 403.
      :inbox (do (fetch-inbox)
                 (when (:logged-in? @*app-state) (fetch-scopes)))
      ;; Freshness, and the same reason the queue is refetched: an agent may have
      ;; deleted something since he last looked, and this page is where that shows.
      :deleted (fetch-deleted)
      ;; And this one is not freshness at all: the page has nothing to draw until
      ;; the body arrives, because the listing never carried one.
      ;;
      ;; **The Scope list comes too, and only this page needed teaching.** The
      ;; picker on it draws from `:scopes` — the owner's whole list, not the
      ;; Recipe's — and that was fetched for the Scopes page and by `fetch-shelf!`
      ;; and nowhere else. An owner arriving at `/recipe/<id>` by its address has
      ;; had neither, so the picker would have rendered as **nothing at all**: a
      ;; control that is silently not there, on the one path with no shelf visit in
      ;; front of it. Signed in only, because the endpoint answers 403 to anybody
      ;; else and a 403 in the console reads as a bug — the same gate `fetch-shelf!`
      ;; puts on it. Asked for on both modes, because the reading is one Cancel
      ;; away from any editor and a picker that arrived a request late would be the
      ;; same silently-absent control one step further along.
      :recipe (do (when-not same-recipe? (fetch-recipe-page! recipe-id))
                  (when (:logged-in? @*app-state) (fetch-scopes)))
      nil)))

(defn go-to-page
  "Show one page: `:shelf`, `:settings`, `:scopes`, `:inbox`, `:deleted` or
  `:recipe` — the last one takes the Recipe's id as a second argument, and is the
  only one that needs anything beyond its own name.

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
  is: it is the one chokepoint.** A Recipe page pushes `/recipe/<id>` — or
  `/recipe/<id>?edit=true` for its editor — and every other page pushes `/`; there
  is one addressable thing in this app and the rest is the app. Written at the call
  sites instead, the bar would be right for as long as every future writer of
  `:page` remembered it, which is the property this function exists to not depend
  on. The third argument is the mode, and it exists so that the *editor's* address
  is written here too rather than by whichever button opens it.

  **A push, including for the editor**, because entering it is a move the reader
  made: Back out of the editor has to land on the reading of the same Recipe, and
  Forward has to return. That is the whole of what makes the two modes one page at
  one address rather than two screens sharing a `:page`.

  Two callers deliberately do not come through here and each answers for the bar
  itself: `logout`, which is a reset rather than a navigation, and
  `sync-from-url!`, where the URL is already what it is."
  ([page] (go-to-page page nil))
  ([page recipe-id] (go-to-page page recipe-id false))
  ([page recipe-id edit?]
   (show-page! page recipe-id edit?)
   (url/push-state! (if (= :recipe page) (url/recipe-path recipe-id edit?) "/"))))

(defn open-recipe-page
  "Open one Recipe's own page — the only button a card's footer carries, and the one
  gesture in this app that puts a thing's identity in the address bar."
  [id]
  (go-to-page :recipe id))

(defn open-recipe-editor
  "Open a Recipe's editor: the same page, at `?edit=true`.

  Named beside `open-recipe-page` and not folded into it with a flag, because these
  are two things a reader asks for and each has one button. What they share — the
  address, the push, the state move — is `go-to-page`'s, which is where it has to
  be."
  [id]
  (go-to-page :recipe id true))

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
  because there is no state to go Back to.

  **`?edit=true` is read here and nowhere else, which is what makes the editor a
  page rather than a mode with a URL beside it.** A cold load at the edit address
  opens the editor because this is what the boot calls; Back and Forward move
  between the two modes of one Recipe because this is what `popstate` calls. Neither
  works if the flag is a thing a button sets.

  **A caller who is not the owner gets the reading, and the query comes off the
  bar.** The wildcard route serves the app to anybody who types this address —
  `(GET \"/recipe/*\")` deliberately does not look at what follows it, and a query
  string never affects the match — so the client is the only thing that can refuse
  it. Two rules already in this app agree, and this is a third instance of each
  rather than a new policy: `core/page-body` sends a visitor from an owner-only page
  back to the shelf, and the paragraph above corrects a bar that names something the
  app is not showing. The API refuses the PUT regardless; a form a visitor can fill
  in and never submit is a worse lie than no form. An unpublished Recipe is still a
  not-found for them, which is the API's answer and not this line's."
  []
  (if-let [id (url/parse-recipe-path (url/current-path))]
    (let [asked-to-edit? (url/editing?)
          edit? (and asked-to-edit? (:logged-in? @*app-state))]
      (show-page! :recipe id edit?)
      (when (and asked-to-edit? (not edit?))
        (url/replace-state! (url/recipe-path id))))
    (do
      (show-page! :shelf nil false)
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
(defn toggle-deleted [] (toggle-page :deleted))

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
         :recipes [] :details {} :open #{} :publishing nil :deleting nil
         :versions {} :versions-request {} :diffing nil :diffing-proposal nil
         :page :shelf :recipe-page-id nil :recipe-page-status nil
         ;; the editor with them: it is the owner's mode of that page, and signing
         ;; out on it must not leave a form up over a PUT the API now refuses
         :recipe-page-edit? false
         ;; a filing save in flight is a statement about the owner's own filing,
         ;; and its `:wanted` set is a chip row a signed-out client must not draw
         :filing nil
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

(defn fetch-deleted
  "The tombstones — what has been deleted and not yet destroyed, newest first.

  Its own key rather than a corner of `:recipes`: these are not on the shelf, and a
  listing that mixed them would have every reader of `:recipes` asking which kind of
  row it was holding. `:deleted` is the page's whole state."
  []
  (api/fetch-json "/api/deleted" (auth-headers)
    #(swap! *app-state assoc :deleted %)))

(defn purge-recipe
  "Destroy one tombstone for good, and then re-read the page it was on.

  `on-done` runs on failure too, because it is what closes the confirmation — the
  same shape `publish-recipe` and `delete-recipe` use, and for the same reason: a
  dialog left open over an error banner is a dialog the reader has to dismiss twice.

  The queue is refetched as well as the list, and that is not belt-and-braces: the
  entries naming this Recipe are still in it and have just stopped being openable
  (`recipe_exists` goes to 0), so a page holding the old copy would go on offering a
  link to text that is gone. `fetch-scopes` because a purge takes the associations
  with it, though the counts do not move — they stopped counting it when it was
  deleted."
  [id on-done]
  (api/delete-simple (str "/api/deleted/" id) (auth-headers)
    (fn [_]
      (fetch-deleted)
      (fetch-inbox)
      (fetch-scopes)
      (on-done))
    (fn [resp]
      (on-done)
      ((err-handler "Could not destroy the deleted Recipe") resp))))

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

(declare stop-diff)

(defn mark-seen
  "Acknowledge one entry, by its **event** id. The list is refetched rather than
  the entry removed here: the server decides what is in the queue, and something
  may have arrived since — which is the whole reason the queue exists.

  **And it closes the viewer if this is the entry the viewer was opened from**,
  which is `resolve-proposal`'s rule one kind along: an entry that has left the
  queue must not be left on screen behind a button that would now 404. Decided here
  rather than at the two call sites, so the row and the surface answer the same way —
  and the check is what makes the row's press a no-op for a viewer that is closed or
  open on something else."
  [event-id]
  (api/post-json (str "/api/inbox/" event-id "/seen") {} (auth-headers)
    (fn [_]
      (when (= event-id (:diffing-event @*app-state)) (stop-diff))
      (fetch-inbox))
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

(defn- cache-detail!
  "One fetched or returned row into `:details`, **merged over what is there rather
  than replacing it**.

  **Because a response may answer with less than the read one does.** `GET
  /api/recipes/:id?detail=full` carries `caution` — the per-line provenance split — and
  the publish POST does not: it returns the row, and the split is a *derived* answer.
  An `assoc-in` therefore dropped the key on writes that had nothing to say about it,
  and `views.recipe/found` keys the *Show provenance* button off the key being there —
  correctly, since a client that has not been told the split must not offer to draw one.
  So filing a Scope took the button off the page until the next full read, and nothing
  said so.

  A merge keeps it, and keeping it is right for exactly the writes that reach here
  without it: a filing save and a publish both leave the version history alone, so the
  split this client holds is still the answer.

  **And the case that used to need forgetting is now answered instead.** A save that
  makes a version does stale the split — but since `df96747` such a save's response
  *carries the new one*, computed over the history including it, so the merge above
  installs the fresh answer in the same motion that used to lose the old one. There is
  no moment left in which this client holds a split that describes text it no longer
  has.

  So the rule, restated because the old one has moved: **keys a response carries win,
  keys it does not carry survive — and every key that can go stale is one the response
  carries when it does.** The previous sentence ended *dropped explicitly by the one
  thing that stales it*, which was the old `forget-derived!` dropping `caution`; that
  is no longer true and no longer needed. What `forget-versions!` forgets now is the
  cached *history*, which is a different fact and the only one the server does not hand
  back."
  [recipe]
  (swap! *app-state update-in [:details (:id recipe)] merge recipe))

(defn fetch-detail
  "The body of one recipe. The only place the client ever asks for a
  description."
  [id on-done]
  (api/fetch-json (str "/api/recipes/" id "?detail=full") (auth-headers)
    (fn [recipe]
      (cache-detail! recipe)
      (when on-done (on-done recipe)))))

(defn fetch-filing!
  "One Recipe's **lean** row into `:details`: its `scopes`, its `modified_at`, its
  counts — everything but the description.

  **Which is the one read of a single Recipe that costs nothing.** GET
  /api/recipes/:id says it in as many words: *a lean read of this same path does
  not count either — it returns the retrieval index, not the Recipe*. That is what
  makes it the right request for the version viewer, and `?detail=full` the wrong
  one. GET /api/inbox is deliberate about this — *reading this list moves no
  `view_count` … reviewing what an agent wrote is not using a Recipe* — and a
  viewer that fetched the body to find out where a Recipe is filed would have
  undone that decision one page further along, ranking his shelf by how much
  triaging he had done.

  The response merges (`cache-detail!`), so a Recipe whose full row is already
  held keeps its description and its `caution` and has its filing and its stamp
  refreshed. Both are what the picker on that surface needs: the filing is what it
  draws, and the stamp is the `modified_at` the filing PUT sends to be told when
  somebody else has saved in between."
  [id]
  (api/fetch-json (str "/api/recipes/" id) (auth-headers) cache-detail!))

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

(defn toggle-provenance
  "Show the Recipe page's body as its source, provenance-tinted, or put the rendered
  markdown back.

  **It fetches nothing.** The split arrived with the body — `caution` rides on the
  same `?detail=full` response — so this is a view of what is already in `:details`
  and not a second question asked of the server. Which is also why it can be a plain
  flip with no request counter and no status of its own, unlike everything above it
  in this section."
  []
  (swap! *app-state update :showing-provenance? not))

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
       (fn [{:keys [versions deleted_at]}]
         (when (= request (get-in @*app-state [:versions-request id]))
           (let [versions (vec versions)]
             (swap! *app-state assoc-in [:versions id] versions)
             ;; **Kept beside the list rather than in it**, and it is the only way this
             ;; client can learn that what it is reading has been deleted: `/api/
             ;; recipes/:id` answers 404 for a tombstone, so `:details` never holds
             ;; one, and this is the one read that sees it (`db.recipe/list-versions`).
             ;; A separate map keyed by id, not a key on `:versions`, because that
             ;; value is a vector every reader walks.
             (swap! *app-state assoc-in [:versions-deleted-at id] deleted_at)
             (when on-landed (on-landed versions)))))))))

(defn- forget-versions!
  "The cached version history for one Recipe, dropped because a new version makes it
  short by one — and a history one version behind the count on the card is exactly the
  contradiction the viewer exists not to show. Dropped rather than refetched: nothing is
  looking at it — the editor is a page and the viewer is over it — so the next open pays
  for it.

  **It was `forget-derived!` and it dropped the provenance split as well, and that is
  the change here: it was conflating two facts.** Both are derived from the version
  history, so both did die on a save that made a version — but the *server* now hands
  back the new split on exactly that save (`update-recipe-handler`, `df96747`), so the
  split is answered rather than forgotten, and `cache-detail!`'s merge installs it. The
  history is the one that still has to be forgotten, because nothing hands that back:
  the response is one row, not a list of them.

  Dropping the split here as well would have been the bug that made the server change
  invisible. `update-recipe` runs `cache-detail!` and then this, in that order, so a
  `dissoc :caution` on this line would throw away the fresh answer the line before had
  just installed — and nothing about it would look wrong. The suite would have been
  green about a button that still disappeared.

  Publishing is not one of these things. It writes no history row and no version bump,
  and it touches none of the three fields a version is made of, so the cached list is
  still true after one — as is the cached split, which is why `cache-detail!` merges."
  [id]
  (swap! *app-state update :versions dissoc id))

(defn- open-viewer!
  "The **only** writer of `:diffing`, `:diffing-proposal` and `:diffing-event`, which
  is why they are written in one `assoc` and can never come apart. All nil is closed.

  One overlay showing one of two comparisons — a step of a recipe's history, or a
  proposal against that recipe — is the same argument `go-to-page` makes about the
  pages: the state that must not exist is not defended at each reader, it is
  unreachable. Two `swap!`s at two call sites would have made 'the viewer is open on
  a proposal and on a history at once' a thing to be careful about.

  **The filing comes with the opening**, for the picker the surface now carries —
  the owner's, so a visitor reading a published Recipe's history asks for nothing.
  Fetched on every open rather than only on a miss, and it is cheap enough to be:
  `fetch-filing!` is the lean read, which counts no consumption, and what it brings
  is exactly the two things that go stale — where the Recipe is filed, and the
  `modified_at` the picker's own PUT has to send. A cached row from a shelf visit
  ten minutes ago would draw chips that were true then.

  **`:diffing-event` is which queue entry the surface was opened from**, and it is a
  third field rather than a reading of the other two. `:diffing-proposal` is an event
  id as well, so the temptation is to reuse it; they answer different questions.
  `:diffing-proposal` names a *proposal* and so decides which of the two readings is
  drawn; this names an entry that can be **acknowledged**, which is what lets the
  surface carry Seen — and it is nil for the two ways in that are not the queue, the
  Recipe page's Versions button and the Deleted page, where there is no entry to
  acknowledge and no button to draw."
  [recipe-id proposal-event-id event-id]
  (swap! *app-state assoc
         :diffing recipe-id
         :diffing-proposal proposal-event-id
         :diffing-event event-id
         :diff-version-idx 0)
  (when (and recipe-id (:logged-in? @*app-state))
    (fetch-filing! recipe-id)))

(defn start-diff
  "Open the version viewer on a recipe, at the newest step. Fetches only on a
  miss; a save is what drops the cache, so what is kept is what is current."
  [id]
  (open-viewer! id nil nil)
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
  ;; The event id goes in as the *proposal*, not as an acknowledgeable entry: a
  ;; `proposed` entry is answered and never acknowledged — POST /api/inbox/:id/seen
  ;; refuses one with a 400, because its being unseen is exactly its being unanswered.
  ;; So the surface it opens carries Approve and Dismiss and no Seen.
  (open-viewer! recipe-id event-id nil))

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
  response from before.

  **`event-id` is the queue entry this was opened from**, and it is what puts Seen on
  the surface — *when we go from the tray/inbox, to the versions, we can approve/dismiss
  but not set \"Seen\". add that*. Every caller of this function is a queue row, so it
  is a required argument rather than an option: a row that opened the viewer without
  saying which entry it was would give a reader a page they cannot answer, which is the
  thing being fixed."
  [id version event-id]
  (open-viewer! id nil event-id)
  (if-let [entries (get-in @*app-state [:versions id])]
    (swap! *app-state assoc :diff-version-idx (step-that-produced entries version))
    (fetch-versions id
      (fn [entries]
        (when (= id (:diffing @*app-state))
          (swap! *app-state assoc :diff-version-idx (step-that-produced entries version)))))))

(defn stop-diff
  "Close the viewer, whichever of the two it was showing — one way out for both
  readings, and it is `views.diff/back-to-origin` in the top bar's left slot.

  `:diff-unified?` deliberately survives this: which of the two layouts a reader
  can read is about the reader, not about the recipe they closed."
  []
  (open-viewer! nil nil nil))

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
  precisely so this one field still speaks for everything the form sends."
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

;; ---------------------------------------------------------------------------
;; the editor's draft
;;
;; The four content fields of the Recipe page's `?edit=true` mode. They were
;; component-local `r/atom`s, which was fine while Save was inside the form and
;; impossible once Save moved into the top bar: the bar cannot see a closure in a
;; page. So the draft is app-state, like every other piece of view state here.

(def ^:private editable-fields
  "The four the editor writes, in the order the form shows them. Named once, because
  three things read this list — what the form draws, what `recipe-edit-fields`
  resolves and what a save sends — and a fourth field added to two of the three would
  be a field you could type into and not save.

  `scope_ids` is deliberately **not** among them. The filing is the reading's, saved
  per chip, and an omitted key keeps it: see `views.recipe/editor`."
  [:title :useful_when :tags :description])

(defn recipe-edit-fields
  "What the editor is showing: **the stored Recipe under whatever he has changed.**

  `:recipe-draft` holds only touched fields, so this is one `merge` and there is
  **no seeding step at all** — which is the point, and it is what makes the cold load
  work. Seeding a copy of the row into the draft needs a moment when the row is
  present *and* the page is in edit mode, and those two arrive in either order:
  pressing Edit has the row already and never fetches, while `/recipe/<id>?edit=true`
  typed into the bar navigates first and gets the row from `fetch-recipe-page!` a
  round trip later. One writer that covers both is possible but fiddly; a draft that
  is a *diff* has nothing to run at the wrong moment, and an editor whose fields came
  up empty because a seed fired too early looks exactly like a Recipe with no content.

  `get` with a default and not `or`, because the default has to lose to an empty
  string: clearing the title puts `\"\"` in the draft and that is a value he typed, not
  an absence. Only a key that is *not there* falls through to the row.

  The row is normalised to `\"\"` here rather than at four inputs — a controlled input
  handed nil is React's uncontrolled-input warning, and the API leaves `tags` empty
  rather than absent, so this is belt and braces on one line."
  []
  (let [{:keys [details recipe-page-id recipe-draft]} @*app-state
        recipe (get details recipe-page-id)]
    (into {} (map (fn [k] [k (get recipe-draft k (or (get recipe k) ""))]))
          editable-fields)))

(defn set-recipe-draft-field
  "One keystroke. Only touched fields go in, which is what `recipe-edit-fields`
  reads back out."
  [k v]
  (swap! *app-state assoc-in [:recipe-draft k] v))

(defn recipe-edit-savable?
  "Whether Save may fire: a title that is not blank. The route answers 400 to a blank
  one, so this is the client agreeing with the server rather than guessing at a rule
  of its own.

  In `state` and not in the bar, because the bar is chrome: it should not have to know
  which of a Recipe's fields is the required one."
  []
  (not (str/blank? (:title (recipe-edit-fields)))))

(defn save-recipe-edit
  "Save the draft and go back to the reading.

  Sends the four resolved fields and **not `scope_ids`** — *a field you leave out
  keeps its current value*, which is what stops a content save from disturbing a
  filing the other mode owns. `update-recipe` adds the `modified_at` guard.

  `go-to-page` on success, which also clears the draft, so the reading that comes up
  is drawn from the row the response cached and not from what was typed."
  []
  (let [id (:recipe-page-id @*app-state)]
    (when (and id (recipe-edit-savable?))
      (update-recipe id (recipe-edit-fields) #(go-to-page :recipe id)))))

(defn cancel-recipe-edit
  "Leave the editor without saving. The draft is dropped by `show-page!` on the way
  through, so this is one call and not a call plus a cleanup — and going back into the
  editor afterwards shows the stored Recipe rather than the abandoned edit."
  []
  (when-let [id (:recipe-page-id @*app-state)]
    (go-to-page :recipe id)))

;; ---------------------------------------------------------------------------
;; the filing
;;
;; Which Scopes a Recipe is filed under, saved as the owner toggles a chip on its
;; page rather than behind a Save. It is the same `PUT /api/recipes/:id` the editor
;; uses and it is deliberately **not** `update-recipe`: three of the things that
;; function does after a save are about a new version, and this save makes none.
;;
;; `update-recipe-handler`'s docstring is where that is settled, and it is quoted
;; rather than re-derived here:
;;
;;   "**`scope_ids` behaves exactly like that**, being the other half of the
;;    filing: omit the key and the Recipe stays filed where it is, send an array
;;    and it replaces the whole set, **send an empty array and it clears them**.
;;    Changing it makes no version either — a Scope is a way back to a Recipe, not
;;    part of it — and it moves `modified_at` for the same reason tags do […] Ids
;;    you do not own are dropped and the response's `scopes` is the receipt"
;;
;; Four consequences, and each one is a line of code below: a one-field PUT is
;; safe ("a field you leave out keeps its current value"), the empty set has to go
;; as `[]`, the version cache must **not** be dropped, and the moved `modified_at`
;; is what makes two quick clicks a 409 unless they are serialised.

(declare put-filing!)

(defn filed-under
  "The set of Scope ids this Recipe is filed under **as far as this client is
  concerned right now**: what the owner has asked for if a save is out, and
  otherwise the receipt the server last sent.

  Two callers and they need it for different reasons. The picker draws it — which
  is A6's optimistic half: a chip answers the moment it is pressed and is then
  corrected by the response, because the handler drops ids the caller does not own
  and a client that believed its own set would show such an id as filed. And
  `toggle-recipe-scope` reads it to work out what the next set is, at click time,
  which is the half that has to be a function of the atom and not of a render."
  [id]
  (let [{:keys [wanted] filing-id :id} (:filing @*app-state)]
    (if (= id filing-id)
      wanted
      (set (map :id (get-in @*app-state [:details id :scopes]))))))

(defn toggle-recipe-scope
  "File this Recipe under one more Scope, or unfile it from one — the read page's
  picker, one PUT per chip.

  **The next set is computed here and now, off `filed-under`, and that is not a
  detail.** The picker is handed the id that was clicked precisely so that this
  reads the live set instead of the one that was on screen: two chips pressed
  inside a single animation frame are two handlers closing over the same rendered
  set, and a component that computed `rendered + this-chip` for each of them would
  send two saves that both succeed with the first chip missing from the result. The
  docstring at `recipe-fields/scope-picker` has the measurement.

  **And a queue, not a disabled row, because the second click has to land.** The
  save moves `modified_at`, and the PUT carries the stamp from the last read as the
  409 guard, so a second request sent while the first is out asks the server to
  overwrite a row it no longer holds: 409, *Could not save the filing*, and a chip
  that springs back for no reason the owner can see. Disabling the chips for the
  round trip stops that and **swallows** the click — a disabled button does not fire
  — so two fast chips would file one. That is the same bug wearing a nicer face.

  So: one request in flight, and the latest thing he has asked for remembered.
  `:filing` is `{:id :wanted :sent}` — `:wanted` is what he has asked for, `:sent`
  is what is on the wire — and `put-filing!`'s callback compares the two and goes
  again when they differ. Any number of clicks during one round trip coalesce into
  one follow-up request, which is the other thing a queue buys over a lock."
  [id scope-id]
  (let [now (filed-under id)
        next (if (contains? now scope-id) (disj now scope-id) (conj now scope-id))]
    (if (= id (:id (:filing @*app-state)))
      ;; one is already out for this Recipe: this is the set that goes next
      (swap! *app-state assoc-in [:filing :wanted] next)
      (do (swap! *app-state assoc :filing {:id id :wanted next :sent next})
          (put-filing! id next)))))

(defn- put-filing!
  "One filing PUT, and the one place that decides whether another has to follow.

  `scope_ids` is **always sent, and always as an array** — `(vec (sort ids))`, so
  the empty set goes as `[]` and clears the filing rather than being omitted and
  keeping it. Those are two different requests to this endpoint and only one of
  them is 'he unfiled the last Scope'. Sorted for the reason the exclusion list on
  the listing URL is: the same set spells the same request every time.

  Nothing else is in the body. *A field you leave out keeps its current value*, so
  a one-field save cannot blank the body, and the title this route insists on is
  the one already stored.

  The retry reads `modified_at` out of `:details` *after* `cache-detail!` has
  written the response, so it carries the stamp its own predecessor made and cannot
  409 against it.

  What follows a success is `update-recipe`'s list minus everything about a new
  version:

  - `cache-detail!` — the response is the full row, carrying the receipt and the
    new `modified_at` the next save will need. It **merges**, which matters here
    more than anywhere: this response has no `caution` key, and an `assoc-in` took
    the *Show provenance* button off the page on every chip. That is written up
    where the merge is.
  - `fetch-recipes` — the badges on the shelf's cards moved.
  - `fetch-scopes` — the per-Scope counts moved, which is the reason
    `update-recipe` refetches them too.
  - **not `forget-versions!`**: no version was made, so the cached history is still
    true — and so is the cached split, which is why this response does not carry one
    and does not need to. Dropping the history would make every chip a Recipe's whole
    history to re-fetch.
  - **not `fetch-inbox`**: filing makes no version and no proposal, so there is
    nothing new for the queue to be about.

  A failure clears `:filing` outright rather than trying `:wanted` next — whatever
  refused the first is likely to refuse the second, and a queue that retries into
  an error banner is worse than a chip that goes back to saying what this client
  last read."
  [id ids]
  (api/put-json (str "/api/recipes/" id)
                {:scope_ids (vec (sort ids))
                 :modified_at (get-in @*app-state [:details id :modified_at])}
                (auth-headers)
    (fn [recipe]
      (cache-detail! recipe)
      (fetch-recipes)
      (fetch-scopes)
      (let [{:keys [wanted sent]} (:filing @*app-state)]
        (if (= wanted sent)
          (swap! *app-state assoc :filing nil)
          (do (swap! *app-state assoc-in [:filing :sent] wanted)
              (put-filing! id wanted)))))
    (fn [resp]
      (swap! *app-state assoc :filing nil)
      ((err-handler "Could not save the filing") resp))))

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
  "Takes the Recipe off the shelf. Since 012 that is a **tombstone** server-side —
  the row, its history and its filing all survive — so the confirmation in front of
  this call is no longer the last word: what it destroys is destroyed on the Deleted
  page, by `purge-recipe`. The dialog still asks, because leaving the shelf is a
  decision, and it says what is true now.

  `on-done` runs on failure too, for the same reason it does in
  `publish-recipe`: it is what closes the confirmation, and the error banner
  renders under the modal's fixed overlay.

  **And it leaves the deleted Recipe's own page, if that is where the reader is.**
  Here rather than in the modal, so it holds for every caller and not only for the
  one button that happens to exist today."
  [id on-done]
  (let [done #(when on-done (on-done))]
    (api/delete-simple (str "/api/recipes/" id) (auth-headers)
      (fn [_]
        ;; The cached row and history go, and that is now about the *reads* rather
        ;; than about the data: the row survives as a tombstone and every read of it
        ;; in this client's audience answers nothing, so a cached copy would be the
        ;; one place it still looked alive. The Deleted page fetches its own list.
        ;; The viewer closes if it was the thing being read,
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
        ;; **Off the page, if the page was this Recipe's**, and the reason is the
        ;; `swap!` above: the cached row is gone while `:recipe-page-id` and
        ;; `:recipe-page-status :found` stay exactly as they were, so
        ;; `views.recipe/recipe-page`'s `:found` branch would fall through its
        ;; `if-let` to `[not-found]` — the case whose comment says *which nothing
        ;; produces today*. This is what keeps that sentence true instead of turning
        ;; it into a lie on every delete from a page. `go-to-page` and not a bare
        ;; `assoc`, so the address bar leaves with the page; it **pushes** `/`, which
        ;; puts the deleted Recipe's address one Back away — and what is there is the
        ;; not-found page, whose own sentence already names *one that has since been
        ;; deleted* as one of the cases it covers.
        (when (= id (:recipe-page-id @*app-state))
          (go-to-page :shelf))
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

(defn- open-on-detail!
  "Latch one of the two Recipe confirmations open — and not before the Recipe's
  **full row is in `:details`**, which is the only place either of them reads.

  **One source, and the guarantee for it in one place.** Both used to find their
  Recipe in `:recipes`, on the argument that the listing already carries the short
  fields their question needs; that was true of the shelf and false of everywhere
  else, and `views.recipe-modals/overlays` records what it cost. Making `:details`
  the source is what lets any surface open them, and this is where that is paid for
  rather than in each modal.

  On a Recipe's own page nothing is ever fetched here: `fetch-recipe-page!` caches
  the row *before* it writes `:found`, so the cached branch always wins. The fetch is
  for a caller holding no row at all — a collapsed card on the shelf is one — and it
  is the fetch the Edit modal always had to make when it lived there, since a form
  needs the body the listing deliberately never carried. It is `?detail=full`, so it
  counts as a read the way expanding a card does; that is the honest price of asking
  a question about a Recipe whose text this client has not got.

  It took a third caller until the Edit modal became a page. The editor needs the
  same row and gets it the same way — `show-page!`'s fetch — which is the whole
  difference between a mode of a page and an overlay over one."
  [id k]
  (if (get-in @*app-state [:details id])
    (swap! *app-state assoc k id)
    (fetch-detail id (fn [_] (swap! *app-state assoc k id)))))

(defn start-purging
  "Latch the purge confirmation open on one tombstone.

  A plain `assoc` and **not** `open-on-detail!`, which is what the publish and delete
  confirmations use: those read their Recipe out of `:details`, and a tombstone is the
  one thing that map cannot hold — every read that fills it excludes the deleted. The
  Deleted page's own list is where its title and version come from, so there is nothing
  to fetch and nothing to wait for."
  [id]
  (swap! *app-state assoc :purging id))

(defn stop-purging []
  (swap! *app-state assoc :purging nil))

;; Publishing asks first. The latch is one-way — nothing in the API takes it
;; back off — so a misplaced click is not something an undo could repair.

(defn start-publishing [id]
  (open-on-detail! id :publishing))

(defn stop-publishing []
  (swap! *app-state assoc :publishing nil))

;; Deleting asks first as well, and for a stronger reason: it removes the
;; recipe together with every version of it, and there is no undo call either.

(defn start-deleting [id]
  (open-on-detail! id :deleting))

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
