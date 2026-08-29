(ns et.cb.filters
  "The shelf's filter gestures, as a matrix that can be tested without a DOM.

  **A namespace of its own because there is now a matrix to test.** There was not:
  cookbook had one Scope gesture — shift+click to hide — and the honest translation
  of tracker's three-way `badge-gesture` was one predicate on `shiftKey`, written
  inline in `views/recipes`. That function's docstring said why, and it said what
  would change it:

  > its three-way matrix and its gate exist because it has positive filters, six
  > category types and a bypass gesture keeping out of each other's way. Here every
  > branch but `:exclude` is unreachable and the gate is always open, so one
  > predicate on `shiftKey` is the honest translation — a copied matrix with two
  > dead branches would read as a promise of gestures that do not exist.

  **The branches are no longer dead.** *ah ok yeah. but when no negative filter is
  selecgted, allow to select positively.* So cookbook has a positive filter and a
  gate, the prediction has arrived from the direction it was written for, and what
  changed is **which states this app can be in — not what the right gesture was.**
  This is `et.tr.filters/badge-gesture` ported rather than re-derived, for the reason
  the shelf copied the gesture in the first place: being the same finger in both
  apps. `src/cljc` exists for this file, as tracker's does for its own.

  What is deliberately **not** ported is the third gesture. Tracker's `shift?+alt?`
  is its bypass, which exists because it has six category types and rules about how
  a filter of one type interacts with another; cookbook has one kind of category and
  no bypass, so a branch for it would be the dead-branch mistake in the other
  direction — the one the old docstring refused to make.

  **And the word rule, for the same reason the gestures are here.** `?search=` is a
  `:where` clause, so the server evaluates it in SQLite — but the Scopes page has to
  answer the same question about a list it already holds, while the owner types, with
  no round trip. Two questions, one rule: what a word is lives in this file now, and
  `db/word-prefix-term-clause` builds its `instr` tests from the same string this
  file's own predicate walks. A second definition on the client would be a search box
  and a filter that agree until the day one of them is edited."
  (:require [clojure.string :as str]))

(def word-separator-chars
  "What ends a word for a word-prefix search — the whole rule, in one string, for
  both of the places that ask.

  **The call:** a word is a run of letters and digits, and *every* other ASCII
  character ends one — whitespace, but punctuation too. One rule with no
  exceptions to remember, and it is the one that makes the real titles behave:
  `re-heating` is two words, so `heating` finds it, and `make/start` is two, so
  `start` does. A curated list would have to answer why `.` separates and `+`
  does not. It is why `claude-coordinator` answers to `coordinator`, which is the
  case the Scopes page was built for.

  Anything **outside** ASCII is a word character, deliberately: `Käse` stays one
  word, so `se` does not find it. Treating every non-alphanumeric byte as a
  separator would have made every accented letter a word boundary.

  **It lived in `et.cb.db` until the Scopes page needed it.** The server turns it
  into `instr` tests inside a `:where` clause; `matches-word-prefix-search?` below
  walks it directly. Same string, so the two cannot come to disagree about what a
  word is — which they would the first day somebody added a separator to one list."
  (str " \t\n\r"
       "!\"#$%&'()*+,-./"
       ":;<=>?@"
       "[\\]^_`"
       "{|}~"))

(defn search-terms
  "A search string as the terms a row must satisfy: split on whitespace, blanks
  dropped, lower-cased. Empty for a blank search, which every caller reads as 'no
  narrowing'."
  [search]
  (if (str/blank? (str search))
    []
    (->> (str/split (str/trim (str search)) #"\s+")
         (remove str/blank?)
         (mapv str/lower-case))))

(defn word-prefix-match?
  "Whether `term` — already lower-cased — is the prefix of some word in `value`.

  **Written as the `instr` test it mirrors rather than as a tokenizer**, and that
  is the point: the server asks `instr(' ' || lower(col), sep || term) > 0` for
  every separator, so this asks the same question with `includes?`. Prepending a
  space turns 'at the start of the value' into 'after a separator', so the first
  word needs no case of its own, and matching stays **literal** — `%` and `_` are
  the characters they look like, here as there, because neither side ever builds a
  pattern.

  A tokenizer would have been shorter and would have been a second implementation
  of the rule: it would have had to decide for itself what an empty word is, what a
  trailing separator does, and what happens to a term that carries a separator of
  its own (`re-heat`, which must match `Re-heating` and must not match `re/heat`).
  Those answers already exist in the shape above.

  The one place the two sides can differ is the fold: this lower-cases with the
  host's own `toLowerCase`, which is Unicode, while SQLite's `lower()` is ASCII
  only. So `KÄSE` is found by `kä` here and not by the endpoint. The client is the
  more generous of the two, which is the safe direction for a filter that exists to
  warn about a clash — it shows the row you might collide with either way."
  [term value]
  (let [v (str " " (str/lower-case (str value)))]
    (boolean (some #(str/includes? v (str % term)) word-separator-chars))))

(defn matches-word-prefix-search?
  "Whether `values` satisfy `search` by the shelf's own rule: **every** term is the
  prefix of some word in **some** of them.

  The AND-across-terms / OR-across-columns shape of
  `db/build-word-prefix-search-clause`, so `cla co` matches a Scope titled
  `claude-coordinator` and a Scope titled `claude-code` tagged `coordinator` alike,
  and each term may land in a different value.

  A blank search matches everything, for the reason the clause is nil for one: a
  caller filtering an empty box wants the whole list, not none of it."
  [search values]
  (let [terms (search-terms search)]
    (every? (fn [term] (some #(word-prefix-match? term %) values)) terms)))

(defn matching-scopes
  "The Scopes a half-typed string leaves on screen: the shelf's own word-prefix
  rule, over each Scope's **title and its tags**.

  **Two surfaces ask this and they ask it for opposite reasons**, which is why it
  lives here rather than on either. The Scopes page filters while a name is being
  typed, to show what the new name might collide with; a Scope picker filters to
  find one chip among forty. Same question — *which of these answers to what I have
  typed* — so the answer is one function, and a picker cannot come to disagree with
  the page that made the Scopes about what a word is.

  **The tags are in it because they are already what a Scope answers to.** A word
  typed here is a word that finds this Scope's Recipes on the shelf, so matching on
  title alone would hide the Scope that has claimed it. A blank search leaves the
  list whole, which is what makes this a filter and not a mode."
  [scopes search]
  (filterv #(matches-word-prefix-search? search [(:title %) (:tags %)]) scopes))

(defn badge-gesture
  "Which of a Scope badge's two filter gestures a click runs, or nil for none.

  `modifiers` is `{:shift?}` off the event; `gate` is what the shelf's two filters
  are currently doing, `{:negative-active? :positive-active?}`.

  **Plain click → `:toggle`, the positive filter, refused while an exclusion is
  up.** That is his rule in as many words — *when no negative filter is selecgted,
  allow to select positively* — and it is tracker's `:else` branch with
  `type-filtered?` dropped. Dropping it is not a simplification: over there that
  flag means *a filter of this badge's own **type** is selected*, and tracker's
  positive filter is one category per type, so the plain click is refused because
  the slot is taken. Cookbook's positive filter is a **set** — the union he asked
  for — so there is no slot to be taken and adding a second Scope is the point.
  A flag copied across with no such rule behind it would be a gate that refused the
  gesture it exists for.

  **Shift+click → `:exclude`, refused once a positive selection is up.** This is
  tracker's condition kept whole — `(or negative-active? (not positive-active?))` —
  and it is the half of the rule he did *not* state, so it is argued rather than
  assumed:

  - The two filters are opposite narrowings over one dimension, and his own gate
    already says they do not operate at once. Keeping only his direction would make
    that true going one way and false coming back: no way to add a selection while
    hiding, but every way to start hiding while selecting.
  - Every card on a positively narrowed shelf carries a selected Scope. Shift is
    also on those badges, so the first thing the gesture offers is *hide the very
    Scope you asked to see* — a control whose most obvious use is a contradiction.
  - The sentences on screen stay sayable. `views/recipes`' `empty-message` ranks
    its four narrowings by which of them can be said in company; with both Scope
    filters live at once there is a combination — *nothing left once those are
    hidden* over *nothing left in the ones you picked* — whose honest sentence is
    neither of them.
  - And it stays reversible: `negative-active?` keeps shift open once an exclusion
    exists, so adding a second one still works. The gate closes the *first* step
    into the other filter, never the way out of one.

  The `nil` answers are the point of the shape. A refused gesture is not folded
  into the other path, because on a badge that fall-through would run the opposite
  filter to the one the finger asked for — tracker's own reason, and it holds here
  with two gestures as well as with three."
  [{:keys [shift?]} {:keys [negative-active? positive-active?]}]
  (cond
    shift? (when (or negative-active? (not positive-active?)) :exclude)
    :else  (when-not negative-active? :toggle)))

(defn badge-consumes-click?
  "Whether the badge keeps a click to itself or lets it through to the card header
  it sits in — which is the expand/collapse target, so a click that falls through
  opens the card.

  **It keeps a click a gesture runs on, and equally one it deliberately refuses.**
  Tracker's rule, ported with its shape: ask whether *either* gesture is open in
  this gate state, not merely whether this one is. A shift+click while a positive
  selection is up, and a plain click while an exclusion is up, are both refusals —
  and a refusal that fell through to the header would answer a filter gesture by
  expanding a card, which is the app doing something unrelated to what was asked.

  **The consequence worth stating**: while an exclusion is active a plain badge
  click does nothing at all, and the card does **not** expand. That is a control
  refusing an input, and it is why the badge's tooltip says which state it is in
  rather than describing one gesture forever — see `views/recipes/scope-badge-hint`.
  A refusal nothing explains is the same trap `excluded-scopes-strip` exists to
  prevent, one layer up.

  There is no state in which a badge has nothing to offer, which is what makes the
  unconditional pointer cursor honest: no filters at all leaves both gestures open,
  a positive selection leaves the plain one, an exclusion leaves the shift one. The
  test file asserts that over every gate state rather than leaving it to be read off
  the two branches."
  [modifiers gate]
  (boolean (or (badge-gesture modifiers gate)
               (badge-gesture {} gate)
               (badge-gesture {:shift? true} gate))))
