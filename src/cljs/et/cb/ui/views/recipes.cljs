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
  with no `tags` key at all. The `logged-in?` gate on `card-tags` is cosmetic, and
  the comment there says why that distinction has to be kept.

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

  Publishing happens from the card, behind a confirmation, and only the owner
  sees the affordance. It is one way — the API has no unpublish — so a published
  card loses its Publish button and wears a badge instead.

  All three fields are markdown, but not the same markdown: the title and the
  useful-when line are rendered inline, so they cannot grow a heading or a list
  and break the card's layout, while the body gets the full parser and the code
  highlighting. See `et.cb.ui.markdown`."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.cb.ui.markdown :as markdown]
            [et.cb.ui.provenance :as provenance]
            [et.cb.ui.state :as state]
            [et.cb.ui.views.diff :as diff]))

(defn- day [timestamp]
  (when (seq (str timestamp))
    (first (str/split (str timestamp) #" "))))

(def ^:private tags-placeholder "Tags — extra words to find this by")

(defn- scope-picker
  "Which Scopes this Recipe is filed under, as a row of toggles over the owner's
  own list. Rendered as nothing at all when he has made no Scopes yet: an empty
  picker would be a control that cannot do anything, and the place to make one is
  the Scopes page.

  `selected` is a ratom holding a set of ids, so this component owns no state of
  its own — the form around it is what sends the set, and reading it back out of a
  child would be the same fact in two places."
  [selected]
  ;; Both derefs happen out here, before the `for`. A deref inside the body of a
  ;; lazy seq is evaluated after reagent has stopped watching, so the chips would
  ;; not repaint when one was clicked — and reagent says so at the console rather
  ;; than silently.
  (let [scopes (:scopes @state/*app-state)
        chosen @selected]
    (when (seq scopes)
      [:div.scope-picker
       [:span.scope-picker-label {:title "Categories this Recipe is filed under"}
        "Scopes"]
       (for [{:keys [id title description]} scopes]
         ^{:key id}
         [:button.scope-chip
          {:type "button"
           :class (when (contains? chosen id) "on")
           :title description
           :on-click #(swap! selected (fn [s] (if (contains? s id) (disj s id) (conj s id))))}
          title])])))

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
          {:type "text" :placeholder tags-placeholder
           :value @tags
           :on-change #(reset! tags (-> % .-target .-value))
           :on-key-down #(when (= (.-key %) "Enter") (submit))}]
         [:textarea.compose-description
          {:placeholder "The recipe itself"
           :rows 4
           :value @description
           :on-change #(reset! description (-> % .-target .-value))}]
         [scope-picker scope-ids]
         [:button {:on-click submit :disabled (str/blank? @title)} "Add"]]))))

(defn- edit-modal
  "Tags and Scopes sit in here with the three content fields even though a save
  that touches only them makes no version — the modal is where you edit a Recipe,
  and which of its fields the version ladder is about is the API's business. The
  subtitle says the version this is editing, and a filing-only save deliberately
  leaves that number where it is.

  The Scopes are prefilled from the Recipe's own `:scopes`, which came in with the
  body when the card was expanded. Sending them on every save is what makes the
  server's rule work for this client: an omitted `scope_ids` would keep the filing,
  and this form has a picker showing a set that the owner may just have emptied
  on purpose."
  [recipe]
  (let [title (r/atom (or (:title recipe) ""))
        useful-when (r/atom (or (:useful_when recipe) ""))
        tags (r/atom (or (:tags recipe) ""))
        description (r/atom (or (:description recipe) ""))
        scope-ids (r/atom (set (map :id (:scopes recipe))))]
    (fn [recipe]
      [:div.modal-backdrop {:on-click state/stop-editing}
       [:div.modal {:on-click #(.stopPropagation %)}
        [:h2 "Edit"]
        [:div.modal-subtitle (str "version " (:version recipe))]
        [:input {:type "text" :placeholder "Title"
                 :value @title
                 :on-change #(reset! title (-> % .-target .-value))}]
        [:input {:type "text" :placeholder "Useful when…"
                 :value @useful-when
                 :on-change #(reset! useful-when (-> % .-target .-value))}]
        [:input.modal-tags {:type "text" :placeholder tags-placeholder
                            :value @tags
                            :on-change #(reset! tags (-> % .-target .-value))}]
        [:textarea.modal-description
         {:placeholder "The recipe itself"
          :rows 8
          :value @description
          :on-change #(reset! description (-> % .-target .-value))}]
        [scope-picker scope-ids]
        [:div.modal-actions
         [:button {:disabled (str/blank? @title)
                   :on-click #(state/update-recipe (:id recipe)
                                                   {:title @title
                                                    :useful_when @useful-when
                                                    :tags @tags
                                                    :description @description
                                                    :scope_ids (vec @scope-ids)}
                                                   state/stop-editing)}
          "Save"]
         [:button.secondary {:on-click state/stop-editing} "Cancel"]]]])))

(defn- publish-modal
  "The latch is one-way: nothing in the API takes it back off, so this asks
  before it fires rather than offering an undo afterwards.

  The confirm button goes dead on the first click. Only the response callback
  closes this dialog — that is deliberate, so a failed publish can put its error
  banner somewhere reachable — which leaves the button live for the whole round
  trip unless something takes it out. Two quick clicks would otherwise send two
  POSTs, and the second one loses a write race server-side: the card would gain
  its published badge at the same moment the banner said the publish failed."
  [_recipe]
  (let [sending? (r/atom false)]
    (fn [{:keys [id title]}]
      [:div.modal-backdrop {:on-click state/stop-publishing}
       [:div.modal {:on-click #(.stopPropagation %)}
        [:h2 "Publish this recipe?"]
        [:div.modal-subtitle title]
        [:p.modal-note
         "It becomes readable by anyone who opens Cookbook, and you have put your
          name to it. There is no unpublish."]
        [:div.modal-actions
         [:button.publish-confirm
          {:disabled @sending?
           :on-click #(do (reset! sending? true)
                          (state/publish-recipe id state/stop-publishing))}
          (if @sending? "Publishing…" "Publish")]
         [:button.secondary {:on-click state/stop-publishing} "Cancel"]]]])))

(defn- delete-modal
  "Deleting takes the recipe and every version of it, and no route puts any of
  it back — so this asks first, the same way publishing does.

  The confirm button goes dead on the first click, and here the latch matters
  more than it does for publishing. Only the response callback closes this
  dialog, so two quick clicks would send two DELETEs: the first succeeds and
  the second 404s, raising 'Could not delete' over a delete that in fact went
  through."
  [_recipe]
  (let [sending? (r/atom false)]
    (fn [{:keys [id title version]}]
      [:div.modal-backdrop {:on-click state/stop-deleting}
       [:div.modal {:on-click #(.stopPropagation %)}
        [:h2 "Delete this recipe?"]
        [:div.modal-subtitle title]
        [:p.modal-note
         (if (= 1 version)
           "Its one version goes with it, and there is no undo."
           (str "All " version " versions go with it, and there is no undo."))]
        [:div.modal-actions
         [:button.delete-confirm.danger
          {:disabled @sending?
           :on-click #(do (reset! sending? true)
                          (state/delete-recipe id state/stop-deleting))}
          (if @sending? "Deleting…" "Delete")]
         [:button.secondary {:on-click state/stop-deleting} "Cancel"]]]])))

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

(def ^:private source-badge-title
  "Spelled out because the two words decide who to trust for a Recipe's text, which
  a bare `3(machine)/17(ui)` does not say. The sentence itself comes from
  `et.cb.ui.provenance`, so this badge and the version viewer's label cannot end up
  naming the same fact differently."
  (str "Where this Recipe's versions came from — " provenance/explanation))

(defn- source-split
  "`3(machine)/17(ui)`, and only the buckets that have something in them: a
  Recipe nothing has written by machine says `17(ui)` rather than carrying a `0`
  around. Both empty cannot happen — the counts sum to the version number, so
  there is always at least one — but a listing row from an older server would have
  no counts at all, and that renders as nothing rather than as `0(ui)`.

  **Two buckets, since migration 010.** There was a third, for versions whose
  origin nothing had recorded; the owner said those were his, 010 wrote it down, and
  the column cannot hold a third value any more. A row from a server older than that
  would still carry `unrecorded_versions`, and this ignores it rather than showing a
  bucket the app no longer has a word for.

  The bucket names are the shared ones, for the reason above the tooltip."
  [{:keys [machine_versions ui_versions]}]
  (let [buckets (->> [[machine_versions provenance/machine-label]
                      [ui_versions provenance/ui-label]]
                     (filter (fn [[n _]] (and (number? n) (pos? n))))
                     (map (fn [[n label]] (str n "(" label ")"))))]
    (when (seq buckets)
      [:span.source-badge {:title source-badge-title} (str/join "/" buckets)])))

(def ^:private views-badge-title
  "One sentence, and it has to carry three things a bare number does not: that a
  *listing* is not a read, that everybody's reads are in there and not only the
  owner's, and that the count starts where migration 008 does — a Recipe written
  last year and read a hundred times says 0 until somebody opens it again.

  Kept here rather than in `et.cb.ui.provenance`, and that is a judgement call
  worth stating: that namespace exists because *two* surfaces named the same fact
  and drifted. This fact has one surface. The API's own wording of it lives in
  `recipe-handler/get-recipe-handler`'s docstring, which is not a second copy but
  a different medium — an agent reads that one out of /api/describe. The day a
  second view in here shows the count, this string moves next to the provenance
  labels rather than being spelled out twice."
  (str "How often this Recipe was actually read — its text fetched in full, here "
       "or through the API, by anyone — never counting a listing, and only since "
       "cookbook started counting"))

(defn- views-badge
  "`12 reads`, beside the version pair, because it is the same kind of fact: a
  count the server keeps about this Recipe, on the one line that says what the
  Recipe is.

  **A `0` is shown**, unlike an empty bucket in `source-split`. There the zero is
  a non-fact — nothing wrote a machine version, so saying `0(machine)` would only
  add noise — while here it is the reading itself: nobody has opened this since
  the count began, which is exactly what the ranking below acts on. What is *not*
  shown is a missing key, which is what a listing row from an older server would
  carry, and that renders as nothing rather than as `0 reads`."
  [view-count]
  (when (number? view-count)
    [:span.views-badge {:title views-badge-title}
     (str view-count (if (= 1 view-count) " read" " reads"))]))

(def ^:private pending-badge-title
  "Three things a one-word badge does not say: what is waiting, that the Recipe
  still reads as it always did, and where to go about it. The last one is the
  point — this badge is not a control, so it has to name the page that is."
  (str "An agent proposes to rewrite this Recipe and is waiting for you. Nothing "
       "here has changed yet — approve or dismiss it in the Inbox"))

(defn- pending-badge
  "That a proposal is waiting on this Recipe, on the collapsed card.

  **This is why `pending` is on a lean listing row at all.** The flag was put
  there so the shelf could show it, and until now only the reads had it: a
  collapsed card is exactly the place that cannot go and fetch a proposal, so
  without the flag the shelf could not say that one was queued.

  **It is a badge and not a control.** Approving or dismissing happens in the
  Inbox, where the agent's text can be read against the Recipe's own — a decision
  nobody should make from a word on a card — so nothing here is clickable and the
  tooltip says where to go instead.

  **The `logged-in?` gate at the call site is cosmetic and must not be read as the
  boundary**, the same distinction `card-tags` draws: a visitor's projection does
  not name the column, so a signed-out client is not holding a `pending` it has
  been asked not to draw — there is no key in what it was sent. Deleting the gate
  would show a visitor nothing extra; deleting the server half would tell strangers
  which of the owner's Recipes an agent is queued to rewrite."
  []
  [:span.pending-badge {:title pending-badge-title} "proposal"])

(defn- card-tags
  "The owner's extra search words, on his own card.

  **This gate is cosmetic and must not be read as the privacy boundary.** The
  boundary is the server: a visitor's projection does not name the `tags` column,
  so a signed-out client is not holding tags it has been asked not to draw — there
  is no `tags` key in what it was sent, and `logged-in?` here would be redundant if
  the client could be trusted, which is exactly why it is not the mechanism.
  Do not 'simplify' `select-columns` on the grounds that this hides them; deleting
  this line would show nothing extra, and deleting the server half would publish
  the owner's filing.

  Rendered as plain text rather than through the markdown renderer the other
  fields use: these are search words, not prose, and a stray `_` in one is a
  character and not emphasis — the same reading the search itself gives it."
  [tags]
  [:div.card-tags {:title "Extra words this Recipe can be found by — yours alone"}
   tags])

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
  — and 'which shelf is this on' is that same question. The description rides along
  as the tooltip, which is the only place a reader meets it outside the Scopes page.

  **This gate is cosmetic and must not be read as the privacy boundary**, exactly
  as with `card-tags`. The boundary is the server: for a visitor the join is not run
  at all, so a signed-out client holds no `scopes` key to draw and `logged-in?` here
  would be redundant if the client could be trusted — which is precisely why it is
  not the mechanism. Do not 'simplify' `db.recipe/with-scopes` on the grounds that
  this hides them; deleting this line would show a signed-out reader nothing extra,
  and deleting the server half would publish the owner's filing.

  The badges are wrapped in an element rather than returned as a bare seq. A
  component whose return value *is* a seq is handed to React as a fragment whose
  children are the raw hiccup vectors — and a cljs vector is iterable, so React
  walks into one and tries to render `:span.scope-badge`, the keyword, as a child.
  A seq of children inside a hiccup vector is the shape reagent converts, and it is
  what every other `for` in this file does."
  [scopes]
  [:span.card-scopes
   (for [{:keys [id title description]} scopes]
     ^{:key id}
     [:span.scope-badge {:title (str (if (str/blank? description)
                                       "A Scope this Recipe is filed under"
                                       description)
                                     " — " scope-badge-hint)
                         :on-click #(exclude-on-shift id %)}
      title])])

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
        [:span.published-badge {:title (str "Published " (day published_at)
                                            " — public, and one way")}
         "published"])
      ;; Next to the latch rather than next to the counts: both are states the
      ;; Recipe is *in* — one settled and one waiting — where the version, the
      ;; provenance split and the reads are all numbers about its past. And a
      ;; Recipe can wear both, which is not a contradiction: a machine may propose
      ;; against a published Recipe, and what a visitor sees stays the approved
      ;; version until he says otherwise.
      (when (and logged-in? pending?)
        [pending-badge])
      (when (and logged-in? (seq scopes))
        [card-scopes scopes])
      [:span.version-badge {:title "Every edit makes a new version"} (str "v" version)]
      [source-split recipe]
      ;; Not gated on `logged-in?`, for the same reason the version badge is not:
      ;; it is a fact about the Recipe rather than about the owner's filing, and
      ;; the server puts it in the visitor's projection deliberately. It also
      ;; explains the order of the shelf a visitor is looking at.
      [views-badge view_count]
      [:span.card-date (day modified_at)]]
     (when (seq useful_when)
       [:div.card-useful-when [markdown/render-inline useful_when]])
     (when (and logged-in? (seq tags))
       [card-tags tags])
     (when expanded?
       [card-body (get details id)])
     (when logged-in?
       [:div.card-footer
        [:span.card-actions
         (when-not published?
           [:button.secondary {:on-click #(state/start-publishing id)} "Publish"])
         [:button.secondary {:on-click #(state/start-editing id)} "Edit"]
         ;; Named for what it shows rather than for the merge view inside it: a
         ;; one-version Recipe has nothing to diff and this still answers the
         ;; question, which is what the `v1` badge next to the title is pointing
         ;; at. Owner-only here because the whole footer is, and owner-only at
         ;; the API too — a visitor gets a 404 from /versions for every id.
         [:button.secondary
          {:on-click #(state/start-diff id)
           :title "Step through every version and see what each save changed"}
          "Versions"]
         [:button.secondary.danger {:on-click #(state/start-deleting id)} "Delete"]]])]))

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

(defn recipes-tab []
  (let [{:keys [recipes search human-only? excluded-scopes logged-in? open details editing
                publishing deleting diffing]}
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
         [card recipe {:logged-in? logged-in? :open open :details details}]))
     ;; Outside the cards: a card's backdrop-filter would make it the containing
     ;; block for the modal's fixed positioning, pinning the modal to that one
     ;; card instead of to the viewport.
     (when-let [recipe (get details editing)]
       [edit-modal recipe])
     ;; The confirmation only needs the two short fields, which the listing
     ;; already carries — unlike the Edit modal it never has to fetch a body.
     (when-let [recipe (first (filter #(= publishing (:id %)) recipes))]
       [publish-modal recipe])
     ;; Same again: the question needs the title and the version count, both of
     ;; which the listing carries, so this one never fetches a body either.
     (when-let [recipe (first (filter #(= deleting (:id %)) recipes))]
       [delete-modal recipe])
     ;; And out here for the same reason, more so: the version viewer is
     ;; full-screen, so a card's backdrop-filter becoming its containing block
     ;; would pin a supposedly full-screen overlay to the inside of one card.
     (when diffing
       [diff/component])]))
