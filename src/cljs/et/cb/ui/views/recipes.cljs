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
  changes something makes the next one.

  Publishing happens from the card, behind a confirmation, and only the owner
  sees the affordance. It is one way — the API has no unpublish — so a published
  card loses its Publish button and wears a badge instead.

  All three fields are markdown, but not the same markdown: the title and the
  useful-when line are rendered inline, so they cannot grow a heading or a list
  and break the card's layout, while the body gets the full parser and the code
  highlighting. See `et.cb.ui.markdown`."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.cb.ui.markdown :as markdown]
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

(defn- publish-modal
  "The latch is one-way: nothing in the API takes it back off, so this asks
  before it fires rather than offering an undo afterwards.

  The confirm button goes dead on the first click. Only the response callback
  closes this dialog — that is deliberate, so a failed publish can put its error
  banner somewhere reachable — which leaves the button live for the whole round
  trip unless something takes it out. Two quick clicks would otherwise send two
  POSTs, and the second one loses a write race server-side: the card would gain
  its published badge at the same moment the banner said the publish failed."
  [_recipe]
  (let [sending? (r/atom false)]
    (fn [{:keys [id title]}]
      [:div.modal-backdrop {:on-click state/stop-publishing}
       [:div.modal {:on-click #(.stopPropagation %)}
        [:h2 "Publish this recipe?"]
        [:div.modal-subtitle title]
        [:p.modal-note
         "It becomes readable by anyone who opens Cookbook, and you have put your
          name to it. There is no unpublish."]
        [:div.modal-actions
         [:button.publish-confirm
          {:disabled @sending?
           :on-click #(do (reset! sending? true)
                          (state/publish-recipe id state/stop-publishing))}
          (if @sending? "Publishing…" "Publish")]
         [:button.secondary {:on-click state/stop-publishing} "Cancel"]]]])))

(defn- card-body
  "`detail` is nil until the fetch this expansion started comes back.

  The body is the one field that gets the full markdown parser, and the only one
  that can carry a fenced code block — so the highlighter is only ever asked for
  something a card has actually been expanded to see."
  [detail]
  (if detail
    (if (str/blank? (:description detail))
      [:div.card-body-empty "No body yet."]
      [:div.card-body [markdown/render (:description detail)]])
    [:div.card-body-loading "Loading…"]))

(defn- card [{:keys [id title useful_when version published published_at modified_at]}
             {:keys [logged-in? open details]}]
  (let [expanded? (contains? open id)
        ;; JSON gives 0/1 and 0 is truthy in cljs, so this has to be a
        ;; comparison rather than a test for presence.
        published? (= 1 published)]
    [:div.card {:class (when published? "published")}
     [:div.card-header {:on-click #(state/toggle-open id)}
      [:span.card-toggle (if expanded? "▾" "▸")]
      [:h2.card-title [markdown/render-inline title]]
      (when (and logged-in? published?)
        [:span.published-badge {:title (str "Published " (day published_at)
                                            " — public, and one way")}
         "published"])
      [:span.version-badge {:title "Every edit makes a new version"} (str "v" version)]
      [:span.card-date (day modified_at)]]
     (when (seq useful_when)
       [:div.card-useful-when [markdown/render-inline useful_when]])
     (when expanded?
       [card-body (get details id)])
     (when logged-in?
       [:div.card-footer
        [:span.card-actions
         (when-not published?
           [:button.secondary {:on-click #(state/start-publishing id)} "Publish"])
         [:button.secondary {:on-click #(state/start-editing id)} "Edit"]
         [:button.secondary.danger {:on-click #(state/delete-recipe id)} "Delete"]]])]))

(defn recipes-tab []
  (let [{:keys [recipes search logged-in? open details editing publishing]} @state/*app-state]
    [:div.shelf
     (when logged-in? [compose-form])
     ;; The endpoint matches words from their start and only in the title, so
     ;; the placeholder says titles, and says beginnings of words rather than
     ;; letting a typist expect a substring to hit.
     [:input.search
      {:type "text" :placeholder "Search titles — start of any word"
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
       [edit-modal recipe])
     ;; The confirmation only needs the two short fields, which the listing
     ;; already carries — unlike the Edit modal it never has to fetch a body.
     (when-let [recipe (first (filter #(= publishing (:id %)) recipes))]
       [publish-modal recipe])]))
