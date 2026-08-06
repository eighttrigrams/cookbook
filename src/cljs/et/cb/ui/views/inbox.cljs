(ns et.cb.ui.views.inbox
  "The Inbox page: what the agents did to the shelf, oldest unseen first.

  **A queue and not a feed.** The oldest thing he has not looked at is at the top,
  new arrivals go to the bottom, and acknowledging an entry takes it out of the
  list — so the page empties as it is worked through and what is on it is exactly
  what is left to do. That is what he asked for: *i can go through the things
  topmost first (oldest unseen change first)*.

  **Everything here is an agent's work**, which is the other thing he asked for
  once he had thought about it: *no my own ui edits should not land in the inbox*.
  So there is no source label on a row — a badge saying `machine` on every line
  would be noise — and no filter to switch his own edits off, because they were
  never in.

  A private page in the sense this app already has three: the shell has no router,
  a button in the top bar names a page, and `:page` in the atom says which one is
  on. See `et.cb.ui.core/page-body`, which renders exactly one of them, and
  `state/go-to-page`, where being *one value* is what makes 'this page and the
  shelf are both up' unreachable rather than merely avoided.

  Each row is the kind, the Recipe's title, when it happened, and one button —
  Seen. For a `created` or `modified` entry the **title is the way through to the
  version viewer, positioned at that version**: the question a row raises is 'what
  did that save change', and the viewer is the thing that answers it. A `deleted`
  entry's title is plain text, because there is nothing left to open.

  The title on a row is a snapshot taken when the change happened, not a lookup —
  so a row about a Recipe that has since been renamed still says what it said then,
  and a row about one that has been deleted still says what it was called. That is
  deliberate and it is the only thing that keeps such a row readable."
  (:require [reagent.core :as r]
            [et.cb.ui.state :as state]
            [et.cb.ui.views.diff :as diff]))

(def ^:private kind-labels
  "What each kind is called on a row. The words are the API's own — an agent reads
  `kind` out of `/api/inbox` and the owner reads it here, and the two should not
  need translating between."
  {"created" "created"
   "modified" "modified"
   "deleted" "deleted"
   "proposed" "proposed"})

(def ^:private kind-titles
  "The tooltip per kind, which is where the version number is explained: `v3` on a
  `modified` row is the version the save *wrote*, and on a `deleted` row it is the
  version the Recipe died on. Those are different facts wearing the same badge."
  {"created" "An agent wrote this Recipe"
   "modified" "An agent's save changed this Recipe's content — the version shown is
               the one it wrote"
   "deleted" "An agent deleted this Recipe — the version shown is the one it died on"
   "proposed" "An agent proposes to rewrite this Recipe, and is waiting for you"})

(defn- openable?
  "Whether this row's change can be looked at in the version viewer.

  Two conditions, and the second is the one that is easy to miss. A `deleted` entry
  cannot be opened — the Recipe and its whole history are gone, so there is no
  version list and a link would 404. But **the `created` and `modified` entries
  above it are just as dead**, and one of them can still be sitting unseen in the
  queue after the `deleted` entry has been acknowledged. So the kind is not the
  question; whether the Recipe is still there is, and the server answers it with
  `recipe_exists` because this client cannot: its copy of the shelf may be narrowed
  by a search, so 'not in the listing' does not mean 'not there'.

  `proposed` is excluded because it gets a pane of its own rather than the version
  viewer — the version it proposes does not exist yet, so there is nothing in the
  history to step to."
  [{:keys [kind recipe_exists]}]
  (and (contains? #{"created" "modified"} kind)
       (= 1 recipe_exists)))

(defn- row
  "One entry. The seen button goes dead on the first click, the way the confirm
  buttons in the modals do and for the same reason: only the response closes this
  out — the list is refetched rather than the row spliced away, because the server
  decides what is in the queue — which leaves the button live for the whole round
  trip unless something takes it out. Two quick clicks would send two POSTs, and
  the second one is an idempotent 200 that nonetheless refetches over the first."
  [_entry]
  (let [sending? (r/atom false)]
    (fn [{:keys [id kind recipe_id recipe_title version created_at] :as entry}]
      [:div.inbox-row
       [:span.inbox-kind {:class (str "kind-" kind) :title (get kind-titles kind)}
        (get kind-labels kind kind)]
       (if (openable? entry)
         [:button.inbox-title-link
          {:title "See what this save changed"
           :on-click #(state/start-diff-at-version recipe_id version)}
          recipe_title]
         ;; Plain text, and told why: a title that simply stopped being clickable
         ;; would read as the row being broken.
         [:span.inbox-title
          {:title (if (= "deleted" kind)
                    "This Recipe is gone, so there is nothing left to open"
                    "This Recipe has since been deleted, so there is nothing left
                     to open")}
          recipe_title])
       (when version
         [:span.inbox-version {:title (get kind-titles kind)} (str "v" version)])
       [:span.inbox-when created_at]
       [:span.inbox-row-actions
        [:button.secondary.inbox-seen
         {:disabled @sending?
          :on-click #(do (reset! sending? true) (state/mark-seen id))}
         (if @sending? "…" "Seen")]]])))

(defn- inbox-block []
  (let [{:keys [inbox]} @state/*app-state]
    [:div.inbox
     [:h2 "Inbox"]
     [:p.settings-note
      "Every change your agents made to a Recipe, oldest first. Work down from the
       top and mark each one seen; the entry disappears and the rest keep their
       order. "
      [:strong "Your own edits are not in here"]
      " — this is the record of what the agents did, not a change log. Click a
       Recipe's title to see what that save changed."]
     (if (empty? inbox)
       [:div.inbox-empty "Nothing your agents did is waiting."]
       [:div.inbox-list
        (for [entry inbox]
          ^{:key (:id entry)} [row entry])])]))

(defn inbox-page
  "The panel and the version viewer, as **siblings**.

  The viewer has to be reachable from here at all — it used to be rendered only
  from the shelf, and this page is not the shelf: `page-body` renders exactly one
  page, so a row that opened `:diffing` while the shelf was not mounted would have
  set the state and shown nothing.

  And it renders outside `.inbox` rather than inside it, for the reason the Recipe
  modals render outside the cards: this block has a `backdrop-filter`, which would
  make it the containing block for the viewer's fixed positioning and pin a
  full-screen overlay inside the panel."
  []
  (let [{:keys [diffing]} @state/*app-state]
    [:<>
     [inbox-block]
     (when diffing
       [diff/component])]))
