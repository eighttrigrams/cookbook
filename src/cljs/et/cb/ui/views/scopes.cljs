(ns et.cb.ui.views.scopes
  "The Scopes page: the owner's categories, where he makes and edits them.

  **A private page, in the sense the app already has one.** Cookbook's shell has
  no router, and the existing pattern for an owner-only surface is the Settings
  page: a button in the top bar names a page, and `:page` in the atom says which
  one is on. So this follows it rather than introducing a route — see
  `et.cb.ui.core/page-body`, which renders exactly one of the three, and
  `state/go-to-page`, where being *one value* is what makes 'this page and the
  shelf are both up' unreachable rather than merely avoided.

  A Scope is a title, a description and its tags and nothing else, so this page is a
  compose row and a list. The description is not decoration: it is what the badge's
  tooltip says on a card, and it is what an agent reads out of `/api/describe` to
  decide which Scope a Recipe belongs under. An undescribed Scope still works and
  simply tells a reader less.

  **The tags are the one field here that changes the shelf.** They are extra search
  terms the Scope lends to every Recipe filed under it — *additional search terms
  for scopes* — so a Scope titled `utwig` tagged `backend tag2 tag3` makes each of
  those words find its Recipes on the overview page, as if they were words of each
  Recipe's title. That is why they are edited here rather than on a Recipe: one field
  on one row relabels a whole category, and a word typed here never has to be typed
  onto a Recipe again. They are the owner's alone in a stronger sense than a Recipe's
  tags, which a signed-out reader's search still matches: a Scope's words widen no
  search but his.

  Each row says how many Recipes are filed under it, from the endpoint's own
  `recipe_count` rather than counted from the shelf — the shelf may be narrowed by
  a search, and a count that quietly meant 'of the ones currently listed' would be
  wrong exactly when it mattered.

  **Deleting asks first, and the question is about the filing rather than about the
  Recipes.** The Recipes survive a delete untouched — each keeps every word of its
  text and loses a badge — so this is not the destructive act the Recipe delete is.
  What does not survive is the filing itself: there is no undo, and refiling ten
  Recipes by hand afterwards is the cost of a misplaced click. That is what the
  dialog says, with the count in it, and that is why it exists at all."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.cb.filters :as filters]
            [et.cb.ui.page-lock :as page-lock]
            [et.cb.ui.state :as state]))

(def ^:private title-placeholder "Title")
(def ^:private description-placeholder "What belongs in it")

(def ^:private tags-placeholder
  "The Recipe editor's `tags-placeholder` said from the other end: there, extra
  words to find *this* by; here, words to find everything filed under it by. One
  field, one sentence about what typing in it does — this and the row's tooltip are
  the only explanation most of these words ever get.

  A word shorter than its twin (`extra` is dropped) because this field shares its
  row with the description rather than having a line to itself, and the placeholder
  has to *fit*: the fuller wording measured one pixel wider than the input at an
  ordinary window width and was cut mid-word — which is the one way a placeholder
  can be worse than a shorter one."
  "Tags — words to find its Recipes by")

(defn same-name?
  "Whether two titles are **the same name** — trimmed and case-folded, which is
  exactly what `db.scope/title-taken?` decides on the server.

  Public so the page and its test read the one predicate. The client cannot be the
  authority here (two tabs, an agent, a stale list), so this is not a second gate:
  the endpoint still answers 409 and the error banner still works. What this buys is
  that the owner is told *before* typing a description he is about to lose."
  [a b]
  (= (str/lower-case (str/trim (str a)))
     (str/lower-case (str/trim (str b)))))

(defn clashing-scope
  "The Scope `title` would collide with, or nil. `except-id` is the row being
  renamed, whose own name is not a clash with itself — the `except-id` of the
  server's own check, in the same shape."
  ([scopes title] (clashing-scope scopes title nil))
  ([scopes title except-id]
   (when-not (str/blank? (str title))
     (first (filter #(and (not= except-id (:id %)) (same-name? title (:title %)))
                    scopes)))))

(def matching-scopes
  "The Scopes a half-typed title should leave on screen — `filters/matching-scopes`,
  which is where it went once the Scope picker needed the same answer for the
  opposite reason (find one chip among forty, rather than see what a new name might
  collide with). Aliased here because this page's own code reads as one sentence
  about composing a Scope, and because the name is what the test calls."
  filters/matching-scopes)

(defn- compose-row
  "Add a Scope. Enter submits from any field, like the Recipe compose form.

  **The title lives one level up**, in `scopes-block`, because the list below is
  filtered by it as it is typed. It is a ratom passed in rather than a value and a
  callback, and rather than a key in `state/*app-state`: a half-typed name is not
  application state — nothing else reads it, `logout` need not clear it, and it dies
  with the page the way a half-written Recipe does."
  [title]
  (let [description (r/atom "")
        tags (r/atom "")]
    (fn [title]
      (let [clash (clashing-scope (:scopes @state/*app-state) @title)
            submit (fn []
                     (when (and (not (str/blank? @title)) (nil? clash))
                       (state/add-scope {:title @title :description @description
                                         :tags @tags}
                                        (fn []
                                          (reset! title "")
                                          (reset! description "")
                                          (reset! tags "")))))]
        [:div.scopes-compose
         [:input.scope-title-input
          {:type "text" :placeholder title-placeholder
           :value @title
           :on-change #(reset! title (-> % .-target .-value))
           :on-key-down #(when (= (.-key %) "Enter") (submit))}]
         [:input.scope-description-input
          {:type "text" :placeholder description-placeholder
           :value @description
           :on-change #(reset! description (-> % .-target .-value))
           :on-key-down #(when (= (.-key %) "Enter") (submit))}]
         [:input.scope-tags-input
          {:type "text" :placeholder tags-placeholder
           :value @tags
           :on-change #(reset! tags (-> % .-target .-value))
           :on-key-down #(when (= (.-key %) "Enter") (submit))}]
         [:button.scope-add
          {:on-click submit :disabled (or (str/blank? @title) (some? clash))
           :title (when clash (str "You already have a Scope called " (:title clash)))}
          "Add"]
         ;; **The whole row, so the note lands under the fields rather than beside
         ;; them.** `flex-basis: 100%` on a wrapping flex row is the one way to say
         ;; 'break here' without a nested container, and a note squeezed into the
         ;; row's own gap is the shape that pushes Add off the end on a narrow window.
         (when clash
           [:div.scope-clash
            "You already have a Scope called "
            [:strong (:title clash)]
            ". Names are the same name whatever the case, so pick another."])]))))

(defn- edit-row
  "One Scope, in place. Editing here rather than in a modal because there are three
  short fields and the list is where the neighbouring titles are — which is what
  you need to see while renaming one of them, and, now that a Scope lends its words
  to a search, while deciding which words are still free to use.

  **The save sends all three fields**, which matters for the tags for the reason it
  already mattered for the title: the endpoint keeps whatever a call leaves out, so
  the only way a field can be *cleared* here is by being sent empty.

  **A rename is the other way to end up with two Scopes of one name**, so it gets
  the same warning the compose row does, off the same predicate and with this row
  excepted — re-casing a Scope's own name (`ops` → `Ops`) is a rename onto itself
  and must stay possible. The list is *not* filtered from this field, unlike the
  compose one: the row you are editing would be the first thing to vanish out from
  under the cursor."
  [scope]
  (let [title (r/atom (:title scope))
        description (r/atom (:description scope))
        tags (r/atom (:tags scope))]
    (fn [scope]
      (let [clash (clashing-scope (:scopes @state/*app-state) @title (:id scope))
            save (fn []
                   (when (and (not (str/blank? @title)) (nil? clash))
                     (state/save-scope (:id scope)
                                       {:title @title :description @description
                                        :tags @tags}
                                       state/stop-editing-scope)))]
        [:div.scope-row.editing
         [:input.scope-title-input
          {:type "text" :placeholder title-placeholder
           :value @title
           :on-change #(reset! title (-> % .-target .-value))
           :on-key-down #(when (= (.-key %) "Enter") (save))}]
         [:input.scope-description-input
          {:type "text" :placeholder description-placeholder
           :value @description
           :on-change #(reset! description (-> % .-target .-value))
           :on-key-down #(when (= (.-key %) "Enter") (save))}]
         [:input.scope-tags-input
          {:type "text" :placeholder tags-placeholder
           :value @tags
           :on-change #(reset! tags (-> % .-target .-value))
           :on-key-down #(when (= (.-key %) "Enter") (save))}]
         [:span.scope-row-actions
          [:button.scope-save
           {:on-click save :disabled (or (str/blank? @title) (some? clash))
            :title (when clash (str "You already have a Scope called " (:title clash)))}
           "Save"]
          [:button.secondary {:on-click state/stop-editing-scope} "Cancel"]]
         (when clash
           [:div.scope-clash
            "That is the name of your Scope "
            [:strong (:title clash)]
            "."])]))))

(defn- filed-count
  "`recipe_count` in words. Spelled out rather than shown as a bare number so the
  zero case reads as a sentence too — a `0` beside a title looks like something
  failed to load."
  [n]
  (case n
    0 "nothing filed here yet"
    1 "1 Recipe"
    (str n " Recipes")))

(defn- scope-row [{:keys [id title description tags recipe_count]}]
  [:div.scope-row
   [:span.scope-row-title title]
   [:span.scope-row-description description]
   ;; **Rendered whether or not there are any**, as an empty span. The row is a
   ;; grid, so an omitted cell would not leave a gap — it would shift the count and
   ;; the buttons of that one row into the wrong columns, and a list where the
   ;; untagged rows are the misaligned ones is worse than a narrow empty column.
   [:span.scope-row-tags {:title "Extra words a search finds this Scope's Recipes by"}
    tags]
   [:span.scope-row-count {:title "How many of your Recipes are filed under it"}
    (filed-count recipe_count)]
   [:span.scope-row-actions
    [:button.secondary {:on-click #(state/start-editing-scope id)} "Edit"]
    [:button.secondary.danger {:on-click #(state/start-deleting-scope id)} "Delete"]]])

(defn- delete-modal
  "Asks before deleting a Scope, and the question is the count.

  The confirm button goes dead on the first click, like the Recipe modals: only the
  response callback closes this dialog — so a failed delete can put its error
  banner somewhere reachable — which would otherwise leave the button live for the
  whole round trip and let two clicks send two DELETEs, the second one 404ing over
  a delete that in fact went through.

  Rendered outside the panel, for the reason the Recipe modals are rendered outside
  the cards: the panel's `backdrop-filter` would become the containing block for
  this fixed overlay and pin it inside the panel."
  [_scope]
  (let [sending? (r/atom false)]
    (fn [{:keys [id title recipe_count]}]
      [:div.modal-backdrop {:on-click state/stop-deleting-scope}
       [page-lock/while-mounted]
       [:div.modal {:on-click #(.stopPropagation %)}
        [:h2 "Delete this Scope?"]
        [:div.modal-subtitle title]
        [:p.modal-note
         (if (zero? recipe_count)
           "No Recipe is filed under it, so nothing else changes. There is no undo."
           (str (filed-count recipe_count)
                (if (= 1 recipe_count) " is" " are")
                " filed under it. They keep every word of their text and lose a
                 badge — but the filing itself does not come back, and there is no
                 undo."))]
        [:div.modal-actions
         [:button.scope-delete-confirm.danger
          {:disabled @sending?
           :on-click #(do (reset! sending? true)
                          (state/delete-scope id state/stop-deleting-scope))}
          (if @sending? "Deleting…" "Delete")]
         [:button.secondary {:on-click state/stop-deleting-scope} "Cancel"]]]])))

(defn- scopes-block
  "The panel: the note, the compose row, and the list the compose row filters.

  **A form-2 component now, and it has to be**, because the half-typed title is a
  `r/atom` created here and read by both children. Created in the `let` that runs
  once rather than in the render body, where it would be a fresh atom on every
  keystroke's re-render — which is the bug that shape produces: a field that
  discards what you type."
  []
  (let [compose-title (r/atom "")]
    (fn []
      (let [{:keys [scopes editing-scope]} @state/*app-state
              typed @compose-title
              shown (matching-scopes scopes typed)]
        [:div.scopes
         [:h2 "Scopes"]
         [:p.settings-note
          "Categories to file Recipes under — a title, a line saying what belongs in
           it, and tags. A Recipe can be in any number of them, chosen when you write it
           or from its Edit form, and they show as badges on the cards. "
          [:strong "The tags are extra search terms"]
          ": a Scope's title and its tags find everything filed under it on the
           overview page, as if those words were in each Recipe's own title — so
           tagging one Scope "
          [:code "backend"]
          " makes every Recipe in it answer to that word, in one edit. "
          [:strong "Yours alone"]
          ": a signed-out reader of a published Recipe is not sent them at all, neither
           is anyone else, and a Scope's words widen nobody's search but yours. An agent
           with credentials is on your side of both — it reads this list from "
          [:code "/api/describe"]
          " to know where to file what it writes, and what to search for."]
         [compose-row compose-title]
         ;; **Three empty states, and they are three different facts.** No Scopes at
         ;; all; a title that matches none of them, which is the *good* answer while
         ;; composing and says so; and the list itself. Collapsing the middle one into
         ;; "No Scopes yet." would tell the owner of forty Scopes that he has none.
         (cond
           (empty? scopes)
           [:div.scopes-empty "No Scopes yet."]

           (empty? shown)
           [:div.scopes-empty
            "No Scope matches " [:strong typed] " — that name is free."]

           :else
           [:div.scopes-list
            ;; The key goes on each branch's vector and not on the `if`: metadata on an
            ;; `if` form is metadata on the form, which the reader drops before reagent
            ;; ever sees it, and the result is a unique-key warning for every row.
            (for [scope shown]
              (if (= editing-scope (:id scope))
                ^{:key (:id scope)} [edit-row scope]
              ^{:key (:id scope)} [scope-row scope]))])]))))

(defn scopes-page
  "The panel and its confirmation, as **siblings**.

  The dialog must not render inside `.scopes`: that block has a `backdrop-filter`,
  which would make it the containing block for the modal's fixed positioning and pin
  a supposedly viewport-centred overlay inside the panel. It is the same trap the
  Recipe modals are rendered outside the cards for — see the comment at the bottom
  of `recipes-tab`."
  []
  (let [{:keys [scopes deleting-scope]} @state/*app-state]
    [:<>
     [scopes-block]
     (when-let [scope (first (filter #(= deleting-scope (:id %)) scopes))]
       [delete-modal scope])]))
