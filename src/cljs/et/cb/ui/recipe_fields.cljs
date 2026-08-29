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
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [et.cb.filters :as filters]
            [et.cb.ui.cm-textarea :as cm-textarea]
            [et.cb.ui.state :as state]))

(def tags-placeholder
  "The owner's extra search words, said the same way by every form that writes
  them. One string and not one per form, for the reason the badges are one
  component: a placeholder is what tells him what the field is for, and two
  wordings of it is how two forms come to describe the same field differently."
  "Tags — extra words to find this by")

(defn edit-fields
  "The four content fields of a Recipe, as a form — **for the two surfaces that write
  them**: a Recipe's own editor at `?edit=true`, and the page a new Recipe is made on.

  **Here rather than in either view, and that is the whole reason this component
  exists.** *at the top of the page the will be an \"Add\" button which takes you to a
  page which looks like when we go from the recipe Page page to edit.* Looking like it
  is not the same as being a copy of it: `views/recipes` records what a second copy
  costs, about its own two card components — *a second component was two copies of a
  row that had to be kept in step by hand, and was not: the Scope badges had to be
  added to both, one at a time.* Four inputs, two placeholders and a class list is
  exactly that shape, so there is one of them and the pages differ in what they put
  around it.

  **The state is the caller's and this holds none.** `:fields` is the resolved map —
  `state/recipe-edit-fields`, for both pages, since a draft over *no* stored row
  resolves to the draft alone — and `:on-change` takes the key and the value. Same
  division as `scope-picker`: the component draws, the caller decides what a keystroke
  means. There is deliberately no second resolver for the new page; a blank form is the
  editor's own machinery with nothing behind it.

  **The body is a CodeMirror and not a `<textarea>`**, which is what puts the IJKL
  scheme under the one field a Recipe is mostly made of — see `ui.cm-textarea`, and
  `ui.codemirror` for why the scheme is a vendored library rather than a table in
  this repo. It is still a `:value` and an `:on-change`, so nothing here or in either
  caller knows the difference; what changed is that `on-change` is handed the text
  rather than an event to dig it out of.

  **`:body` replaces that editor**, for the one thing the two surfaces genuinely do
  differently: the editor can swap the body for its tinted provenance source, and the
  new page cannot, because a Recipe that does not exist has no history to tint. Passed
  in rather than flagged, so this component holds no opinion about provenance at all —
  and the legend that goes above the source view comes through the same slot, since
  both belong to *what is standing in for the body*.

  **`:on-enter`, and only the three short fields get it.** The compose form this
  replaces on the shelf submitted on Enter and the Recipe editor does not, so one of
  the two habits had to win on the new page; Enter is how a Recipe gets added quickly
  and losing it would be a real change to that. It is the caller's to supply — the
  editor passes none, because Enter in the middle of correcting a Recipe should not
  save and navigate — and it is never on the body, where Enter is a newline in the
  text he is writing."
  [{:keys [fields on-change on-enter body]}]
  (let [{:keys [title useful_when tags description]} fields
        enter (when on-enter
                #(when (= (.-key %) "Enter") (on-enter)))]
    [:div.recipe-page-edit
     [:input.recipe-page-edit-title
      {:type "text" :placeholder "Title"
       :value title
       :on-key-down enter
       :on-change #(on-change :title (-> % .-target .-value))}]
     [:input
      {:type "text" :placeholder "Useful when…"
       :value useful_when
       :on-key-down enter
       :on-change #(on-change :useful_when (-> % .-target .-value))}]
     [:input.recipe-page-edit-tags
      {:type "text" :placeholder tags-placeholder
       :value tags
       :on-key-down enter
       :on-change #(on-change :tags (-> % .-target .-value))}]
     (or body
         [cm-textarea/cm-textarea
          {:class "recipe-page-edit-body"
           :placeholder "The recipe itself"
           :value description
           :on-change #(on-change :description %)}])]))

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
  never what it looks like.

  **`:label` and `:label-title` are the exception to that rule and are why it is
  worth stating.** The chip says `Scopes` and its tooltip says *Categories this
  Recipe is filed under* — true on the compose form and on a Recipe's page, and a
  small lie on the shelf, where the same row files nothing and narrows a listing
  instead. So the words are the caller's while everything else stays this
  component's: what a surface says about a shared control is where it sits **and
  what it is for here**, never what it looks like. Duplicating the component to
  change two strings would have been the drift this namespace exists to prevent.

  **`:disabled?` puts every chip out of action**, for a caller whose filter is
  refused while another is running — the shelf's, while an exclusion is up. A real
  `disabled` attribute and not a swallowed click: the cursor changes, the chip goes
  dim, and the keyboard skips it, so a control that cannot act *looks* like one.
  That is the opposite decision from `views.recipe/scope-filing`, which deliberately
  disables nothing while a save is in flight — there the click is going to land a
  moment later and swallowing it would lose it, here there is nothing for it to do
  at all. `:disabled-title` is what to say instead of the description, because a
  refused control that does not say why is the trap `excluded-scopes-strip` exists
  to prevent, one layer up.

  **`:filterable?` puts a find-box at the head of the row**, for the surfaces where
  the list has outgrown being scanned: *i need a way to have an search input, when i
  put something in, it matches scopes by title or tags as usual and only shows the
  filtered ones, whether they are active or not. and then i can activate or
  deactivate those matching ones. and an x button clears the filter.* Forty chips
  over four lines is a control you read rather than use, and the Recipe you are
  filing usually belongs to one you can already name.

  Three things it deliberately does not do, each of which is a way of getting this
  wrong:

  - **It does not care whether a chip is on.** *whether they are active or not* —
    the box answers *which Scopes are called this*, never *which are selected*, so
    the same word finds the chip you meant whether you are about to file under it or
    unfile from it. A filter that quietly dropped the active ones would hide exactly
    the chip a second thought is about.
  - **It does not touch the filing.** Narrowing what is on screen is not a change to
    anything, and clearing the box brings every chip back with the same ones lit. So
    it is honest to leave a filter in place, and no surface has to remember to clear
    it before saving.
  - **It holds its own text and nothing else.** The filter is a view of the list, not
    a fact about the Recipe, so it lives in a ratom here rather than in
    `state/*app-state` — nothing else reads it, and it dies with the surface.

  It is **opt-in and off on the shelf**, which is the one caller that must not have
  it: the shelf's chip row sits directly under a search box that already narrows
  Recipes by a Scope's words, and two inputs an inch apart doing two different
  things is worse than a long row. `:label` made the same call for the same reason —
  what a surface gets to say about a shared control is what it is for *here*."
  [_]
  (let [filter-text (r/atom "")]
    (fn [{:keys [selected on-toggle class label label-title disabled? disabled-title
                 filterable?]
          :or {label "Scopes"
               label-title "Categories this Recipe is filed under"}}]
      ;; The deref happens out here, before the `for`. A deref inside the body of a
      ;; lazy seq is evaluated after reagent has stopped watching, so the chips would
      ;; not repaint when one was clicked — and reagent says so at the console rather
      ;; than silently.
      (let [scopes (:scopes @state/*app-state)
            typed (if filterable? @filter-text "")
            shown (filters/matching-scopes scopes typed)]
        (when (seq scopes)
          [:div.scope-picker {:class class}
           [:span.scope-picker-label {:title label-title} label]
           (when filterable?
             [:span.scope-picker-find
              [:input.scope-picker-filter
               {:type "text"
                :placeholder "Find a Scope…"
                :value @filter-text
                :disabled (boolean disabled?)
                :title (when disabled? disabled-title)
                :on-change #(reset! filter-text (-> % .-target .-value))}]
              ;; Only while there is something to clear. A permanent `×` beside an
              ;; empty box is a control that cannot do anything — the same argument
              ;; that renders this whole picker as nothing when there are no Scopes.
              (when-not (str/blank? @filter-text)
                [:button.scope-picker-clear
                 {:type "button"
                  :title "Clear the filter"
                  :on-click #(reset! filter-text "")}
                 "×"])])
           (for [{:keys [id title description]} shown]
             ^{:key id}
             [:button.scope-chip
              {:type "button"
               :class (str (when (contains? selected id) "on")
                           (when disabled? " refused"))
               :disabled (boolean disabled?)
               :title (if disabled? disabled-title description)
               :on-click #(on-toggle id)}
              title])
           ;; Said rather than left as an empty row, which is indistinguishable from
           ;; a picker that has failed to load — the argument `body-unchanged-note`
           ;; makes on the diff surface.
           (when (and filterable? (seq (str typed)) (empty? shown))
             [:span.scope-picker-none "no Scope matches"])])))))
