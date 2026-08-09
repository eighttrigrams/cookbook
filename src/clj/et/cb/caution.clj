(ns et.cb.caution
  "How careful an agent should be in each part of a Recipe's body — the
  `us-vs-them` question, asked of the version ladder `et.cb.db.recipe` already
  keeps.

  **Cookbook records the two things the library needs and has never joined them
  up.** Every version of every Recipe carries a `source` of `'ui'` or `'machine'`
  (see `db.recipe`), and every superseded version keeps its own text in
  `recipe_history`. That is exactly a history of versions each under identifiable
  authorship, which is the only input `et.uvt.caution/assess` asks for — so this
  namespace is an adapter and nothing more. None of the arithmetic is here, and
  none of it should come here: the library is a sibling checkout, wired in by
  `:local/root`, and its `caution_test.clj` is the specification of what the
  numbers mean.

  What *is* cookbook's is the three statements below, and the words it hands out
  with the answer. The three are small and each silently wrong-able — the ranges come
  back well-formed whichever way round the history is read — which is why each one
  has a test of its own next door. The words are `legend`, which is wrong-able the
  same quiet way: a legend that reads the spectrum backwards is a correct answer with
  a lie attached to it.

  **The counts on the card and these ranges answer different questions.**
  `machine_versions`/`ui_versions` say how many *versions* came from where; this
  says which *lines* of the text that is there now did. A Recipe he wrote once and
  an agent has since edited nineteen times reads `1(ui)/19(machine)` on the card
  while his opening paragraph is still at `1.00` here, and that is the whole point
  of asking the second question."
  (:require [et.uvt.caution :as uvt]))

(def ours
  "Which `source` label counts as us: the one a save from the web UI carries.

  This is the only place cookbook takes sides, and it is a set of one because the
  column holds two values and neither is a third thing (migration 010). Every
  other label — today that is `'machine'` and nothing else — is them, which is the
  right way for this to fail if a third ever appears: a label nobody has
  classified is treated as an agent's, so the mistake is being needlessly careful
  rather than editing his work freely.

  Note that it is `et.cb.ui.provenance/ui-label` spelled again rather than shared:
  that namespace is cljs and this is clj. The value is the database's, and the
  `CHECK` constraint is what actually holds the two ends together."
  #{"ui"})

(def legend
  "How to read the numbers, in one line, to be handed out *with* them on every read
  that carries them.

  His reason for it being in the body rather than only in `/api/describe`: *it
  should, every time, give brief explanation in the return body that the spectrum
  meaning. 1.00 meaning human, 0.00 meaning agentic.* The reader of this API is an
  agent that may have fetched exactly one Recipe and read nothing else, and to that
  reader a bare `0.0` beside a line range is a number it has to already know how to
  read — so the ranges travel with their own key.

  **The wording is `et.cb.ui.provenance/explanation`'s, deliberately.** *Saved here
  by hand* and *written by an agent* are the owner's own words for these two
  authors, already on screen in the badge's tooltip, and the same fact told to the
  same person in two vocabularies is two facts to whoever reads both. That
  namespace is cljs and this is clj, so this is spelled again rather than shared —
  the same seam, and for the same reason, as `ours` above.

  What it adds to the tooltip's version is the middle. The badge counts versions and
  a version is one thing or the other; a line can have been worked on by both, and a
  number between the ends is what says so."
  (str "1.00 saved here by hand, 0.00 written by an agent; "
       "in between, a stretch both have touched"))

(defn ranges
  "The ranges of a Recipe's current description, each with how careful an agent
  should be in it — `1.0` his, `0.0` up for grabs, and the spectrum in between
  where the two have been mixed. `[{:from :to :caution}]`, one-based and
  inclusive, in the numbering an editor already uses.

  `versions` is `(:versions (db.recipe/list-versions …))` **as it comes**, newest
  first. Three things happen to it here and each is a decision:

  - **It is reversed**, because `assess` replays a history forwards and the ladder
    arrives with today on top. Read in the order it comes, every line would be
    attributed to whoever wrote the version *after* it — which is not a crash and
    not a malformed answer, just a confident inversion of who wrote what.
  - **The text is the `description`**, not the title and not the useful-when. Those
    two are a line each; a line is the unit this measures in, and there is nothing
    to be careful *within* a single line. So this answers about the body, and the
    other two fields keep the per-version `source` they already had.
  - **The source is the version's own label**, which `list-versions` reads off the
    row for the current version and off each history row for the superseded ones.
    `archive!` is what makes those labels trustworthy: a version goes into history
    with the source it was saved under and never with the source of the save that
    displaced it.

  A nil description is read as the empty string. It cannot arrive from the write
  paths — the column defaults to `''` and every write coalesces — but it *can*
  arrive from a caller who selected the lean projection by mistake, and a lean read
  is defined by not carrying a description. The empty string is the honest reading
  of that: one line, attributed to whoever the version is. It is not a guard
  against a missing column so much as a refusal to have this throw on the app's
  hottest read.

  There is always at least one version, so there is always an answer: a Recipe is
  created at v1 and `list-versions` puts the current row in the list."
  [versions]
  (uvt/assess (mapv (fn [{:keys [description source]}]
                      {:text (or description "") :source source})
                    (reverse versions))
              {:ours ours}))
