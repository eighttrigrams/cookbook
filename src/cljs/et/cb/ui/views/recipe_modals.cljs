(ns et.cb.ui.views.recipe-modals
  "The two confirmations that stand in front of an irreversible change to one
  Recipe: Publish, and Delete.

  **Both of them ask a question, and that is now the whole of what is in here.** The
  Edit form was the third and it has become a page — `views.recipe`'s second mode, at
  `?edit=true` — which leaves this namespace one idea rather than two: a dialog is for
  a step that cannot be taken back, and everything that can be is a control on the
  page it is about. The filing needs no dialog either and never had one; it saves per
  chip on the reading.

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
    `/recipe/<id>` is up, so a button there wired to `state/start-deleting` would set
    the state and render nothing. Both of these are overlays keyed off global state
    (`:publishing`, `:deleting`), so the root is where they belong: they are over
    whichever page is up, not part of one.
  - It is also more of the containing-block argument that kept them outside the
    cards. A card's `backdrop-filter` makes it the containing block for a
    `position: fixed` overlay, which would pin a modal to that one card instead of
    to the viewport; `.recipe-page` and `.inbox` have that same filter, so being
    outside the *page* is the same care one level up.

  The version viewer comes with them for both reasons at once — see `overlays`."
  (:require [reagent.core :as r]
            [et.cb.ui.state :as state]
            [et.cb.ui.views.diff :as diff]))

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
  "The three surfaces that stand over whichever page is up, mounted once at the app
  root — the ns docstring says why that is the root and not a page.

  **The version viewer is in here with the two confirmations rather than beside
  them**, and it was mounted twice before this: once in `recipes-tab` and once in
  `inbox-page`, because those were the two pages a reader could open it from and
  `page-body` renders only one of them. That is the same duplication this whole
  namespace removes — a second copy is a second thing to remember when a third page
  gets a Versions button, which is exactly what happened.

  The Inbox's dismiss confirmation deliberately stays where it is. It is keyed to an
  entry in `:inbox` rather than to a Recipe, and the only place it can be opened from
  is the one page that draws that list, so it is that page's and not a Recipe's."
  []
  (let [{:keys [details publishing deleting diffing]} @state/*app-state]
    [:<>
     ;; **Both from `:details`, which is the one map holding a full Recipe row.**
     ;; They used to be looked up in `:recipes` instead, on the argument that each
     ;; needs only short fields the listing already carries — a title, a version
     ;; count — so neither had to wait for a body. True of the shelf and false of
     ;; everywhere else: a Recipe the listing does not contain has a working page and
     ;; a confirmation that silently did not render. `state/open-on-detail!` is the
     ;; one place each of these is latched open and it answers for the row being
     ;; there, so this is one source rather than each modal knowing where its Recipe
     ;; comes from.
     (when-let [recipe (get details publishing)]
       [publish-modal recipe])
     (when-let [recipe (get details deleting)]
       [delete-modal recipe])
     (when diffing
       [diff/component])]))
