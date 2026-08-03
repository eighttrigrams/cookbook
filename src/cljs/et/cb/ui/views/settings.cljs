(ns et.cb.ui.views.settings
  "The owner's one setting: the machine user's password.

  There is no username field and no add form, because there is nothing to name —
  the machine user is the literal `machine-user`, and creating it and changing its
  password are the same operation on that fixed name. So this is one field and one
  button, and what it shows back is only whether the row exists and when the
  password was last set. The password is never returned by any endpoint, so there
  is nothing here that could display one."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.cb.ui.state :as state]))

(defn machine-user-block []
  (let [password (r/atom "")]
    (fn []
      (let [{:keys [machine-user]} @state/*app-state
            exists? (:exists machine-user)
            submit (fn []
                     (when-not (str/blank? @password)
                       (state/set-machine-user-password @password #(reset! password ""))))]
        [:div.settings
         [:h2 "Machine user"]
         [:p.settings-note
          "One agent account, named "
          [:code (or (:username machine-user) "machine-user")]
          ". It writes here unsupervised, with no gate and no toggle. The only
           things it cannot do are publish a Recipe and change one you have
           already published."]
         [:div.settings-status
          (if exists?
            [:span.settings-present
             "Exists — password last set " [:strong (:password_set_at machine-user)]]
            [:span.settings-absent "Not created yet."])]
         [:div.settings-row
          [:input.machine-password
           {:type "password"
            :auto-complete "new-password"
            :placeholder (if exists? "New password" "Password")
            :value @password
            :on-change #(reset! password (-> % .-target .-value))
            :on-key-down #(when (= (.-key %) "Enter") (submit))}]
          [:button.machine-password-save
           {:on-click submit :disabled (str/blank? @password)}
           (if exists? "Reset password" "Create machine user")]]]))))
