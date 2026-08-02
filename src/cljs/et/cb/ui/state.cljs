(ns et.cb.ui.state
  (:require [reagent.core :as r]
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
           :dark-mode (initial-dark-mode)}))

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
                   :current-user (js->clj (js/JSON.parse user-str) :keywordize-keys true))))))))

(defn login [username password on-success]
  (api/post-json "/api/auth/login" {:username username :password password} {}
    (fn [{:keys [token user]}]
      (swap! *app-state assoc :logged-in? true :token token :current-user user :error nil)
      (save-token! token user)
      (when on-success (on-success)))
    (err-handler "Invalid credentials")))

(defn logout []
  (clear-token!)
  (swap! *app-state assoc :logged-in? false :token nil :current-user nil))

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
