(ns et.cb.ui.views.recipe-modals
  "The overlays that change one Recipe: the Edit form, and the two confirmations
  in front of Publish and Delete.

  **A namespace of its own because two views ask for them.** They were `defn-`s in
  `views.recipes` while the shelf's card footer was the only thing that opened them;
  the four actions are on `views.recipe` now, and that page must not require the
  shelf — a page for one Recipe pulling in the whole listing view would be a require
  cycle waiting for the first thing the shelf wants back. A third namespace both can
  require is what avoids it, rather than a `declare` papering over the loop.

  **And they are mounted once at the app root, beside `page-body`, not inside a
  page.** Two reasons, and the first is the one that made this change necessary:

  - `core/page-body` renders **exactly one of five pages** — the shelf is not a
    backdrop the others are laid over, and its docstring is emphatic about it. A
    modal mounted inside `recipes-tab` is therefore not on the page at all while
    `/recipe/<id>` is up, so a button there wired to `state/start-editing` would set
    the state and render nothing. These three are overlays keyed off global state
    (`:editing`, `:publishing`, `:deleting`), so the root is where they belong: they
    are over whichever page is up, not part of one.
  - It is also more of the containing-block argument that kept them outside the
    cards. A card's `backdrop-filter` makes it the containing block for a
    `position: fixed` overlay, which would pin a modal to that one card instead of
    to the viewport; `.recipe-page` and `.inbox` have that same filter, so being
    outside the *page* is the same care one level up.

  The version viewer comes with them for both reasons at once — see `overlays`."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.cb.ui.state :as state]
            [et.cb.ui.views.diff :as diff]))

(def tags-placeholder
  "The owner's extra search words, said the same way in both forms that write them
  — the shelf's compose form and the Edit modal below. One string and not two, for
  the reason the badges are one component: a placeholder is what tells him what the
  field is for, and two wordings of it is how the two forms come to describe the
  same field differently."
  "Tags — extra words to find this by")

(defn scope-picker
  "Which Scopes this Recipe is filed under, as a row of toggles over the owner's
  own list. Rendered as nothing at all when he has made no Scopes yet: an empty
  picker would be a control that cannot do anything, and the place to make one is
  the Scopes page.

  `selected` is a ratom holding a set of ids, so this component owns no state of
  its own — the form around it is what sends the set, and reading it back out of a
  child would be the same fact in two places.

  Shared with the shelf's compose form, and it lives here with the Edit modal
  rather than there because of which way the requires may point: the root mounts
  this namespace, so it must not reach into the shelf."
  [selected]
  ;; Both derefs happen out here, before the `for`. A deref inside the body of a
  ;; lazy seq is evaluated after reagent has stopped watching, so the chips would
  ;; not repaint when one was clicked — and reagent says so at the console rather
  ;; than silently.
  (let [scopes (:scopes @state/*app-state)
        chosen @selected]
    (when (seq scopes)
      [:div.scope-picker
       [:span.scope-picker-label {:title "Categories this Recipe is filed under"}
        "Scopes"]
       (for [{:keys [id title description]} scopes]
         ^{:key id}
         [:button.scope-chip
          {:type "button"
           :class (when (contains? chosen id) "on")
           :title description
           :on-click #(swap! selected (fn [s] (if (contains? s id) (disj s id) (conj s id))))}
          title])])))

(defn- edit-modal
  "Tags and Scopes sit in here with the three content fields even though a save
  that touches only them makes no version — the modal is where you edit a Recipe,
  and which of its fields the version ladder is about is the API's business. The
  subtitle says the version this is editing, and a filing-only save deliberately
  leaves that number where it is.

  The Scopes are prefilled from the Recipe's own `:scopes`, which came in with the
  body when the card was expanded. Sending them on every save is what makes the
  server's rule work for this client: an omitted `scope_ids` would keep the filing,
  and this form has a picker showing a set that the owner may just have emptied
  on purpose."
  [recipe]
  (let [title (r/atom (or (:title recipe) ""))
        useful-when (r/atom (or (:useful_when recipe) ""))
        tags (r/atom (or (:tags recipe) ""))
        description (r/atom (or (:description recipe) ""))
        scope-ids (r/atom (set (map :id (:scopes recipe))))]
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
        [:input.modal-tags {:type "text" :placeholder tags-placeholder
                            :value @tags
                            :on-change #(reset! tags (-> % .-target .-value))}]
        [:textarea.modal-description
         {:placeholder "The recipe itself"
          :rows 8
          :value @description
          :on-change #(reset! description (-> % .-target .-value))}]
        [scope-picker scope-ids]
        [:div.modal-actions
         [:button {:disabled (str/blank? @title)
                   :on-click #(state/update-recipe (:id recipe)
                                                   {:title @title
                                                    :useful_when @useful-when
                                                    :tags @tags
                                                    :description @description
                                                    :scope_ids (vec @scope-ids)}
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

(defn overlays
  "The four surfaces that stand over whichever page is up, mounted once at the app
  root — the ns docstring says why that is the root and not a page.

  **The version viewer is in here with the three modals rather than beside them**,
  and it was mounted twice before this: once in `recipes-tab` and once in
  `inbox-page`, because those were the two pages a reader could open it from and
  `page-body` renders only one of them. That is the same duplication this whole
  namespace removes — a second copy is a second thing to remember when a third page
  gets a Versions button, which is exactly what has just happened.

  The Inbox's dismiss confirmation deliberately stays where it is. It is keyed to an
  entry in `:inbox` rather than to a Recipe, and the only place it can be opened from
  is the one page that draws that list, so it is that page's and not a Recipe's."
  []
  (let [{:keys [details editing publishing deleting diffing]} @state/*app-state]
    [:<>
     ;; **All three from `:details`, which is the one map holding a full Recipe
     ;; row.** The two confirmations used to be looked up in `:recipes` instead, on
     ;; the argument that each of them needs only short fields the listing already
     ;; carries — a title, a version count — so neither had to wait for a body. True
     ;; of the shelf and false of everywhere else: arrive at `/recipe/<id>` by URL and
     ;; the listing was never fetched, so that lookup found nil and the modal
     ;; silently did not render. `state/open-on-detail!` is the one place each of
     ;; these is latched open and it answers for the row being there, so this is one
     ;; source rather than three modals each knowing where its Recipe comes from.
     (when-let [recipe (get details editing)]
       [edit-modal recipe])
     (when-let [recipe (get details publishing)]
       [publish-modal recipe])
     (when-let [recipe (get details deleting)]
       [delete-modal recipe])
     (when diffing
       [diff/component])]))
