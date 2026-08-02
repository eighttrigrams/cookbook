(ns et.cb.ui.views.recipes
  "The shelf: one Recipe per card, most recently saved first.

  A collapsed card shows the **title and the useful-when line and nothing
  else** — that pair is a retrieval index, both for a reader and for the agents
  that write here. The body is not hidden behind the collapse, it is genuinely
  not on the client: the listing endpoint does not send it, and expanding a card
  is what goes and fetches `?detail=full`. Do not 'optimise' this by loading
  every description up front, which would make the collapse cosmetic and
  contradict the API's own rule.

  Recipes are versioned. The card shows which version it is on; every save that
  changes something makes the next one."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.cb.ui.state :as state]))

(defn- day [timestamp]
  (when (seq (str timestamp))
    (first (str/split (str timestamp) #" "))))

(defn- compose-form []
  (let [title (r/atom "")
        useful-when (r/atom "")
        description (r/atom "")]
    (fn []
      (let [submit (fn []
                     (when-not (str/blank? @title)
                       (state/add-recipe {:title @title
                                          :useful_when @useful-when
                                          :description @description}
                                         (fn []
                                           (reset! title "")
                                           (reset! useful-when "")
                                           (reset! description "")))))]
        [:div.compose
         [:input.compose-title
          {:type "text" :placeholder "Title"
           :value @title
           :on-change #(reset! title (-> % .-target .-value))
           :on-key-down #(when (= (.-key %) "Enter") (submit))}]
         [:input.compose-useful-when
          {:type "text" :placeholder "Useful when…"
           :value @useful-when
           :on-change #(reset! useful-when (-> % .-target .-value))
           :on-key-down #(when (= (.-key %) "Enter") (submit))}]
         [:textarea.compose-description
          {:placeholder "The recipe itself"
           :rows 4
           :value @description
           :on-change #(reset! description (-> % .-target .-value))}]
         [:button {:on-click submit :disabled (str/blank? @title)} "Add"]]))))

(defn- edit-modal [recipe]
  (let [title (r/atom (or (:title recipe) ""))
        useful-when (r/atom (or (:useful_when recipe) ""))
        description (r/atom (or (:description recipe) ""))]
    (fn [recipe]
      [:div.modal-backdrop {:on-click state/stop-editing}
       [:div.modal {:on-click #(.stopPropagation %)}
        [:h2 "Edit"]
        [:div.modal-subtitle (str "version " (:version recipe))]
        [:input {:type "text" :placeholder "Title"
                 :value @title
                 :on-change #(reset! title (-> % .-target .-value))}]
        [:input {:type "text" :placeholder "Useful when…"
                 :value @useful-when
                 :on-change #(reset! useful-when (-> % .-target .-value))}]
        [:textarea.modal-description
         {:placeholder "The recipe itself"
          :rows 8
          :value @description
          :on-change #(reset! description (-> % .-target .-value))}]
        [:div.modal-actions
         [:button {:disabled (str/blank? @title)
                   :on-click #(state/update-recipe (:id recipe)
                                                   {:title @title
                                                    :useful_when @useful-when
                                                    :description @description}
                                                   state/stop-editing)}
          "Save"]
         [:button.secondary {:on-click state/stop-editing} "Cancel"]]]])))

(defn- card-body
  "`detail` is nil until the fetch this expansion started comes back."
  [detail]
  (if detail
    (if (str/blank? (:description detail))
      [:div.card-body-empty "No body yet."]
      [:div.card-body (:description detail)])
    [:div.card-body-loading "Loading…"]))

(defn- card [{:keys [id title useful_when version modified_at]}
             {:keys [logged-in? open details]}]
  (let [expanded? (contains? open id)]
    [:div.card
     [:div.card-header {:on-click #(state/toggle-open id)}
      [:span.card-toggle (if expanded? "▾" "▸")]
      [:h2.card-title title]
      [:span.version-badge {:title "Every edit makes a new version"} (str "v" version)]
      [:span.card-date (day modified_at)]]
     (when (seq useful_when)
       [:div.card-useful-when useful_when])
     (when expanded?
       [card-body (get details id)])
     (when logged-in?
       [:div.card-footer
        [:span.card-actions
         [:button.secondary {:on-click #(state/start-editing id)} "Edit"]
         [:button.secondary.danger {:on-click #(state/delete-recipe id)} "Delete"]]])]))

(defn recipes-tab []
  (let [{:keys [recipes search logged-in? open details editing]} @state/*app-state]
    [:div.shelf
     (when logged-in? [compose-form])
     [:input.search
      {:type "text" :placeholder "Search titles and useful-when"
       :value search
       :on-change #(state/set-search (-> % .-target .-value))}]
     (if (empty? recipes)
       [:div.empty (if (seq search) "Nothing matches." "No recipes yet.")]
       (for [recipe recipes]
         ^{:key (:id recipe)}
         [card recipe {:logged-in? logged-in? :open open :details details}]))
     ;; Outside the cards: a card's backdrop-filter would make it the containing
     ;; block for the modal's fixed positioning, pinning the modal to that one
     ;; card instead of to the viewport.
     (when-let [recipe (get details editing)]
       [edit-modal recipe])]))
