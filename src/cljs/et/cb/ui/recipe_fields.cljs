(ns et.cb.ui.recipe-fields
  "The pieces of a Recipe's form that more than one surface draws.

  A **leaf**, requiring nothing but `state`, which is the whole point of it: the
  compose form on the shelf (`views.recipes`) and the Recipe's own page
  (`views.recipe`) both want these, and neither may require the other. They lived
  in `views.recipe-modals` while the Edit modal was the second surface; that is a
  view, and a view is the wrong thing for two other views to point at.

  Same argument as `ui.recipe-badges` and `ui.scope-badges` one field along: what
  is here is here because two surfaces would otherwise each grow their own, and
  two spellings of one control is how they drift."
  (:require [et.cb.ui.state :as state]))

(def tags-placeholder
  "The owner's extra search words, said the same way by every form that writes
  them. One string and not one per form, for the reason the badges are one
  component: a placeholder is what tells him what the field is for, and two
  wordings of it is how two forms come to describe the same field differently."
  "Tags — extra words to find this by")

(defn scope-picker
  "Which Scopes this Recipe is filed under, as a row of toggles over the owner's
  own list. Rendered as nothing at all when he has made no Scopes yet: an empty
  picker would be a control that cannot do anything, and the place to make one is
  the Scopes page.

  **Controlled, and it holds nothing.** `:selected` is the set of ids that are on
  and `:on-toggle` is handed **the set the row would become**, so the caller says
  what a toggle means for it — a ratom on the compose form, a PUT on the read page
  — and this component owns neither answer. Keeping its own copy would be the same
  fact in two places, which is the argument its first version made about reading a
  child's state back out.

  It computes the next set rather than reporting the id that was clicked, because
  otherwise every caller writes `(if (contains? s id) (disj s id) (conj s id))`
  again and one of them eventually writes it differently.

  `:disabled?` takes the whole row out of service. Nothing passes it today — the
  read page **queues** a second toggle instead of refusing it, see
  `state/set-recipe-scopes` — and it is here because a row of live chips that
  silently drop clicks is the worse of the two failures to have available."
  [{:keys [selected on-toggle disabled?]}]
  ;; The deref happens out here, before the `for`. A deref inside the body of a
  ;; lazy seq is evaluated after reagent has stopped watching, so the chips would
  ;; not repaint when one was clicked — and reagent says so at the console rather
  ;; than silently.
  (let [scopes (:scopes @state/*app-state)]
    (when (seq scopes)
      [:div.scope-picker
       [:span.scope-picker-label {:title "Categories this Recipe is filed under"}
        "Scopes"]
       (for [{:keys [id title description]} scopes]
         ^{:key id}
         [:button.scope-chip
          {:type "button"
           :class (when (contains? selected id) "on")
           :title description
           :disabled (boolean disabled?)
           :on-click #(on-toggle (if (contains? selected id)
                                   (disj selected id)
                                   (conj selected id)))}
          title])])))
