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

(defn- found
  "The Recipe. The body gets the full markdown parser and the code highlighting,
  the two short fields get the inline one — the same split the card makes, and for
  the same reason: a title is a phrase holding a place in a layout.

  A Recipe with no body says so rather than ending after its useful-when line, the
  way the expanded card does. On a page of its own that matters more: a card with
  nothing under it still has its neighbours around it to show that the shelf is
  working, and a page has nothing else on it at all."
  [recipe logged-in?]
  [:<>
   [header recipe logged-in?]
   (when (seq (:useful_when recipe))
     [:div.recipe-page-useful-when [markdown/render-inline (:useful_when recipe)]])
   (if (str/blank? (:description recipe))
     [:div.card-body-empty "No body yet."]
     [:div.recipe-page-body [markdown/render (:description recipe)]])])

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
  (let [{:keys [logged-in? recipe-page-id recipe-page-status details recipes]}
        @state/*app-state]
    [:div.recipe-page
     [back-to-shelf]
     (case recipe-page-status
       :found (if-let [recipe (get details recipe-page-id)]
                [found (with-provenance recipe recipes) logged-in?]
                ;; The status says the fetch landed and the cache says otherwise,
                ;; which nothing produces today — `fetch-recipe-page!` caches before
                ;; it writes the status. Rendered as the not-found rather than as a
                ;; blank, because a page with only a Back button on it is the one
                ;; outcome this file exists to never produce.
                [not-found])
       :missing [not-found]
       [:div.card-body-loading "Loading…"])]))
