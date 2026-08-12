(ns et.cb.ui.views.deleted
  "The Deleted page: what has been deleted and not yet destroyed.

  *we can have a page bringing us to revisit and hard delete data* — and it is the
  other half of the tombstone. A delete takes a Recipe off the shelf and keeps every
  word of it (012), so something has to be able to say *and now really* — this is
  that, and it is the only place in the app that can.

  **Two things a row offers, and they are the two halves of 'revisit'.** Its title
  opens the version viewer on the version it was deleted on, read-only, which is what
  makes the deletion reviewable rather than merely recorded. And a purge, behind a
  confirmation, which is the one irreversible act in this app.

  There is no *undelete* here, and its absence is deliberate rather than pending: the
  row is one `UPDATE` away from coming back, and whether a Recipe can return to the
  shelf is a decision about what the shelf means, not about what the data allows. It
  waits for a word.

  Modelled on `views.scopes`: a panel, a list of rows, and a confirmation rendered as
  its **sibling** rather than inside it — the panel has a `backdrop-filter`, which
  would become the containing block for a fixed overlay and pin it inside the panel."
  (:require [reagent.core :as r]
            [et.cb.ui.page-lock :as page-lock]
            [et.cb.ui.recipe-badges :as recipe-badges]
            [et.cb.ui.scope-badges :as scope-badges]
            [et.cb.ui.state :as state]))

(defn- row
  "One tombstone: what it was called, what it was filed under, how far it had got,
  and when it went.

  The title opens the viewer, so it is a button and wears the same class the queue's
  openable titles do — one look for 'this text can be read', wherever the reader meets
  it. The badges are `scope-badges` with no gesture and no promise, exactly as the
  queue's are: shift-clicking one here would set a filter on a shelf this Recipe is
  not on."
  [{:keys [id title version deleted_at scopes]}]
  [:div.deleted-row
   [:span.deleted-row-title
    [:button.inbox-title-link
     {:title "Read this Recipe as it was when it was deleted"
      :on-click #(state/start-diff id)}
     title]
    (when (seq scopes)
      [scope-badges/badges scopes {:class "inbox-scopes"}])]
   [:span.deleted-row-meta
    [:span.badge {:title "The version it was on when it was deleted"} (str "v" version)]
    [:span.card-date {:title "When it was deleted"} (recipe-badges/day deleted_at)]]
   [:span.deleted-row-actions
    [:button.secondary.danger
     {:on-click #(state/start-purging id)}
     "Destroy"]]])

(defn- purge-modal
  "Asks before destroying a tombstone, and the question is what survives it.

  The confirm button goes dead on the first click, like every other confirmation
  here: only the response closes the dialog — so a failed purge can put its error
  banner somewhere reachable — and two clicks would otherwise send two DELETEs, the
  second 404ing over a purge that in fact went through.

  **It says what a purge leaves behind**, because that is the part nobody would guess:
  the queue entries naming this Recipe survive it, as events always have, and go back
  to being un-openable. A reader who found those rows dead afterwards without having
  been told would read it as a bug."
  [_recipe]
  (let [sending? (r/atom false)]
    (fn [{:keys [id title version]}]
      [:div.modal-backdrop {:on-click state/stop-purging}
       [page-lock/while-mounted]
       [:div.modal {:on-click #(.stopPropagation %)}
        [:h2 "Destroy this Recipe for good?"]
        [:div.modal-subtitle title]
        [:p.modal-note
         (str "All " version (if (= 1 version) " version" " versions")
              " of it go, and its filing with them. There is no undo and nothing
               serves the text again — this is the step the delete deliberately
               was not.")]
        [:p.modal-note
         "What stays is your queue: the entries that recorded what an agent did to
          this Recipe keep its title and stop being openable, because what happened
          did happen."]
        [:div.modal-actions
         [:button.danger
          {:disabled @sending?
           :on-click #(do (reset! sending? true)
                          (state/purge-recipe id state/stop-purging))}
          (if @sending? "Destroying…" "Destroy")]
         [:button.secondary {:on-click state/stop-purging} "Cancel"]]]])))

(defn- panel []
  (let [{:keys [deleted]} @state/*app-state]
    [:div.deleted-panel
     [:h2 "Deleted"]
     [:p.settings-note
      "Recipes you or your agents have deleted. They are off the shelf, out of every
       search and out of the Scope counts, and every word of them is still here."]
     [:p.settings-note
      "Click a title to read it as it was when it was deleted. "
      [:strong "Destroy"]
      " is what finally removes it — the text, the whole version history and the
       filing — and there is no undo."]
     (if (empty? deleted)
       [:div.inbox-empty "Nothing is deleted."]
       [:div.deleted-list
        (for [recipe deleted]
          ^{:key (:id recipe)} [row recipe])])]))

(defn deleted-page
  "The panel and the confirmation, as siblings — `views.inbox/inbox-page`'s shape and
  for its reason.

  The confirmation is keyed to a row in `:deleted` rather than to `:details`, unlike
  the two Recipe modals: a tombstone is not in `:details` at all — every read that
  fills that map excludes it — so this page's own list is the only place its title and
  version can be read from, and `state/start-purging` is a plain latch rather than
  `open-on-detail!`."
  []
  (let [{:keys [deleted purging]} @state/*app-state]
    [:<>
     [panel]
     (when-let [recipe (first (filter #(= purging (:id %)) deleted))]
       [purge-modal recipe])]))
