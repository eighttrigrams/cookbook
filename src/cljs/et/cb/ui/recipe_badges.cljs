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
  "One sentence, and it has to carry four things a bare number does not: that a
  *listing* is not a read, that everybody's reads are in there and not only the
  owner's, that the count starts where migration 008 does — a Recipe written last
  year and read a hundred times says 0 until somebody opens it again — and now what
  the two buckets mean for a **read**.

  **The fourth is the one that cannot be left to the labels**, because it is where
  the word `ui` on this badge would mean something different from the word `ui` on
  the badge beside it. A version has two possible authors and a visitor cannot
  write; a read has three sources, and 008 counts the anonymous one on purpose. So
  the tooltip says outright that a stranger's read is counted as a person's — the
  reader has no way to derive that, and the alternative reading (that a stranger is
  the machine bucket, or is uncounted) is the one somebody would assume.

  It also has to say that the split starts *later* than the total, which is the
  visible consequence of 013 arriving after 008: a Recipe can honestly say `212
  reads` with two of them attributed.

  Kept beside the badge rather than in `et.cb.ui.provenance`, and that judgement
  call reads differently now that there are two surfaces than it did when there was
  one: what that namespace is for is a fact whose *wording* two places must agree
  on, and this string is now shared by being in one place rather than by being
  looked up in another. **The bucket names still come from there**, which is the
  half that has not changed — this string explains what they mean here, and does not
  respell them. The API's own wording of it lives in
  `recipe-handler/get-recipe-handler`'s docstring, which is not a second copy but a
  different medium — an agent reads that one out of /api/describe."
  (str "How often this Recipe was actually read — its text fetched in full, here "
       "or through the API, by anyone — never counting a listing, and only since "
       "cookbook started counting. (" provenance/machine-label ") is a read by an "
       "agent holding a machine token and (" provenance/ui-label ") is everything "
       "else, including a stranger reading a published Recipe: a person read it. "
       "Reads counted before cookbook started attributing them are in the total and "
       "in neither bucket, which is why the total is named when the two do not add "
       "up to it"))

(defn views-badge
  "`12 reads`, beside the version pair, because it is the same kind of fact: a
  count the server keeps about this Recipe, on the one line that says what the
  Recipe is — and, since migration 013, **who did the reading**: *and break the
  reads down by human/machine as well.*

  **One pill and not two**, which is the layout decision this took. The obvious
  shape was a second badge in `source-split`'s form beside the total, and it is the
  wrong one for one reason: it would sit two pills away from the *version* split and
  read identically to it — `4(ui)` and `1(ui)` on one line, meaning different things
  about different nouns. Two counts and a total are one badge's worth of
  information, so they are one badge.

  **Three renderings, from one rule: show the buckets there are, and name the total
  when the buckets do not account for it.**

      212 reads                 nothing attributed yet — today's badge, unchanged
      3(ui)/1(machine) of 212   some reads predate 013 and belong to neither bucket
      33(machine)/1(ui) reads   everything counted has a bucket

  The middle one is what stops this being a lie about the past. 008 counts and 013
  attributes, so every Recipe on his shelf has reads that nothing recorded a reader
  for — `34(ui)` on a Recipe read 212 times would be a claim about data that was
  never taken. `total − (human + machine)` is exactly that remainder and it is
  *named* rather than bucketed, because there is no honest word for it that is not a
  sentence: the tooltip carries the sentence. It vanishes on its own as a Recipe is
  read from here on, which is the same shape `source-split` gets from dropping empty
  buckets.

  **A `0` total is still shown**, unlike an empty bucket. There the zero is a
  non-fact — nothing wrote a machine version, so `0(machine)` would only add noise —
  while here it is the reading itself: nobody has opened this since the count began,
  which is exactly what the ranking acts on. What is *not* shown is a missing key,
  which is what a listing row from an older server would carry, and that renders as
  nothing rather than as `0 reads`.

  **The buckets are absent for a visitor and the total is not**, which the component
  gets for free by drawing what it was sent: `db.recipe/read-split-columns` is not in
  a visitor's projection, so their rows carry no `human_reads` at all and this falls
  through to the first rendering. The gate is the server's, and there is deliberately
  no `logged-in?` here to look like a second one — the same distinction `pending` and
  the tags keep, from the other side.

  The bucket names are `provenance`'s, like the version split's: two surfaces naming
  one pair of words, and this is a third."
  [{:keys [view_count human_reads machine_reads]}]
  (when (number? view_count)
    (let [buckets (->> [[machine_reads provenance/machine-label]
                        [human_reads provenance/ui-label]]
                       (filter (fn [[n _]] (and (number? n) (pos? n))))
                       (map (fn [[n label]] (str n "(" label ")"))))
          attributed (+ (if (number? human_reads) human_reads 0)
                        (if (number? machine_reads) machine_reads 0))
          reads (if (= 1 view_count) " read" " reads")]
      [:span.views-badge {:title views-badge-title}
       (cond
         (empty? buckets) (str view_count reads)
         (= attributed view_count) (str (str/join "/" buckets) reads)
         :else (str (str/join "/" buckets) " of " view_count reads))])))

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
