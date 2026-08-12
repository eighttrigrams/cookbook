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

;; ---------------------------------------------------------------------------
;; Aligning a draft against the body the ranges are about
;;
;; **This replaced an index-and-text rule, and the owner is the one who reported
;; what was wrong with it.** That rule kept a stored line's caution only where a
;; draft line sat at the same index *and* read the same, and it was defended here
;; at length on the grounds that it could only ever under-claim. What it could not
;; survive is the commonest edit of all, and the one this feature is *for*:
;;
;; > i think you need to compare how provenance looks before and after a change.
;; > the interesting case is when i insert human edit into agentic surroundings
;;
;; Insert one hand-written line into six an agent wrote and every line below it
;; shifts by one, so the preview showed three red lines and then four blank ones —
;; and after Save the same body came back red, blue, red. The blank ones included
;; his own new line. A preview that goes quiet about exactly the thing the reader
;; is looking at is not being conservative, it is being useless: *reflect the
;; volatile state* was the ask, and under-claiming everything below the caret is
;; not a reading of the volatile state.
;;
;; The old text also argued that a real diff was unavailable, because `views.diff`
;; is CodeMirror's merge view and its alignment is behind an editor mount. That
;; was true and beside the point: a line-level longest common subsequence is
;; twenty lines of code and needs no library at all.
;; ---------------------------------------------------------------------------

(def ^:private alignment-budget
  "The largest DP table this will build, in cells, once the common head and tail
  are off. Beyond it the middle is left **unaligned** rather than aligned slowly —
  see `aligned-to-stored` for why that is a third answer and not a nil.

  40k is 200 changed lines against 200, which is not an edit but a rewrite, and
  *we do not know* is the honest reading of a rewrite. Ordinary typing never comes
  near it: trimming the head and tail leaves a middle the size of what you touched,
  so inserting a line into a thousand is a 1×0 table. The preview is also computed
  when the toggle is pressed and not as you type — while it is up the textarea is
  not on the page — so this is insurance rather than a budget being spent."
  40000)

(defn- prefix-count
  "How many lines `a` and `b` share from the top."
  [a b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (if (and (< i n) (= (nth a i) (nth b i))) (recur (inc i)) i))))

(defn- suffix-count
  "How many lines `a` and `b` share from the bottom, without running back into the
  `already` lines the head has claimed — so that head and tail can never overlap
  and the middle is never a negative slice."
  [a b already]
  (let [na (count a) nb (count b)
        n (- (min na nb) already)]
    (loop [i 0]
      (if (and (< i n) (= (nth a (- na i 1)) (nth b (- nb i 1))))
        (recur (inc i))
        i))))

(defn- lcs-alignment
  "For each index of `b`, the index of `a` it is matched to by a longest common
  subsequence, or nil.

  Plain O(n·m) dynamic programming over an `Int32Array`, filled from the bottom
  right so that `dp[i][j]` is the answer for the two tails and the walk back out
  reads forwards. Nothing clever: the tables are small by the time this is called,
  and a subtle diff would be a worse thing to own than a slow one.

  **A line can be identical and still come back nil**, when the subsequence chosen
  did not include it — two lines swapped round is the plain case. That is a real
  imprecision and it lands on the safe side; `draft-cautions` says which side that
  is and why."
  [a b]
  (let [na (count a) nb (count b)
        w (inc nb)
        dp (js/Int32Array. (* (inc na) w))]
    (loop [i (dec na)]
      (when (>= i 0)
        (loop [j (dec nb)]
          (when (>= j 0)
            (aset dp (+ (* i w) j)
                  (if (= (nth a i) (nth b j))
                    (inc (aget dp (+ (* (inc i) w) (inc j))))
                    (max (aget dp (+ (* (inc i) w) j))
                         (aget dp (+ (* i w) (inc j))))))
            (recur (dec j))))
        (recur (dec i))))
    (let [out (js/Array. nb)]
      (loop [i 0 j 0]
        (when (< j nb)
          (cond
            (>= i na)
            (do (aset out j nil) (recur i (inc j)))

            (= (nth a i) (nth b j))
            (do (aset out j i) (recur (inc i) (inc j)))

            (>= (aget dp (+ (* (inc i) w) j)) (aget dp (+ (* i w) (inc j))))
            (recur (inc i) j)

            :else
            (do (aset out j nil) (recur i (inc j))))))
      (vec out))))

(defn- aligned-to-stored
  "For each line of `draft`, **which line of `stored` it is** — the same line,
  wherever it has moved to — as one of three answers:

  - an **index** into `stored`: this is that line, and it keeps that line's caution
  - **nil**: no stored line is this one, so it is a line being typed now
  - **:unknown**: the alignment was not computed here at all

  The third is not a nil, and keeping them apart is the whole reason this returns a
  vector of three kinds rather than of indices-or-nil. *You typed this* and *we did
  not work it out* have opposite consequences one function along: the first is the
  claim that a line is his, the second is a refusal to claim anything. Collapsing
  them would make a budget overrun read as *you wrote all of this*, which on a
  pasted-in body is the one confident lie this preview must not tell.

  The common head and tail come off first — cheap, and on ordinary typing they are
  nearly the whole body — and only what is left goes through `lcs-alignment`."
  [stored draft]
  (let [ns (count stored) nd (count draft)
        p (prefix-count stored draft)
        s (suffix-count stored draft p)
        a (subvec stored p (- ns s))
        b (subvec draft p (- nd s))
        mid (if (> (* (count a) (count b)) alignment-budget)
              (vec (repeat (count b) :unknown))
              (mapv #(when (number? %) (+ p %)) (lcs-alignment a b)))]
    (-> (vec (range 0 p))
        (into mid)
        (into (map #(+ (- ns s) %)) (range s)))))

(defn draft-cautions
  "The same one-number-per-line, for a body **being edited** — a draft the server has
  never seen — aligned against the ranges it has.

  *show provenance button should be avilable in both edit and view modes. and in edit
  modes it should reflect the volatile state.* The volatile state is the difficulty:
  `caution`'s ranges index the **stored** description's lines, and a draft's lines are
  not those lines. The rule, and it is two sentences on purpose:

  > A draft line the diff matches to a stored line keeps that line's caution, wherever
  > it has moved to. A line the diff matches to nothing is one you are typing now, so
  > it is yours.

  **The second sentence is a claim, and here is what backs it.** This function serves
  one surface: the body field of the edit form, in a browser, under a Save that writes
  a `ui` version. A line that is in the draft and in no stored line is a line that
  reached the document through that field — so it is his, by the same rule the server
  will apply the moment he presses Save. It is not a guess about an unknown author; it
  is the only author this input has. Checked against the server rather than asserted:
  on the reported case — one line typed into six an agent wrote — the preview and the
  answer that comes back after Save agree line for line.

  **Where it is imprecise, it is imprecise towards blue, and that is the safe way
  round.** `lcs-alignment` can leave an unmoved line unmatched when two are swapped,
  and such a line is then drawn as his. The failure this whole feature exists to
  prevent is the other one — an agent told a line is free that is really his — and
  `et.cb.caution/ours` already chooses the same direction for the same reason: an
  unclassified author is treated as an agent's so that the mistake is *being
  needlessly careful*. Blue where red belonged costs an agent a line it might have
  rewritten. Red where blue belonged costs him a sentence.

  **And nothing here survives a Save.** The next read brings ranges computed by
  `us-vs-them` over the text that actually landed, so this is a preview in the
  strict sense: wrong for as long as it takes to press a button.

  **A text-keyed lookup is still worse than it looks**, and that older note stands:
  matching a draft line to *any* stored line with the same text mis-attributes a body
  with two identical lines — an empty line, `---`, `## Notes`. The diff does not do
  that. It matches in order, so the second `## Notes` can only ever match the second."
  [stored-description ranges draft-description]
  (let [stored (vec (split-lines stored-description))
        stored-cautions (line-cautions ranges (count stored))
        draft (vec (split-lines draft-description))]
    (mapv (fn [m]
            (cond
              (= :unknown m) nil
              (nil? m) 1.0
              :else (nth stored-cautions m nil)))
          (aligned-to-stored stored draft))))
