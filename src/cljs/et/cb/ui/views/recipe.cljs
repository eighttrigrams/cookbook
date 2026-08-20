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
  and the one surface that is about one Recipe is the one that can change it.

  They live in **three** places on it, and the line between them is still the page's
  own rule about its own controls: the ways of *looking* at this Recipe — `← Shelf`,
  Edit, Versions — are in the top bar's **left** slot; **Publish is in the bar's
  right-hand slot, beside the theme toggle** — *In the Page view, put the Publish
  button in the top right, to the left of the dark mode switcher.*; and Delete stays
  down in the panel, at its bottom right. See `navigation-actions` for the rule and
  what the last move did to it, `publish-action` for that move, `delete-action` for the
  one control that did not make it, and `views.recipe-modals` for why the overlays they
  open are mounted at the app root rather than by whichever page opened them.

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
  (:require [clojure.string :as str]
            [et.cb.ui.edit-keys :as edit-keys]
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
  the page's identity rather than the reading's.

  **`corner` is whatever belongs in the panel's top-right, level with the title** —
  today the provenance toggle, in both modes. *also it should be placed in the top
  right corner of that REcipe's space.* It is passed in rather than reached for, so
  this function stays a statement of what the Recipe *is* and holds no opinion about
  the controls that happen to sit beside the title; the two modes each hand it their
  own, and either may hand it nothing."
  [{:keys [title tags version published published_at modified_at pending]
    :as recipe}
   logged-in? corner]
  (let [published? (= 1 published)
        pending? (= 1 pending)]
    [:div.recipe-page-header
     ;; The title and the corner share a line, which is what *top right corner* means
     ;; here — the corner of the panel and not of the body below it. A row rather than
     ;; a float, so a title long enough to reach the button pushes it rather than
     ;; running under it.
     [:div.recipe-page-title-row
      [:h1.recipe-page-title [markdown/render-inline title]]
      corner]
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
      ;; The whole row, like `source-split` above it — the reads badge draws its
      ;; own split now and decides which buckets exist.
      [recipe-badges/views-badge recipe]
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

(defn navigation-actions
  "**Edit and Versions, in the top bar's left slot beside `← Shelf`.** *edit and
  versions can now move to the top, next to the back to shelf button.*

  **The rule the split makes, because it answers where the next button goes: this slot
  carries ways of *looking* at this Recipe, and what *changes* it is not in here.**
  `← Shelf` leaves the Recipe, Edit switches which mode you are reading it in,
  Versions opens its history — none of the three touches it. Publish is a one-way
  latch with no unpublish in the API, and Delete takes the Recipe and its whole
  history with it, so a mis-aimed click among three ways of looking would cost a
  Recipe rather than a step. That is the same argument the publish and delete
  confirmations are already making one layer down, one layer up.

  **The rule as first written said 'and the panel keeps what changes it', and that
  half has now been overruled — it is rewritten here rather than quietly reversed.**
  *In the Page view, put the Publish button in the top right, to the left of the dark
  mode switcher.* So Publish is in the bar after all, at the bar's **other end**, which
  is what keeps the paragraph above true rather than merely surviving it: the two slots
  are not one row, nothing in the right-hand corner can be aimed at by mistake from
  here, and a reader stepping through `← Shelf`, Edit and Versions still meets no
  write. What is left of the original rule is exactly this slot's half of it, and
  `publish-action` and `core/surface-actions` carry the other.

  **Delete has not moved, and the reason it stays is still good** — its worst outcome
  is losing the Recipe, and that is what earns it a place out of the reading path at
  the bottom right of the panel (`delete-action`). Publish going up is not licence to
  send Delete after it: publishing is a step in a Recipe's life, taken on purpose and
  at a moment of the owner's choosing, and deleting is the one control on this page
  that can lose one.

  `secondary` for both, which is `back-to-shelf`'s register and therefore the slot's:
  three controls that sit in a row have to look like three of a kind, and this page
  already has one word for *a control that is not the point of the page*.

  Owner-only at the call site. The gate is not cosmetic — both of these lead somewhere
  the server refuses anybody else, Versions with a 404 from `/versions` for every id."
  [{:keys [id]}]
  [:<>
   ;; A navigation and not an overlay: same Recipe, same page, `?edit=true`. The
   ;; address is `go-to-page`'s to write, which is why this calls a named move rather
   ;; than assembling one.
   [:button.secondary {:on-click #(state/open-recipe-editor id)} "Edit"]
   ;; Named for what it shows rather than for the merge view inside it: a one-version
   ;; Recipe has nothing to diff and this still answers the question, which is what
   ;; the `v1` badge in the header is pointing at.
   [:button.secondary
    {:on-click #(state/start-diff id)
     :title "Step through every version and see what each save changed"}
    "Versions"]])

(defn publish-action
  "**Publish, in the top bar's right-hand slot, immediately left of the dark-mode
  toggle.** *In the Page view, put the Publish button in the top right, to the left of
  the dark mode switcher.*

  It was four buttons under the header, then two, then one, and now there is no row
  there at all: Edit and Versions went up into the bar's left slot, Delete went to the
  bottom right (`delete-action`), and Publish was the last thing left in
  `.recipe-page-actions`. `navigation-actions` writes down the rule that decided the
  first move and what this one did to it; `found` keeps the argument this one overruled.

  **The row is gone rather than left standing with the one thing missing**, and the
  argument for that was already written: `mutating-actions`, the function this replaces,
  drew nothing at all for a *published* Recipe, because Publish was the only thing in
  there and a `div` with the panel's spacing around it and nothing in it is a gap a
  reader has to account for. That case has become every case, so the function is
  deleted rather than emptied — a `when-not` guarding a container nothing can ever fill
  is the same leftover one indirection along. It is the **sixth** time this run of work
  has met that: music's card footer, this panel's top edge, the diff header's `✕`, the
  Publish-only row, `.recipe-page-body-tools` with only the legend left in it, and now
  the row itself. `core/surface-actions` pays it once more, one level up, for the
  container that holds this button.

  **`views.recipe`'s own component, drawn by `core/top-bar`** — exactly as
  `back-to-shelf` and `navigation-actions` are drawn by `core/left-slot`, and for that
  reason: the bar places controls and knows nothing about what any of them do, and the
  record of why *this page* offers this belongs with the page. Which is also why the
  conditions are not in here. `core/surface-actions` is the one place that decides when
  the bar carries a surface's own actions, and it says at length what Publish is gated
  on — including what happens in the editor and under the version viewer, neither of
  which this component can see.

  **The same word the card's footer had, deliberately.** `header` argues that a reader
  who knows the shelf can read this page without learning anything, about the six
  facts; it holds for the controls too, and it has to hold across a move as well — the
  word did not change when the place did.

  `.bar-action` and not `.secondary`, which is the one thing about it that is new. The
  neighbours in that corner are 32px icon-ish toggles and this is a *word*; the
  stylesheet's rule is that register for everything a focused surface puts up there, so
  a word reads as a control beside a glyph without a panel-sized glass button wandering
  into a bar. Owner-only and unpublished-only, both decided at the call site, and the
  first of those gates is **not** cosmetic the way the header's two are: this is a
  write, and the server refuses it to anybody else."
  [{:keys [id]}]
  [:button.bar-action.recipe-publish
   {:on-click #(state/start-publishing id)
    :title "Publish this Recipe — anyone can then read it, and there is no unpublish"}
   "Publish"])

(defn- delete-action
  "**Delete, at the bottom right of the panel — in both of this page's modes.** *on
  both edit and view pages the delete button goes to the bottom right.*

  **This overrules an argument written in `found`, and that argument is rewritten there
  rather than dropped.** It said that a page's body has no length, so a footer under it
  would put Delete at the end of a scroll. That is still true and it is now the accepted
  cost: bottom-right is the conventional home for a destructive control *because* it is
  out of the reading path and reached deliberately, and on a long Recipe being a scroll
  away is the price of that. The reader who comes here in six months should find the
  trade, not a silent reversal.

  Right-aligned and last, so both halves of *out of the way* hold: nothing follows it
  in the panel, and nothing sits beside it to be aimed at by mistake. It keeps
  `.danger`, which is the only colour in the panel and now the only thing wearing it.

  **The tab order comes out right without being arranged here**, and moving it made
  that stronger rather than weaker: the bar precedes the panel in the document, so
  `← Shelf`/Edit/Versions — or Save/Cancel — and Publish at the bar's other end are
  reached first, and Delete is now the *last* stop on the page rather than the fourth.

  On the edit page it appears for the first time, and it means deleting a Recipe you
  have unsaved edits to. Three mechanisms already cooperate to make that safe rather
  than one: the confirmation is mounted at the app root, `state/delete-recipe` leaves
  for the shelf when it deletes the page's own Recipe, and `show-page!` drops the draft
  on every page move. Three things lining up is exactly what stops being true quietly,
  so it is checked rather than assumed."
  [{:keys [id]}]
  [:div.recipe-page-delete
   [:button.secondary.danger {:on-click #(state/start-deleting id)} "Delete"]])

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

(defn- provenance-legend
  "The legend, over the body, while the source view is up.

  **It stayed when the toggle moved to the corner.** The toggle is a control about the
  page and belongs with the title; this explains the *tints*, so it belongs with the
  thing being tinted — a legend in a corner would be a sentence about something two
  paragraphs away.

  It renders only with the legend in it, and that is why the `when` is at the call
  sites rather than in here: `.recipe-page-body-tools` used to hold the toggle as well,
  so it was on the page whether the view was up or not. With only the legend left, a
  row that rendered unconditionally would be an empty one with the panel's spacing
  around it — the fifth time this run of work has met that, after music's card footer,
  this panel's top edge, the diff header's ✕ and the Publish-only row.

  The API's own string in both modes: it explains the *scale*, and the scale does not
  change because the text is unsaved."
  [legend]
  [:div.recipe-page-body-tools
   [:div.provenance-legend legend]])

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
  claim is that what you are looking at is what is stored — or, in edit mode, what you
  have typed.

  **`cautions` is handed in rather than derived here, because the two modes align
  differently.** The reading's lines *are* the lines the ranges index, so its caller
  passes `provenance/line-cautions`; the editor's are a draft the server has never
  seen, so its caller passes `provenance/draft-cautions`, which is where that rule is
  written down. This function renders one number per line and holds no opinion about
  where the numbers came from.

  It used to derive them, which guaranteed that the rows and the numbers came from one
  string. `nth` with a nil default is what replaces that guarantee: a `cautions` shorter
  than the body draws the tail **untold** rather than throwing, which is the same honest
  nothing every other unanswered line gets."
  [description cautions]
  (let [lines (provenance/split-lines description)]
    [:div.provenance-source
     (map-indexed (fn [i line]
                    ^{:key i} [source-line (inc i) line (nth cautions i nil)])
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

  **What the owner can *do* to the Recipe is split across three places, and none of
  them is under the header any more**: the ways of *looking* at it are in the top bar's
  left slot beside `← Shelf`; **Publish is in the bar's right-hand slot**, beside the
  theme toggle; and **Delete is at the bottom right**, after the body. So between the
  useful-when line and the body this function now draws no controls at all.

  **Publish under the header was an argument too, and it is rewritten here because it
  was overruled rather than mistaken.** It ran: under the header is where a control
  that *changes* the Recipe belongs — with the facts that say which Recipe it is, and a
  row of its own apart from the chrome. He asked for the corner instead — *In the Page
  view, put the Publish button in the top right, to the left of the dark mode
  switcher.* — and what the old paragraph had not reckoned with is that by then the row
  was down to **one** button: the two controls Publish used to read as a set with had
  already gone up into the bar, so what was being kept apart from the chrome was a
  single word in a row of one. A row of one is not a place. The corner it went to is
  the corner the provenance toggle was moved to for the neighbouring reason — a control
  level with the chrome is about the page, where one in the text column reads as being
  about the text — and what the panel keeps is the one control whose worst outcome is
  losing the Recipe.

  **Delete used to be beside it, and the argument for keeping it there is recorded here
  because it was overruled rather than mistaken.** It ran: a page's body is *not* a
  card's. A card's footer sits under a paragraph or two between twelve neighbours, while
  a page's body is the Recipe in full and has no length at all — so a footer under it
  puts Delete at the end of a scroll. He asked for the end of the scroll anyway — *on
  both edit and view pages the delete button goes to the bottom right* — and it is his
  design and the conventional one: bottom-right is where a destructive control lives
  **because** it is out of the reading path and reached deliberately. The cost is exactly
  what the old paragraph named, and it is now the thing being accepted rather than the
  thing being avoided: on a long Recipe, Delete is a scroll away. That is the trade, and
  a reader six months from now should find it here rather than a silent reversal.

  **The provenance toggle used to sit between them, above the body, and the argument for
  it is recorded here because it was half overruled.** It ran: Publish is what you can
  *do* to the Recipe and the toggle is how you want to *look* at it, and the doing is not
  a property of the text. **The distinction survives and is still why they are not one
  row** — what did not survive is the conclusion that the looking-at control therefore
  belongs over the body. He asked for the corner — *also it should be placed in the top
  right corner of that REcipe's space* — and the corner says the same thing more plainly:
  a control level with the title is about the page, where one over the body reads as
  being about the body. Only the **legend** stayed down there, with the tints it
  explains.

  The tab order comes out right without being arranged here, and both moves made it
  stronger: the bar precedes the panel in the document, so `← Shelf`, Edit and Versions
  are reached first and Publish after them at the bar's other end, and Delete is the
  **last** stop on the page rather than sitting beside Publish near the top.

  **The Scope picker sits between the header and the useful-when line**, where the
  card's header wears the badges it replaces — the filing is part of what says which
  Recipe this is, and it reads with the tags row above it, which is the other half of
  the same filing. It is above the actions row rather than among it because it is not
  one of them: those are things you ask for, and this one is already saved by the time
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
     [header recipe logged-in? (when offered? [provenance-toggle showing?])]
     (when logged-in?
       [scope-filing recipe])
     (when (seq (:useful_when recipe))
       [:div.recipe-page-useful-when [markdown/render-inline (:useful_when recipe)]])
     (when showing?
       [provenance-legend legend])
     (cond
       blank? [:div.card-body-empty "No body yet."]
       ;; The reading's lines *are* the lines the ranges index, so the alignment is the
       ;; identity one — see `source-view` for why the caller chooses it.
       showing? [source-view body (provenance/line-cautions
                                   ranges (count (provenance/split-lines body)))]
       :else [:div.recipe-page-body [markdown/render body]])
     (when logged-in?
       [delete-action recipe])]))

(defn- editor
  "The Recipe's four content fields — this page's other mode, at `?edit=true`, with
  **Save and Cancel in the top bar's left slot** rather than under the form.

  *instead of an edit modal, lets go to a separate page, with ?edit=true query param*,
  and then *when we go to edit, the save and cancel buttons should go where the back
  button sits and the back button should not be there.* So this draws the fields and
  nothing else: `core/left-slot` draws the two buttons, and both of them are
  `state/save-recipe-edit` and `state/cancel-recipe-edit` rather than anything this
  component owns.

  **Which is why the draft is in app-state.** It was four component-local `r/atom`s,
  which was right while Save was inside this markup and impossible the moment it left:
  a button in the bar cannot see a closure in a page. `state/recipe-edit-fields` is
  the draft resolved against the stored row and it says at length why the draft is a
  *diff* rather than a copy — the short version is that a copy needs seeding, seeding
  needs the row, and on a cold load at this address the row arrives after the
  navigation.

  These stay **controlled** inputs, now against app-state: every keystroke is a
  `swap!` and the value drawn is always what the state says. A keystroke is still not
  a save — the reading's Scope chips are this page's only control that saves per
  gesture.

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
  that a save must **omit `scope_ids` entirely** — *a field you leave out keeps its
  current value* — so a content save cannot disturb the filing. The modal sent the
  key on every save and had to, because it carried a picker whose set the owner might
  just have emptied on purpose; with the picker gone, sending it would be the bug and
  omitting it is the fix.

  **The provenance toggle is here too, and here it describes the draft.** *show
  provenance button should be avilable in both edit and view modes. and in edit modes
  it should reflect the volatile state.* So it swaps the textarea for the tinted source
  of what is in the editor, exactly as the reading swaps its rendered body — the same
  control, the same row, the same legend, and `provenance/draft-cautions` for the one
  thing that differs: which line of a draft may keep a stored line's number.

  **It is a look and not an edit**, so there is nothing to guard: while the source is up
  the textarea is not on the page, and you cannot type into a `div`. Turning it off
  brings the field back with the draft still in it, because the draft is in app-state
  and was never in the textarea.

  The toggle is keyed off `caution` being in the response, as the reading's is — so a
  Recipe the client has no split for offers the button in **neither** mode rather than
  in one. Blankness is read off the *draft*: a body you have just emptied has nothing to
  number, which is the same sentence the reading makes about a stored one."
  [recipe logged-in?]
  (let [{:keys [description] :as fields} (state/recipe-edit-fields)
        {:keys [legend ranges]} (:caution recipe)
        offered? (and (seq ranges) (not (str/blank? description)))
        showing? (and offered? (:showing-provenance? @state/*app-state))]
    [:<>
     ;; ⌘9 saves without leaving, from anywhere on the page — mounted here so the
     ;; chord exists exactly as long as the editor does, rather than living for the
     ;; app's whole life and asking the mode on every keypress. Draws nothing.
     [edit-keys/while-editing]
     [header recipe logged-in? (when offered? [provenance-toggle showing?])]
     ;; **The four inputs are `recipe-fields/edit-fields`' and not this file's**, since
     ;; the page a new Recipe is made on draws the same four — *a page which looks like
     ;; when we go from the recipe Page page to edit*. What stays here is what is this
     ;; mode's: the provenance source standing in for the body, and no `:on-enter`,
     ;; because Enter in the middle of correcting a Recipe must not save and navigate.
     [recipe-fields/edit-fields
      {:fields fields
       :on-change state/set-recipe-draft-field
       :body (when showing?
               [:<>
                [provenance-legend legend]
                ;; The **stored** body is what the ranges are about, so it is what the
                ;; draft is aligned against — `(:description recipe)` and not the
                ;; draft's own text.
                [source-view description
                 (provenance/draft-cautions (:description recipe) ranges description)]])}]
     ;; **The same place in this mode as in the reading**, which is the whole of *on
     ;; both edit and view pages*: a control that is somewhere else depending on which
     ;; mode you are in is a control you have to look for. Deleting a Recipe you have
     ;; unsaved edits to is the interaction that follows from it — `delete-action` says
     ;; which three mechanisms make it safe.
     [delete-action recipe]]))

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
                ;; because an empty panel under a bar offering nothing but the way out
                ;; is the one outcome this file exists to never produce. That reading
                ;; got sharper when the way out moved into the bar: the panel really
                ;; would be empty now, where before it at least had a button in it.
                [not-found])
       :missing [not-found]
       [:div.card-body-loading "Loading…"])]))
