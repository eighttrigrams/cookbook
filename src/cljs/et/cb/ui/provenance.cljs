(ns et.cb.ui.provenance
  "What cookbook calls the three places a version of a Recipe can have come from.

  Two surfaces say this now — the card's `2(machine)/1(ui)` badge and the version
  viewer's `Version 2 · ui` label — and they must not name the same fact
  differently. So the names live here once and both read them from here, rather
  than each spelling out its own and drifting.

  `nil` is a **third category and not a synonym for machine**: it means nothing
  ever recorded where that version came from, which is true of every version
  written before cookbook noted it. That is why it has a name of its own here
  instead of being an absent suffix — an omission reads identically to a bug.
  See the comment in migration 005-version-source, which refuses a column default
  for the same reason.")

(def ui-label "ui")

(def machine-label "machine")

(def unrecorded-label
  "The name for a version whose origin was never recorded. Short, because the
  card's badge is a badge — and the *same* token in the viewer's label, so a
  reader cannot take the two for two different facts."
  "?")

(defn label
  "The name for one version's `:source` as it came off the API, where nil is
  unrecorded rather than missing."
  [source]
  (or source unrecorded-label))

(def explanation
  "Spelled out for the tooltip of both surfaces, because `?` is the bucket every
  Recipe written before this shipped falls into, and a reader who is not told
  would read it as the app being unsure rather than as nothing having been
  recorded."
  (str "(" ui-label ") saved here by hand, "
       "(" machine-label ") written by an agent, "
       "(" unrecorded-label ") not recorded, which is every version from before "
       "cookbook noted this"))
