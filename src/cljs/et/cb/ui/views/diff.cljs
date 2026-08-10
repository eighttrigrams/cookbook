(ns et.cb.ui.views.diff
  "The viewer: two texts side by side on a surface of their own, read-only.

  **One surface, two readings.** A step of a Recipe's history — the older
  description on the left of a codemirror merge view and the newer on the right, or,
  where there is no older, that one version on its own. Or a **proposal**: what the
  Recipe says now against what an agent wants to make of it, with the two answers in
  the header.

  The second reading used to be an inline pane under its queue row, and he said what
  was wrong with it: *Proposals, just like the other changes, should be shown on a
  different page (note the difference in treatment)*. A row whose comparison lives
  inside the list is a row that shoulders every entry after it off the screen, and it
  crops the very thing it exists to show — the panes were capped at 320px, so a
  change beginning in the second paragraph of a long body was past the fold on a
  page whose whole job was to show it.

  So both readings are drawn by `shell` and neither draws its own overlay. That is
  the load-bearing part: a second surface that merely *resembled* this one is how the
  two would drift, and the words are the only thing that differs between them —
  headings, a label, and which buttons are in the header.

  Modelled on rhizome's `ui.main.diff`, which this follows down to the ✕ / ← / →
  header and the Split/Unified toggle. Three places it deliberately does **not**:

  **Every version is a step.** Rhizome collapses runs of versions whose text is
  identical, because there a run of them means a pure title change with nothing to
  diff. Cookbook must not: the card carries a version *count*, so a viewer that
  silently skipped some would contradict a number the reader is looking at, with
  no way to tell which of the two was lying. The consequence is then handled
  rather than hidden — two adjacent versions really can share a description (a
  title-only or useful-when-only save), so both sides' `title` and `useful_when`
  are on show above the pane, and a note says when the body is what did not
  change. An empty diff pane on its own reads as broken.

  **A first version is shown, not described.** Rhizome answers a version with
  nothing behind it with `[:p \"No previous version to compare against.\"]`, and
  this said the same sentence until it was read by somebody who had come for the
  text: a Recipe an agent has just written is on v1, so a `created` row in the
  Inbox opened the viewer onto that sentence and nothing else — *i have no chance
  to see the contents of a new thing*. The sentence was true and it was the whole
  page. So the nothing-older case renders the version instead: the same metadata
  strip with nothing marked, and the body in a plain read-only `EditorView`. Not a
  merge view handed a stand-in for the side that does not exist — the same document
  twice draws two panes with nothing marked, which is the failure the paragraph
  above is about, and an empty `original` marks every line as an insertion, which
  says this version replaced something. It is that paragraph's argument reaching
  the one case it had not.

  **The source suffix is always there.** Rhizome appends ` · source` only when
  there is one; here there always is one — since migration 010 a version is `ui` or
  `machine` and nothing else — so the label is unconditional rather than a value
  that might be missing. It used to be unconditional for a subtler reason: nil was
  a third category, and dropping the suffix for it would have read exactly like a
  bug. The category is gone and the habit was right anyway.

  `description` is the field being diffed: the only one long enough to warrant a
  merge view, and the one that already goes through the full markdown parser.

  The overlay is full-screen and is mounted at the app root rather than inside a
  page, with the Recipe modals and for their reasons — see
  `et.cb.ui.views.recipe-modals`. Being full-screen is a claim about the **mouse**
  only, so what is behind it is `inert` while it is up: see `inert-behind!`, which is
  the whole of why this surface is a dialog rather than a panel that happens to cover
  everything."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [et.cb.ui.provenance :as provenance]
            [et.cb.ui.state :as state]
            ["@codemirror/merge" :refer [MergeView unifiedMergeView]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/view" :refer [EditorView]]
            ["@codemirror/lang-markdown" :refer [markdown]]))

;; ---------------------------------------------------------------------------
;; the editor

(defn- css-var
  "One colour out of the live stylesheet. Rhizome's theme hardcodes `wheat`;
  cookbook has a dark mode, and reading the palette back out of CSS is what keeps
  the editor in both themes without a second copy of the palette to maintain here.
  Read at mount time, when `html.dark-mode` is already on or off — which is also
  why the call site's `:key` counts `:dark-mode`."
  [name]
  (-> (js/getComputedStyle (.-documentElement js/document))
      (.getPropertyValue name)
      str
      str/trim))

(defn- editor-theme
  [dark?]
  (.theme EditorView
          #js {"&" #js {:backgroundColor (css-var "--glass-bg-subtle")
                        :color (css-var "--text-primary")}
               ".cm-content" #js {:caretColor (css-var "--text-primary")}
               ".cm-gutters" #js {:backgroundColor (css-var "--surface-subtle")
                                  :color (css-var "--text-secondary")
                                  :border "none"}}
          ;; Not cosmetic: @codemirror/merge picks its inserted- and
          ;; deleted-chunk colours off this flag, and the light ones are
          ;; unreadable over cookbook's dark palette.
          #js {:dark dark?}))

(defn- base-extensions
  "The viewer is read-only in both senses — no editing gesture is accepted, and
  nothing here can write: there is no save path out of this component at all."
  [dark?]
  [(markdown)
   (.-lineWrapping EditorView)
   (editor-theme dark?)
   (.of (.-editable EditorView) false)
   (.of (.-readOnly EditorState) true)])

(defn- mount-diff!
  [el older newer unified? dark?]
  (if unified?
    (EditorView. #js {:doc newer
                      :extensions (into-array (conj (base-extensions dark?)
                                                    (unifiedMergeView #js {:original older
                                                                           :mergeControls false})))
                      :parent el})
    (MergeView. #js {:a #js {:doc older :extensions (into-array (base-extensions dark?))}
                     :b #js {:doc newer :extensions (into-array (base-extensions dark?))}
                     :parent el})))

(defn- mount-version!
  "One version on its own, in the same read-only editor each side of a merge view
  is. A third mount rather than one of the two above handed something to stand in
  for the missing side — see the ns docstring for why neither stand-in is honest."
  [el doc dark?]
  (EditorView. #js {:doc doc
                    :extensions (into-array (base-extensions dark?))
                    :parent el}))

(defn- diff-editor
  "Mounts on attach and destroys on detach. Nothing mutates a live view: the call
  site keys this on everything it was built from, so a step, a mode flip or a
  theme change replaces the editor instead of trying to reconfigure it."
  [_older _newer _unified? _dark?]
  (let [*view (atom nil)]
    (fn [older newer unified? dark?]
      [:div.diff-editor
       {:ref (fn [el]
               (if el
                 (reset! *view (mount-diff! el (or older "") (or newer "") unified? dark?))
                 (when-let [view @*view] (.destroy view) (reset! *view nil))))}])))

(defn- version-editor
  "`diff-editor`'s contract with one document instead of two: mounted on attach,
  destroyed on detach, never reconfigured, and keyed at the call site on the text
  and the theme it was built from."
  [_doc _dark?]
  (let [*view (atom nil)]
    (fn [doc dark?]
      [:div.diff-editor
       {:ref (fn [el]
               (if el
                 (reset! *view (mount-version! el (or doc "") dark?))
                 (when-let [view @*view] (.destroy view) (reset! *view nil))))}])))

;; ---------------------------------------------------------------------------
;; the two sides

(defn- version-label
  [{:keys [version source]}]
  (str "Version " version " · " (provenance/label source)))

(defn- step-label
  "`Version 1 · ui → Version 2 · machine (current)`. With one version there is no step,
  so it names the single version rather than going blank — rhizome renders nothing
  there, and nothing is what a reader cannot tell from a failed fetch."
  [older newer current-step?]
  (cond
    (and older newer) (str (version-label older) " → " (version-label newer)
                           (when current-step? " (current)"))
    newer (str (version-label newer) " (current)")
    :else nil))

(defn- when-label
  "The one timestamp `/versions` carries means **two different things** depending
  on which entry it sits on, so it is named rather than left bare.

  For the current version it is the row's `modified_at`: when that version was
  written. For a superseded one it is its `recipe_history` row's `created_at`,
  which takes sqlite's `datetime('now')` default at the moment `archive!` inserts
  it — and archiving happens when the *next* save displaces it. So it is when that
  version stopped being current, and no column anywhere records when it was
  written. Calling both of them the same thing would read as a straight
  contradiction on a title-only save, where the two columns show the same second
  under the same word."
  [{:keys [current created_at]}]
  (str (if current "Written " "Replaced ") created_at))

(defn- meta-row
  [key-label value changed?]
  [:div.diff-meta-row {:class (when changed? "changed")}
   [:span.diff-meta-key key-label]
   (if (str/blank? (str value))
     [:span.diff-meta-empty "empty"]
     ;; The stored text, not markdown-rendered. This is a view of what a version
     ;; *says*, and running the inline parser here would hide differences that
     ;; only exist in the source — a title gaining backticks renders as the same
     ;; words.
     [:span.diff-meta-value (str value)])])

(defn- side
  "One column of the metadata strip, from a heading, a subheading and the two short
  fields. `other` is what is on the far side, so a field that differs is marked on
  both — the reader is comparing, and a mark on only one column would make them hunt
  for what it replaced.

  It takes its two labels rather than deriving them, because there are two things
  being compared in this app now: two versions of a Recipe, and a Recipe against a
  proposal that is not a version at all. The layout is the same and the words are
  not."
  [{:keys [heading sub] :as entry} other]
  [:div.diff-meta-side
   [:div.diff-meta-version heading]
   [:div.diff-meta-when sub]
   [meta-row "Title" (:title entry) (not= (:title entry) (:title other))]
   [meta-row "Useful when" (:useful_when entry) (not= (:useful_when entry) (:useful_when other))]])

(defn- as-side
  "One entry of a version list, with the two labels this comparison wants on it. The
  history reading's headings; `proposal-sides` supplies its own, because a proposal is
  not a version and calling it one would be the wrong word in the one place a reader is
  deciding whether to accept it."
  [entry]
  (assoc entry :heading (version-label entry) :sub (when-label entry)))

(defn- body-unchanged-note
  "Said out loud, because the alternative is a merge view showing no changes,
  which is indistinguishable from a broken diff. Reachable two ways: this viewer
  walks every version instead of collapsing the runs rhizome collapses, and a
  proposal can propose a title without touching the body."
  [older newer]
  (when (= (:description older) (:description newer))
    [:p.diff-note
     (if (or (not= (:title older) (:title newer))
             (not= (:useful_when older) (:useful_when newer)))
       "The body is not what changed here — what did is above."
       "These two are identical in all three fields. No save through the API
        produces that between versions, and a proposal that changed nothing would
        have been refused as a no-op.")]))

;; ---------------------------------------------------------------------------

(defn- pane
  "The comparison itself: the two metadata columns, the note about an unchanged body,
  and the merge view under them. Everything the viewer draws below its header, in
  either reading.

  The two sides are maps of `{:heading :sub :title :useful_when :description}` —
  `left` is the older or current text, `right` the newer or proposed one, which is the
  order both readings want.

  It used to be public, so that the Inbox could show a proposal without a second diff
  being written. That was half a solution: the panes were shared and the surface
  around them was not, which is how the two came to be *pages* rather than one. Both
  readings are in here now, so this is private again.

  `key?` is not a parameter: the caller keys this on everything it was built from, the
  way both readings do, because nothing here reconfigures a live editor."
  [left right unified? dark?]
  [:<>
   [:div.diff-meta
    [side left right]
    [side right left]]
   [body-unchanged-note left right]
   ^{:key (str (boolean unified?) "-" (boolean dark?) "-" (hash [(:description left)
                                                                 (:description right)]))}
   [diff-editor (:description left) (:description right) unified? dark?]])

(defn- version-pane
  "One version, with nothing behind it to compare against: the metadata strip and
  the body, in the same two pieces `pane` is made of.

  `side` marks the fields that differ from the far column, and here there is no far
  column. It is handed the entry as its own `other`, which marks nothing — the
  truth, since nothing differs. nil there would mark every field, which reads as
  each one having replaced something.

  Keyed on the theme like `pane`'s editor and for the same reason: the palette is
  sampled out of the live stylesheet at mount, so a theme flip has to build a new
  view rather than reconfigure the one on screen."
  [entry dark?]
  [:<>
   [:div.diff-meta.single
    [side entry entry]]
   ^{:key (str (boolean dark?) "-" (hash (:description entry)))}
   [version-editor (:description entry) dark?]])

;; ---------------------------------------------------------------------------
;; a proposal
;;
;; The other thing two texts can be here: not two versions of a Recipe, but the
;; Recipe as it reads now against the version an agent wants to write. Both texts
;; arrive on the inbox entry — see `db.proposal/attach-to-events` — so this reading
;; fetches nothing and opens on the click.

(defn- staleness-note
  "Said in words, before the click, when the proposal was written against an older
  version than the Recipe now has.

  **The one thing about approving that cannot be left to be discovered afterwards.**
  The diff below it is against the Recipe as it reads *now*, so approving really does
  replace his newer text with the agent's — `base_version` is not a guard, and the
  API will not refuse it. That is his call to make, which is exactly why it has to be
  visible while he makes it."
  [{:keys [base_version recipe_version]}]
  (when (and base_version recipe_version (< base_version recipe_version))
    [:p.proposal-note
     (str "Proposed against version " base_version ", and this Recipe is on version "
          recipe_version " — you have saved it since. The comparison below is against "
          "your current text, so approving replaces it with the agent's.")]))

(defn- published-note
  "Said in words, before the click, when the Recipe is published.

  An agent may propose against a published Recipe — the owner's call, *its up to the
  human to approve or not* — so this button is the only thing standing between an
  agent's wording and text that is already public and that he has put his name to.
  There is no unpublish. That is a decision worth making deliberately rather than
  discovering afterwards, which is the same argument `staleness-note` makes about a
  base version, one door along.

  What it does **not** say, because it is not true: nothing about a visitor's view
  changes while this sits here. They are served the last approved version and never the
  proposal, published or not."
  [{:keys [recipe_published]}]
  (when (= 1 recipe_published)
    [:p.proposal-note
     "This Recipe is published — it is public, and publishing put your name to it.
      Approving replaces that public text with the agent's wording, and there is no
      unpublish. Until you do, a reader still sees the version you approved."]))

(defn- proposal-sides
  "The two columns of a proposal reading, current on the left and proposed on the
  right — which is the order both readings of `pane` want: what there is, then what
  it would become.

  `side` takes its two labels rather than deriving them precisely so this can say
  what it is. A proposal is not a version, and `as-side`'s `Version 4 · machine`
  would be the wrong word in the one place a reader is deciding whether to let it
  become one."
  [proposal]
  [{:heading "This Recipe now"
    :sub (str "Version " (:recipe_version proposal))
    :title (:current_title proposal)
    :useful_when (:current_useful_when proposal)
    :description (:current_description proposal)}
   {:heading "Proposed by an agent"
    :sub (str "Against version " (:base_version proposal)
              (when (:modified_at proposal)
                (str " · last revised " (:modified_at proposal))))
    :title (:title proposal)
    :useful_when (:useful_when proposal)
    :description (:description proposal)}])

(defn- proposal-actions
  "The two answers, in the header of the surface he read them on.

  **They are here as well as on the queue row, and both are wanted.** The row's job
  is triage — a proposal he already recognises should not cost a round trip through a
  page to accept — and this one's is deciding having read, which is a decision that
  must not send him somewhere else to make. `state/resolve-proposal` is where the two
  entry points become one resolution: it closes this viewer whichever button was
  pressed, so an answered proposal is never left on screen.

  Approve goes dead on the first click, like every confirm button in this app and for
  the same reason: only the response closes this out, so two quick clicks would send
  two POSTs and the second would 409 over a decision that in fact went through.
  Dismiss opens the confirmation instead, because the agent's text is not served
  anywhere afterwards — the same modal the row opens, which is why it renders above
  this surface rather than under it."
  [_entry]
  (let [sending? (r/atom false)]
    (fn [{:keys [id]}]
      [:<>
       [:button.proposal-approve
        {:disabled @sending?
         :on-click #(do (reset! sending? true) (state/approve-proposal id nil))}
        (if @sending? "Approving…" "Approve")]
       [:button.secondary.danger.proposal-dismiss
        {:on-click #(state/start-dismissing-proposal id)} "Dismiss"]])))

;; ---------------------------------------------------------------------------
;; the surface, and what it has to take out of the tab order

(def ^:private inert-attr
  "Our own mark on what this surface made inert, so that releasing clears exactly
  that set and nothing a future overlay may have inerted for its own reasons."
  "data-inert-behind-viewer")

(defn- inert-behind!
  "Everything this surface is drawn over, taken out of the tab order.

  `position: fixed; inset: 0` stops the **mouse** and says nothing at all to the
  keyboard. Every button under here kept its tab stop, its focus ring and its Enter,
  and the DOM order puts the page before the overlay — so one Tab after opening a
  proposal to read it landed on the *row's* Approve, painted over by this surface with
  the ring invisible, one Enter from writing an agent's wording into a Recipe. Approve
  is the one write in this app with nothing in front of it, and `Versions` from a shelf
  card had the same hole with Delete as the next stop.

  **`inert` on what is behind, rather than a Tab handler on what is in front.** With
  the rest of the page inert the only focusable controls in the document are this
  surface's own, so wrapping round at the last one is the browser's job and there is no
  key handler of ours to have a hole in — the same argument `open-viewer!` makes about
  one `assoc` instead of two. It also takes those controls out of the accessibility
  tree, which `aria-modal` beside it only claims.

  Walks up to `body` and inerts the siblings at each level, because the overlay is
  rendered as a sibling of the page it covers — `core/app` mounts it beside
  `page-body`, next to the top bar and the error banner — and there is no one
  container holding everything else. Walking rather than inerting one known parent is
  also what made the move out of the pages a non-event here: this reads the tree it
  is actually in.

  **`.modal-backdrop` is skipped**, and that is the stylesheet's z-index argument in
  focus terms: the dismiss confirmation is opened from this surface's own header and
  renders at 30 over this 25, so it is not behind anything and must keep its buttons."
  [overlay-el]
  (loop [el overlay-el]
    (when-let [parent (.-parentElement el)]
      (doseq [sib (array-seq (.-children parent))]
        (when (and (not (identical? sib el))
                   (not (.-inert sib))
                   (not (.matches sib ".modal-backdrop")))
          (set! (.-inert sib) true)
          (.setAttribute sib inert-attr "")))
      (when-not (identical? parent (.-body js/document))
        (recur parent)))))

(defn- release-behind! []
  (doseq [el (array-seq (.querySelectorAll js/document (str "[" inert-attr "]")))]
    (set! (.-inert el) false)
    (.removeAttribute el inert-attr)))

(defn- surface-ref
  "Mount and unmount of the trap, as one closure per `shell` so that React calls it
  on attach and detach and not on every render — a `:ref` rebuilt each render is
  detached and reattached each time, which here would re-take focus while the reader
  is tabbing through the header.

  Focus goes to `.diff-page` and not to a button: the ✕ and Approve are both one
  Enter from doing something, and a reader who has just opened a page to read it has
  read nothing yet. It has `tabindex=\"-1\"` for that and for nothing else, so the
  first Tab is the ✕.

  The opener is read **before** anything is inerted, because inerting an ancestor of
  the focused element blurs it, and restored on the way out only if it is still in the
  document — the row a proposal was opened from is gone once it has been answered."
  [*opener]
  (fn [el]
    (if el
      (do (reset! *opener (.-activeElement js/document))
          (inert-behind! el)
          (some-> (.querySelector el ".diff-page") (.focus)))
      (let [opener @*opener]
        (release-behind!)
        (reset! *opener nil)
        (when (and opener (.-isConnected opener))
          (.focus opener))))))

(defn- shell
  "The surface: the overlay, the page, the header, and whatever the reading puts
  under it.

  **There is one of these and there are two readings**, which is the whole shape of
  this namespace. The chrome, the ✕, the Split/Unified toggle and the dark-mode
  wiring are written once, so a proposal cannot come to be read on a page that merely
  looks like the version viewer. What a reading supplies is words — a `heading`, the
  `subject` it is about, a `label` — and, in the two places where the readings really
  do differ, hiccup: `nav` for the ← → a history can be stepped through, and
  `actions` for the buttons a proposal can be answered with. A reading that has
  neither passes nil, and nothing is rendered where they would be.

  `toggle-disabled?` rather than a toggle each: there is no merge view to lay out
  either way on a Recipe's first version, and that is the only case.

  **A dialog and not a panel**, which is three attributes and the `:ref` above:
  `role`, `aria-modal` and a name for assistive technology, and `inert` on everything
  behind so that the keyboard cannot leave a surface the mouse cannot. Form-2 for the
  one reason — the ref closure has to outlive a render."
  [_opts _body]
  (let [ref (surface-ref (atom nil))]
    (fn [{:keys [heading subject label label-title nav unified? toggle-disabled? actions]} body]
      [:div.diff-overlay
       {:ref ref
        :role "dialog"
        :aria-modal true
        :aria-label (str heading (when (seq (str subject)) (str " — " subject)))}
       [:div.diff-page {:tab-index -1}
        [:div.diff-header
         [:button.diff-close {:on-click state/stop-diff :title "Close"} "✕"]
         [:h2 heading]
         [:span.diff-recipe-title subject]
         nav
         [:span.diff-version-label {:title label-title} label]
         [:button.diff-mode-toggle
          ;; Dead where there is no merge view to lay out either way, rather than
          ;; live and doing nothing: the ← and → next to it go grey for the same
          ;; reason.
          {:on-click state/toggle-diff-unified :disabled toggle-disabled?}
          (if unified? "Split" "Unified")]
         actions]
        body]])))

(defn- version-reading
  "A step of one Recipe's history. `:diff-version-idx` steps the list, which arrives
  newest-first: index 0 is the step into today's text, so ← walks backwards in time
  and → forwards, as in rhizome."
  [recipe-id]
  (let [{:keys [diff-version-idx diff-unified? dark-mode recipes versions]}
        @state/*app-state
        recipe (first (filter #(= recipe-id (:id %)) recipes))
        entries (get versions recipe-id)
        total (count entries)
        ;; Two adjacent versions make one step, so N versions are N-1 steps —
        ;; and a single version is step 0 with nothing older, which is the
        ;; fallback rather than an empty pane.
        max-idx (max 0 (- total 2))
        idx (max 0 (min (or diff-version-idx 0) max-idx))
        newer (nth entries idx nil)
        older (nth entries (inc idx) nil)]
    [shell
     {:heading "Versions"
      :subject (str (:title recipe)
                    (when (pos? total)
                      (str " · " total (if (= 1 total) " version" " versions"))))
      :nav [:<>
            [:button.diff-step
             {:on-click #(state/step-diff 1)
              :disabled (>= idx max-idx)
              :title "Older"} "←"]
            [:button.diff-step
             {:on-click #(state/step-diff -1)
              :disabled (<= idx 0)
              :title "Newer"} "→"]]
      :label (step-label older newer (zero? idx))
      :label-title (str "Where each version came from — " provenance/explanation)
      :unified? diff-unified?
      :toggle-disabled? (nil? older)}
     (cond
       (nil? entries)
       [:p.diff-loading "Loading…"]

       (nil? older)
       ;; **A Recipe on its first version, and only that.** The comment here used
       ;; to name a second case — the oldest step of a Recipe that has more — and
       ;; `max-idx` in the `let` above rules it out: with `total` ≥ 2 the last step
       ;; is `total - 2`, whose older side is the last entry in the list, and ← is
       ;; disabled there. So the oldest version of a Recipe that has a history is
       ;; read as the left-hand side of a diff, and never through here.
       ;;
       ;; `newer` cannot be nil beside a non-nil `entries`: `/versions` on a Recipe
       ;; that exists always carries at least its current row, and on one that does
       ;; not it 404s, which lands no handler at all and leaves `entries` nil under
       ;; the branch above.
       ^{:key (str recipe-id "-" idx)}
       [version-pane (as-side newer) dark-mode]

       :else
       ^{:key (str recipe-id "-" idx)}
       [pane (as-side older) (as-side newer) diff-unified? dark-mode])]))

(defn- proposal-reading
  "A proposal against the Recipe it is about, on the same surface.

  The two notes come first and both can be on at once: a proposal against older text
  on a Recipe that is also published is two things he needs to know, not a choice
  between them. There is no third, deleted case to be exclusive with them — a
  `proposed` entry always has its Recipe, because deleting one resolves the proposal
  and takes the entry out of the queue in the same transaction.

  **Nothing here is capped in height.** That is the point of the move: this reading
  used to be a 320px pane inside a queue row, and a change beginning in the second
  paragraph of a 2550-character body was below the fold of the thing meant to show
  it. 2550 is the Recipe he reported it on, and not his longest — that one is nearly
  three times as long, which `db.proposal/attach-to-events` now has the number for."
  [{:keys [id recipe_title proposal]}]
  (let [{:keys [diff-unified? dark-mode]} @state/*app-state
        [current proposed] (proposal-sides proposal)]
    [shell
     {:heading "Proposal"
      :subject recipe_title
      :label (str "Version " (:recipe_version proposal) " → proposed")
      :label-title "The Recipe's current version, and the rewrite waiting on it —
                    which is not a version until you approve it"
      :unified? diff-unified?
      :actions [proposal-actions {:id id}]}
     [:<>
      [published-note proposal]
      [staleness-note proposal]
      ^{:key (str "proposal-" id)}
      [pane current proposed diff-unified? dark-mode]]]))

(defn component
  "Rendered only when `:diffing` names a recipe; `:diffing-proposal` says which of
  the two readings that is.

  A proposal is looked up in the queue it was opened from rather than held anywhere
  of its own, so there is one copy of it and the page cannot come to disagree with
  the list behind it.

  **Finding none renders nothing, and this `when-let` is what holds the invariant.**
  Not a guard in the state: `resolve-proposal` closes this on his answer, which is what
  keeps the *atom* honest — both entry points to a resolution pass through it, so an
  answered proposal is never left named there — and `fetch-inbox` used to carry a
  second guard for an entry leaving the queue some other way, which could not fire and
  has been removed. If one ever did, what keeps this from drawing a comparison out of
  nothing is the lookup here. That is measurable rather than argued: with both guards
  taken out, the state still said a proposal was open and the overlay was **gone from
  the DOM**, because there was no entry to find. Unreachable states being structural
  rather than defended is what `open-viewer!` is about, and this is the render-time
  half of it."
  []
  (let [{:keys [diffing diffing-proposal inbox]} @state/*app-state]
    (if diffing-proposal
      (when-let [entry (first (filter #(= diffing-proposal (:id %)) inbox))]
        [proposal-reading entry])
      [version-reading diffing])))
