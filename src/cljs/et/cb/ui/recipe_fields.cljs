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
  and `:on-toggle` is handed **the id that was clicked**. The caller says what a
  toggle means for it — a `swap!` on the compose form, a PUT on the read page — and
  this component owns neither answer. Keeping its own copy would be the same fact
  in two places, which is the argument its first version made about reading a
  child's state back out.

  **The id and not the set the row would become, and that is the whole of what this
  component gets right.** Handing over the next set is the obvious shape and it was
  the first one written here; it loses the second of two clicks that land in the
  same animation frame. `:selected` is a value out of a *render*, so both handlers
  close over the same set, and the second computes `that + Scratch` where the owner
  meant `that + Ops + Scratch`: two saves both succeed and one chip he pressed is
  simply not filed. Measured, not reasoned about — clicking two chips as fast as a
  hand can sent both PUTs and left the first Scope off.

  So the next set has to be computed from whatever is current **at click time**, and
  only the holder of the set can do that: `swap!` reads the ratom, and
  `state/toggle-recipe-scope` reads the atom, including the set a save already in
  flight is going to produce. The cost is that `(if (contains? s id) (disj s id)
  (conj s id))` is written at both call sites, which is a line state.cljs already
  carries twice for the same reason — a cheap price for a correctness property that
  cannot be got back any other way.

  `:class` goes on the row, the way `scope-badges/badges` and `recipe-badges/tags`
  take one: what a surface gets to say about a shared component is where it sits,
  never what it looks like."
  [{:keys [selected on-toggle class]}]
  ;; The deref happens out here, before the `for`. A deref inside the body of a
  ;; lazy seq is evaluated after reagent has stopped watching, so the chips would
  ;; not repaint when one was clicked — and reagent says so at the console rather
  ;; than silently.
  (let [scopes (:scopes @state/*app-state)]
    (when (seq scopes)
      [:div.scope-picker {:class class}
       [:span.scope-picker-label {:title "Categories this Recipe is filed under"}
        "Scopes"]
       (for [{:keys [id title description]} scopes]
         ^{:key id}
         [:button.scope-chip
          {:type "button"
           :class (when (contains? selected id) "on")
           :title description
           :on-click #(on-toggle id)}
          title])])))
