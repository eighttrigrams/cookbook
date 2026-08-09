(ns et.cb.ui.core
  (:require [reagent.dom.client :as rdomc]
            [reagent.core :as r]
            [et.cb.ui.state :as state]
            [et.cb.ui.views.inbox :as inbox]
            [et.cb.ui.views.recipe :as recipe]
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

(defn- top-bar []
  (let [{:keys [auth-required? logged-in? show-login? dark-mode page]}
        @state/*app-state]
    [:div.top-bar
     [:div.brand
      [:span.brand-mark "▤"]
      [:span.brand-name "Cookbook"]]
     [:div.top-bar-right
      ;; The Inbox. Owner-only like the other two, and it is the one of the three
      ;; that carries a number: how many of his agents' changes are waiting. The
      ;; count is the length of the list the page itself draws — there is no
      ;; endpoint for it — so the badge and the page cannot come to disagree about
      ;; what is unseen. Shown at 0 as well, because a button that appeared only
      ;; when there was work would leave him with no way to look at an empty queue
      ;; and confirm that it is empty.
      (when logged-in?
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
      (when logged-in?
        [:button.settings-toggle.scopes-toggle
         {:on-click state/toggle-scopes
          :class (when (= :scopes page) "active")
          :title "Scopes"}
         "▦"])
      ;; only the owner has a setting to make: the machine user's password
      (when logged-in?
        [:button.settings-toggle
         {:on-click state/toggle-settings
          :class (when (= :settings page) "active")
          :title "Machine user"}
         "⚙"])
      [:button.dark-mode-toggle
       {:on-click state/toggle-dark-mode
        :title (if dark-mode "Switch to light" "Switch to dark")}
       (if dark-mode "☀" "☾")]
      (cond
        (not auth-required?) nil
        logged-in? [:button.secondary {:on-click state/logout} "Sign out"]
        show-login? nil
        :else [:button.secondary
               {:on-click #(swap! state/*app-state assoc :show-login? true)} "Sign in"])]]))

(def ^:private owner-only-pages
  "The pages a signed-out caller is sent away from. Named as a set, because the
  question the gate below asks changed the day a page arrived that is *not* one of
  these — see `page-body`."
  #{:scopes :settings :inbox})

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
    :recipe [recipe/recipe-page]
    [:div.main-layout
     [recipes/recipes-tab]]))

(defn app []
  (let [{:keys [auth-required? logged-in? show-login? error page]} @state/*app-state]
    (if (nil? auth-required?)
      [:div.loading "Loading…"]
      [:div
       [top-bar]
       (when error
         [:div.error error [:button.error-dismiss {:on-click state/clear-error} "×"]])
       (when (and auth-required? (not logged-in?) show-login?)
         [login-form])
       [page-body logged-in? page]])))

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
