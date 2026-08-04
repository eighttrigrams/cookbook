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
  changes something makes the next one. Beside that it shows where those versions
  came from — `3(machine)/17(ui)` — from counts the listing endpoint aggregates,
  because a collapsed card is exactly the place that cannot go and fetch a version
  list.

  The shelf can be narrowed two ways at once — by a title search, and to the
  Recipes a human has edited here rather than an agent. Both are the listing
  endpoint's own `:where` clauses; nothing on this side filters rows it was
  given.

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

(defn- delete-modal
  "Deleting takes the recipe and every version of it, and no route puts any of
  it back — so this asks first, the same way publishing does.

  The confirm button goes dead on the first click, and here the latch matters
  more than it does for publishing. Only the response callback closes this
  dialog, so two quick clicks would send two DELETEs: the first succeeds and
  the second 404s, raising 'Could not delete' over a delete that in fact went
  through."
  [_recipe]
  (let [sending? (r/atom false)]
    (fn [{:keys [id title version]}]
      [:div.modal-backdrop {:on-click state/stop-deleting}
       [:div.modal {:on-click #(.stopPropagation %)}
        [:h2 "Delete this recipe?"]
        [:div.modal-subtitle title]
        [:p.modal-note
         (if (= 1 version)
           "Its one version goes with it, and there is no undo."
           (str "All " version " versions go with it, and there is no undo."))]
        [:div.modal-actions
         [:button.delete-confirm.danger
          {:disabled @sending?
           :on-click #(do (reset! sending? true)
                          (state/delete-recipe id state/stop-deleting))}
          (if @sending? "Deleting…" "Delete")]
         [:button.secondary {:on-click state/stop-deleting} "Cancel"]]]])))

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

(def ^:private source-badge-title
  "Spelled out because `(?)` is the bucket every Recipe written before this
  shipped falls into, and a reader who is not told would read it as the app being
  unsure rather than as nothing having been recorded."
  (str "Where this Recipe's versions came from — "
       "(ui) saved here by hand, "
       "(machine) written by an agent, "
       "(?) not recorded, which is every version from before cookbook noted this"))

(defn- source-split
  "`3(machine)/17(ui)`, and only the buckets that have something in them: a
  Recipe nothing has written by machine says `17(ui)` rather than carrying a `0`
  around. All three empty cannot happen — the counts sum to the version number, so
  there is always at least one — but a listing row from an older server would have
  no counts at all, and that renders as nothing rather than as `0(?)`."
  [{:keys [machine_versions ui_versions unrecorded_versions]}]
  (let [buckets (->> [[machine_versions "machine"] [ui_versions "ui"] [unrecorded_versions "?"]]
                     (filter (fn [[n _]] (and (number? n) (pos? n))))
                     (map (fn [[n label]] (str n "(" label ")"))))]
    (when (seq buckets)
      [:span.source-badge {:title source-badge-title} (str/join "/" buckets)])))

(defn- card [{:keys [id title useful_when version published published_at modified_at] :as recipe}
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
      [source-split recipe]
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
         [:button.secondary.danger {:on-click #(state/start-deleting id)} "Delete"]]])]))

(defn- empty-message
  "Why the shelf is empty, and never a lie about it. 'No recipes yet.' is a claim
  about the shelf, so it may only be said when nothing is narrowing the view —
  with a filter on, what is empty is the result and not the shelf.

  The human filter gets a sentence of its own rather than sharing 'Nothing
  matches.', because its empty case is the expected one at first: the provenance
  bit is only recorded going forward, so every Recipe reads as not-human-edited
  until it is next saved from here. A reader who is told that will not read it as
  a broken filter."
  [search human-only?]
  (cond
    (seq search) "Nothing matches."
    human-only?  "Nothing here has been edited in this UI yet."
    :else        "No recipes yet."))

(defn recipes-tab []
  (let [{:keys [recipes search human-only? logged-in? open details editing publishing deleting]}
        @state/*app-state]
    [:div.shelf
     (when logged-in? [compose-form])
     [:div.shelf-controls
      ;; The endpoint matches words from their start and only in the title, so
      ;; the placeholder says titles, and says beginnings of words rather than
      ;; letting a typist expect a substring to hit.
      [:input.search
       {:type "text" :placeholder "Search titles — start of any word"
        :value search
        :on-change #(state/set-search (-> % .-target .-value))}]
      ;; Shown signed out as well as in. A visitor is served the published
      ;; Recipes and has no version history to consult, so 'which of these did the
      ;; human write himself' is a question only this control can answer for them
      ;; — and it is a narrowing of what they can already see, never a way past
      ;; the latch.
      [:label.human-filter {:title "Recipes with at least one edit made here, rather than by an agent"}
       [:input {:type "checkbox"
                :checked (boolean human-only?)
                :on-change #(state/set-human-only (-> % .-target .-checked))}]
       "Human-edited only"]]
     (if (empty? recipes)
       [:div.empty (empty-message search human-only?)]
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
       [publish-modal recipe])
     ;; Same again: the question needs the title and the version count, both of
     ;; which the listing carries, so this one never fetches a body either.
     (when-let [recipe (first (filter #(= deleting (:id %)) recipes))]
       [delete-modal recipe])]))
