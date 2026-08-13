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
;; Two rules fall out of that, and there are three functions below, because the second
;; rule turned out to have two halves:
;;
;; - **The left slot holds one of three things**: the brand, a back button, or Save
;;   and Cancel. Never two of them, and never the brand beside a back button — which
;;   is why it is a slot with an ordered decision in it rather than a row of `when`s.
;; - **The right-hand side is conditional, and the theme toggle is the only widget in
;;   every view.** Everything else up there answers to `focused-surface?`: the app's
;;   widgets are there while the app's chrome is (`chrome?`, in `top-bar`), and where
;;   they are not, **the surface on screen puts its own actions in the space they
;;   left** — `surface-actions`. So the right-hand side is a second slot with an
;;   ordered decision in it, for `left-slot`'s reason exactly: two focused surfaces
;;   can be on screen at once, and only one of them may answer for that corner.

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
  that decides what may join them — **this slot carries ways of *looking* at the thing,
  and what *changes* it is not in here** — is `views.recipe/navigation-actions`',
  because it is that page's rule about its own controls. A destructive control in a row
  of navigation is a mis-aimed click that costs a Recipe rather than a step.

  **The rule used to end 'the surface keeps what changes it', and that half has been
  overruled — for Publish, and at the bar's other end.** *In the Page view, put the
  Publish button in the top right, to the left of the dark mode switcher.* It is
  `surface-actions` that draws it, in the right-hand slot, which is what leaves this
  paragraph intact rather than merely outlived: the two slots are not one row, and
  nothing a mis-aimed click here can reach writes anything. Delete did not go with it
  and stays at the bottom right of the panel, out of the reading path, because its
  worst outcome is losing the Recipe — see `views.recipe/publish-action`, which is
  where the two are told apart.

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

     ;; **The app's name, and on a submode it is also the way back to the shelf.**
     ;; *when i\'m, coming from the overview, go into any submode (trashcan, settings,
     ;; inbox) to get out, i must click on such an item in the right hand top side
     ;; again. make that clicking on the cookbook brand logo takes me also back.* The
     ;; buttons on the right are toggles, so each of them is its own way out and only
     ;; its own; the brand is the one thing on the bar that means *the app*, which is
     ;; exactly what a reader on a submode wants to get back to.
     ;;
     ;; **A `button` on a submode and a `div` on the shelf**, rather than a div with a
     ;; click handler either way. The keyboard and a screen reader get it for free, and
     ;; on the shelf there is nothing to offer: a control that is already where it goes
     ;; is a control that answers a press with nothing, and this bar has no other
     ;; example of one. `.brand` and its two spans are unchanged in both, which is the
     ;; ns docstring\'s rule about that class kept — what varies is whether the name is
     ;; also a control, never what the name looks like.
     :else
     (let [home? (= :shelf page)]
       [(if home? :div.brand :button.brand.brand-home)
        (cond-> {}
          (not home?) (assoc :on-click #(state/go-to-page :shelf)
                             :title "Back to the shelf"))
        [:span.brand-mark "▤"]
        [:span.brand-name "Cookbook"]]))])

(defn- surface-actions
  "The top bar's right-hand slot, at the end of it nearest the middle: **what the
  surface on screen offers on the thing it is about, immediately left of the dark-mode
  toggle.**

  *In the Page view, put the Publish button in the top right, to the left of the dark
  mode switcher.*

  **This is the other half of `chrome?`, and it is what makes that corner more than
  simply narrower on a focused surface.** `top-bar` gates the app's widgets — the page
  selectors, Sign in / Sign out — on there being no focused surface, so a Recipe page
  and the version viewer have shown the theme toggle alone up here since that rule was
  written. What stands there now is this: the app's controls step aside for a surface
  about one thing, and that surface's own controls take the space they left.
  `focused-surface?` says the chrome steps aside and `left-slot` says what replaces it
  on the left; this is the same answer for the right, and it takes the whole state for
  `focused-surface?`'s reason — `:diffing` is not a `:page`, so a function of `page`
  would have needed widening rather than extending.

  **An ordered `cond` and not a row of `when`s**, exactly as `left-slot` is and for the
  same reason: the version viewer opens *over* a Recipe page, so both surfaces are live
  at once and the bar must not carry both of their answers. The viewer outranks the page
  underneath, so a focused surface's actions **replace** whatever the bar had rather
  than joining it — which is also how Publish comes to be unreachable while a dialog is
  up: it falls out of the order rather than being a condition somebody has to remember.
  `views.diff/inert-behind!` is where that consequence is argued from the other side,
  and it is the reason this is an order at all: that function exempts the whole top bar
  from the overlay's `inert`, so anything standing here is reachable by keyboard from
  inside the dialog. The viewer's own answers being reachable from it is right; a
  Recipe's one-way latch being reachable from it is one Tab and one Enter from a
  publish nobody asked for.

  **The viewer's answers are the viewer's to name.** *also, on the inbox/tray. place
  the Seen Approve/Dismiss buttons in that position.* Which of the two readings is up,
  and whether the entry behind it is still in the queue, are `views.diff`'s questions —
  `views.diff/answers` answers them, and this places the result. The same division as
  the left slot's, where `back-to-origin` derives its own label and the slot only puts
  it somewhere.

  **Publish shows when all five of these hold**, and each one is something that would
  otherwise be a lie up there:

  - **the page is `:recipe`** — it is one Recipe's control and there is no other
    surface it means anything on.
  - **`logged-in?`** — and this gate is *not* cosmetic the way the header badges' are:
    it is a write, and the server refuses it to anybody else. A visitor who followed a
    link to a published Recipe has the theme toggle up here and nothing else.
  - **the Recipe is there**, read as a row in `:details` under `:recipe-page-id`. That
    is the same lookup `views.recipe/recipe-page` draws the reading from, so the bar and
    the panel cannot come to be about different Recipes, or disagree about whether there
    is one — `left-slot`'s argument about `:recipe-page-id`, one field along. The page's
    other two states have no row, and a Publish button over `Loading…` or over *No such
    Recipe here* is the failure this replaces: those two used to get it right for free,
    by having the button drawn inside `found`.
  - **it is not published yet** — off that same row's `published`, which is
    `mutating-actions`' `(when-not (= 1 published) …)` moved rather than re-derived, for
    the reason above: one fact, read once, in the place the panel reads it. The latch is
    one-way and the API has no unpublish, so this button's whole life is the moment
    before it is pressed.
  - **not `recipe-page-edit?`**, and this is the one of the five that is a decision
    rather than a reading. Publish was never on the edit page, and it is deliberately
    not put there now that the bar is where it lives: publishing is a one-way latch, and
    pressing it over a draft that has not been saved would make a Recipe public in a
    state its own editor disagrees with. The left slot already treats the two modes as
    different — editing has Save and Cancel and no way back, because *leaving an editor
    is a question with two answers* — and a Publish standing beside them would be a
    third answer that means neither of them. The reading is where a Recipe is what it
    says it is, and that is where it can be published.

  **The container is drawn only when there is something to put in it**, which is this
  run of work's leftover container met once more: an empty flex child in a bar with an
  8px gap moves the theme toggle in from the edge for nothing.
  `views.recipe/publish-action` keeps the list."
  [{:keys [page recipe-page-edit? recipe-page-id logged-in? diffing details]}]
  (when-let [actions
             (cond
               ;; **The viewer outranks the page it was opened over**, as it does in
               ;; `left-slot`, and it answers for this corner with its own answers:
               ;; Approve and Dismiss on a proposal, Seen on the other three kinds,
               ;; and nothing at all on a version viewer opened from a Recipe's page
               ;; or from the Deleted page, where there is no queue entry behind it.
               ;; Which of those it is, is `views.diff`'s question and not the bar's.
               ;;
               ;; **Called, not mounted as `[diff/answers]`.** A component vector is
               ;; truthy whatever it renders, so the `when-let` below would draw the
               ;; box for a viewer with no answers and put an empty flex child in the
               ;; bar — the leftover this whole slot is careful about. Calling it lets
               ;; a nil answer *be* nil here.
               (some? diffing)
               (diff/answers)

               (and (= :recipe page) (not recipe-page-edit?) logged-in?)
               (when-let [recipe (get details recipe-page-id)]
                 (when-not (= 1 (:published recipe))
                   [recipe/publish-action recipe])))]
    [:div.top-bar-actions actions]))

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
        ;; where the brand would be, and it is the trade the rule comes with.
        ;;
        ;; **The rule was 'the right-hand side is widgets, and only the theme toggle is
        ;; in every view', and the first half of that has been overruled** — *In the
        ;; Page view, put the Publish button in the top right, to the left of the dark
        ;; mode switcher.* Publish is not a widget and it is not global: it is one
        ;; Recipe's one-way latch, on that Recipe's page. So the sentence is now two,
        ;; and the second half of it is untouched — **the theme toggle is still the only
        ;; thing in every view**, and it is still the only thing here that is not gated
        ;; on something. What the corner holds is the app's widgets while the app's
        ;; chrome is up, and otherwise the focused surface's own actions, which are as
        ;; conditional as the widgets they replace and conditional on something else:
        ;; `surface-actions` above, which is the one place that decides. And the
        ;; narrowing this `chrome?` was written for stands exactly as it did — **the
        ;; owner's configuration surfaces are still reached from the global view**, and
        ;; a control that belongs to one Recipe arriving up here is not a reason to send
        ;; the Inbox back after it.
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
      ;; **What the surface on screen offers on the thing it is about**, in the space
      ;; the app's widgets vacate on a focused surface — Publish on a Recipe page. Last
      ;; before the toggle, because *to the left of the dark mode switcher* is where he
      ;; asked for it and because the toggle is the fixed point of this corner: a
      ;; control that moved the one thing present in every view would be paid for on
      ;; every page. `surface-actions` decides what this is and whether there is any.
      (surface-actions app-state)
      ;; **The one widget in every view**, and on a focused surface the only thing here
      ;; that is not that surface's own. Not gated, because reading in the wrong theme
      ;; is a reason to change it wherever you are.
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
