(ns et.cb.ui.views.recipe
  "One Recipe, on a page of its own, at `/recipe/<id>`.

  **The first page in this app a reader can arrive at without pressing anything.**
  The other four are reached by a button in the top bar and exist for as long as the
  tab does; this one has an address, so it can be linked to, bookmarked, sent to
  somebody and reloaded. That is what the whole change is for, and it is why the
  server has a route behind the path rather than only a `pushState` here.

  **And the first page that is not owner-only.** Settings, Scopes and the Inbox are
  the owner's and `core/page-body` sends a visitor from any of them back to the
  shelf; a link to a published Recipe that only worked while signed in would not be
  a link at all, so this one is outside that set. What a visitor gets is what the
  API gives them: a published Recipe in full, and the same 404 for an unpublished
  one as for an id nobody ever wrote.

  Three states, and all three are real:

  - **loading** — the fetch is out. Always passed through, even when the card for
    this Recipe was expanded a moment ago and its body is already in `:details`:
    the page is a fresh read of one Recipe rather than a rearrangement of what the
    shelf happened to be holding, and the read is the thing that counts (below).
  - **found** — the title, the useful-when line, the body through the full markdown
    parser, and the header facts the card carries.
  - **not found** — a sentence and a way back. Never a blank page and never a stuck
    spinner.

  **Opening this page counts as a read, and that is correct.** It fetches
  `?detail=full`, which is what `record-view!` counts and what
  `0.7 × view_count + 0.3 × version` ranks the shelf by — the same count expanding a
  card makes, and the same count an agent makes fetching the Recipe through the API.
  A Recipe read at its own address is a Recipe that was used. Nobody should 'fix'
  this by fetching lean and filling the body in afterwards; the number would then
  mean 'read, unless by link', which is not a number anybody could rank by.

  The badges are `et.cb.ui.recipe-badges`' and the Scope pills are
  `et.cb.ui.scope-badges`', because the shelf's card says the same six things about
  the same Recipe and two spellings of one fact is how they drift. The Scope badges
  carry no gesture here: shift+clicking one on the shelf hides the Recipes filed
  under it, which is a filter over a listing — from this page it would be a filter
  over a page the reader is not looking at, and `scope-badges` says why a surface
  with no gesture explains none."
  (:require [clojure.string :as str]
            [et.cb.ui.markdown :as markdown]
            [et.cb.ui.provenance :as provenance]
            [et.cb.ui.recipe-badges :as recipe-badges]
            [et.cb.ui.scope-badges :as scope-badges]
            [et.cb.ui.state :as state]))

(defn- back-to-shelf
  "The way off this page, and it is on all three states rather than only on the two
  that went well. A reader who followed a link to a Recipe that is not there has no
  history to go back through and no top-bar button that means 'the shelf' — the
  three there are the owner's, and two of them are a page each. Without this a
  visitor's dead link would be a dead end.

  It goes through `state/go-to-page`, so the address bar goes back to `/` with it:
  leaving the page by this button and leaving it by the top bar have to put the
  same thing in the bar, or one of them is telling the truth and the other is not."
  []
  [:button.secondary.recipe-page-back
   {:on-click #(state/go-to-page :shelf)
    :title "Back to the shelf"}
   "← Shelf"])

(defn- header
  "The one line that says what this Recipe is — the card's header, on a page.

  Same facts and the same order, deliberately: a reader who knows the shelf can
  read this without learning anything. Three of them are gated on `logged-in?` at
  this call site exactly as they are at the card's, and the gate is **cosmetic** —
  a visitor's row carries no `tags`, no `scopes` and no `pending` key at all, and
  the server is the boundary. See the docstrings in `recipe-badges`."
  [{:keys [title tags scopes version published published_at modified_at view_count pending]
    :as recipe}
   logged-in?]
  (let [published? (= 1 published)
        pending? (= 1 pending)]
    [:div.recipe-page-header
     [:h1.recipe-page-title [markdown/render-inline title]]
     [:div.recipe-page-badges
      (when (and logged-in? published?)
        [recipe-badges/published-badge published_at])
      (when (and logged-in? pending?)
        [recipe-badges/pending-badge])
      (when (and logged-in? (seq scopes))
        [scope-badges/badges scopes {:class "recipe-page-scopes"}])
      [recipe-badges/version-badge version]
      ;; The whole row and not the two counts: `source-split` reads them itself and
      ;; decides which buckets exist, which is the decision that must not be made
      ;; twice.
      [recipe-badges/source-split recipe]
      [recipe-badges/views-badge view_count]
      [:span.card-date (recipe-badges/day modified_at)]]
     (when (and logged-in? (seq tags))
       [recipe-badges/tags tags {:class "recipe-page-tags"}])]))

(defn- provenance-toggle
  "The control, in an editor's register — *Show line numbers*, except that the
  numbers are the smaller half of what it shows.

  A `secondary` button, which is what `recipe-page-back` is: this page already has a
  word for 'a control that is not the point of the page', and a new one invented for
  the second such control would be the two drifting from the first change onwards."
  [showing?]
  [:button.secondary.recipe-page-provenance-toggle
   {:on-click state/toggle-provenance
    :title (str "Show the body as its source, each line tinted by who wrote it — "
                "instead of the rendered text")}
   (if showing? "Hide provenance" "Show provenance")])

(defn- source-line
  "One source line: its number, its provenance, and the text exactly as it is stored.

  The number comes off the enumeration and the colour off `caution`, and neither is
  computed from the other. A line the answer does not cover is drawn **untold**
  rather than tinted — a row with no colour says nothing, where a row defaulting to
  either end would say something false about who wrote it, and red in particular
  would be an invitation to rewrite his line."
  [n line caution]
  [:div.provenance-line
   (if (number? caution)
     ;; The number goes into CSS as a percentage and the two ends stay in
     ;; `base.css`, so `color-mix` interpolates them and both themes get their own
     ;; pair for free. Computing an `rgb()` here instead would put the palette in
     ;; the cljs and freeze it at the theme that was on when the row was drawn.
     {:style {"--caution" (str (* 100 caution) "%")}
      :title (str "caution " (.toFixed caution 2))}
     {:class "provenance-line-untold"
      :title "no provenance for this line"})
   [:span.provenance-line-number n]
   [:span.provenance-line-bar]
   [:span.provenance-line-text line]])

(defn- source-view
  "The body as its source, line numbered and provenance tinted.

  **The source and not the rendered markdown, and that is the whole design of this
  view rather than a shortcut.** `caution`'s ranges index the description's *source*
  lines, and rendering does not preserve them: a paragraph is many source lines
  joined into one `<p>`, a fenced block is many lines inside one `<pre>`, and a list
  item wraps. Tinting rendered blocks would mean guessing which block a line ended
  up in — and a paragraph half his and half an agent's would have to pick one colour
  and would then be telling the reader something false about his own text. So this
  behaves like an editor's line-number toggle: it shows you the text, and the
  rendered body comes back when it is turned off. The two never show at once.

  No markdown parsing at all, therefore, and the text goes in as a string: a body is
  full of `#`, `*` and `[]` that mean something to a parser, and this view's entire
  claim is that what you are looking at is what is stored."
  [description ranges]
  (let [lines (provenance/split-lines description)
        cautions (provenance/line-cautions ranges (count lines))]
    [:div.provenance-source
     (map-indexed (fn [i line]
                    ^{:key i} [source-line (inc i) line (nth cautions i)])
                  lines)]))

(defn- found
  "The Recipe. The body gets the full markdown parser and the code highlighting,
  the two short fields get the inline one — the same split the card makes, and for
  the same reason: a title is a phrase holding a place in a layout.

  A Recipe with no body says so rather than ending after its useful-when line, the
  way the expanded card does. On a page of its own that matters more: a card with
  nothing under it still has its neighbours around it to show that the shelf is
  working, and a page has nothing else on it at all.

  **The provenance toggle exists exactly when the answer does**, read off `caution`
  being in the response and not off `logged-in?`. The API leaves that key out for an
  anonymous reader on purpose — the split is derived from the version history and the
  history is the owner's — so keying the button off the property is one fact read
  once, with the server still the boundary, which is the argument
  `recipe-badges/source-split` already makes about a count it was not sent. A body
  that is blank is the other half of it: there is nothing to number, and the page
  already has a sentence for that case.

  The legend is the API's own string and is not retyped here. It is in the response
  for this, and a second wording of a scale is how two surfaces come to explain it
  differently."
  [recipe logged-in? showing-provenance?]
  (let [{:keys [legend ranges]} (:caution recipe)
        body (:description recipe)
        blank? (str/blank? body)
        offered? (and (seq ranges) (not blank?))
        showing? (and offered? showing-provenance?)]
    [:<>
     [header recipe logged-in?]
     (when (seq (:useful_when recipe))
       [:div.recipe-page-useful-when [markdown/render-inline (:useful_when recipe)]])
     (when offered?
       [:div.recipe-page-body-tools
        [provenance-toggle showing?]
        (when showing? [:div.provenance-legend legend])])
     (cond
       blank? [:div.card-body-empty "No body yet."]
       showing? [source-view body ranges]
       :else [:div.recipe-page-body [markdown/render body]])]))

(defn- not-found
  "What an address that names no readable Recipe gets.

  **It does not try to tell 'no such Recipe' from 'not yours to read'**, because
  the server does not either: an id nobody wrote and an unpublished Recipe a
  visitor asked for are the same 404 by design, and that is the whole of what keeps
  a stranger from discovering which of the owner's Recipes exist by trying ids.
  A page that guessed at the difference would be undoing that from the client, in
  words, on the one surface a stranger is looking at.

  So one sentence covering both, and it says the two things a reader can act on:
  the address may be wrong, and signing in may be the answer if it is his."
  []
  [:div.recipe-page-missing
   [:h1.recipe-page-title "No such Recipe here"]
   ;; Assembled with `str` rather than written across two source lines, the way
   ;; `excluded-scopes-strip`'s tooltip is: a string literal that wraps keeps the
   ;; newline *and* the indent. HTML would collapse both here, so this is tidiness
   ;; rather than a fix — but it is the same sentence in the DOM either way, and a
   ;; check that reads `.textContent` reads what is actually in there.
   [:p (str "This address does not name a Recipe you can read. It may be a Recipe "
            "that was never written, one that has since been deleted, or one of "
            "the owner's that has not been published — if it is yours, sign in and "
            "try again.")]])

(defn- with-provenance
  "The Recipe as this page draws it: the fetched row, plus the two version counts
  off the listing row for the same Recipe.

  **The counts are a listing aggregate and `GET /api/recipes/:id` does not carry
  them.** That is the endpoint's own design — a collapsed card cannot go and fetch
  a version list, so `list-recipes` counts them in the same query — and nothing
  about it was written with a page for one Recipe in mind. Left alone, the badge
  renders as nothing (`source-split` treats a missing count as a fact it has not
  been told, which it is), so the page would have quietly shown five of the card's
  six header facts and nobody would have been told which one was gone.

  So the two rows the client is already holding about one Recipe are joined here,
  and the fetched one wins every key it has: the listing row is lean and has no
  `description`, and its `view_count` is a request older. What this cannot do is
  invent the counts for a Recipe the listing did not return — narrowed away by a
  search, or filed under a hidden Scope — and in that case the badge is absent
  again, which is the same honest nothing it shows for a server that never sent
  them.

  **The API is the better place for this and it is not this change's to widen** —
  see the report accompanying this work."
  [recipe recipes]
  (if-let [row (first (filter #(= (:id recipe) (:id %)) recipes))]
    (merge row recipe)
    recipe))

(defn recipe-page
  "The page, chosen by the status `state/fetch-recipe-page!` last wrote.

  `:loading` is a state and not a default: `nil` cannot arrive here, because
  `show-page!` writes `:loading` in the same `swap!` that puts `:page` on
  `:recipe`, so there is no moment in which this page is up and nothing has been
  said about the fetch. The `nil` branch below therefore renders the spinner and
  not a blank, on the principle that a surprise should look like the honest state
  nearest to it."
  []
  (let [{:keys [logged-in? recipe-page-id recipe-page-status details recipes
                showing-provenance?]}
        @state/*app-state]
    [:div.recipe-page
     [back-to-shelf]
     (case recipe-page-status
       :found (if-let [recipe (get details recipe-page-id)]
                [found (with-provenance recipe recipes) logged-in? showing-provenance?]
                ;; The status says the fetch landed and the cache says otherwise,
                ;; which nothing produces today — `fetch-recipe-page!` caches before
                ;; it writes the status. Rendered as the not-found rather than as a
                ;; blank, because a page with only a Back button on it is the one
                ;; outcome this file exists to never produce.
                [not-found])
       :missing [not-found]
       [:div.card-body-loading "Loading…"])]))
