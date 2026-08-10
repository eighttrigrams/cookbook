(ns et.cb.ui.views.recipes
  "The shelf: one Recipe per card, the most used first.

  **The order is the server's and nothing here re-sorts it.** `list-recipes`
  ranks by `0.7 × view_count + 0.3 × version` and falls back to most-recently-
  modified for ties; `state/fetch-recipes` stores the rows as they arrived and
  this file renders them in that order. A sort added here would be this client's
  private opinion about a shelf an agent sees ranked differently.

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

  And beside those, `12 reads`: how often the Recipe was actually consumed —
  expanded here or fetched whole by an agent, never a listing. Expanding a card
  is what makes one, so the number a card wears is the count as the listing was
  fetched and it goes up on the next listing rather than under your cursor. The
  same number ranks the shelf, together with the version count.

  A card also says when **a proposal is waiting** on its Recipe, which is what the
  `pending` flag on a lean listing row is for: a collapsed card is exactly the
  place that cannot go and fetch one. It is a badge and not a control — the
  deciding happens in the Inbox, against the agent's text — and it is owner-only
  like the others, cosmetically here and for real on the server.

  The shelf can be narrowed three ways at once — by a search over titles and tags,
  to the Recipes a human has edited here rather than an agent, and away from the
  Recipes filed under given Scopes. All three are the listing endpoint's own
  `:where` clauses, carried as query params on the request; **nothing on this side
  filters rows it was given**, and that sentence is why the Scope exclusion is a
  parameter rather than a `remove` over `:recipes`. It could not be one: the shelf
  is ranked and sliced by the server, so rows dropped here would leave a short page
  this client has no way to top up.

  **Tags** are the owner's extra search words. They are searched for everybody,
  including a signed-out visitor, and displayed to nobody but the owner — and the
  displaying half is the *server's* doing, not this file's: a visitor's rows arrive
  with no `tags` key at all. The `logged-in?` gate on `recipe-badges/tags` is
  cosmetic, and the docstring there says why that distinction has to be kept.

  **The six header facts are `et.cb.ui.recipe-badges`' and not this file's**, since
  a Recipe's own page (`views/recipe`) shows the same Recipe and says the same
  things about it — the argument `et.cb.ui.provenance` and `et.cb.ui.scope-badges`
  each already make about two surfaces naming one fact. What stays here is what is
  the *shelf's*: the header placement, the expand-on-click it sits inside, and the
  shift+click gesture over the Scope badges.

  **Scopes** are the other half of the filing, and they sit in the collapsed card's
  header beside the version and published badges — which is where they belong,
  because that header is the retrieval index and a Scope is how a Recipe is found
  again. Same arrangement as the tags: the server sends a visitor no `scopes` key
  at all and the `logged-in?` gate here is cosmetic. Picking them happens in the
  compose form and the Edit modal, from the owner's own list; making them happens
  on the Scopes page (`et.cb.ui.views.scopes`), not here.

  **Shift+click a Scope badge and the Recipes filed under it leave the shelf.**
  Tracker's gesture, and being the same finger in both apps is the reason for it
  rather than a preference — `filters.cljc/badge-gesture` reads `shift? →
  :exclude` over there, and option only appears in its `shift+option → :bypass`
  pair. The filter is **negative-only**: a plain click still falls through to the
  header and expands the card, because he asked to hide and not to select, and
  tracker's plain click *is* a positive filter — so inventing one here would be
  the wrong half of the parallel. `excluded-scopes-strip` is where an active
  exclusion is seen and undone, and it is not decoration; the reason is written
  down there.

  **A card's footer carries one button, and it is *Page*.** Publishing, editing,
  deleting and reading a version history were four buttons beside it and are on the
  Recipe's own page now (`views.recipe/actions`) — *all the buttons go to that page
  then*, which leaves this file the retrieval index it says it is above and puts
  every gesture that changes a Recipe on the one surface that is about one Recipe.
  The footer stays owner-only all the same, which `card` argues where the button is.

  What the card still says about the publish latch is the badge, and the badge is
  enough to say it: publishing is one way — the API has no unpublish — so a published
  Recipe wears it and has no Publish button anywhere, here or on its page.

  All three fields are markdown, but not the same markdown: the title and the
  useful-when line are rendered inline, so they cannot grow a heading or a list
  and break the card's layout, while the body gets the full parser and the code
  highlighting. See `et.cb.ui.markdown`."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.cb.ui.markdown :as markdown]
            [et.cb.ui.recipe-badges :as recipe-badges]
            [et.cb.ui.scope-badges :as scope-badges]
            [et.cb.ui.state :as state]
            ;; The picker and the placeholder, borrowed from the Edit form rather
            ;; than kept here: the Edit modal is mounted at the app root now and
            ;; must not reach into the shelf to draw itself — see
            ;; `views.recipe-modals`. This direction is the safe one.
            [et.cb.ui.views.recipe-modals :as recipe-modals]))

(defn- compose-form []
  (let [title (r/atom "")
        useful-when (r/atom "")
        tags (r/atom "")
        description (r/atom "")
        scope-ids (r/atom #{})]
    (fn []
      (let [submit (fn []
                     (when-not (str/blank? @title)
                       (state/add-recipe {:title @title
                                          :useful_when @useful-when
                                          :tags @tags
                                          :description @description
                                          :scope_ids @scope-ids}
                                         (fn []
                                           (reset! title "")
                                           (reset! useful-when "")
                                           (reset! tags "")
                                           (reset! description "")
                                           (reset! scope-ids #{})))))]
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
         [:input.compose-tags
          {:type "text" :placeholder recipe-modals/tags-placeholder
           :value @tags
           :on-change #(reset! tags (-> % .-target .-value))
           :on-key-down #(when (= (.-key %) "Enter") (submit))}]
         [:textarea.compose-description
          {:placeholder "The recipe itself"
           :rows 4
           :value @description
           :on-change #(reset! description (-> % .-target .-value))}]
         [recipe-modals/scope-picker scope-ids]
         [:button {:on-click submit :disabled (str/blank? @title)} "Add"]]))))

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

(def ^:private scope-badge-hint
  "The gesture, spelled out on every badge, because a modifier key is the one
  affordance a reader cannot see. Tracker gets away without saying it — its badges
  do something on a plain click, so a user has already learned they are controls —
  while here a plain click expands the card like the rest of the header, so
  nothing but this sentence suggests there is anything to hold shift for."
  "shift+click to hide the Recipes filed under it")

(defn- exclude-on-shift
  "Cookbook's `badge-consumes-click?`: **consume the click when the modifier is
  held, let a plain one through.**

  The badges sit inside the card header, which is the expand/collapse target, so
  a handled click has to stop propagating or shift+clicking a badge would hide
  rows and expand the card in the same gesture. Tracker has the same collision and
  the same answer, in a function whose complexity is all about states cookbook
  cannot be in: its three-way matrix and its gate exist because it has positive
  filters, six category types and a bypass gesture keeping out of each other's
  way. Here every branch but `:exclude` is unreachable and the gate is always
  open, so one predicate on `shiftKey` is the honest translation — a copied matrix
  with two dead branches would read as a promise of gestures that do not exist.

  It also drops the text selection the browser makes on the way in, and that is
  not tidiness. Shift+click is *also* the gesture for extending a selection, and
  the browser runs that on mousedown, long before this handler sees a click — so
  without this every exclusion left a swathe of the card highlighted blue behind
  the rows that had just gone. `stopPropagation` cannot help with it and neither
  can `preventDefault` on a click that has already happened; the selection has to
  be collapsed once it exists. Only the handled gesture does it, so selecting text
  anywhere else is untouched."
  [id e]
  (when (.-shiftKey e)
    (.stopPropagation e)
    (when-let [selection (js/window.getSelection)]
      (.removeAllRanges selection))
    (state/toggle-excluded-scope id)))

(defn- card-scopes
  "The Scopes this Recipe is filed under, as badges in the collapsed card's header.

  They belong here rather than under the useful-when line: the header **is** the
  retrieval index — title, useful-when, which version, where its versions came from
  — and 'which shelf is this on' is that same question.

  **The pill itself is `ui.scope-badges`' and not this file's**, because the Inbox's
  rows wear the same one: two badge styles for one concept is how they drift. What
  stays here is what is the *shelf's* — the header placement, and the shift+click
  gesture, which is a filter over this listing and would be a filter over a page he
  was not looking at anywhere else.

  **This gate is cosmetic and must not be read as the privacy boundary**, exactly
  as with `recipe-badges/tags`. The boundary is the server: for a visitor the join is not run
  at all, so a signed-out client holds no `scopes` key to draw and `logged-in?` here
  would be redundant if the client could be trusted — which is precisely why it is
  not the mechanism. Do not 'simplify' `db.recipe/with-scopes` on the grounds that
  this hides them; deleting this line would show a signed-out reader nothing extra,
  and deleting the server half would publish the owner's filing."
  [scopes]
  [scope-badges/badges scopes {:class "card-scopes"
                               :hint scope-badge-hint
                               :on-click exclude-on-shift}])

(defn- card [{:keys [id title useful_when tags scopes version published published_at modified_at
                     view_count pending]
              :as recipe}
             {:keys [logged-in? open details]}]
  (let [expanded? (contains? open id)
        ;; JSON gives 0/1 and 0 is truthy in cljs, so these have to be
        ;; comparisons rather than tests for presence. `pending` is absent
        ;; altogether from a visitor's row, which `= 1` reads as false — the same
        ;; answer, arrived at without the client having to know.
        published? (= 1 published)
        pending? (= 1 pending)]
    [:div.card {:class (when published? "published")}
     [:div.card-header {:on-click #(state/toggle-open id)}
      [:span.card-toggle (if expanded? "▾" "▸")]
      [:h2.card-title [markdown/render-inline title]]
      (when (and logged-in? published?)
        [recipe-badges/published-badge published_at])
      ;; Next to the latch rather than next to the counts: both are states the
      ;; Recipe is *in* — one settled and one waiting — where the version, the
      ;; provenance split and the reads are all numbers about its past. And a
      ;; Recipe can wear both, which is not a contradiction: a machine may propose
      ;; against a published Recipe, and what a visitor sees stays the approved
      ;; version until he says otherwise.
      (when (and logged-in? pending?)
        [recipe-badges/pending-badge])
      (when (and logged-in? (seq scopes))
        [card-scopes scopes])
      [recipe-badges/version-badge version]
      [recipe-badges/source-split recipe]
      ;; Not gated on `logged-in?`, for the same reason the version badge is not:
      ;; it is a fact about the Recipe rather than about the owner's filing, and
      ;; the server puts it in the visitor's projection deliberately. It also
      ;; explains the order of the shelf a visitor is looking at.
      [recipe-badges/views-badge view_count]
      [:span.card-date (recipe-badges/day modified_at)]]
     (when (seq useful_when)
       [:div.card-useful-when [markdown/render-inline useful_when]])
     (when (and logged-in? (seq tags))
       [recipe-badges/tags tags {:class "card-tags"}])
     (when expanded?
       [card-body (get details id)])
     (when logged-in?
       ;; **One button, and it is the way off the shelf.** Publish, Edit, Versions
       ;; and Delete were here beside it and are on the Recipe's own page now — he
       ;; asked for a card that carries nothing else and said where they were to go:
       ;; *all the buttons go to that page then*. What is left is a card that is only
       ;; the retrieval index this namespace's docstring says it is, and one route to
       ;; the surface that can change the Recipe. `views.recipe/actions` is where the
       ;; four went; nothing about them lives here any more.
       ;;
       ;; **"Page" and not "Open"**, because expanding the card is what "open"
       ;; already means here — a reader with both words in front of them would have
       ;; to guess which one leaves the shelf. What this does is put the Recipe at an
       ;; address, so it is named after the thing it takes you to.
       ;;
       ;; **Still owner-only, and that is a decision rather than a leftover.** With
       ;; the other four gone the gate around a single navigation looks like it could
       ;; come off, and the consequence of keeping it is worth stating rather than
       ;; discovering: a signed-out visitor has no footer, so from the shelf there is
       ;; no button to a Recipe's page. They can still *follow* a link to a published
       ;; one, which is what the address is for. Ungating it would be a visibility
       ;; change he has not asked for, so it stays as it was.
       [:div.card-footer
        [:span.card-actions
         [:button.secondary
          {:on-click #(state/open-recipe-page id)
           :title "Open this Recipe on a page of its own, at an address you can keep"}
          "Page"]]])]))

(defn- excluded-scopes-strip
  "The Scopes the shelf is currently hiding: one chip each with an × that clears
  it, and a Clear all once there is more than one.

  **This is not decoration and it is not optional.** An excluded Scope's badges
  leave the shelf along with the Recipes carrying them, so there is no badge left
  to shift+click a second time — without somewhere else to see and undo it, the
  first exclusion is a trap: rows vanish and nothing on screen says why or offers a
  way back. Tracker answers the same problem by swapping its sidebar over to the
  negative filters while any is set; cookbook has no sidebar, so this is that.

  It sits with the search box and the human-edited checkbox because it is the third
  narrowing and those are the other two, and this is then the one line that says
  everything currently taking rows away.

  The titles come off the owner's own Scope list rather than off the cards, which
  is the only place left that has them — the cards carrying an excluded Scope are
  exactly the ones that are gone. An id the list does not know renders as `Scope 7`
  rather than being skipped: `state/delete-scope` drops a deleted id from the set,
  so it should not arise, but a chip that declined to draw itself would leave rows
  hidden with no way to bring them back, and that is the one outcome worth
  defending against twice.

  Nothing here is gated on `logged-in?` and nothing needs to be: the set can only
  be filled by clicking a badge, a visitor is sent none, and `logout` empties it —
  so signed out there is nothing to draw.

  The deref happens before the `for`, for the reason `scope-picker` gives: a deref
  inside the body of a lazy seq is evaluated after reagent has stopped watching.
  One deref here and not that function's two — both keys come out of the same
  destructuring — but the same care, since the `for` is the same lazy seq."
  []
  (let [{:keys [excluded-scopes scopes]} @state/*app-state
        title-of (into {} (map (juxt :id :title)) scopes)]
    (when (seq excluded-scopes)
      [:div.excluded-scopes
       [:span.excluded-scopes-label
        ;; Assembled with `str` rather than written across two source lines: a
        ;; string literal that wraps keeps the newline *and* the indent, and a
        ;; tooltip is the one place that shows up verbatim.
        {:title (str "These Scopes' Recipes are hidden — the server leaves them "
                     "out of the listing, so the shelf below is short by however "
                     "many carry one")}
        "Hiding"]
       ;; By title, like every other list of Scopes in this app — the badges on a
       ;; card and the Scopes page both read that way. The id breaks a tie between
       ;; two Scopes named the same, which only the fallback label can produce.
       (for [id (sort-by (fn [id] [(or (title-of id) "") id]) excluded-scopes)]
         ^{:key id}
         [:span.excluded-chip
          (or (title-of id) (str "Scope " id))
          [:button.excluded-chip-clear
           {:type "button"
            :title "Show the Recipes filed under this Scope again"
            :on-click #(state/clear-excluded-scope id)}
           "×"]])
       (when (> (count excluded-scopes) 1)
         [:button.clear-exclusions
          {:type "button"
           :title "Stop hiding all of them"
           :on-click state/clear-excluded-scopes}
          "Clear all"])])))

(defn- empty-message
  "Why the shelf is empty, and never a lie about it. 'No recipes yet.' is a claim
  about the shelf, so it may only be said when nothing is narrowing the view —
  with a filter on, what is empty is the result and not the shelf.

  The human filter gets a sentence of its own rather than sharing 'Nothing
  matches.', because its empty case is the expected one at first: the provenance
  bit is only recorded going forward, so every Recipe reads as not-human-edited
  until it is next saved from here. A reader who is told that will not read it as
  a broken filter.

  **The Scope exclusion outranks both of the others, because its sentence is the
  only one that stays true in company.** 'Nothing left once those Scopes are
  hidden' is about the *result* — with those Scopes hidden, nothing is left — and
  that holds however many other narrowings are taking rows away alongside it. The
  other two are claims a hidden Recipe can falsify: 'Nothing here has been edited
  in this UI yet' when something has been and is filed under a hidden Scope, and
  'Nothing matches.' when a Recipe does match the search and is absent only
  because its Scope is hidden.

  **That last one is a correction, not a precaution.** The exclusion used to sit
  below the search, on the argument that a sentence about the result could not
  make the search's untrue — and then searching `sourdough` with Baking hidden
  said 'Nothing matches.' while *Sourdough starter* matched it. The rule that
  survives the case is the one above: rank by which sentence can be said in
  company, not by which narrowing feels the more specific."
  [search human-only? excluded-scopes]
  (cond
    (seq excluded-scopes) "Nothing left once those Scopes are hidden."
    (seq search)          "Nothing matches."
    human-only?           "Nothing here has been edited in this UI yet."
    :else                 "No recipes yet."))

(defn recipes-tab
  "The shelf, and nothing over it.

  **The overlays used to be mounted from in here and are now at the app root** —
  the Edit form, the two confirmations and the version viewer, see
  `views.recipe-modals`. The reason they were outside the cards is the reason they
  are now outside the page: a card's `backdrop-filter` becomes the containing block
  for a `position: fixed` overlay, and `.recipe-page` has that same filter, so a
  modal mounted in a page is pinned to that page. And this page is no longer the
  only one that opens them, which is what made a per-page mount wrong rather than
  merely careful."
  []
  (let [{:keys [recipes search human-only? excluded-scopes logged-in? open details]}
        @state/*app-state]
    [:div.shelf
     (when logged-in? [compose-form])
     [:div.shelf-controls
      ;; The endpoint matches words from their start, in the title and in the
      ;; tags, so the placeholder names both and says beginnings of words rather
      ;; than letting a typist expect a substring to hit. It says the same thing
      ;; signed out, and truthfully: tags are searched for everyone — only the
      ;; values are the owner's.
      [:input.search
       {:type "text" :placeholder "Search titles and tags — start of any word"
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
     ;; Under the other two narrowings rather than beside them: it is a list that
     ;; grows with each exclusion, where those are one control each, and it is only
     ;; there at all while something is being hidden.
     [excluded-scopes-strip]
     (if (empty? recipes)
       [:div.empty (empty-message search human-only? excluded-scopes)]
       (for [recipe recipes]
         ^{:key (:id recipe)}
         [card recipe {:logged-in? logged-in? :open open :details details}]))]))
