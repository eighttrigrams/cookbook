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

  **And an expanded card shows the first ten blocks of that body rather than all
  of it**, with a See more for the rest — tracker's gesture, and the same
  argument as the collapse one level down: a card that unfolds to full length
  pushes the shelf off the screen. `clampable-body` is where that lives, and the
  Recipe's own page is where a body is still rendered whole.

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

  The shelf can be narrowed four ways at once — by a search over titles and tags,
  to the Recipes a human has edited here rather than an agent, **to** the Recipes
  filed under given Scopes, and **away from** the Recipes filed under given Scopes.
  All four are the listing endpoint's own `:where` clauses, carried as query params
  on the request; **nothing on this side filters rows it was given**, and that
  sentence is why both Scope filters are parameters rather than a `remove` or a
  `filter` over `:recipes`. Neither could be one: the shelf is ranked and sliced by
  the server, so rows dropped here would leave a short page this client has no way
  to top up — and rows *kept* here would leave one shorter still.

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
  at all and the `logged-in?` gate here is cosmetic.

  **A card only ever says which Scopes a Recipe is under; it does not file it.** The
  filing is done on the Recipe's own page, where the badges are a picker that saves
  as it is toggled (`views.recipe/scope-filing`) — filing makes no version, so it
  did not belong behind a Save.

  **This used to end with an exception, and the exception has gone rather than been
  disproved.** It read: *the compose form here picks Scopes for a Recipe that does not
  exist yet, which is the one case that cannot be done on a page.* That was true while
  the form was on the shelf, and it stopped being true the moment there was a page for
  it — *at the top of the page the will be an \"Add\" button which takes you to a page
  which looks like when we go from the recipe Page page to edit.* So the general rule
  now holds without a carve-out: **filing happens on the surface that is about the
  Recipe**, and for a Recipe that does not exist yet that surface is
  `views.new-recipe`. What the exception was really about survives one layer down —
  that picker *collects* rather than saving per chip, because there is nothing to PUT
  to until Save, and its docstring says so.

  Making a Scope happens on the Scopes page (`et.cb.ui.views.scopes`), not here either.

  **A Scope badge carries both filters now: plain click selects, shift+click
  hides.** Tracker's gesture, and being the same finger in both apps is the reason
  for it rather than a preference — `filters.cljc/badge-gesture` reads `shift? →
  :exclude` and plain `→ :toggle` over there, and option only appears in its
  `shift+option → :bypass` pair, which cookbook has no use for.

  **This paragraph used to say the filter was negative-only, and that argument is
  rewritten here rather than dropped, because it was overruled and not mistaken.**
  It ran: a plain click still falls through to the header and expands the card,
  because he asked to hide and not to select — and tracker's plain click *is* a
  positive filter, so inventing one here would be the wrong half of the parallel.
  Every clause of that was true of the app it described. He has since asked for the
  other half, twice: *list all scopes and have them be an OR filter for scopes*, and
  then, about the badge itself, *ah ok yeah. but when no negative filter is
  selecgted, allow to select positively.* So the parallel is now whole rather than
  half — and the old paragraph's own reasoning is what says so, since its complaint
  was never that a positive filter was wrong but that copying one gesture out of a
  pair would be.

  **What decides which gesture a click runs is `et.cb.filters/badge-gesture`**, in
  `src/cljc`, ported from tracker rather than re-derived and tested over every state
  the two filters can be in. Its own docstring predicted this moment from the other
  side — every branch but `:exclude` was unreachable *then*, and a copied matrix
  would have promised gestures that did not exist — so what changed is which states
  this app can be in, not what the right gesture was. The rule in one line: **the two
  filters never both start.** A plain click is refused while an exclusion is up (his
  words) and a shift+click is refused while a selection is up (the half he did not
  state, argued there).

  **Both narrowings must be visible and undoable wherever they are active**, which is
  one constraint met twice: `excluded-scopes-strip` is where an exclusion is seen and
  undone — an excluded Scope's badges leave the shelf with the Recipes carrying them,
  so nothing on the shelf can undo it — and `scope-filter-row` is where a selection
  is, which is easier, since every card left on the shelf is carrying a lit badge.
  Neither is decoration; the reasons are written down at each.

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
            ;; The gesture matrix, out of `src/cljc` so that it is testable without
            ;; a DOM — the shelf's two Scope filters keeping out of each other's
            ;; way is a rule with states, and a rule with states belongs somewhere a
            ;; test can enumerate them. Tracker's `et.tr.filters` is the model.
            [et.cb.filters :as filters]
            [et.cb.ui.markdown :as markdown]
            [et.cb.ui.recipe-badges :as recipe-badges]
            [et.cb.ui.scope-badges :as scope-badges]
            [et.cb.ui.state :as state]
            ;; The picker and the placeholder, out of a leaf namespace rather than
            ;; out of whichever view happened to have them last: this form and the
            ;; Recipe's own page both draw them, and neither view may require the
            ;; other. See `et.cb.ui.recipe-fields`.
            [et.cb.ui.recipe-fields :as recipe-fields]))

(defn add-action
  "**Add, in the top bar's right-hand slot** — the shelf's own action on the shelf,
  drawn by `core/surface-actions` exactly as `views.recipe/publish-action` is drawn on
  a Recipe's page.

  *on the overview page, there is a whole section for creating a new cookbook recipe. i
  dont want that, i want that page to be about filtering. what we gonna do. at the top
  of the page the will be an \"Add\" button which takes you to a page which looks like
  when we go from the recipe Page page to edit.*

  **`compose-form` was here and is gone, not moved.** It was the title, useful-when,
  tags and description inputs, a Scope picker and an Add button, sitting above the
  search box — so the first thing on the page a reader came to *find* something on was
  a form for making something. `views.new-recipe` is where those fields are now, and
  they are `recipe-fields/edit-fields`' rather than a second copy of themselves.
  `state/add-recipe` is untouched: what changed is where it is called from.

  **'The top of the page' is the bar, and that is a reading this app has already
  committed to.** Publish, Approve, Dismiss and Seen all went into that corner over
  the two commits before this one, on the rule that a surface's own action goes there.
  Add is the shelf's, so it goes there too — and the alternative reading, a button at
  the top of the panel, would have put the one control that leaves the shelf inside the
  thing it leaves.

  **A glyph and the first of the page buttons, which is where he put it.** It was the
  word *Add* in the surface-action slot for one commit — the position Publish, Approve
  and Seen occupy — and the corner it made was four glyphs and a word: *lets have the
  ADd button become a plus and go to the left of this list.* So it is `✚` in
  `.settings-toggle`'s register, at the head of the row rather than at its tail, and it
  is **not** a surface action any more: the shelf is not a focused surface, and what he
  has done is make Add one of the app's own widgets rather than the shelf's answer to
  the thing it is about. `core/surface-actions` is the poorer for it and the rule is the
  clearer: that slot is what a *focused* surface offers, and the shelf is not one.

  **`✚` and not `+` or `➕`, and the difference is measured.** At the row's size the
  ink heights are `+` 12px, `✚` 13px and `➕` 21px — the last because it is an emoji and
  falls back to a font that draws it half again as tall as everything beside it. `✚` is
  the only one of the three whose ink is exactly the ▦ and ☾ cluster's, so it needs no
  correction to sit level with them: see the stylesheet, where the other four glyphs do.

  A glyph needs its tooltip to be the label, which is why this one says what pressing it
  *does* rather than naming the page.

  Owner-only at the call site. The gate is **not** cosmetic: this button leads to a
  page that exists to POST, and the API answers a signed-out POST 401."
  []
  [:button.settings-toggle.shelf-add
   {:on-click state/open-new-recipe
    :title "Add — write a new Recipe on a page of its own"}
   "✚"])

(def ^:private visible-blocks
  "How many blocks of a body an unexpanded card shows. Tracker's number, from
  `ui.components.task-item/clampable-description`, and it is the same number here
  because being the same gesture in both apps is the point of copying it at all."
  10)

(defn- fence-line?
  "A line that opens or closes a fenced code block. Only ``` and ~~~ at the start
  of a line count, which is what marked's block tokenizer reads as a fence too;
  inline triple backticks in the middle of a sentence are not one."
  [line]
  (some? (re-find #"^\s*(```|~~~)" line)))

(defn- body-blocks
  "The body cut into the blocks a reader sees. Blank lines are the boundaries,
  as in tracker's `markdown-blocks` — **except inside a fenced code block**,
  where a blank line is part of the code and not a break between two thoughts.

  That exception is why this is a loop over lines rather than tracker's one-line
  `str/split`, and it is cookbook's own case rather than a refinement of
  tracker's: the bodies here are technical recipes whose fences routinely have
  blank lines in them (`markdown.cljs` gives this field the full parser and the
  highlighter for exactly that reason), so a naive split both counts one code
  listing as as many blocks as it has blank lines and can cut between two of
  them — leaving the visible half with an unclosed fence, which marked then reads
  as code running to the end of the text. Splitting on prose boundaries only
  means the cut can never land inside a fence, so nothing downstream has to
  repair one."
  [text]
  (loop [lines (str/split-lines (or text ""))
         fenced? false
         current []
         done []]
    (if-let [line (first lines)]
      (let [fence? (fence-line? line)
            ;; A fence line toggles the state and belongs to the block it bounds,
            ;; whichever end of the pair it is.
            inside? (if fence? (not fenced?) fenced?)]
        (if (and (not inside?) (not fence?) (str/blank? line))
          (recur (rest lines) inside? []
                 (cond-> done (seq current) (conj (str/join "\n" current))))
          (recur (rest lines) inside? (conj current line) done)))
      (cond-> done (seq current) (conj (str/join "\n" current))))))

(defn- clampable-body
  "The rendered body, abbreviated until he asks for the rest.

  **Expanding a card is not a request to read the whole Recipe**, it is a look at
  what the retrieval index could not say — and a body that arrives at full length
  pushes the next card off the screen, which is the shelf's own job undone. So an
  expansion shows the first `visible-blocks` blocks and a See more, and the whole
  body is one click further on. The Recipe's own page still renders it entire
  (`views.recipe/found`): a page is where a reader has said they are reading this
  one, and nothing there is competing for the screen.

  This is tracker's `clampable-description`, ported — same threshold, same
  one-way expansion, same quiet `.see-more` affordance rather than a button —
  down to keeping the state in a component-local ratom, which is why collapsing
  the card and expanding it again comes back abbreviated: the component goes with
  the collapse, and where the reader had got to in a card they have shut is not
  worth a key in app state.

  Two things of tracker's are deliberately **not** here. There is no
  `content-type` arm, because every body in cookbook is markdown — the html
  escape hatch over there exists for mail. And the click needs no
  `stopPropagation`: tracker's description sits inside the row that opens the
  item, while `.card-body` is a sibling of the header that toggles the card, so
  there is no ancestor handler to swallow. Adding one would be a guard against
  nothing, and would suggest to the next reader that there is."
  [text]
  (let [expanded? (r/atom false)]
    (fn [text]
      (let [blocks (body-blocks text)
            clamped? (and (not @expanded?) (> (count blocks) visible-blocks))]
        [:div.card-body
         [markdown/render (if clamped?
                            (str/join "\n\n" (take visible-blocks blocks))
                            text)]
         (when clamped?
           [:span.see-more {:on-click #(reset! expanded? true)} "See more"])]))))

(defn- card-body
  "`detail` is nil until the fetch this expansion started comes back.

  The body is the one field that gets the full markdown parser, and the only one
  that can carry a fenced code block — so the highlighter is only ever asked for
  something a card has actually been expanded to see. It is also asked for less
  than the whole of that: see `clampable-body`."
  [detail]
  (if detail
    (if (str/blank? (:description detail))
      [:div.card-body-empty "No body yet."]
      [clampable-body (:description detail)])
    [:div.card-body-loading "Loading…"]))

(defn- filter-gate
  "What the shelf's two Scope filters are currently doing, as
  `et.cb.filters/badge-gesture` wants it. One reading, so the badge and the chip row
  cannot come to disagree about which filter is running."
  [{:keys [excluded-scopes included-scopes]}]
  {:negative-active? (boolean (seq excluded-scopes))
   :positive-active? (boolean (seq included-scopes))})

(defn- scope-badge-hint
  "The gesture, spelled out on every badge, because a modifier key is the one
  affordance a reader cannot see. Tracker gets away without saying it — its badges
  do something on a plain click, so a user has already learned they are controls —
  and cookbook's plain click does something now too, which weakens that half of the
  reason without touching the other: shift is still invisible.

  **It says what this badge will do *now*, and that is what makes a refusal
  legible.** It was one sentence forever, when there was one gesture that was
  always open. With a gate there are three things a badge can be — both gestures
  open, only the plain one, only the shift one — and a badge that promised the same
  two in every state would be lying in two of the three, silently, at the exact
  moment a click does nothing (`filters/badge-consumes-click?` keeps a refused click
  off the card header, so the card does not even open). A control that is refused
  has to look refused; this is the badge's half of that, and the chip row's own
  refusal notice is the other."
  [gate]
  (cond
    (:negative-active? gate)
    (str "Scopes are being hidden — clear the Hiding row below the search to pick "
         "Scopes instead. shift+click still hides another one")

    (:positive-active? gate)
    "click to add or drop this Scope from the filter — clear the row to hide instead"

    :else
    "click to show only the Recipes filed under it · shift+click to hide them"))

(defn- badge-click
  "The badge's click handler: run whichever gesture the gate leaves open, and keep
  the click off the card header when it was a filter gesture — including one that
  was **refused**.

  `et.cb.filters` decides both halves and this only wires them, which is the whole
  point of that namespace: the matrix is testable without a DOM, and this function
  is what a test of it would otherwise have to be written against. It is tracker's
  `badge-click` in cookbook's two-gesture shape.

  **A refused click is consumed too**, which is tracker's rule and worth restating
  where the consequence lands: with an exclusion up, a plain click on a badge does
  nothing *and does not expand the card*. Letting it fall through instead would mean
  a click asking to filter got answered by an unrelated card opening. What keeps that
  from being a dead end is that the badge says so — see `scope-badge-hint`.

  **It drops the text selection the browser makes on the way in, and that is not
  tidiness.** Shift+click is *also* the gesture for extending a selection, and the
  browser runs that on mousedown, long before this handler sees a click — so without
  this every exclusion left a swathe of the card highlighted blue behind the rows
  that had just gone. `stopPropagation` cannot help with it and neither can
  `preventDefault` on a click that has already happened; the selection has to be
  collapsed once it exists.

  **That fix survived this rewrite by being moved rather than kept where it was**: it
  used to hang on the shift branch, which was then the only handled gesture, and it
  now hangs on **consuming** the click. Same coverage for shift, and it also covers
  the case that did not exist before — a shift+click the gate refuses, which the
  browser has already selected text for whether this app acts on it or not."
  [id e]
  (let [gate (filter-gate @state/*app-state)
        modifiers {:shift? (.-shiftKey e)}]
    (when (filters/badge-consumes-click? modifiers gate)
      (.stopPropagation e)
      (when-let [selection (js/window.getSelection)]
        (.removeAllRanges selection)))
    (case (filters/badge-gesture modifiers gate)
      :exclude (state/toggle-excluded-scope id)
      :toggle (state/toggle-included-scope id)
      nil)))

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
  and deleting the server half would publish the owner's filing.

  The hint is a function of the gate rather than a constant, because what a badge
  will do now depends on which filter is running — `scope-badge-hint` says why that
  had to change when the second gesture arrived."
  [scopes gate]
  [scope-badges/badges scopes {:class "card-scopes"
                               :hint (scope-badge-hint gate)
                               :on-click badge-click}])

(defn- card [{:keys [id title useful_when tags scopes version published published_at modified_at
                     pending]
              :as recipe}
             {:keys [logged-in? open details gate]}]
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
        [card-scopes scopes gate])
      [recipe-badges/version-badge version]
      [recipe-badges/source-split recipe]
      ;; Not gated on `logged-in?`, for the same reason the version badge is not:
      ;; it is a fact about the Recipe rather than about the owner's filing, and
      ;; the server puts it in the visitor's projection deliberately. It also
      ;; explains the order of the shelf a visitor is looking at.
      ;; The whole row and not the count: since 013 this badge draws the split as
      ;; well, and `source-split` beside it already takes the row for the same
      ;; reason — the component decides which buckets exist, which is the decision
      ;; that must not be made twice.
      [recipe-badges/views-badge recipe]
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

(defn- order-switcher
  "Which of the two orders the shelf is in — *i also need a switcher on the main page
  between the ranked order we have now, and one order which is most recently added
  first.*

  **Two named buttons and not a checkbox or a `select`.** The human filter beside it is
  a checkbox because it is one narrowing that is either on or off; this is a choice
  between two things that both have names, and a checkbox would have made one of them
  the unlabelled default — *Newest first ☐* leaves 'and otherwise what?' on the screen
  unanswered. Two buttons with the current one lit says both names and which is on, in
  the width a `select` would have taken to say one.

  **Not a narrowing, so nothing else has to know about it.** `empty-message` gets no
  case: reordering an empty result leaves it empty for whatever reason it already was,
  and a fifth sentence there would be one that could never be true. It is also why the
  switcher does not live with the two Scope filters in `filter-gate`'s world — there is
  no gate between an order and a filter, they compose the way the search and the human
  filter do.

  **The words say what the order is, and the tooltips carry what a word cannot.**
  *Ranked* alone does not say ranked by *what*, and the weights are the one thing a
  reader cannot infer from a shelf — `db.recipe/ranking-score` has that sentence, so
  the tooltip is this file's paraphrase of it rather than a new claim. *Added* is worded
  to keep it distinct from most-recently-**touched**: the shelf used to be ordered by
  `modified_at` outright, that is still the ranking's first tiebreaker, and a Recipe
  edited this morning is first by it and among the last by this one.

  **Shown signed out**, like the search box and the human filter, and for the reason
  `recipe-badges/views-badge` gives about the count that drives the ranking: it explains
  the order of the shelf a visitor is looking at, so a visitor may choose that order
  too. Both orders are the endpoint's for every caller."
  [shelf-order]
  [:div.order-switcher
   [:span.order-switcher-label {:title "What order the shelf is in"} "Order"]
   (for [[order label title]
         [[:ranked "Most used"
           (str "The default: how often each Recipe has been read, weighted with how "
                "often it has been edited — 0.7 × reads + 0.3 × versions. The shelf "
                "leads with what has proved useful")]
          [:newest "Newest"
           (str "Most recently added first, by when each Recipe was created — which is "
                "not the same as most recently edited")]]]
     ^{:key order}
     [:button.order-option
      {:type "button"
       :class (when (= order shelf-order) "on")
       :title title
       :on-click #(state/set-shelf-order order)}
      label])])

(defn- scope-filter-row
  "Every Scope the owner has, as toggles under the search box: the shelf's positive
  filter.

  *and on the main page, below the searchbar, list all scopes and have them be an
  OR filter for scopes, i.e. it filters when one or more are selected for all
  recipes which match one or more selectd scopes.* So: none on and the shelf is
  unnarrowed, one or more on and a Recipe is kept if it carries **at least one** of
  them. The union is the endpoint's `IN` (`db.scope/inclusion-clause`) and not
  anything computed here — this row sends ids and draws what came back.

  **`recipe-fields/scope-picker`, with its words changed and nothing else.** The
  same chips the compose form and a Recipe's page wear, so a lit chip means the same
  thing wherever it is met; what this surface says about it is where it sits and
  what it is for here — `Filter` rather than `Scopes`, and a tooltip about narrowing
  a shelf rather than about filing a Recipe, since this row files nothing. Two
  strings, passed in; a second component to change them would be the drift that
  namespace exists to prevent.

  **`on-toggle` hands `state/toggle-included-scope` the id and lets it compute the
  next set from the atom.** That is not a stylistic echo of the compose form: the
  picker deliberately hands over the id that was clicked rather than the set the row
  would become, because `:selected` is a value out of a render and two chips pressed
  inside one animation frame would then both compute from the same stale set — the
  second silently dropping the first. Measured over there, not reasoned about.

  **Owner-only, and this gate is real rather than cosmetic** — which is the
  distinction this file keeps carefully everywhere else (the badges and the tags are
  gated here and refused on the server). It happens to be true that a visitor is sent
  no Scopes and so `scope-picker` would draw nothing at all for one; that coincidence
  is not the gate and must not be mistaken for it. `db.recipe/list-recipes` refuses a
  visitor `?include-scopes` outright, because the rows that came back would *be* the
  published Recipes carrying a Scope — so a control that offered to ask would be a
  control promising something the server will not do.

  **Refused, visibly, while an exclusion is up.** His rule is about the filters and
  not about which control was used — *when no negative filter is selecgted, allow to
  select positively* — so a chip may not start a selection in a state where a badge
  may not either; `filters/badge-gesture` is the one place that decides, read here
  through `filter-gate`. The chips go genuinely `disabled` rather than swallowing
  clicks: dim, skipped by the keyboard, and carrying the reason in their tooltip,
  with the row saying it in words beside them. A control that silently ate clicks
  would be `excluded-scopes-strip`'s trap one layer up — a shelf narrowed with
  nothing on screen explaining it.

  **Clear appears whenever anything is selected**, where the exclusion strip's
  appears only above one. The row is a single control, so there is no
  clearing-one-at-a-time to become work; what it is really for is the id whose Scope
  has been *deleted elsewhere*, which has no chip left in this row — the row is drawn
  from the Scope list — while still narrowing the shelf to nothing, since an
  unrecognised id keeps no rows. `state/delete-scope` handles the case where this
  client did the deleting; this handles the case where it did not."
  [gate]
  (let [{:keys [included-scopes logged-in?]} @state/*app-state
        refused? (:negative-active? gate)]
    (when logged-in?
      [:div.scope-filter
       [recipe-fields/scope-picker
        {:selected included-scopes
         :on-toggle #(state/toggle-included-scope %)
         :class "shelf-scope-filter"
         :label "Filter"
         :label-title (str "Show only the Recipes filed under the Scopes you pick — "
                           "one or more, and a Recipe needs only one of them")
         :disabled? refused?
         :disabled-title (str "Scopes are being hidden below. Clear that first — the "
                              "shelf narrows one way at a time")}]
       (when refused?
         [:span.scope-filter-refused
          "Picking is off while Scopes are hidden — clear the row below."])
       (when (seq included-scopes)
         [:button.clear-scope-filter
          {:type "button"
           :title "Stop narrowing to those Scopes"
           :on-click state/clear-included-scopes}
          "Clear"])])))

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

  **'No recipes yet.' used to be said with a compose form directly above it**, which
  answered it: the sentence named the state and the form was the way out of it. The
  form is on a page of its own now, so the only thing on screen that answers this one
  is **Add** in the top bar — which is why the sentence names it. A shelf with nothing
  on it and nothing to press was the one outcome this change could plausibly have
  produced, and it is the reason this branch was revisited at all rather than left
  alone as a string about a case that had not changed.

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
  company, not by which narrowing feels the more specific.

  **The Scope *selection* is the fourth, and the rule puts it beside the exclusion
  rather than after the search.** 'Nothing left in the Scopes you picked' is the
  same shape as its sibling's sentence — a claim about the *result*, which stays
  true however many other narrowings are taking rows away alongside it — and it is
  worded that way on purpose. The obvious wording is the one to avoid: 'Nothing is
  filed under those Scopes' is a claim about the **filing**, and a search or the
  human filter can falsify it in exactly the way that produced the correction above,
  by hiding a Recipe that *is* filed under one.

  **The two Scope branches never compete**, because a badge and the chip row both
  refuse to start one filter while the other is running (`et.cb.filters`). So their
  order relative to each other decides nothing today; they are adjacent, and the
  exclusion is first, so that the pair reads as a pair and a reader who arrives here
  after the gate is ever loosened finds them together rather than a page apart."
  [search human-only? excluded-scopes included-scopes]
  (cond
    (seq excluded-scopes) "Nothing left once those Scopes are hidden."
    (seq included-scopes) "Nothing left in the Scopes you picked."
    (seq search)          "Nothing matches."
    human-only?           "Nothing here has been edited in this UI yet."
    :else                 "No recipes yet. Add is in the top right."))

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
  (let [{:keys [recipes search human-only? excluded-scopes included-scopes
                shelf-order logged-in? open details]
         :as app-state}
        @state/*app-state
        gate (filter-gate app-state)]
    [:div.shelf
     ;; No compose form: *i want that page to be about filtering.* What was here is
     ;; `views.new-recipe`, reached by `add-action` in the top bar — and the shelf now
     ;; begins with the controls that narrow it, which is what the page is for.
     [:div.shelf-controls
      ;; The endpoint matches words from their start, in the title and in the
      ;; tags, so the placeholder names them and says beginnings of words rather
      ;; than letting a typist expect a substring to hit.
      ;;
      ;; **And it names the Scopes signed in, because signed out they are not
      ;; searched.** This used to say the same thing to both callers, and the note
      ;; here was that it could — tags are searched for everyone, only the values
      ;; are the owner's. A Scope's own title and tags are the one thing where that
      ;; stops being true: they widen the owner's search and nobody else's (see
      ;; `db.recipe/list-recipes`), so one string for both would have to be either
      ;; a promise a visitor's search does not keep or a silence about what a
      ;; signed-in search can now do. The branch is the only honest option, and it
      ;; is the same `logged-in?` the tags on a card are gated on.
      [:input.search
       {:type "text" :placeholder (if logged-in?
                                    "Search titles, tags and Scopes — start of any word"
                                    "Search titles and tags — start of any word")
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
       "Human-edited only"]
      ;; **On the controls line and not on a row of its own**, unlike the Scope
      ;; filter: it is one control of a fixed width — two short words — where that is
      ;; every Scope the owner has and wraps. It is last on the line because it is the
      ;; one control that does not *narrow*: search, then narrow, then how to order
      ;; what is left, which is also the order a reader would say them in.
      [order-switcher shelf-order]]
     ;; **The permanent row first, the transient one under it**, and that order is
     ;; the one thing about the placement worth arguing. *below the searchbar, list
     ;; all scopes* puts the picker here, directly under the search box, and it is
     ;; on screen whether or not anything is selected. The Hiding strip is only
     ;; there while an exclusion is up — so above the picker it would appear and
     ;; disappear *between* the search and a row that never moves, shoving that row
     ;; up and down the page as Scopes are hidden and shown. A control that jumps
     ;; when a neighbour comes and goes is harder to hit than one that does not.
     ;;
     ;; The consequence to accept, since it cuts the other way: while an exclusion
     ;; is up, the row explaining it sits *below* the row it is refusing. That is
     ;; why the refusal is said in the picker's own band — in the chips' tooltips
     ;; and in a line beside them — and points down, rather than relying on the
     ;; reader finding the reason underneath by themselves.
     [scope-filter-row gate]
     [excluded-scopes-strip]
     (if (empty? recipes)
       [:div.empty (empty-message search human-only? excluded-scopes included-scopes)]
       (for [recipe recipes]
         ^{:key (:id recipe)}
         [card recipe {:logged-in? logged-in? :open open :details details
                       :gate gate}]))]))
