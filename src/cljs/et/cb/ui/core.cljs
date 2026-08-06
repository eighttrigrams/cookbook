(ns et.cb.ui.core
  (:require [reagent.dom.client :as rdomc]
            [reagent.core :as r]
            [et.cb.ui.state :as state]
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

(defn- page-body
  "Exactly one of the three, chosen by `:page` — the shelf is not a backdrop the
  other two are laid over. It used to be: both panels rendered `(when open?)` and
  the shelf rendered unconditionally underneath, so opening Settings gave you the
  settings *and* the search box, the compose form and every card below it.

  **A caller who is not signed in gets the shelf whatever `:page` says.** The
  Settings and Scopes pages are reached by buttons only the owner has, so a
  visitor left on one would be looking at a blank page with no way off it.
  `logout` already puts `:page` back to `:shelf`; this is the second half of that
  guarantee, and the one that does not depend on every future writer of the state
  remembering it."
  [logged-in? page]
  (case (if logged-in? page :shelf)
    :scopes [scopes/scopes-page]
    :settings [settings/machine-user-block]
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
  (state/fetch-auth-required)
  (rdomc/render root [app]))
