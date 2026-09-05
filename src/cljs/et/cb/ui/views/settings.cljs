(ns et.cb.ui.views.settings
  "The owner's two settings, and they are two halves of one idea.

  The **machine user's password** says who may *write* here. The **encryption
  key** says who may *read the prose*. They are separate credentials on purpose:
  an agent holding a token can fill this shelf and, without the key, cannot read
  a word of what is already on it — and a browser holding the key can read
  everything and, without a login, cannot change anything.

  Neither field ever shows what it holds back. No endpoint returns a password,
  and the key is imported non-extractably, so there is nothing here that *could*
  display one."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.cb.ui.state :as state]
            [et.cb.ui.key-store :as key-store]))

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

(defn encryption-key-block
  "Where the key gets into this browser, and the only place it can.

  There is deliberately no other import path — no URL fragment, no query
  parameter, nothing an agent could hand over. The two surfaces that decrypt are
  this page and `plurama-cli`; a third way in would be a third way to lose a key
  that has no reset and no recovery.

  What is shown back is a **fingerprint**, eight hex characters of SHA-256 over
  the key. It is here so that a phone and a laptop can be told they hold the same
  key without either being able to say what it is."
  []
  (let [pasted (r/atom "")
        problem (r/atom nil)]
    (fn []
      (let [{:keys [status fingerprint]} @key-store/state
            submit (fn []
                     (reset! problem nil)
                     (-> (key-store/import-key! @pasted)
                         (.then (fn [_] (reset! pasted "")))
                         (.catch (fn [e] (reset! problem (.-message e))))))]
        [:div.settings.settings-key
         [:h2 "Encryption key"]
         [:p.settings-note
          "Descriptions, useful-when lines and the reason/context an agent writes
           are sealed before they leave this browser. Titles, tags and Scopes are
           not — they are how you find things, and sealing them would cost the
           search everything and buy nothing. The key never goes to the server."]
         [:div.settings-status
          (case status
            :present [:span.settings-present
                      "Held by this browser — " [:code.key-fingerprint fingerprint]]
            :absent  [:span.settings-absent
                      "Not in this browser. Sealed prose will read as "
                      [:code "enc:v1:…"] " until you paste the key in."]
            [:span.settings-absent "Looking…"])]
         [:div.settings-row
          [:input.seal-key
           {:type "password"
            :auto-complete "off"
            :spell-check false
            :placeholder "base64 key"
            :value @pasted
            :on-change #(reset! pasted (-> % .-target .-value))
            :on-key-down #(when (= (.-key %) "Enter") (submit))}]
          [:button.seal-key-save
           {:on-click submit :disabled (str/blank? @pasted)}
           (if (= :present status) "Replace key" "Import key")]
          (when (= :present status)
            [:button.seal-key-forget
             {:on-click #(key-store/forget!)
              :title "Forget it on this device. The key itself is not destroyed."}
             "Forget"])]
         (when @problem [:p.settings-problem @problem])
         [:p.settings-note.settings-warning
          "It is stored as a non-extractable key, so this page can use it and
           cannot read it back — which also means it cannot be recovered from
           here. Lose every copy and the sealed Recipes are gone: there is no
           reset."]]))))

(defn settings-page
  "Both blocks, write-credential first. The order is the order they were built in
  and also the order they matter in: a shelf nobody can write to is empty, and an
  empty shelf has nothing to seal."
  []
  [:<>
   [machine-user-block]
   [encryption-key-block]])
