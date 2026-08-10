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

  **And it is where the owner's four actions live.** Publish, Edit, Versions and
  Delete were the shelf card's footer, beside the button that comes here; he asked for
  a card that carries nothing but *Page* and said where the rest were to go — *all the
  buttons go to that page then*. So the shelf is the retrieval index it says it is,
  and the one surface that is about one Recipe is the one that can change it. See
  `actions`, and `views.recipe-modals` for why the overlays they open are mounted at
  the app root rather than by whichever page opened them.

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

  The badges are `et.cb.ui.recipe-badges`', because the shelf's card says the same
  things about the same Recipe and two spellings of one fact is how they drift.

  **The Scopes are the exception, and they are a control here rather than a fact.**
  The card wears them as `scope-badges` pills; this page draws the owner's whole
  Scope list as a picker and files the Recipe as he toggles — see `scope-filing`.
  The shelf's gesture does not come with them and could not: shift+clicking a badge
  over there hides the Recipes filed under that Scope, which is a filter over a
  *listing*, and from this page it would be a filter over a page the reader is not
  looking at. A chip's plain click already means something here, which is the other
  half of why there is nothing for a modifier to add."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.cb.ui.markdown :as markdown]
            [et.cb.ui.provenance :as provenance]
            [et.cb.ui.recipe-badges :as recipe-badges]
            [et.cb.ui.recipe-fields :as recipe-fields]
            [et.cb.ui.state :as state]))

(defn back-to-shelf
  "The way off this page — **in the top bar's left slot, where the brand sits
  everywhere else.** *the back button should go there where on the list view the
  cookbook brand logo is.*

  **It is on all three states, and it is now so by construction.** It used to be
  rendered inside the panel by `recipe-page`, above the `case`, so that loading,
  found and not-found all got one: a reader who followed a link to a Recipe that is
  not there has no history to go back through, and — the argument as it stood — no
  top-bar button that means 'the shelf'. There *is* such a button now and this is it,
  so the reasoning has not gone away, it has got stronger. The bar does not know
  which of the three states the page is in and has no way to leave one of them out,
  where three call sites inside the panel were three chances to forget.

  It stays a `views.recipe` component that `core/top-bar` renders, rather than
  becoming the bar's own. `core` already requires this namespace, so either direction
  compiles; what decides it is that this is *this page's* control — it exists because
  of what a Recipe page can be arrived at without, and that record belongs with the
  page rather than in the chrome that happens to draw it.

  It goes through `state/go-to-page`, so the address bar goes back to `/` with it:
  leaving the page by this button and leaving it by anything else have to put the
  same thing in the bar, or one of them is telling the truth and the other is not."
  []
  [:button.secondary.recipe-page-back
   {:on-click #(state/go-to-page :shelf)
    :title "Back to the shelf"}
   "← Shelf"])

(defn- header
  "The one line that says what this Recipe is — the card's header, on a page.

  Same facts and the same order, deliberately: a reader who knows the shelf can
  read this without learning anything. Two of them are gated on `logged-in?` at
  this call site exactly as they are at the card's, and the gate is **cosmetic** —
  a visitor's row carries no `tags` and no `pending` key at all, and the server is
  the boundary. See the docstrings in `recipe-badges`.

  **The Scopes are no longer among them, and that is not a fact this page stopped
  saying.** The card's header shows them as badges because a card cannot do
  anything about them; here they are a *control* — `scope-filing`, below the
  header — and the picker's lit chips are the display. Drawing both would be the
  same fact twice, two paragraphs apart, with only one of them able to be wrong.

  Drawn by both of this page's modes, reading and editing, which is what makes it
  the page's identity rather than the reading's."
  [{:keys [title tags version published published_at modified_at view_count pending]
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
      [recipe-badges/version-badge version]
      ;; The whole row and not the two counts: `source-split` reads them itself and
      ;; decides which buckets exist, which is the decision that must not be made
      ;; twice.
      [recipe-badges/source-split recipe]
      [recipe-badges/views-badge view_count]
      [:span.card-date (recipe-badges/day modified_at)]]
     (when (and logged-in? (seq tags))
       [recipe-badges/tags tags {:class "recipe-page-tags"}])]))

(defn- scope-filing
  "Which Scopes this Recipe is filed under, as the control that files it — the
  owner's whole Scope list as chips, the ones this Recipe carries lit, **saving on
  every toggle**.

  *lets make it that we select the scopes not in the modal but on the Page page*,
  and then, asked whether it belonged here or on the editor: *yeah, we dont need no
  version bump on this and can go to the read page*. That is not a preference the
  client is honouring, it is what the API already does —
  `update-recipe-handler`'s docstring: **Changing it makes no version either — a
  Scope is a way back to a Recipe, not part of it.** So filing is not editing, and
  the place it belongs is the page you are reading, not the form you save.

  There is no Save here for the same reason. A confirmation would be a step in
  front of a change that costs nothing to undo: the chip you just lit unlights, and
  no version, no history row and no `has_human_edit` mark was made either way.
  `state/toggle-recipe-scope` is where the two things that *are* delicate live — the
  empty set going as `[]`, and one request at a time.

  **What is lit is the receipt, not the click** — `state/filed-under` is that
  sentence, and it is the one place both the drawing and the toggling read from. It
  answers with what the server last confirmed, or with what the owner has asked for
  while a save is out, so a chip answers immediately and is then corrected by the
  response. It has to be that way round: the handler drops ids the caller does not
  own, so a picker that trusted its own set would show such an id as filed.

  **This component computes nothing.** It hands `state/toggle-recipe-scope` the id
  that was pressed, and the next set is worked out there against the live one —
  which is what makes two chips pressed inside one frame both land. Anything derived
  here would be derived from a render, and a render is exactly what two clicks in
  one frame share.

  Nothing is disabled while a save is out. `toggle-recipe-scope` queues instead, and
  says why at length: a disabled chip does not fire, so the second of two quick
  clicks would be lost rather than merely delayed.

  The `logged-in?` gate is the call site's, and it is **as cosmetic as the badge row
  it replaces**: a visitor is sent no `scopes` key on the Recipe and no Scope list
  at all — `/api/scopes` answers them 403 and `show-page!` does not even ask — so
  there is nothing here for them to draw. The boundary is the server, twice over,
  and this gate is convenience."
  [{:keys [id]}]
  [recipe-fields/scope-picker
   {:selected (state/filed-under id)
    :on-toggle #(state/toggle-recipe-scope id %)
    :class "recipe-page-filing"}])

(defn- actions
  "Publish, Edit, Versions and Delete — the four the shelf's card footer used to
  carry, on the page they are about. *all the buttons go to that page then*, which
  is the half of his ask that is not a removal.

  **The same four words, deliberately.** `header` argues that a reader who knows the
  shelf can read this page without learning anything, and it is about the six facts;
  it holds at least as strongly for four controls. A page that called them *Publish
  changes*, *Modify*, *History* and *Remove* would be four things to learn about a
  Recipe he has already learnt on the card.

  `secondary` for all four, which is this page's word for a control that is not the
  point of the page — `recipe-page-back` and `provenance-toggle` are both that, and
  `provenance-toggle`'s docstring says that a second word invented for the same idea
  is how two things drift. Delete keeps `.danger`, the one distinction the card made.
  Full-size buttons rather than the card's smaller ones, for the reason the title is
  an `h1`: a card is one of thirteen and this is the Recipe, alone.

  **Publish only while there is something to publish.** The latch is one way — the
  API has no unpublish — so a published Recipe loses the button and wears the badge
  in the header instead. Same rule as the card's, and the same reading of the JSON:
  `published` arrives as 0 or 1 and 0 is truthy in cljs, so it is a comparison.

  Owner-only at the call site, and here the gate is **not** merely cosmetic the way
  the header's three are: these are four writes, and the server refuses every one of
  them to anybody else — including Versions, which answers a visitor 404 for every
  id. What the gate prevents is a row of controls that could only produce errors."
  [{:keys [id published]}]
  (let [published? (= 1 published)]
    [:div.recipe-page-actions
     (when-not published?
       [:button.secondary {:on-click #(state/start-publishing id)} "Publish"])
     ;; A navigation and not an overlay: same Recipe, same page, `?edit=true`. The
     ;; address is `go-to-page`'s to write, which is why this calls a named move
     ;; rather than assembling one.
     [:button.secondary {:on-click #(state/open-recipe-editor id)} "Edit"]
     ;; Named for what it shows rather than for the merge view inside it: a
     ;; one-version Recipe has nothing to diff and this still answers the question,
     ;; which is what the `v1` badge above it is pointing at.
     [:button.secondary
      {:on-click #(state/start-diff id)
       :title "Step through every version and see what each save changed"}
      "Versions"]
     [:button.secondary.danger {:on-click #(state/start-deleting id)} "Delete"]]))

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
  differently.

  **The owner's four actions go under the header and above the body**, and both
  halves of that are on purpose:

  - Under the header rather than beside `recipe-page-back`, because leaving the page
    is not one of the things you can do to the Recipe. That button is on all three
    states and these exist only where there is a Recipe to act on, so they are not
    the same row — and keeping them apart is what leaves the way off first in the
    tab order, with Delete not one stop from the top of the page.
  - Above the body, which is *not* where the card puts them. A card's footer sits
    under a body that is a paragraph or two between twelve neighbours; a page's body
    is the Recipe in full and has no length at all — a footer under it would put Edit
    below the fold and Delete at the end of a scroll. So the controls that change the
    Recipe sit with the facts that say which Recipe it is, and the body follows them.

  Above `recipe-page-body-tools` for the same reading: this row is what you can *do*
  to the Recipe, that one is how you want to *look* at it, and the doing is not a
  property of the text the way the provenance view is.

  **The Scope picker sits between the header and the useful-when line**, where the
  card's header wears the badges it replaces — the filing is part of what says which
  Recipe this is, and it reads with the tags row above it, which is the other half of
  the same filing. It is above the four actions rather than among them because it is
  not one: those four are things you ask for and this one is already saved by the time
  your finger is off it.

  It is drawn by this mode only. The editor has no picker — `editor` says why — so
  this is the one surface in the app that files a Recipe, and there is no second
  control anywhere disagreeing about when it saves."
  [recipe logged-in? showing-provenance?]
  (let [{:keys [legend ranges]} (:caution recipe)
        body (:description recipe)
        blank? (str/blank? body)
        offered? (and (seq ranges) (not blank?))
        showing? (and offered? showing-provenance?)]
    [:<>
     [header recipe logged-in?]
     (when logged-in?
       [scope-filing recipe])
     (when (seq (:useful_when recipe))
       [:div.recipe-page-useful-when [markdown/render-inline (:useful_when recipe)]])
     (when logged-in?
       [actions recipe])
     (when offered?
       [:div.recipe-page-body-tools
        [provenance-toggle showing?]
        (when showing? [:div.provenance-legend legend])])
     (cond
       blank? [:div.card-body-empty "No body yet."]
       showing? [source-view body ranges]
       :else [:div.recipe-page-body [markdown/render body]])]))

(defn- editor
  "The Recipe's four content fields, behind a Save — this page's other mode, at
  `?edit=true`.

  *instead of an edit modal, lets go to a separate page, with ?edit=true query
  param*. It was `views.recipe-modals/edit-modal` and it is gone: a form over a page
  that had to be a fixed overlay outside every containing block, and that could not
  be linked to, reloaded or left with Back. Here it is the same Recipe at the same
  address in a second mode, so all three of those come for free from
  `state/sync-from-url!`.

  **The header is drawn above the form, and the version subtitle the modal had is
  gone with it.** The modal said *version 3* because it had no header of its own to
  say it; this page's header wears the version badge already, and a second copy is
  the same fact twice. Read the two halves as *what is saved* over *what you are
  about to save* — that is also why the title is an `h1` above a title field rather
  than a redundancy: the heading is the Recipe as it stands, the field is what you
  are proposing to make of it, and they are allowed to differ until you press Save.
  It is the same `header` the reading draws, which is what makes the two modes one
  page rather than two screens.

  **No Scope picker, and that is the whole point of the split.** Filing happens on
  the reading, saves as it is toggled and makes no version; putting a picker here too
  would be one control on two surfaces disagreeing about when it saves. It follows
  that this form must **omit `scope_ids` entirely** — *a field you leave out keeps
  its current value* — so a content save cannot disturb the filing. The modal sent
  the key on every save and had to, because it carried a picker whose set the owner
  might just have emptied on purpose; with the picker gone, sending it would be the
  bug and omitting it is the fix.

  Local ratoms and one `update-recipe` at the end, as the modal had: a keystroke is
  not a save, and this page's other control is the one that saves per gesture. Save
  is dead on a blank title, which is the 400 this route answers — refusing it here
  is the client agreeing with the server rather than guessing.

  Cancel and a successful Save both go through `state/go-to-page :recipe id`, so the
  bar loses `?edit=true` either way. Nothing is discarded by Cancel that was not
  already only in this form."
  [recipe _logged-in?]
  (let [title (r/atom (or (:title recipe) ""))
        useful-when (r/atom (or (:useful_when recipe) ""))
        tags (r/atom (or (:tags recipe) ""))
        description (r/atom (or (:description recipe) ""))]
    (fn [recipe logged-in?]
      (let [id (:id recipe)
            leave #(state/go-to-page :recipe id)]
        [:<>
         [header recipe logged-in?]
         [:div.recipe-page-edit
          [:input.recipe-page-edit-title
           {:type "text" :placeholder "Title"
            :value @title
            :on-change #(reset! title (-> % .-target .-value))}]
          [:input
           {:type "text" :placeholder "Useful when…"
            :value @useful-when
            :on-change #(reset! useful-when (-> % .-target .-value))}]
          [:input.recipe-page-edit-tags
           {:type "text" :placeholder recipe-fields/tags-placeholder
            :value @tags
            :on-change #(reset! tags (-> % .-target .-value))}]
          [:textarea.recipe-page-edit-body
           {:placeholder "The recipe itself"
            :rows 16
            :value @description
            :on-change #(reset! description (-> % .-target .-value))}]
          [:div.recipe-page-edit-actions
           [:button {:disabled (str/blank? @title)
                     ;; No `:scope_ids`. The filing is the reading's and an omitted
                     ;; key keeps it — see the docstring.
                     :on-click #(state/update-recipe id
                                                     {:title @title
                                                      :useful_when @useful-when
                                                      :tags @tags
                                                      :description @description}
                                                     leave)}
            "Save"]
           [:button.secondary {:on-click leave} "Cancel"]]]]))))

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
  "The page, chosen by the status `state/fetch-recipe-page!` last wrote — and, when
  the Recipe is there, by which of the two modes the address asks for.

  `:loading` is a state and not a default: `nil` cannot arrive here, because
  `show-page!` writes `:loading` in the same `swap!` that puts `:page` on
  `:recipe`, so there is no moment in which this page is up and nothing has been
  said about the fetch. The `nil` branch below therefore renders the spinner and
  not a blank, on the principle that a surprise should look like the honest state
  nearest to it.

  **The mode only ever chooses between the two things a *found* Recipe can be shown
  as.** Loading and not-found have one rendering each: there is nothing to edit
  until the Recipe is there, and an editor over a 404 would be a form pointed at a
  Recipe that does not exist. `?edit=true` on a missing id therefore gets the same
  sentence as without it, which is also the answer that leaks nothing.

  **`logged-in?` gates the editor here as well as in `sync-from-url!`**, and the
  second half is the one that does not depend on remembering. It is `page-body`'s
  argument exactly: that function sends a visitor off an owner-only page rather than
  trusting `logout` to have reset the state, and this is the same guarantee for a
  mode. A visitor cannot reach `:recipe-page-edit? true` today — the flag is
  derived from the address by a function that ands it with the session — so this
  clause is unreachable and stays anyway, because 'unreachable' is a property of
  today's callers and a rendered form is a promise to somebody the API will refuse."
  []
  (let [{:keys [logged-in? recipe-page-id recipe-page-status recipe-page-edit? details
                recipes showing-provenance?]}
        @state/*app-state]
    [:div.recipe-page
     ;; No way out drawn in here any more: `back-to-shelf` is in the top bar's left
     ;; slot, which is where it is present on all three of these states without this
     ;; function having to put it above the `case`.
     (case recipe-page-status
       :found (if-let [recipe (get details recipe-page-id)]
                (if (and recipe-page-edit? logged-in?)
                  [editor (with-provenance recipe recipes) logged-in?]
                  [found (with-provenance recipe recipes) logged-in? showing-provenance?])
                ;; The status says the fetch landed and the cache says otherwise,
                ;; which nothing produces today — `fetch-recipe-page!` caches before
                ;; it writes the status, and `state/delete-recipe`, which is the one
                ;; thing that takes a row back out of `:details`, leaves this page in
                ;; the same breath. Rendered as the not-found rather than as a blank,
                ;; because a page with only a Back button on it is the one outcome
                ;; this file exists to never produce.
                [not-found])
       :missing [not-found]
       [:div.card-body-loading "Loading…"])]))
