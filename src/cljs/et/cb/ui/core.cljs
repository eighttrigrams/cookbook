(ns et.cb.ui.core
  (:require [reagent.dom.client :as rdomc]
            [reagent.core :as r]
            [et.cb.ui.state :as state]
            [et.cb.ui.views.diff :as diff]
            [et.cb.ui.views.deleted :as deleted]
            [et.cb.ui.views.inbox :as inbox]
            [et.cb.ui.views.recipe :as recipe]
            [et.cb.ui.views.recipe-modals :as recipe-modals]
            [et.cb.ui.views.recipes :as recipes]
            [et.cb.ui.views.scopes :as scopes]
            [et.cb.ui.views.settings :as settings]))

(defn login-form []
  (let [username (r/atom "")
        password (r/atom "")]
    (fn []
      (let [do-login #(state/login @username @password
                                   (fn [] (reset! username "") (reset! password "")))]
        [:div.login-form
         [:input {:type "text" :auto-complete "off" :placeholder "Username"
                  :value @username
                  :on-change #(reset! username (-> % .-target .-value))
                  :on-key-down #(when (= (.-key %) "Enter") (do-login))}]
         [:input {:type "password" :placeholder "Password"
                  :value @password
                  :on-change #(reset! password (-> % .-target .-value))
                  :on-key-down #(when (= (.-key %) "Enter") (do-login))}]
         [:button {:on-click do-login} "Sign in"]]))))

;; ---------------------------------------------------------------------------
;; the top bar
;;
;; **The design principle, in his words, because everything here is measured against
;; it and the next change will be too:**
;;
;;   *so the basic design is we have a top level bar with either cookbook brand, back
;;   button, or save cancel on the left hand side and a couple of widgets on the right
;;   hand side, of which only dark light mode is shown in every view.*
;;
;; Two rules fall out of that, and they are the two functions below:
;;
;; - **The left slot holds one of three things**: the brand, a back button, or Save
;;   and Cancel. Never two of them, and never the brand beside a back button — which
;;   is why it is a slot with an ordered decision in it rather than a row of `when`s.
;; - **The right-hand side is conditional, and the theme toggle is the only widget in
;;   every view.** Everything else up there answers to `focused-surface?`.

(defn- focused-surface?
  "Whether what is on screen is a **focused surface**: about one thing, arrived at by
  its own address or opened over everything else, and carrying its own way out. Today
  that is the Recipe page and the version viewer.

  **One question, asked in one place, because the answer decides more than one
  thing** — the right-hand side keeps the theme toggle alone, and the left slot holds
  that surface's own chrome instead of the brand. Written as conditions at the call
  sites, adding a surface would mean finding all of them; written here, it is one
  clause, and the viewer is what that was built for.

  It takes the whole state rather than `page`, which is what let the viewer join
  without changing the shape: `:diffing` is not a `:page` at all — it is an overlay
  over whichever page is up. A predicate over `page` would have needed widening
  instead of extending.

  What is *not* one answer is the left slot's **contents**: each focused surface has
  its own way out and only it knows what that is. This decides which chrome goes
  away; `left-slot` decides what replaces it."
  [{:keys [page diffing]}]
  (or (= :recipe page) (some? diffing)))

(defn- left-slot
  "The top bar's left-hand side, which is **one slot with more than one thing in
  it**: the app's name where you are looking at the app, and a focused surface's own
  chrome where you are looking at one thing.

  *the back button should go there where on the list view the cookbook brand logo
  is.* So on `/recipe/<id>` the slot is `views.recipe/back-to-shelf`, and the app's
  name is not on screen. That is the ordinary contextual-back pattern and it is the
  trade it comes with — a bar that kept both by shrinking the brand would be paying
  for a word nobody needs on a page they arrived at by address.

  **The slot and not the brand is what varies**, which is why `.brand` stays exactly
  what it was and gets rendered *into* here. A `.brand` that sometimes held a button
  would have made every rule keyed off that class a question.

  **Reading a Recipe, it holds three: `← Shelf`, Edit and Versions.** *edit and
  versions can now move to the top, next to the back to shelf button.* And the rule
  that decides what may join them — **the slot carries ways of *looking* at the thing,
  the surface keeps what *changes* it** — is `views.recipe/navigation-actions`', because
  it is that page's rule about its own controls. Publish and Delete stay down in the
  panel: a destructive control in a row of navigation is a mis-aimed click that costs a
  Recipe rather than a step.

  **And while a Recipe is being edited it holds Save and Cancel, with no way back.**
  *when we go to edit, the save and cancel buttons should go where the back button
  sits and the back button should not be there.* No `← Shelf` is the interesting half
  and it is right: leaving an editor is a question with two answers, and a third
  button that quietly meant *the first one* would be the one a hurried reader pressed.
  Cancel is the way out, and it lands on the reading, where the three are again.

  Save and Cancel are `state/save-recipe-edit` and `state/cancel-recipe-edit` — the bar
  holds no editing knowledge at all, not even which field a save may not blank — and
  the reading's three are `views.recipe`'s own components. The slot **places** things;
  it does not know what any of them do.

  **And the version viewer's back button outranks all of it**, which is the one thing
  about this `cond` worth reading as an *order* rather than as four cases. The viewer
  is opened *from* the reading — case 3 — so its button **replaces** `← Shelf`, Edit
  and Versions rather than joining them: a slot offering Versions while the versions
  view is up would be a control for the surface you are already on, and `← Shelf`
  beside it would leave two back buttons meaning different things.

  Keyed off the page and the mode, not off `focused-surface?`, deliberately: that
  predicate answers *whether* the app's chrome steps aside, and this answers *what
  stands there instead*, which is a different answer for every surface that ever does
  it — and here, for every mode of one.

  It needs the Recipe's id and gets it from `:recipe-page-id`, which is the same value
  the page itself is drawn from, so the slot cannot come to be about a different
  Recipe than the panel under it."
  [page edit? recipe-id logged-in? diffing]
  [:div.top-bar-left
   (cond
     (some? diffing)
     [diff/back-to-origin page]

     (and (= :recipe page) edit?)
     [:<>
      [:button.recipe-edit-save
       {:disabled (not (state/recipe-edit-savable?))
        :on-click state/save-recipe-edit
        :title "Save this Recipe"}
       "Save"]
      [:button.secondary.recipe-edit-cancel
       {:on-click state/cancel-recipe-edit
        :title "Leave without saving"}
       "Cancel"]]

     (= :recipe page)
     [:<>
      [recipe/back-to-shelf]
      ;; Owner-only, and the gate is the panel's: both of these lead somewhere the
      ;; server refuses anybody else. A visitor keeps `← Shelf` alone, which is the
      ;; one control on this page that is theirs.
      (when logged-in?
        [recipe/navigation-actions {:id recipe-id}])]

     :else
     [:div.brand
      [:span.brand-mark "▤"]
      [:span.brand-name "Cookbook"]])])

(defn- top-bar []
  (let [app-state @state/*app-state
        {:keys [auth-required? logged-in? show-login? dark-mode page
                recipe-page-edit? recipe-page-id diffing]} app-state
        ;; **On a focused surface the right-hand side keeps the theme toggle and
        ;; nothing else.** *a couple of widgets on the right hand side, of which only
        ;; dark light mode is shown in every view* — so this gates every one of the
        ;; others, and `focused-surface?` is the one place that decides what counts.
        ;;
        ;; He asked first for *the inbox or settings selectors* to go, and then for
        ;; the third: *the scope configuration should also only be accessible from
        ;; the global view*. The code would have argued that on its own — the Scopes
        ;; button borrows the settings button's styling precisely because it is the
        ;; same kind of control in the same corner, so hiding two of three identical
        ;; adjacent controls reads as a bug rather than as a decision. The principle
        ;; underneath, now that it has been said twice: **the owner's configuration
        ;; surfaces are reached from the global view, not from a surface about one
        ;; Recipe.** A reader who wants the Inbox goes to the shelf first. That is a
        ;; deliberate narrowing — the next person to look will otherwise try to put
        ;; them back.
        ;;
        ;; **Sign in / Sign out goes with them**, which is the part with a
        ;; consequence rather than just a tidier corner: a visitor who followed a
        ;; link to a published Recipe has no Sign in button *on that page* and has to
        ;; go through `← Shelf` first. That is one click, it is the control standing
        ;; where the brand would be, and it is the trade the rule comes with — the
        ;; right-hand side is widgets, and only the theme toggle is in every view.
        chrome? (not (focused-surface? app-state))]
    [:div.top-bar
     [left-slot page recipe-page-edit? recipe-page-id logged-in? diffing]
     [:div.top-bar-right
      ;; The Inbox. Owner-only like the other two, and it is the one of the three
      ;; that carries a number: how many of his agents' changes are waiting. The
      ;; count is the length of the list the page itself draws — there is no
      ;; endpoint for it — so the badge and the page cannot come to disagree about
      ;; what is unseen. Shown at 0 as well, because a button that appeared only
      ;; when there was work would leave him with no way to look at an empty queue
      ;; and confirm that it is empty.
      (when (and chrome? logged-in?)
        (let [n (state/unseen-count)]
          [:button.settings-toggle.inbox-toggle
           {:on-click state/toggle-inbox
            :class (when (= :inbox page) "active")
            :title (if (zero? n)
                     "Inbox — nothing your agents did is waiting"
                     (str "Inbox — " n (if (= 1 n) " change" " changes")
                          " your agents made, unseen"))}
           "✉"
           (when (pos? n) [:span.inbox-count n])]))
      ;; The Scopes page. Owner-only, and gated the same way the Settings page is
      ;; — this shell has no router, so which page is on is one value in the atom
      ;; and these two buttons move between them. The gate is `logged-in?` and not
      ;; a claim about safety: the endpoints behind it answer 403 to anybody else,
      ;; which is the boundary. It borrows `.settings-toggle`'s styling because it
      ;; is the same kind of control in the same corner.
      (when (and chrome? logged-in?)
        [:button.settings-toggle.scopes-toggle
         {:on-click state/toggle-scopes
          :class (when (= :scopes page) "active")
          :title "Scopes"}
         "▦"])
      ;; The Deleted page, beside the Scopes for the same reason and with the same
      ;; borrowed styling: it is a page only the owner has, reached by a button
      ;; because this shell has no router. **No count on it**, unlike the Inbox: a
      ;; number there would be a standing reminder to destroy things, and a tombstone
      ;; is not work waiting to be done — leaving one alone forever is a legitimate
      ;; way to use this page.
      (when (and chrome? logged-in?)
        [:button.settings-toggle.deleted-toggle
         {:on-click state/toggle-deleted
          :class (when (= :deleted page) "active")
          :title "Deleted — Recipes off the shelf, still readable"}
         "🗑"])
      ;; only the owner has a setting to make: the machine user's password
      (when (and chrome? logged-in?)
        [:button.settings-toggle
         {:on-click state/toggle-settings
          :class (when (= :settings page) "active")
          :title "Machine user"}
         "⚙"])
      ;; **The one widget in every view**, and the only thing on this side of a
      ;; focused surface. Not gated, because reading in the wrong theme is a reason
      ;; to change it wherever you are.
      [:button.dark-mode-toggle
       {:on-click state/toggle-dark-mode
        :title (if dark-mode "Switch to light" "Switch to dark")}
       (if dark-mode "☀" "☾")]
      ;; Signing in and out is gated with the selectors, not with the theme toggle —
      ;; see `chrome?` above for the consequence, which is a visitor going through
      ;; `← Shelf` to find Sign in. Dev never draws either of these
      ;; (`:dangerously-skip-logins?` leaves `auth-required?` false), so the rule
      ;; here is only observable by making `auth-required?` true in the atom, which
      ;; is what the check does.
      (when chrome?
        (cond
          (not auth-required?) nil
          logged-in? [:button.secondary {:on-click state/logout} "Sign out"]
          show-login? nil
          :else [:button.secondary
                 {:on-click #(swap! state/*app-state assoc :show-login? true)} "Sign in"]))]]))

(def ^:private owner-only-pages
  "The pages a signed-out caller is sent away from. Named as a set, because the
  question the gate below asks changed the day a page arrived that is *not* one of
  these — see `page-body`."
  #{:scopes :settings :inbox :deleted})

(defn- page-body
  "Exactly one of the five, chosen by `:page` — the shelf is not a backdrop the
  others are laid over. It used to be: both panels rendered `(when open?)` and
  the shelf rendered unconditionally underneath, so opening Settings gave you the
  settings *and* the search box, the compose form and every card below it.

  **A caller who is not signed in gets the shelf whatever an owner-only `:page`
  says.** The Settings, Scopes and Inbox pages are reached by buttons only the
  owner has, so a visitor left on one would be looking at a blank page with no way
  off it — and in the Inbox's case at a page whose one request the server answers
  with a 403. `logout` already puts `:page` back to `:shelf`; this is the second
  half of that guarantee, and the one that does not depend on every future writer
  of the state remembering it.

  **The gate used to be `logged-in?` and is now 'is this page owner-only?', because
  `:recipe` is the first page that is not.** Every sentence above is still true of
  the three it was written about; what is new is a page a visitor can legitimately
  be on, since it is reached by an address rather than by a button and a link to a
  published Recipe that only worked while signed in would not be a link at all.
  Written the old way, `/recipe/1` would have rendered the shelf for everybody who
  was not the owner — the URL saying one thing and the screen another, which is
  exactly the failure the whole address is meant to prevent."
  [logged-in? page]
  (case (if (and (not logged-in?) (owner-only-pages page)) :shelf page)
    :scopes [scopes/scopes-page]
    :settings [settings/machine-user-block]
    :inbox [inbox/inbox-page]
    :deleted [deleted/deleted-page]
    :recipe [recipe/recipe-page]
    [:div.main-layout
     [recipes/recipes-tab]]))

(defn app
  "The shell: the top bar, the banner, the one page, and the overlays over it.

  **The overlays are the app's and not a page's**, which is the last thing
  `page-body` needed to be able to keep its promise. They are keyed off global state
  — `:publishing`, `:deleting`, `:diffing` — and exactly one page is
  mounted at a time, so a modal mounted inside a page is absent from every other
  one: a Recipe page's Edit button would have set the state and rendered nothing.
  Mounted here they stand over whichever page is up, and there is one copy of each
  rather than one per page that can open it. `views.recipe-modals/overlays` says the
  rest, including why the Inbox's own confirmation is not among them.

  After `page-body` in the source, which is only tidiness: which of these is on top
  is the stylesheet's answer and not the DOM's — `.modal-backdrop` at 30 over
  `.diff-overlay` at 25 over the page — and the argument is written where the
  z-index is."
  []
  (let [{:keys [auth-required? logged-in? show-login? error page]} @state/*app-state]
    (if (nil? auth-required?)
      [:div.loading "Loading…"]
      [:div
       [top-bar]
       (when error
         [:div.error error [:button.error-dismiss {:on-click state/clear-error} "×"]])
       (when (and auth-required? (not logged-in?) show-login?)
         [login-form])
       [page-body logged-in? page]
       [recipe-modals/overlays]])))

(defonce root (rdomc/create-root (.getElementById js/document "app")))

(defn init []
  (state/setup-dark-mode!)
  ;; Back and Forward. **The whole view is re-derived from the URL**, which is
  ;; personalist's shape (`et.pe.ui.core/init`) rather than tracker's: tracker's
  ;; handler only closes a modal, and it is right to, because its address names a
  ;; modal over a page that never moved. Here the address names the *page*, so Back
  ;; from a Recipe has to land on the shelf and Forward has to land on the Recipe
  ;; again — and it must not push, because the browser has already moved the bar.
  ;;
  ;; Registered here rather than beside the fetch below because a listener belongs
  ;; to the window and not to a round trip: `fetch-auth-required` is what calls
  ;; `sync-from-url!` for the *first* reading, and it does so from inside its own
  ;; callback, where who is calling is finally known.
  (.addEventListener js/window "popstate" (fn [_] (state/sync-from-url!)))
  (state/fetch-auth-required)
  (rdomc/render root [app]))
