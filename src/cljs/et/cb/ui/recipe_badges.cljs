(ns et.cb.ui.recipe-badges
  "The header facts a Recipe wears, for the two surfaces that show a Recipe.

  Which version it is on, where those versions came from, how often it has been
  read, whether it is published, whether a proposal is waiting on it, and the
  owner's extra search words. The shelf's collapsed card has worn all six since
  each of them existed. A Recipe's own page wears them now, because that page is
  the same Recipe at the same address and a reader arriving by link should be told
  what a reader arriving by shelf is told.

  **These moved here rather than being copied**, and the codebase has already made
  that argument twice: `et.cb.ui.provenance` exists because two surfaces named the
  same fact and drifted, and `et.cb.ui.scope-badges` because two surfaces drew the
  same pill. A second `12 reads` with its own tooltip would be the third instance
  of the same mistake — and the tooltips are most of what these are, since a bare
  `3(machine)/17(ui)` says nothing at all on its own.

  **What is deliberately not here is any gate.** Three of these are the owner's
  business — the tags, the pending flag, the published latch — and every call site
  wraps them in `logged-in?` itself, exactly as the card always did. That is not an
  oversight to be tidied into this namespace: the gate is *cosmetic* and the server
  is the boundary, and the docstrings below say so at some length. A component that
  gated itself would read as the mechanism, which is the one thing it must not be
  mistaken for.

  What is also not here is the Scope badges. They are `ui.scope-badges`' already,
  and the shelf's shift+click gesture over them belongs to the shelf — see
  `views/recipes/card-scopes`."
  (:require [clojure.string :as str]
            [et.cb.ui.provenance :as provenance]))

(defn day
  "The date out of a timestamp — everything up to the first space. A Recipe's
  header says which day, never which second."
  [timestamp]
  (when (seq (str timestamp))
    (first (str/split (str timestamp) #" "))))

(defn version-badge
  "`v3`. Not gated on anything: how many times a Recipe has been edited is a fact
  about the Recipe."
  [version]
  [:span.version-badge {:title "Every edit makes a new version"} (str "v" version)])

(defn published-badge
  "That the latch is set, and when it was.

  Gated on `logged-in?` at both call sites, and the reason is that it says nothing
  to anybody else: everything a visitor can see at all is published, so a badge on
  every Recipe they meet would be a word that never varies. It is the owner who has
  a shelf of mostly unpublished Recipes for it to distinguish between."
  [published_at]
  [:span.published-badge {:title (str "Published " (day published_at)
                                      " — public, and one way")}
   "published"])

(def ^:private source-badge-title
  "Spelled out because the two words decide who to trust for a Recipe's text, which
  a bare `3(machine)/17(ui)` does not say. The sentence itself comes from
  `et.cb.ui.provenance`, so this badge and the version viewer's label cannot end up
  naming the same fact differently."
  (str "Where this Recipe's versions came from — " provenance/explanation))

(defn source-split
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

  Kept beside the badge rather than in `et.cb.ui.provenance`, and that judgement
  call reads differently now that there are two surfaces than it did when there was
  one: what that namespace is for is a fact whose *wording* two places must agree
  on, and this string is now shared by being in one place rather than by being
  looked up in another. The API's own wording of it lives in
  `recipe-handler/get-recipe-handler`'s docstring, which is not a second copy but a
  different medium — an agent reads that one out of /api/describe."
  (str "How often this Recipe was actually read — its text fetched in full, here "
       "or through the API, by anyone — never counting a listing, and only since "
       "cookbook started counting"))

(defn views-badge
  "`12 reads`, beside the version pair, because it is the same kind of fact: a
  count the server keeps about this Recipe, on the one line that says what the
  Recipe is.

  **A `0` is shown**, unlike an empty bucket in `source-split`. There the zero is
  a non-fact — nothing wrote a machine version, so saying `0(machine)` would only
  add noise — while here it is the reading itself: nobody has opened this since
  the count began, which is exactly what the ranking acts on. What is *not* shown
  is a missing key, which is what a listing row from an older server would carry,
  and that renders as nothing rather than as `0 reads`.

  Not gated on `logged-in?`, and the Recipe page inherits that: it is a fact about
  the Recipe rather than about the owner's filing, the server puts it in the
  visitor's projection deliberately, and it explains the order of the shelf a
  visitor is looking at."
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

(defn pending-badge
  "That a proposal is waiting on this Recipe.

  **This is why `pending` is on a lean listing row at all.** The flag was put
  there so the shelf could show it: a collapsed card is exactly the place that
  cannot go and fetch a proposal, so without the flag the shelf could not say that
  one was queued. A Recipe's own page fetches the whole row and gets the same flag
  in it, which is why one badge serves both.

  **It is a badge and not a control.** Approving or dismissing happens in the
  Inbox, where the agent's text can be read against the Recipe's own — a decision
  nobody should make from a word on a card — so nothing here is clickable and the
  tooltip says where to go instead.

  **The `logged-in?` gate at the call sites is cosmetic and must not be read as the
  boundary**, the same distinction `tags` draws: a visitor's projection does not
  name the column, so a signed-out client is not holding a `pending` it has been
  asked not to draw — there is no key in what it was sent. Deleting the gate
  would show a visitor nothing extra; deleting the server half would tell strangers
  which of the owner's Recipes an agent is queued to rewrite."
  []
  [:span.pending-badge {:title pending-badge-title} "proposal"])

(defn tags
  "The owner's extra search words, on his own Recipe.

  **The gate at the call sites is cosmetic and must not be read as the privacy
  boundary.** The boundary is the server: a visitor's projection does not name the
  `tags` column, so a signed-out client is not holding tags it has been asked not to
  draw — there is no `tags` key in what it was sent, and `logged-in?` there would be
  redundant if the client could be trusted, which is exactly why it is not the
  mechanism. Do not 'simplify' `select-columns` on the grounds that this hides them;
  deleting those lines would show nothing extra, and deleting the server half would
  publish the owner's filing.

  That paragraph is now true of two call sites rather than one, which strengthens
  it rather than weakening it: there are two places a client could be trusted and
  wrongly, and one place the question is actually answered.

  Rendered as plain text rather than through the markdown renderer the other
  fields use: these are search words, not prose, and a stray `_` in one is a
  character and not emphasis — the same reading the search itself gives it.

  The wrapper's class is the caller's, for the reason `scope-badges/badges` gives:
  the *layout* is the surface's while the words are not. `.card-tags` sits under a
  collapsed card's useful-when line; the Recipe page has a header of its own."
  [tags-text {:keys [class]}]
  [:div {:class class
         :title "Extra words this Recipe can be found by — yours alone"}
   tags-text])
