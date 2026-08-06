(ns et.cb.ui.provenance
  "What cookbook calls the two places a version of a Recipe can have come from.

  Two surfaces say this — the card's `2(machine)/1(ui)` badge and the version
  viewer's `Version 2 · ui` label — and they must not name the same fact
  differently. So the names live here once and both read them from here, rather
  than each spelling out its own and drifting.

  **It was three until migration 010.** `source` was nullable and nil was a
  category of its own: nothing had recorded where that version came from, which was
  true of everything written before 005. It had a name here for the same reason it
  had a column value — an omission on screen reads identically to a bug. The owner
  was asked what those versions were and said they were his, 010 wrote that down
  and made the column `NOT NULL CHECK (source IN ('ui','machine'))`, and so there is
  no third bucket left to name: every version now says which of the two it is.")

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
