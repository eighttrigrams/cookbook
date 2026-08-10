(ns et.cb.ui.provenance
  "What cookbook calls the two places a version of a Recipe can have come from, and
  how far each *line* of a body leans towards one of them.

  Two surfaces say the first — the card's `2(machine)/1(ui)` badge and the version
  viewer's `Version 2 · ui` label — and they must not name the same fact
  differently. So the names live here once and both read them from here, rather
  than each spelling out its own and drifting.

  The second is the Recipe page's provenance view, and it is the same fact measured
  differently: the badge counts *versions*, `caution` attributes *the lines of the
  text as it stands*. A Recipe he wrote once and an agent has since edited nineteen
  times wears `1(ui)/19(machine)` and still has his opening paragraph at `1.00`. The
  arithmetic behind that number is `us-vs-them`'s and the API's — nothing here
  computes it — so what this namespace adds is the one translation the view needs:
  ranges into lines. See `et.cb.caution` (clj) for the question, and
  `recipe-handler/get-recipe-handler`'s docstring for the shape it arrives in.

  **It was three until migration 010.** `source` was nullable and nil was a
  category of its own: nothing had recorded where that version came from, which was
  true of everything written before 005. It had a name here for the same reason it
  had a column value — an omission on screen reads identically to a bug. The owner
  was asked what those versions were and said they were his, 010 wrote that down
  and made the column `NOT NULL CHECK (source IN ('ui','machine'))`, and so there is
  no third bucket left to name: every version now says which of the two it is."
  (:require [clojure.string :as str]))

(def ui-label "ui")

(def machine-label "machine")

(defn label
  "The name for one version's `:source` as it came off the API. Total on the two
  values the column can hold, which since 010 is all of them — no nil case, because
  a nil cannot arrive."
  [source]
  (if (= machine-label source) machine-label ui-label))

(def explanation
  "Spelled out for the tooltip of both surfaces: two words that mean quite
  different things about who to trust for a Recipe's text, and neither is
  self-evident from a badge."
  (str "(" ui-label ") saved here by hand, "
       "(" machine-label ") written by an agent"))

(defn split-lines
  "A description into its lines, **the way the server numbered them**.

  Not `clojure.string/split-lines`, and the difference is not cosmetic: that one
  drops trailing empty lines, and `caution`'s ranges keep them. A body ending in a
  newline — which is most bodies typed into a textarea — is `n+1` lines to the API
  and `n` to `split-lines`, so the view would be one row short of the answer it is
  drawing, at the end, silently. `-1` keeps them, in cljs as in clj."
  [description]
  (str/split (or description "") #"\n" -1))

(defn line-cautions
  "`caution`'s ranges flattened to one number per line, indexed from 0 for the
  view's `map-indexed`.

  The API hands back `[{:from :to :caution}]` over one-based inclusive lines,
  covering the body exactly once and in order, because a range is how the underlying
  question is answered: the library measures islands of his writing rather than
  lines, and a stretch's number is a property of the stretch. Expanding it per line
  is therefore a **view** convenience and not a truer reading of it — every line of
  one range carries that range's number, including the middling ones.

  `line-count` is passed in rather than taken from the last range, so that the rows
  and the numbers come from the same string the view is about. They agree today; a
  view drawn from the text and tinted from a stale answer is the one way they could
  stop agreeing, and this makes that show up as an untinted row rather than as a
  colour attributed to the wrong line."
  [ranges line-count]
  (let [by-line (reduce (fn [acc {:keys [from to caution]}]
                          (reduce #(assoc %1 %2 caution) acc (range from (inc to))))
                        {}
                        ranges)]
    (mapv #(get by-line (inc %)) (range line-count))))

(defn draft-cautions
  "The same one-number-per-line, for a body **being edited** — a draft the server has
  never seen — aligned against the ranges it has.

  *show provenance button should be avilable in both edit and view modes. and in edit
  modes it should reflect the volatile state.* The volatile state is the difficulty:
  `caution`'s ranges index the **stored** description's lines, and a draft's lines are
  not those lines. The rule, and it is one sentence on purpose:

  > A draft line keeps its stored caution when it is at the **same index** and has the
  > **same text**. Anything else is untold.

  **What matters more than the rule is that it can only ever under-claim.** Insert a
  line at the top and every line below shifts, so all of them fall to untold — which
  reads as *we do not know*, and we do not. The other failure, a confident tint against
  the wrong line, would be the view lying about who wrote something, which is the one
  thing this whole feature exists not to do. So the conservative arm is the feature and
  not a limitation to be apologised for, and a reader tempted to sharpen it should read
  the next two paragraphs first.

  **A real diff is not the fix.** `views.diff` looks like a source of one and is not:
  it is CodeMirror's merge view, so the alignment lives inside the library and behind
  an editor mount, not in a function you can call on two strings. Pulling a line-diff
  in would be new machinery in aid of a *guess* — and a guess the server overrules the
  moment the draft is saved, because the next real read brings ranges computed from the
  text that actually landed.

  **A text-keyed lookup is worse than it looks.** Matching a draft line to any stored
  line with the same text mis-attributes a body with two identical lines — an empty
  line, `---`, `## Notes` — and trades a conservative wrong for a confident one. Index
  *and* text, therefore, and nothing cleverer.

  A line past the end of the stored body has no counterpart at its index and is untold
  by the same rule, with no special case: `nth` with a nil default sees to it. Which is
  also what makes a brand-new line at the end read correctly, and it is the commonest
  edit there is."
  [stored-description ranges draft-description]
  (let [stored (split-lines stored-description)
        stored-cautions (line-cautions ranges (count stored))]
    (into []
          (map-indexed (fn [i line]
                         (when (= line (nth stored i nil))
                           (nth stored-cautions i nil))))
          (split-lines draft-description))))
