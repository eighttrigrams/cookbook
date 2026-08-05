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
  (let [{:keys [auth-required? logged-in? show-login? dark-mode settings-open? scopes-open?]}
        @state/*app-state]
    [:div.top-bar
     [:div.brand
      [:span.brand-mark "▤"]
      [:span.brand-name "Cookbook"]]
     [:div.top-bar-right
      ;; The Scopes page. Owner-only, and gated the same way the settings panel is
      ;; — this shell has no router, so a private page is a flag in the atom and a
      ;; button that flips it. The gate is `logged-in?` and not a claim about
      ;; safety: the endpoints behind it answer 403 to anybody else, which is the
      ;; boundary. It borrows `.settings-toggle`'s styling because it is the same
      ;; kind of control in the same corner.
      (when logged-in?
        [:button.settings-toggle.scopes-toggle
         {:on-click state/toggle-scopes
          :class (when scopes-open? "active")
          :title "Scopes"}
         "▦"])
      ;; only the owner has a setting to make: the machine user's password
      (when logged-in?
        [:button.settings-toggle
         {:on-click state/toggle-settings
          :class (when settings-open? "active")
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

(defn app []
  (let [{:keys [auth-required? logged-in? show-login? error settings-open? scopes-open?]}
        @state/*app-state]
    (if (nil? auth-required?)
      [:div.loading "Loading…"]
      [:div
       [top-bar]
       (when error
         [:div.error error [:button.error-dismiss {:on-click state/clear-error} "×"]])
       (when (and auth-required? (not logged-in?) show-login?)
         [login-form])
       (when (and logged-in? scopes-open?)
         [scopes/scopes-page])
       (when (and logged-in? settings-open?)
         [settings/machine-user-block])
       [:div.main-layout
        [recipes/recipes-tab]]])))

(defonce root (rdomc/create-root (.getElementById js/document "app")))

(defn init []
  (state/setup-dark-mode!)
  (state/fetch-auth-required)
  (rdomc/render root [app]))
