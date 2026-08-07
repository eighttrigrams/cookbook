(ns et.cb.ui.views.diff
  "The version viewer: one step of a Recipe's history at a time, the older
  description on the left of a codemirror merge view and the newer on the right,
  read-only — or, where there is no older, that one version on its own.

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

  The overlay is full-screen and is rendered outside the cards, for the reason
  the modals are — see the comment at the bottom of `et.cb.ui.views.recipes`."
  (:require [clojure.string :as str]
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
  "One version on its own, in the same read-only editor the two sides of a diff
  are. A third mount rather than one of the two above given something to stand in
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
  version viewer's headings; the Inbox supplies its own, because a proposal is not a
  version and calling it one would be the wrong word in the one place a reader is
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

(defn pane
  "The comparison itself: the two metadata columns, the note about an unchanged body,
  and the merge view under them. Everything the version viewer draws below its header.

  **Exposed so the Inbox can show a proposal without a second diff being written.**
  The two sides are maps of `{:heading :sub :title :useful_when :description}` —
  `left` is the older or current text, `right` the newer or proposed one, which is the
  order both readings of this component want.

  `key?` is not a parameter: the caller keys this on everything it was built from, the
  way `component` does, because nothing here reconfigures a live editor."
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

(defn component
  "Rendered only when `:diffing` names a recipe. `:diff-version-idx` steps the
  list, which arrives newest-first: index 0 is the step into today's text, so ←
  walks backwards in time and → forwards, as in rhizome."
  []
  (let [{:keys [diffing diff-version-idx diff-unified? dark-mode recipes versions]}
        @state/*app-state
        recipe (first (filter #(= diffing (:id %)) recipes))
        entries (get versions diffing)
        total (count entries)
        ;; Two adjacent versions make one step, so N versions are N-1 steps —
        ;; and a single version is step 0 with nothing older, which is the
        ;; fallback rather than an empty pane.
        max-idx (max 0 (- total 2))
        idx (max 0 (min (or diff-version-idx 0) max-idx))
        newer (nth entries idx nil)
        older (nth entries (inc idx) nil)]
    [:div.diff-overlay
     [:div.diff-page
      [:div.diff-header
       [:button.diff-close {:on-click state/stop-diff :title "Close"} "✕"]
       [:h2 "Versions"]
       [:span.diff-recipe-title
        (str (:title recipe)
             (when (pos? total)
               (str " · " total (if (= 1 total) " version" " versions"))))]
       [:button.diff-step
        {:on-click #(state/step-diff 1)
         :disabled (>= idx max-idx)
         :title "Older"} "←"]
       [:button.diff-step
        {:on-click #(state/step-diff -1)
         :disabled (<= idx 0)
         :title "Newer"} "→"]
       [:span.diff-version-label
        {:title (str "Where each version came from — " provenance/explanation)}
        (step-label older newer (zero? idx))]
       [:button.diff-mode-toggle
        ;; Dead where there is no merge view to lay out either way, rather than
        ;; live and inert: the ← and → next to it go grey for the same reason.
        {:on-click state/toggle-diff-unified
         :disabled (nil? older)}
        (if diff-unified? "Split" "Unified")]]
      (cond
        (nil? entries)
        [:p.diff-loading "Loading…"]

        (nil? older)
        ;; **A Recipe on its first version, and only that.** The comment here used
        ;; to name a second case — the oldest step of a Recipe that has more — and
        ;; the arithmetic three lines up rules it out: with `total` ≥ 2 the last
        ;; step is `total - 2`, whose older side is the last entry in the list, and
        ;; ← is disabled there. So the oldest version of a Recipe with a history is
        ;; read as the left-hand side of a diff and this branch is never how.
        ;;
        ;; `newer` cannot be nil beside a non-nil `entries`: `/versions` on a Recipe
        ;; that exists always carries at least its current row, and on one that does
        ;; not it 404s, which lands no handler at all and leaves `entries` nil under
        ;; the branch above.
        ^{:key (str diffing "-" idx)}
        [version-pane (as-side newer) dark-mode]

        :else
        ;; The same pane the Inbox shows a proposal in — one layout, two readings.
        ^{:key (str diffing "-" idx)}
        [pane (as-side older) (as-side newer) diff-unified? dark-mode])]]))
