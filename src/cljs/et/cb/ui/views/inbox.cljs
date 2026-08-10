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

  **Every entry is a row, and the row is one line.** The kind, the Recipe's title,
  when it happened, and the buttons that answer it. The title is the way through to
  the viewer, which is where a change is read — and that is the same sentence for
  every kind, which it was not: a `proposed` entry used to carry its whole two-pane
  comparison inside the list. He said what was wrong with that in one line —
  *Proposals, just like the other changes, should be shown on a different page (note
  the difference in treatment)* — and it is visible in one screen: four entries, one
  of them two thirds of the page, and every entry after it pushed off the bottom of a
  queue whose whole purpose is to be worked through top to bottom.

  The kinds raise different questions on the way through and the viewer answers all
  of them: a `modified` row asks what that save changed, a `created` row asks what
  the thing says, since he has never seen it — *i have no chance to see the contents
  of a new thing* — and a `proposed` row asks what an agent wants to make of a Recipe
  he has already written. So the words on the way through say which. A `deleted`
  entry's title is plain text, and so is one whose Recipe has since gone: there is
  nothing left to open.

  **A `proposed` entry is still not a notification**, and the buttons are where that
  shows. It has no Seen button — the API refuses to acknowledge a question — and
  carries the two answers instead, Approve and Dismiss, with Dismiss asking first
  because the agent's text is gone afterwards. The same two are in the viewer's
  header, because a page you read a decision on that then sends you elsewhere to make
  it is a worse version of what he was complaining about.

  **And Approve on the row is dead for the two proposals that have something to say
  first**: one against a published Recipe, and one written against text he has saved
  since. Those two are the only things about approving that cannot be discovered
  afterwards, both are said in paragraphs in the viewer, and a row has no room for a
  paragraph — so the row says them in a flag and a version badge, and sends the answer
  to the surface the words are on. See `approve-warnings` and `row`.

  The title on a row is a snapshot taken when the change happened, not a lookup —
  so a row about a Recipe that has since been renamed still says what it said then,
  and a row about one that has been deleted still says what it was called. That is
  deliberate and it is the only thing that keeps such a row readable.

  **Beside the title, the Scopes the Recipe is filed under**, as the same badges a
  shelf card wears (`ui.scope-badges`). He asked for them here in as many words —
  *this page doesnt show the scope badges yet … so i dont know for what the recipes
  are* — and this is the page where it matters most: a queue of nine is worked through
  by deciding what to look at, and a title alone does not say what area a change was
  in. They are **current** where the title is a snapshot, which `subject` explains,
  and a Recipe that is gone or filed under nothing simply has none."
  (:require [reagent.core :as r]
            [et.cb.ui.scope-badges :as scope-badges]
            [et.cb.ui.state :as state]))

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
  "Whether this row's change can be looked at in the viewer.

  Two conditions, and the second is the one that is easy to miss. A `deleted` entry
  cannot be opened — the Recipe and its whole history are gone, so there is no
  version list and a link would 404. But **the `created` and `modified` entries
  above it are just as dead**, and one of them can still be sitting unseen in the
  queue after the `deleted` entry has been acknowledged. So the kind is not the
  question; whether the Recipe is still there is, and the server answers it with
  `recipe_exists` because this client cannot: its copy of the shelf may be narrowed
  by a search, so 'not in the listing' does not mean 'not there'.

  **`proposed` is in here now**, and it passes the second condition free: deleting a
  Recipe resolves its pending proposal and takes the entry out of the queue in the
  same transaction, so every `proposed` entry that is in the list at all has its
  Recipe. It is asked anyway rather than assumed, because the flag is what this
  question is, and one kind exempting itself from it is how the next kind would."
  [{:keys [kind recipe_exists]}]
  (and (contains? #{"created" "modified" "proposed"} kind)
       (= 1 recipe_exists)))

(defn- approve-warnings
  "The two things about approving a proposal that must not be left to be discovered
  afterwards, as flags on the row that carries the button.

  `attach-to-events` puts `recipe_published` and both version numbers on the entry for
  exactly this — *both numbers are here so a client can say so before the click rather
  than leaving it to be discovered after* — and `approve-proposal-handler` deliberately
  refuses to check `base_version` itself, because it is his call and not the API's. So
  the client's warning is the only gate there is, and until now it was on one of the two
  Approve buttons: both notes moved into the viewer with the panes, and the row kept the
  button.

  The words the row can hold are these two flags; the paragraphs stay in the viewer
  (`diff/published-note`, `diff/staleness-note`), which has the room for them. What ties
  them to the button is that Approve on a row with either of these is **dead** — see
  `row`."
  [{:keys [base_version recipe_version recipe_published]}]
  {:published? (= 1 recipe_published)
   :stale? (boolean (and base_version recipe_version (< base_version recipe_version)))})

(def ^:private scope-hint
  "Said on every badge here, because it is the one thing about a row that behaves
  differently from the title beside it: the badges are read now, the title was written
  down when the change happened. So a Recipe he has refiled shows its new Scopes next
  to the name it had then — which is the pairing triage wants, and a puzzle if nothing
  says so. The shelf's badges need no such sentence: a card is the Recipe as it is.

  Worded without a dash of its own, because it is appended after the description with
  one: two em-dashes running together read as a stray fragment rather than as a note."
  "where this Recipe is filed now, while the title is as it read then")

(defn- subject
  "What the row is about: the title, and the area it belongs to.

  **One grid cell and not two columns**, and that is the layout carrying an argument.
  A row already holds a kind, a title, a version, a timestamp and up to two buttons;
  the badges therefore go inside the cell the title already has, where they wrap under
  it when there are several — so a Recipe filed under three Scopes cannot push Approve
  off the line, which a sixth column would have done by taking the room out of the
  title or moving the buttons from row to row.

  `title-el` is passed in rather than built here because the badges are the same on
  every row and the title is not: what it opens depends on the kind, and on a row
  whose Recipe is gone it opens nothing. `title-element` answers that; this places
  it.

  **No `logged-in?` gate on the badges, unlike the shelf's cards, and that is
  considered rather than forgotten.** The gate there is cosmetic anyway — a visitor's
  rows arrive with no `scopes` key at all — but here there is not even a visitor to
  gate: `/api/inbox` is the owner's alone and answers 403 to a machine token and to an
  anonymous caller alike, so every row this page can hold was fetched by the one reader
  who may see the filing."
  [title-el scopes]
  [:span.inbox-subject
   title-el
   (when (seq scopes)
     [scope-badges/badges scopes {:class "inbox-scopes" :hint scope-hint}])])

(defn- title-element
  "The row's title, and what clicking it opens.

  **The same viewer for all three openable kinds, and three different questions.**
  `openable?` admits exactly `created`, `modified` and `proposed`, so the `case` needs
  no default — a fourth kind would have to get past it first. A `created` row
  promising to show what a save *changed* was promising the one thing a first version
  cannot have, which is why these are three sentences and not one."
  [{:keys [id kind recipe_id recipe_title version] :as entry}]
  (if (openable? entry)
    [:button.inbox-title-link
     {:title (case kind
               "created" "Read what the agent wrote"
               "modified" "See what this save changed"
               "proposed" "Read what the agent proposes, against this Recipe's text")
      :on-click (if (= "proposed" kind)
                  ;; By the **event** id, which is what the two answers are keyed by,
                  ;; and the recipe id, which is what the viewer is open on. The
                  ;; version on a `proposed` row is the one it was written against,
                  ;; not a step in the history — so there is nothing here to step to.
                  #(state/start-proposal-diff id recipe_id)
                  #(state/start-diff-at-version recipe_id version))}
     recipe_title]
    ;; Plain text, and told why: a title that simply stopped being clickable
    ;; would read as the row being broken.
    [:span.inbox-title
     {:title (if (= "deleted" kind)
               "This Recipe is gone, so there is nothing left to open"
               "This Recipe has since been deleted, so there is nothing left
                to open")}
     recipe_title]))

(defn- row
  "One entry, on one line, whichever kind it is.

  **One component for all four kinds**, where there used to be two. The kinds differ
  in what the title opens and in which buttons answer them, and in nothing else — so
  a second component was two copies of a row that had to be kept in step by hand, and
  was not: the Scope badges had to be added to both, one at a time. A queue whose
  rows are the same shape is also the thing he asked for here, and a row that is a
  row on every kind is what makes that true structurally rather than by care.

  The button goes dead on the first click — Seen and Approve alike — the way the
  confirm buttons in the modals do and for the same reason: only the response closes
  this out, because the list is refetched rather than the row spliced away (the server
  decides what is in the queue), which leaves the button live for the whole round trip
  unless something takes it out. Two quick clicks on Seen would send two POSTs, the
  second an idempotent 200 that nonetheless refetches over the first; two on Approve
  would put a 409 over a decision that in fact went through.

  Dismiss opens a confirmation instead, the way Delete and Publish do, because the
  agent's text is not served anywhere afterwards and nothing brings it back. A
  `proposed` row has **no Seen button**, deliberately: a proposal is not something to
  acknowledge, and the API refuses to acknowledge one.

  **Approve is dead on the row for the two proposals that have something to say
  first**, and those rows are answered in the viewer, which is where the sentences are.
  Triage is the row's job and it keeps it for the ordinary case — an agent's rewrite of
  an unpublished Recipe he has not touched since is a proposal he can accept from the
  list. But approving a rewrite of *published* text, or over a save of his own, is the
  one write in this app with no confirmation in front of it, and a button that does that
  with nothing on screen saying so is not triage. So the gate is structural rather than
  a note nobody has to read: the button that can be pressed without reading is the
  button with nothing to read.

  The disabled state is told why in the row's own words — the `published` flag beside
  it, the version badge showing `v1 → v3` — because a button that simply went grey would
  read as the row being broken, which is the argument `title-element` makes one function
  up.

  Five grid cells and it stays five: the flag rides inside the actions cell, next to the
  button it is about, for the reason the Scope badges ride inside the title's — a sixth
  column would take its room out of the title or move the buttons from row to row."
  [_entry]
  (let [sending? (r/atom false)]
    (fn [{:keys [id kind version created_at scopes proposal] :as entry}]
      (let [proposed? (= "proposed" kind)
            {:keys [published? stale?]} (when proposed? (approve-warnings proposal))]
        [:div.inbox-row
         [:span.inbox-kind {:class (str "kind-" kind) :title (get kind-titles kind)}
          (get kind-labels kind kind)]
         [subject (title-element entry) scopes]
         ;; **The relationship and not one half of it.** `version` on a `proposed` row
         ;; is `base_version` — the version the agent wrote against — so a row printing
         ;; a bare `v1` beside a Recipe that is on v3 was printing a stale number as if
         ;; it were current, which is worse than printing nothing.
         (when version
           (if stale?
             [:span.inbox-version.stale
              {:title (str "Proposed against version " version ", and this Recipe is on "
                           "version " (:recipe_version proposal)
                           " — you have saved it since. Open it to read what approving "
                           "would replace.")}
              (str "v" version " → v" (:recipe_version proposal))]
             [:span.inbox-version {:title (get kind-titles kind)} (str "v" version)]))
         [:span.inbox-when created_at]
         [:span.inbox-row-actions
          (if proposed?
            [:<>
             (when published?
               [:span.proposal-flag
                {:title "This Recipe is public and your name is on it. Approving replaces
                         that public text, and there is no unpublish — so this one is
                         answered in the viewer, where the whole note is."}
                "published"])
             [:button.proposal-approve
              {:disabled (or @sending? published? stale?)
               :title (when (or published? stale?)
                        "Not from the row: open it and read what approving would do.")
               :on-click #(do (reset! sending? true) (state/approve-proposal id nil))}
              (if @sending? "Approving…" "Approve")]
             [:button.secondary.danger.proposal-dismiss
              {:on-click #(state/start-dismissing-proposal id)} "Dismiss"]]
            [:button.secondary.inbox-seen
             {:disabled @sending?
              :on-click #(do (reset! sending? true) (state/mark-seen id))}
             (if @sending? "…" "Seen")])]]))))

(defn- dismiss-modal
  "Asks before dismissing, and the question is what is lost. The Recipe is not
  touched either way — what goes is the agent's text, which nothing serves again.

  The confirm button goes dead on the first click, like every other confirmation
  here: only the response closes the dialog, so two quick clicks would send two
  POSTs and the second would 409 over a dismissal that in fact went through."
  [_entry]
  (let [sending? (r/atom false)]
    (fn [{:keys [id recipe_title]}]
      [:div.modal-backdrop {:on-click state/stop-dismissing-proposal}
       [:div.modal {:on-click #(.stopPropagation %)}
        [:h2 "Dismiss this proposal?"]
        [:div.modal-subtitle recipe_title]
        [:p.modal-note
         "The Recipe is not touched — it keeps every word it has now. What goes is
          the text the agent proposed, and there is no undo: nothing serves it again
          after this. The agent is free to propose something else."]
        [:div.modal-actions
         [:button.danger
          {:disabled @sending?
           :on-click #(do (reset! sending? true)
                          (state/dismiss-proposal id state/stop-dismissing-proposal))}
          (if @sending? "Dismissing…" "Dismiss")]
         [:button.secondary {:on-click state/stop-dismissing-proposal} "Cancel"]]]])))

(defn- inbox-block []
  (let [{:keys [inbox]} @state/*app-state]
    [:div.inbox
     [:h2 "Inbox"]
     ;; **One sentence about the titles, because there is now one behaviour.** This
     ;; paragraph used to describe the `proposed` kind as the exception that showed its
     ;; change in the list and had no title to click; both halves of that are gone, and
     ;; what is left of the distinction is which buttons answer a row.
     ;;
     ;; It also once said the title showed what the save changed, which a `created` row
     ;; has never been able to do: a first version has nothing behind it, and what it
     ;; opens is that version itself. So the kinds are named rather than one of them
     ;; described three times.
     [:p.settings-note
      "Everything your agents did to a Recipe, oldest first. "
      [:strong "Your own edits are not in here"]
      " — this is the record of what the agents did, not a change log."]
     [:p.settings-note
      "Click a Recipe's title to open it: what an agent's save changed on a "
      [:strong "modified"]
      " entry, on a "
      [:strong "created"]
      " one the Recipe it wrote, which has nothing behind it to compare against yet,
       and on a "
      [:strong "proposed"]
      " one what the agent wants to make of it, beside the text you have now."]
     [:p.settings-note
      "Most entries are something that already happened: mark it "
      [:em "Seen"]
      " and it leaves the queue. A "
      [:strong "proposed"]
      " entry is a question instead — an agent wants to rewrite the Recipe and is
       waiting for you — so it asks you to "
      [:em "Approve"]
      " or "
      [:em "Dismiss"]
      " it, here on the row or on the page it opens. Either way the entry disappears
       and the rest keep their order."]
     ;; Said on the page and not only on the row, because it is the one place where a
     ;; button is deliberately dead: a reader who finds Approve grey and no sentence
     ;; anywhere would take it for a bug rather than for the point.
     [:p.settings-note
      "Two of them cannot be approved from the row: one against a Recipe that is "
      [:strong "published"]
      ", and one written against a version you have "
      [:strong "saved since"]
      " — the row flags both, and approving is on the page that says in full what it
       would replace."]
     (if (empty? inbox)
       [:div.inbox-empty "Nothing your agents did is waiting."]
       [:div.inbox-list
        (for [entry inbox]
          ^{:key (:id entry)} [row entry])])]))

(defn inbox-page
  "The panel and the dismiss confirmation, as **siblings**.

  **The viewer is not mounted here any more and that is not a loss of reach.** It
  used to be rendered from this page *and* from the shelf, because those were the
  two pages a reader could open it from and `page-body` renders exactly one page —
  so a row that opened `:diffing` while the only mount was on the shelf would have
  set the state and shown nothing. `views.recipe-modals/overlays` now mounts it once
  at the app root, above whichever page is up, which is the same guarantee without a
  second copy of it to keep in step.

  The confirmation stays here, and it is the one overlay in this app that is a
  *page's* rather than a Recipe's: it is keyed to an entry in `:inbox`, and the only
  place an entry can be dismissed from is the page that draws the list. It renders
  outside `.inbox` rather than inside it, for the reason the Recipe overlays render
  outside the pages: this block has a `backdrop-filter`, which would make it the
  containing block for a fixed overlay's positioning and pin it inside the panel.

  It has to be able to land over the viewer, too — Dismiss is a button on the row
  *and* in the viewer's header — and that is a matter of z-index and not of DOM
  order: `.modal-backdrop` outranks `.diff-overlay` in the stylesheet, which is
  where it is argued, and which is why the viewer moving to the root changes
  nothing about it."
  []
  (let [{:keys [inbox dismissing-proposal]} @state/*app-state]
    [:<>
     [inbox-block]
     (when-let [entry (first (filter #(= dismissing-proposal (:id %)) inbox))]
       [dismiss-modal entry])]))
