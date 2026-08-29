(ns et.cb.server.recipe-handler
  (:require [clojure.string :as str]
            [et.cb.caution :as caution]
            [et.cb.server.common :as common]
            [et.cb.db.proposal :as db.proposal]
            [et.cb.db.recipe :as db.recipe]))

(defn- lean?
  "`?detail=full` is the only thing that widens the projection. Anything else,
  including no param at all, gets the retrieval index."
  [req]
  (not= "full" (common/query-param req "detail")))

(defn- human-only?
  "`?human=true` is the only thing that narrows the listing to what a human has
  edited — read the same way `?detail=full` is read, so absent, `false` and
  garbage all mean 'do not narrow' rather than each meaning something."
  [req]
  (= "true" (common/query-param req "human")))

(defn- excluded-scope-ids
  "`?exclude-scopes=3,7` — which Scopes' Recipes to hide from the listing.

  **Ids and not tracker's names.** Tracker's `parse-excluded-categories` takes
  names because that is the currency a caller names a category in over there;
  cookbook's is the id everywhere a caller names a Scope — `scope_ids` on POST and
  PUT, `GET /api/scopes` handing them back — and a second convention inside one app
  is the one that rots.

  Parsed leniently by `common/parse-id-list`, which argues why a read may drop
  junk where a write may not. Nothing is checked here: an id the caller does not
  own is well-formed and excludes nothing, which the db layer arranges by joining
  through `scopes`, and answering 404 for it would tell a caller which ids exist —
  the same call `bad-scope-ids?` makes for a write body."
  [req]
  (common/parse-id-list (common/query-param req "exclude-scopes")))

(defn- included-scope-ids
  "`?include-scopes=3,7` — narrow the listing to the Recipes filed under at least
  one of those Scopes. `excluded-scope-ids`' mirror, and every word of that
  docstring applies: ids and not names, because the id is the currency a caller
  names a Scope in everywhere else in this app, and parsed leniently by
  `common/parse-id-list` because a read may drop junk where a write may not.

  **Named `include-scopes` for the symmetry and not `scopes`**, which was the
  shorter option and the worse one: `scopes` is already the name of a *key on a
  row* in this API's responses, and a query parameter with the same name as a
  field means two things a page apart. The pair reads as a pair, which is what a
  reader needs, since the two do opposite things to the same listing."
  [req]
  (common/parse-id-list (common/query-param req "include-scopes")))

(defn- listing-order
  "`?order=newest` — which of the two orders the listing comes back in, defaulting to
  the ranking.

  **Read the way `?detail=full` and `?human=true` are read**: one exact value means
  the other thing and everything else means the default, so absent, `?order=`,
  `?order=NEWEST` and `?order=whatever` all get the ranked shelf. That is this API's
  established way of reading a parameter, and the alternative — a 400 for a name it
  does not know — would be a read that refuses to answer, which no other narrowing or
  ordering here does.

  It is a keyword out of `db.recipe/orders`' own keys rather than a string compared
  twice, so the vocabulary lives in one place: the day there is a third order, this
  function needs nothing but the entry that already has to exist over there."
  [req]
  (if (= "newest" (common/query-param req "order")) :newest :ranked))

(defn- human-write?
  "Whether this write is one to record as a human edit: the caller carries no
  *machine* token. `common/machine-caller?` reads the token's `:machine?` claim,
  put there at login, so this is authenticated rather than guessed — and a dev
  owner with no token, whom `authenticated?` deliberately accepts, is not mistaken
  for a machine.

  'Not a machine' is the owner here, because an anonymous caller never reaches a
  write handler: `wrap-recipe-write-guard` answers those with a 401 in front of
  the router."
  [req]
  (not (common/machine-caller? req)))

(def ^:private writable-fields
  "What a recipe write may carry. Named once so the create and the save cannot
  drift apart about it, and as an allowlist rather than a dissoc of the fields that
  are refused: `published`, `has_human_edit` and `source` are not writable from a
  body, and a key nobody selected is a key nobody can smuggle in.

  **`reason` and `context` are in the list and are still not content.** They are
  writable — a version records what its writer said about itself — but they change
  no text, so `content-would-change?` never consults them: a machine `PUT` carrying
  only a new reason changes nothing, and a no-op stays a no-op. What makes them
  unlike the other five is that they are *required* of one kind of caller, which is
  `machine-write-explanation-missing` below and not this list."
  [:title :useful_when :description :tags :scope_ids :reason :context])

(def ^:private explanation-fields
  "The pair a machine write has to carry: **why** the change was made, and **what
  the agent was doing** when it made it.

  Two fields rather than one — *make it explicit. lets make two fields there, both
  mandatory. reason and context* — because they answer two questions and only one
  of them is guessable from the diff. A rewrite six months old shows *what* changed;
  `reason` says why it was worth changing, and `context` says what the agent was
  working on at the time, which is the thing that makes the queue readable in a
  batch: the entries from one session read as one session."
  [:reason :context])

(defn- machine-write-explanation-missing
  "The field names a machine write left blank, in order, or nil when it carried
  both. nil for the owner's own writes, always.

  **Only a machine is held to this**, and the asymmetry is the point: the pair
  exists to explain an agent's edit to the person reviewing it, and the owner is
  that person — a field asking him why he edited his own Recipe would be a prompt on
  every save answered by nobody. So his writes carry NULL, the schema allows it (see
  migration 015), and this is the only place the requirement lives.

  Blank counts as missing, whitespace included: an agent that sends `\" \"` to get
  past a presence check has satisfied nothing, and the surfaces would show an empty
  line where an explanation was promised."
  [req body]
  (when (common/machine-caller? req)
    (seq (filterv #(str/blank? (str (get body %))) explanation-fields))))

(defn- explanation-missing-response
  "One 400 for both writes, naming the fields that were missing and what they are
  for — an agent that gets this back has to be able to fix it without reading
  anything else, which is the same standard `/api/describe` is held to."
  [missing]
  {:status 400
   :body {:error (str "A machine write must say why: "
                      (str/join " and " (map name missing))
                      " "
                      (if (= 1 (count missing)) "is" "are")
                      " required. `reason` is why this change was made; `context` is"
                      " what you were working on when you made it.")
          :missing (mapv name missing)}})

(defn- bad-scope-ids?
  "Whether `scope_ids` is present and is not an array of integers.

  Refused with a 400 rather than coerced, because the two ways it can be wrong
  mean opposite things and neither should be guessed at: a bare number would have
  to be read as a one-element array, and a string as either an id or a title. What
  is *not* a 400 is an id the caller does not own — that is well-formed and simply
  drops out (see `db.scope/set-recipe-scopes!`), and answering 404 for it would
  tell a caller which ids exist."
  [body]
  (and (contains? body :scope_ids)
       (let [ids (:scope_ids body)]
         (not (and (sequential? ids) (every? int? ids))))))

(def ^:private bad-scope-ids-response
  {:status 400 :body {:error "scope_ids must be an array of Scope ids"}})

(defn- overwrite?
  "`?overwrite=true` is the only thing that lets a machine replace a proposal that is
  already pending — read the way `?detail=full` and `?human=true` are read, so absent,
  `false` and garbage all mean 'do not' rather than each meaning something. A
  destructive parameter is exactly the one that must not be satisfied by `?overwrite=1`
  or `?overwrite=maybe`."
  [req]
  (= "true" (common/query-param req "overwrite")))

(defn- pending-body
  "How a pending proposal is described to an agent, in the 409 and in the 202 alike —
  one shape, so a caller that learns to read it from one learns to read the other.
  `base_version` is in it because that is what tells the agent what the proposal was
  written against, and `modified_at` because that is what says whether it is the text
  the agent itself last wrote or a revision it has since forgotten.

  **`reason` and `context` are in it for the second of those reasons**, one field
  further: an agent meeting the 409 is reading a proposal it may not have written —
  another agent's, or its own from a session it no longer remembers — and the two
  sentences saying why that text is there are what decide whether replacing it with
  `?overwrite=true` is right. On the 202 they are the receipt for what was just
  filed."
  [proposal]
  (select-keys proposal [:title :useful_when :description :base_version
                         :created_at :modified_at :reason :context]))

(defn- stale-write-response
  "The 409 for a save that raced somebody else, **named**. PUT /api/recipes/:id can
  now answer 409 for two quite different reasons — this one, and a proposal already
  waiting — and an agent that had to tell them apart by guessing at the body's shape
  would be a trap. So each carries a `:reason`, and this one keeps the `:current` it
  has always carried.

  `:current` includes the filing because the client redraws its copy from it, and a
  `:current` without `scopes` would blank the badges as a side effect of a refused
  save."
  [ds user-id id]
  {:status 409 :body {:error "Recipe was modified elsewhere"
                      :reason "modified-elsewhere"
                      :current (db.recipe/get-recipe ds user-id id
                                                     {:lean? false :scopes? true})}})

(defn- published?
  "Whether this row carries the latch. A comparison and not a truth test: JSON gives
  0 and 1, and 0 is not falsey here any more than it is in the client."
  [recipe]
  (= 1 (:published recipe)))

(defn- read-audience
  "Owner or visitor, decided once per read. A visitor is deliberately *not*
  described by a user-id: `common/get-user-id` gives nil for one, and the db
  layer reads a nil user-id as `user_id IS NULL`, which is a real owner in this
  schema — so the visitor path never asks for a user-id at all."
  [req]
  (if (common/authenticated? req)
    (common/get-user-id req)
    db.recipe/visitor-audience))

(defn- caution-body
  "The line-level provenance split for one Recipe's body, or nil for a caller who
  is not to be served it.

  **The legend rides with the ranges, in one key**, because neither half is
  meaningful alone: the numbers need reading and the reading is about nothing
  without them. A sibling `caution_legend` would also be two keys for a rule that
  wants to be one omission — see the visitor paragraph below, which has to take the
  legend away too, and would be a bug the day it took away only one of them.

  **Only on a `?detail=full` read of one Recipe, and only for a logged-in caller.**
  Both halves of that are decided here rather than in `et.cb.caution`, which is
  arithmetic and has no audience: this is where the app already knows whether a
  body was handed over and to whom.

  The visitor refusal is the history's refusal, inherited. These ranges are derived
  from `list-versions`, which answers 404 for an anonymous caller at every id —
  publishing puts today's text in public, not the record of who wrote which part of
  it. So the key is absent for a visitor, like `tags` and `scopes`, rather than
  present and empty.

  **It costs a second read and a fold over the whole history**, on the app's
  hottest route: `list-versions` re-reads the row and selects every history row for
  it, and `assess` then diffs each version against the one before it. That is
  linear in versions and quadratic in lines, which is nothing at the size of a
  Recipe and is the thing to look at first if this route ever gets slow. It is not
  cached and there is no column for it, deliberately — a stored split could come to
  disagree with the labels the version list shows, which is the argument
  `db.recipe/source-split-columns` already makes about the counts on the card."
  [ds req id]
  (when (common/authenticated? req)
    {:legend caution/legend
     :ranges (caution/ranges
              (:versions (db.recipe/list-versions ds (common/get-user-id req) id)))}))

(defn list-recipes-handler
  "GET /api/recipes — the caller's recipes, **ranked by how much they are used**,
  optionally narrowed by ?search over the **title, the tags and the words of the
  Scopes the Recipe is filed under**.

  **The default order is a weighted sum: `0.7 × view_count + 0.3 × version`, highest
  first**, then most recently modified, then highest id. `view_count` is how often
  the Recipe's description was actually fetched (see GET /api/recipes/:id) and
  `version` is how many times it has been edited, so the shelf leads with what has
  proved useful rather than with whatever was touched last. A listing is therefore a
  recommendation and not just an inventory: the first entries are the ones somebody
  has kept coming back to. The weights are on the raw counts, so once a Recipe has
  been read a few dozen times the version term stops being able to move it.

  **`?order=newest` asks for the other order: most recently added first**, which is
  `created_at` descending and then `id` descending — the id because `created_at` is
  second-resolution and two Recipes written inside one second would otherwise be an
  untotal order that shuffles between two identical requests. That is the only other
  value; **anything else, including no parameter at all, is the ranking**, read the way
  ?detail and ?human are read.

  Note what most recently *added* is not: most recently **touched**. That is
  `modified_at`, which is the ranking's first tiebreaker, and a Recipe edited this
  morning is first by it and among the last by `created_at`.

  Both orders are served to every caller — this UI, an agent, an anonymous visitor —
  and neither is anybody's private view. There is still no general ?sort: two orders
  answer two questions the owner asked for, and anything else you want, sort the rows
  you were given.

  **?search is a word-prefix match, AND across terms.** The search splits on
  whitespace, and a recipe matches when every term is the prefix of some word in
  its title *or its tags*, case-insensitively: `?search=ab cd` finds `abc cde` but
  not `ad cd`, and `?search=cd` does not find `abcd` — a prefix is not a
  substring. A word is a run of letters and digits, so `heating` finds
  `Re-heating` and `start` finds `make/start`. The terms need not all land in the
  same column: a recipe titled `Sourdough starter` tagged `bread baking` is found
  by `sour bak`. Nothing else is searched on the row: not useful-when, not the
  description. `%` and `_` are ordinary characters here, not wildcards.

  **The Scopes a Recipe is filed under lend it their words too** — their own
  `title` and their own `tags` (see GET /api/scopes), searched by the same
  word-prefix rule as if they were in the Recipe's title. A Recipe titled `abc def`
  filed under a Scope titled `utwig` and tagged `backend tag2 tag3` is found by
  `utwig`, by `backend`, by `tag2` — and by `ab utw`, one term off the title and one
  off the Scope, since each term may land anywhere. A Scope's *description* is not
  searched, for the reason useful-when is not: names and curated words, never prose.
  Two things follow. A Scope's words are **inherited, not copied**: tagging one
  Scope makes every Recipe in it findable by that word in a single write, and
  unfiling a Recipe takes the words away again. And a Recipe filed under nothing is
  searched by its own two columns exactly as before.

  **Tags on the row are searched for every caller and sent only to the owner.** An
  anonymous visitor's rows carry no `tags` key at all — the column is not in their
  projection — while their ?search still matches against it, so a term aimed at the
  row returns the same recipes whoever asks. That is deliberate: one search behaves
  one way, and columns that shifted with the caller would make the same query mean
  two things. The consequence, stated rather than left to be discovered: a visitor
  can find out that a published Recipe carries some word by probing search terms,
  though the tags themselves are never readable. A machine token reads in the
  owner's audience, so an agent both reads and writes tags — cookbook is an agentic
  memory store and a curated retrieval index is most of what an agent gets out of
  one. The boundary here is around anonymous readers, not machines.

  **The Scopes' words are the exception: they are searched for a caller who may see
  the filing, and for nobody else.** An anonymous ?search is the two-column one
  described above — the filing is not reached at all. It is the same refusal as
  `?exclude-scopes` and `?include-scopes` being ignored for a visitor, arriving
  through the search: a caller who could match a Scope's title could test which
  published Recipes carry it, one probe at a time, which is precisely what those two
  are not honoured for. So a Scope's tags are *more* private than a Recipe's, and
  that is the only place the two kinds of tag differ. A machine token is on the
  owner's side of the line, here as everywhere.

  **?human=true narrows to the Recipes a human has edited**, and only the exact
  value `true` does: absent, `false` or anything else leaves the listing alone.
  A Recipe carries `has_human_edit` once it has been created or saved by a caller
  holding something other than a machine token — the web UI, in practice, since
  `cookbook-tui` authenticates as the machine user. Publishing does not count and
  neither does a save that changed nothing. The bit is only recorded going
  forward from the migration that introduced it, so a Recipe written before that
  and not saved since reads as not-human-edited even if the owner wrote every word
  of it: what was never recorded is not asserted. It composes with ?search.

  **There are two Scope filters and they point opposite ways** — a *negative* one
  and a *positive* one, each taking a comma-separated list of **Scope ids** from
  GET /api/scopes.

  **?exclude-scopes=3,7 hides the Recipes filed under those Scopes**, and is the
  negative one. Several ids take more away and never less — a Recipe survives only
  if it carries none of them, so one carrying an excluded Scope alongside a kept one
  is still gone. **A Recipe filed under no Scope at all is never hidden by this**,
  which is the case worth saying rather than leaving to be discovered.

  **?include-scopes=3,7 keeps only those Recipes**, and is the positive one: a
  Recipe is listed if it carries **at least one** of the named Scopes — *an OR
  filter for scopes* — so several ids take *less* away and never more, which is the
  one thing about the pair that reads backwards until you have said it out loud.
  **A Recipe filed under no Scope at all falls out of this one**, which is the same
  mechanism as the sentence above producing the opposite answer, and the wanted one:
  asked for the Recipes in Baking, nobody is asking for the unfiled ones too.

  This used to say there was no way to ask for the Recipes *of* a Scope, because the
  owner had asked to hide rather than to select. He has since asked to select as
  well — *and on the main page, below the searchbar, list all scopes and have them be
  an OR filter for scopes* — so both exist, and the sentence that replaces it is that
  the two are **independent clauses**: passing both means *in these and not in
  those*, and this endpoint has no opinion about whether a caller should. The UI's
  rule that the two never operate at once is a rule about gestures and lives there.

  All four narrowings compose, because all four are clauses on the one query.

  Junk narrows by nothing rather than being refused, for both: a non-numeric id, an
  empty list, and an id you do not own all answer 200. **What that means differs
  between them and is worth knowing before you rely on it** — an unowned id
  *excludes* nothing, so the listing is unchanged, and *includes* nothing, so the
  listing is empty. Neither is an error, for the same reason: a 404 would say which
  ids exist, which is the call `scope_ids` already gets on a write.

  **An anonymous visitor's ?exclude-scopes and ?include-scopes are ignored
  entirely**, and that is a refusal rather than the filter applied to fewer rows. A
  visitor is sent no `scopes` key on anything, and — unlike the tags, whose presence
  is testable through ?search — the Scopes' presence is not testable either, because
  **a visitor's ?search does not reach the filing**: a Scope lends its title and its
  tags to the search of a caller who may see the filing, and to nobody else's, which
  is that same decision made a third time rather than an exception to it (see the
  search paragraphs above). Honouring either would hand that back, and the positive one
  hands it back **directly**: rows vanishing on request is a way to ask which
  published Recipes carry Scope 4 one id at a time, and rows *arriving* on request is
  the same question answered in one call. Scopes are a stronger boundary than the
  tags on purpose, and the owner said so in as many words: *to logged in users only,
  no matter what*. A machine token reads in the owner's audience and is honoured,
  like every other Scope read.

  **Every row carries the provenance split**: `machine_versions` and
  `ui_versions`, counting how many of that Recipe's versions an agent wrote and how
  many were saved by hand in the web UI. **The two always sum to `version`**, so
  `machine_versions = version` says every version of this Recipe is an agent's —
  which is exactly the rule that decides whether an agent may edit it directly or
  has to propose (see PUT /api/recipes/:id). `source` is there too, the *current*
  version's own label, one of `ui` or `machine`.

  There is no third bucket and no null: a version's origin used to be unrecorded for
  everything written before cookbook noted it, and that category was retired — the
  owner said those versions were his, so they read `ui` and the column now refuses
  anything but the two. Per-version labels are on GET /api/recipes/:id/versions.

  **`pending` is 1 when a proposal is waiting** for the owner to approve or dismiss
  on that Recipe, 0 otherwise (0/1 like `published`, because that is what this schema
  serves). It saves an agent a doomed round trip: a PUT to a Recipe with something
  already pending answers 409 unless it carries `?overwrite=true`. What it does *not*
  tell you is whether you may write at all — that is `machine_versions = version`,
  above. There is deliberately no `approval_required` flag beside it: the rule is a
  comparison of two numbers you are already sent, and a flag could come to disagree
  with them. A visitor's rows carry no `pending` key at all, like the tags: whether an
  agent is waiting to rewrite something is the owner's business.

  **Lean by default**: the response carries no `description` key at all — and,
  for a visitor, no `tags` key either, at any ?detail. Pass ?detail=full to
  include the description. The two short fields are meant as a retrieval
  index — scan them, decide which recipe you want, then fetch that one body. The
  counts are aggregated in the same query and cost the caller no extra round trip,
  and they do not widen the projection — a lean listing still has no body in it.

  An anonymous visitor is served the **published** recipes instead of anybody's
  private ones. An unpublished recipe is absent from that listing rather than
  redacted in it: no title, no id, and nothing that reveals it is there. Both
  narrowings run inside that audience, so ?human=true can only take rows away from
  what the caller could already see.

  **And what a visitor is shown is the last approved version, always.** An agent's
  proposal is not a version and no read here consults one, so a Recipe with a rewrite
  waiting on it lists exactly as it did before the rewrite was offered — for a visitor,
  for the owner, and for the agent that offered it. Publishing does not change that
  either: it is allowed while a proposal pends, and it publishes the row, which is the
  approved state."
  [req]
  {:status 200
   ;; Both Scope narrowings are passed for every caller and the db layer is what
   ;; refuses a visitor them — `list-recipes` decides that off the audience, the way
   ;; `with-scopes` decides whether the Scopes are attached at all. Asking the
   ;; question here as well would be two places answering it, which is how they
   ;; come to disagree; the flag is a request and the audience is the answer. That
   ;; matters more with two parameters than it did with one: a guard written here
   ;; would have had to be remembered twice.
   :body (db.recipe/list-recipes (common/ensure-ds) (read-audience req)
                                 {:search-term (common/query-param req "search")
                                  :human-only? (human-only? req)
                                  :excluded-scope-ids (excluded-scope-ids req)
                                  :included-scope-ids (included-scope-ids req)
                                  :order (listing-order req)
                                  :lean? (lean? req)})})

(defn get-recipe-handler
  "GET /api/recipes/:id — one recipe. Lean by default like the listing;
  ?detail=full adds the description. 404 when the id matches nothing you own.

  For an anonymous visitor only a published recipe matches, and an unpublished
  one is the same 404 as an id that does not exist. `?detail=full` then shows a
  visitor every **content** field of it — title, useful-when and description —
  because the collapse is about verbosity.

  **Tags are the exception, and they are why that sentence now says 'content'.**
  Until they existed the publish latch was the whole privacy boundary and
  ?detail=full could be described as widening everything; a visitor's projection
  never names `tags`, at any ?detail, so the key is absent rather than empty. The
  owner and a machine token (which reads in his audience) get it. Note the asymmetry
  that goes with it: ?search still matches tags for a visitor — see the listing —
  so their presence is testable even though their contents are not readable.

  **`scopes` is the same, and stricter.** A caller who may see them gets
  `[{id, title, description}]`, empty for an unfiled Recipe; a visitor gets no
  `scopes` key at any ?detail, and there is no query that would produce one for
  them — the join is not run rather than run and then hidden. Unlike the tags,
  their presence is not testable either: a visitor's ?search does not reach the
  filing (a Scope's own title and tags widen the owner's search alone, which is why
  a Scope's tags are stricter than a Recipe's), and the two
  parameters that could have made them testable — ?exclude-scopes and
  ?include-scopes on the listing — are both ignored outright for an anonymous caller
  rather than applied to their published rows. Watching rows vanish is a way of
  asking, so it is refused as one; **asking for the rows of a Scope is the same
  question with the answer handed over**, so it is refused by the same line, in
  `list-recipes`, off the audience.

  **`pending`** rides along here too, 1 when a proposal is waiting on this Recipe —
  see GET /api/recipes for what it does and does not say. A visitor gets no such key.
  Note what a pending proposal does *not* change: this response is the Recipe as it
  reads now, at the last approved version, whatever an agent has queued against it.
  **That holds at `?detail=full` and it holds for a visitor**, which is the case worth
  stating outright: a published Recipe with an unapproved rewrite waiting on it hands an
  anonymous reader the approved text and no part of the proposal. What a visitor is
  shown is the last approved version, always.

  **`caution` is the line-level provenance split of the body**, and it rides along
  on a ?detail=full read only — there is no body on a lean one for it to be about.
  It is `{legend, ranges}`. `ranges` is `[{from, to, caution}]`: ranges of the
  description's lines, one-based and inclusive, each with a number from `1.0` — his,
  treat as sacred — down to `0.0` — an agent's, up for grabs — and the spectrum in
  between where a stretch has been written by both. They cover the body exactly once,
  in order, and adjacent lines that come out at the same number are one range.

  `legend` is that scale said in one line, and it is in the response **on every full
  read** rather than here only: you may have fetched one Recipe and never read this
  text, and a bare `0.0` beside a line range is a number you would have to already
  know how to read. It is the same string every time — it explains the spectrum, not
  this Recipe's answer — so it is documentation to read once and thereafter a
  constant, not a field to branch on.

  **It is not the counts on the listing asked again.** `machine_versions` and
  `ui_versions` say how many *versions* came from where; this says which *lines of
  the text as it stands now* did. A Recipe he wrote once and an agent has edited
  nineteen times reads `1(ui)/19(machine)` on its card while his opening paragraph
  still reads `1.00` here. That is the point of it: an agent about to rewrite this
  body can see which parts of it are its own to redo and which are his to leave
  alone, which the version counts cannot tell it.

  It is computed from the Recipe's version history by `us-vs-them`, a sibling
  library, and it is an **estimate** — a diff-based attribution and not a record
  anybody kept per line. A machine token is served it, deliberately: it is the one
  number in this API written for an agent to act on. **A visitor gets no `caution`
  key at all**, legend included, at any ?detail, because it is derived from the
  version history and the history is the owner's — GET /api/recipes/:id/versions is
  a 404 for an anonymous caller at every id, published or not.

  **A ?detail=full read of an existing Recipe counts as a consumption**: it bumps
  that Recipe's `view_count`, which is how the shelf is ranked (see GET
  /api/recipes). This request is the only one in the API that hands back the
  description of one Recipe, so it is the only one that proves somebody used it —
  a listing is a scan and counts for nothing at any ?detail. A **lean** read of
  this same path does not count either: it returns the retrieval index, not the
  Recipe. Nothing about the increment is in your hands — there is no header, no
  parameter and no way to read without counting, and every caller counts, an
  anonymous reader of a published Recipe included. A 404 does not count, so an id
  that does not exist and an unpublished Recipe a visitor asked for both leave the
  number alone.

  **And it is attributed: the same read bumps `human_reads` or `machine_reads`.**
  A read carrying a **machine token** is the machine one; everything else is the
  human one, *including an anonymous reader* — a person read it, and which client
  they used is not something this API is told. Note that this is the opposite
  treatment of silence from the one the write paths give it, where an unattributed
  write is a machine's (see `source` on PUT /api/recipes/:id): a write has two
  possible authors because a visitor cannot write, and a read has three sources that
  have to land in two buckets.

  **The two do not necessarily sum to `view_count`, and that is not a bug to
  report.** The total has been counted since an earlier migration than the split, so
  a Recipe read before attribution existed carries reads that belong to neither
  bucket: `view_count - human_reads - machine_reads` is exactly that remainder, and
  it can only shrink relative to the total as a Recipe goes on being read. **The
  shelf is ranked on the total** and not on either bucket — the split is there to be
  read, not to reorder anything.

  Both counters ride on the listing and on this read for a caller who may have
  them, beside `view_count`. **A visitor gets no `human_reads` or `machine_reads`
  key at all**, at any ?detail, while they do get `view_count`: the total explains
  the order of the shelf they are looking at, and the split would instead say how
  much of the owner's traffic is his own agents."
  [req]
  (let [ds (common/ensure-ds)
        id (common/recipe-id req)
        full? (not (lean? req))
        recipe (when id (db.recipe/get-recipe ds (read-audience req) id
                                              {:lean? (not full?) :scopes? true}))]
    (if recipe
      (do
        ;; After the read, and the response is **the row we read** rather than a
        ;; re-read of it. A caller does not need its own view reflected in the
        ;; number it is holding — that number is 'reads before mine', which is
        ;; the honest thing to have fetched — and a second SELECT would double
        ;; the work on the app's hottest read to say something nobody asked. The
        ;; other reading is the one a reviewer will assume, hence this comment:
        ;; the next listing shows the incremented value.
        ;; **The caller's kind is read once, here, and handed down.** `machine-caller?`
        ;; is the token's own claim and it is the same question `human-write?` asks on
        ;; the write paths — asking it a second time inside the db layer would be the
        ;; second way of deciding who the caller is that `source-of` refuses. What it
        ;; is *not* is `human-write?`'s flag inverted: silence there means a machine,
        ;; and silence here means an anonymous visitor, whose read is a human's. See
        ;; `record-view!`.
        (when full? (db.recipe/record-view! ds id (common/machine-caller? req)))
        ;; `full?` is asked here rather than inside `caution-body` because it is
        ;; a fact about *this response* — there is no body on a lean read for a
        ;; split to be about — while who may be served one is a fact about the
        ;; caller, and that lives in the one function. nil for either reason leaves
        ;; the key off entirely rather than null, which is the shape `tags` and
        ;; `scopes` already take for a caller who may not have them.
        (let [split (when full? (caution-body ds req id))]
          {:status 200 :body (cond-> recipe split (assoc :caution split))}))
      {:status 404 :body {:error "Recipe not found"}})))

(defn add-recipe-handler
  "POST /api/recipes — create a recipe from {:title :useful_when :description
  :tags :scope_ids}. The title is required and must be non-blank; the others
  default to empty.

  `tags` is a plain string of extra words to find this Recipe by — whatever the
  owner or an agent would search for that the title does not say. It is searched
  for everybody and shown to nobody but the owner (see GET /api/recipes), and it
  is not versioned: changing it later writes no history row. There is no syntax to
  get right, no separator that means anything and no list to keep deduplicated.

  `scope_ids` is an **array of Scope ids** — the categories to file this Recipe
  under, 0 to n of them, from GET /api/scopes. Omit it and the Recipe is filed
  under none, which for a row that did not exist yet is the only thing an omission
  could mean. Ids you do not own are dropped rather than refused, so the `scopes`
  on the response is the receipt for what was actually filed. 400 if it is not an
  array of integers.

  The new recipe is version 1 with no history, and it is **private**:
  `published` is not accepted here, because publishing is its own deliberate
  act — POST /api/recipes/:id/publish. 201 with the created recipe in the full
  shape, 400 on a blank title.

  A create from a caller without a machine token sets `has_human_edit` on the new
  row; a machine's create leaves it at 0. The same fact labels the version being
  created: `source` is `ui` or `machine` accordingly. Neither is writable from the
  body — both are taken from the token, like the owner the row is filed under.

  **A machine's create is announced in the owner's inbox** (GET /api/inbox) as a
  `created` entry, so writing here is not writing unobserved: he goes through what
  his agents wrote oldest-first. His own creates make no entry — the inbox is the
  record of what the agents did, not a change log.

  **From a machine token, `reason` and `context` are required, and a create without
  both is a 400 that writes nothing** — no Recipe and no inbox entry. They are two
  questions, and answering one in the other's field wastes the pair:

  - **`reason`** — why this Recipe is worth writing. What you learned, what it is
    for, why it deserves a place on the shelf rather than living in the transcript
    of the session that produced it.
  - **`context`** — *what you were working on when you wrote it.* The task, the
    repository, the bug, the conversation. Name it concretely enough that a reader
    six months from now can tell which piece of work this came out of: `debugging a
    flaky auth test in tracker` says something, `working on code` does not. This is
    the field the owner reads to make sense of a queue of entries in a batch — the
    ones from a single session read as a single session — and it is the one thing
    that cannot be recovered from the diff afterwards.

  Both are stored on the version this create makes and shown beside it on the
  Recipe's version page. Blank or whitespace does not count as an answer. The owner's
  own creates carry neither and are never asked for them: the pair exists to explain
  an agent's work to the person reviewing it."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [title] :as body} (:body req)]
    (cond
      (str/blank? (str title))
      {:status 400 :body {:error "title is required"}}

      (bad-scope-ids? body)
      bad-scope-ids-response

      ;; Checked before the write and after the shape checks, so a machine that
      ;; forgot to say why is told that and not something else — and so that a
      ;; refused create writes nothing at all, inbox entry included.
      :else
      (if-let [missing (machine-write-explanation-missing req body)]
        (explanation-missing-response missing)
        {:status 201
         :body (db.recipe/create-recipe (common/ensure-ds) user-id
                                        (select-keys body writable-fields)
                                        {:human? (human-write? req)})}))))

(defn update-recipe-handler
  "PUT /api/recipes/:id — save {:title :useful_when :description :tags
  :scope_ids}, plus {:reason :context} from a machine. A field you leave out keeps
  its current value, so an edit meant for one field cannot silently clear the
  others; a blank title is refused with 400.

  **From a machine token, `reason` and `context` are required on every write to this
  route, and one without both is a 400 that writes nothing** — not the content, not
  the filing, not a proposal. Two questions, and the second is the one agents get
  wrong by answering the first twice:

  - **`reason`** — why you are changing this Recipe. What was wrong, missing or
    newly learned.
  - **`context`** — *what you were working on when you changed it.* The task, the
    repository, the bug, the conversation that led you here. `while fixing a
    flaky auth test in tracker` is a context; `improving the docs` is not. It is
    what lets the owner read a queue of entries in a batch and see which session
    each one came out of, and it is the only part of a write that cannot be
    reconstructed from the diff.

  **The pair follows the write wherever it lands.** If the save goes straight
  through, they are stored on the version it makes. If it becomes a proposal (below),
  they ride with the proposal, are shown on the item page where you approve or
  dismiss it, and are **copied onto the version on approval** — so the sentences read
  while deciding are the sentences the version page keeps. They are replaced whole by
  a revision (`?overwrite=true`), never merged from the previous version: a reason is
  about the write it came with. Blank or whitespace is not an answer.

  **The requirement holds even when the write turns out to change nothing**, because
  whether it would is only knowable after the comparison the answer would depend on.
  One rule — every machine write says why — rather than one that is true except when
  it happens not to be. The owner's own saves carry neither and are never asked.

  Every save that changes **content** archives the outgoing state as a version
  and moves the row to the next one. A save that changes nothing is a no-op —
  same version, no history row. Pass `modified_at` from the last read to be told
  (409) when someone else saved in between.

  **A save that changes only `tags` is neither of those.** It is written, because
  tags are a field like any other; it makes no version, because tags are not part
  of the Recipe's content — no history row, no version bump, and `source` and
  `has_human_edit` untouched, since there is no new version for a label to be
  about and filing a Recipe under a word is not writing it. It does move
  `modified_at`, so the `modified_at` guard still covers everything this route can
  write: tags and the content fields are edited in the same form, and a tag write
  that left the stamp alone would let a client holding a pre-tag read overwrite the
  new tags with no 409. `published` is not writable here —
  POST /api/recipes/:id/publish is the only thing that sets it, and nothing
  clears it. 404 when the id matches nothing you own.

  **`scope_ids` behaves exactly like that**, being the other half of the filing:
  omit the key and the Recipe stays filed where it is, send an array and it
  replaces the whole set, **send an empty array and it clears them**. Changing it
  makes no version either — a Scope is a way back to a Recipe, not part of it — and
  it moves `modified_at` for the same reason tags do, so one 409 guard still covers
  everything this route can write. Ids you do not own are dropped and the response's
  `scopes` is the receipt; 400 if `scope_ids` is not an array of integers. Renaming
  or deleting a Scope is not done here — see PUT and DELETE /api/scopes/:id.

  **A save that makes a version answers with `caution` — the same line-level split a
  `?detail=full` read carries, recomputed over the history including this save.** It is
  there exactly when a version was made, which is exactly when the split you were
  holding stopped being true: the lines just moved, so an answer about the old ones
  would be worse than none. A filing-only save and a no-op make no version and carry no
  `caution`, because the split you have is still the answer — and computing one costs a
  fold over the whole history, which is not a thing to pay per Scope chip. `legend` and
  `ranges` ride in the one key here as they do on a read.

  A **202** carries none either, and that is a decision: its `:recipe` is the Recipe as
  it *still* reads, nothing was applied, so the split you hold is unchanged. It is not
  that agents have no use for it — a machine is served the split on a read and on a
  direct save, being the number that says which lines to leave alone.

  A save from a caller without a machine token also sets `has_human_edit`, which
  is what ?human=true on the listing narrows by. Like `published` it cannot be
  carried in the body, and unlike `published` a machine may write over the
  content freely — what it cannot do is clear the mark, because nothing clears
  it. A no-op save does not set it either: it returns before the write.

  The new version's `source` is set from that same fact, and the version it
  displaces keeps the label it was saved under: the outgoing one goes into history
  with its own `source`, not with this save's, so an agent's edit never
  retroactively relabels what the owner wrote. A no-op save leaves `source` alone
  as well, for the same reason it leaves the version alone.

  **A machine's save that makes a version is announced in the owner's inbox** (GET
  /api/inbox) as a `modified` entry naming the new version. The three cases above
  are exactly the split: a no-op makes no entry because it makes no version, and a
  filing-only save makes none because filing is not content. His own saves make
  none at all — the inbox is the record of what the agents did.

  ## When a machine's save becomes a proposal instead

  **A Recipe is yours to write directly only while every one of its versions was
  written by an agent and it is not published.** In the numbers this endpoint already
  gives you (GET /api/recipes): `machine_versions = version`, and `published` 0. One
  save of the owner's anywhere in its history — even a superseded one, so the row's own
  `source` does not answer this — and your next edit to its **content** is not applied;
  it is filed as a proposal for him to approve or dismiss, and the Recipe goes on
  reading exactly as it did. There is no flag for this on the row, deliberately: the
  rule is a comparison of numbers you are already sent, so it is checkable before you
  write rather than something to be told afterwards.

  **A published Recipe is the second half of that rule, and it outranks the first.** A
  content PUT to one is *always* a proposal, even when every version of it is an
  agent's and the count above would otherwise let you write straight through: the owner
  has put his name to that text, so a change to it is his to accept. What you may not
  do to a published Recipe is anything else — a `DELETE` is 403, a publish is 403, and
  a PUT carrying `tags` or `scope_ids` is **403 with nothing applied**, whether or not
  it also carries content. Filing a published Recipe stays the owner's, and a mixed
  request would otherwise half-land. So on a published Recipe, propose the three
  content fields and nothing else in the same call.

  A proposal answers **202**, not 200 — 'accepted, not applied' — with
  `{:pending {…} :recipe {…}}`: what you proposed, and the Recipe as it *still*
  reads. Treating a 202 as 'my text is live' would be the one way to be wrong here,
  which is why it is not a 200.

  **A proposal is a whole version, and the absent-keeps rule above applies to it
  unchanged.** Send one field and you propose that field plus the Recipe's own other
  two — the same three you would have written had the save landed directly, so the same
  request means the same thing either way. Nothing is cleared by not being mentioned:
  to propose an empty body, send `\"description\": \"\"`, which is a value, where
  omitting the key and sending `null` both mean 'keep'. `:pending` in the 202 is the
  whole version that is waiting, so it is also the receipt for this.

  Three things about that path:

  - **`tags` and `scope_ids` in the same request are applied immediately.** Filing
    is not the text he wrote, so a machine retags and refiles an approval-required
    Recipe freely. A mixed request therefore does both — files the content, applies
    the filing — and the `:recipe` in the response shows the filing already changed
    while the content has not. That is not a bug and it is why this paragraph exists.
  - **One proposal at a time.** If one is already waiting you get **409** with
    `:reason \"proposal-pending\"` and `:pending` carrying its text, so you can see
    what you or another agent proposed. Add **`?overwrite=true`** to replace it; only
    the exact string `true` counts. An overwrite keeps its place in his queue and
    stays one item, so revising three times does not ask him three times. Nothing at
    all is written by the 409 — not even the filing — so retrying with the parameter
    lands both.
  - **Two 409s, told apart by `:reason`.** `proposal-pending` is the one above;
    `modified-elsewhere` is the `modified_at` guard, with `:current` as before. The
    guard is checked first, so an agent proposing against text that has already
    moved hears about that rather than about the proposal.

  A machine's `DELETE` of such a Recipe is refused outright (403) rather than
  proposed: there is no way to propose a deletion, and deleting a Recipe he has
  written is not something an agent should be able to queue up. Publishing is
  refused for a machine on any Recipe, as before."
  [req]
  (let [ds (common/ensure-ds)
        user-id (common/get-user-id req)
        id (common/recipe-id req)
        {:keys [title modified_at] :as body} (:body req)
        ;; **`{:lean? false}`, and that is load-bearing rather than tidy.** This row is
        ;; what the proposal payload is merged into, and a lean row is exactly the one
        ;; that carries no `description` — so a read left lean here meant a machine
        ;; renaming a Recipe proposed deleting its body, and approving that wrote the
        ;; deletion. One read, in the caller's audience, handed to everything below
        ;; that asks a question about this Recipe's text.
        current (when id (db.recipe/get-recipe ds user-id id {:lean? false}))]
    (cond
      (nil? current)
      {:status 404 :body {:error "Recipe not found"}}

      (and (some? title) (str/blank? (str title)))
      {:status 400 :body {:error "title cannot be blank"}}

      (bad-scope-ids? body)
      bad-scope-ids-response

      ;; **Before the branch that decides whether this is a save or a proposal**, so
      ;; that one rule covers both landings: an agent has to say why whatever becomes
      ;; of its write. Below the shape checks for the reason the create's copy of this
      ;; is, and above every write — including the filing-only one inside the proposal
      ;; branch, which would otherwise apply the tags of a request that is about to be
      ;; refused.
      ;;
      ;; It covers a machine PUT that turns out to be a **no-op** too, and that is
      ;; deliberate rather than overlooked: whether the text would change is a question
      ;; only the db layer answers, so a caller cannot be told 'you may skip the reason
      ;; this time' without first doing the comparison the answer depends on. One rule
      ;; an agent can hold — *every write says why* — beats a rule that is true except
      ;; when it happens not to be.
      (machine-write-explanation-missing req body)
      (explanation-missing-response (machine-write-explanation-missing req body))

      ;; **The three conditions that make this a proposal instead of a save**, in this
      ;; order and all of them. A machine caller, a Recipe that is not the agents' to
      ;; write, and content that would actually change — the last one is what keeps a
      ;; machine PUT sending the same title back the no-op it has always been rather
      ;; than a pending proposal of nothing.
      ;;
      ;; The middle condition is **two** questions joined by `or`, and the order they
      ;; are written in is not the order they are checked in — that is the point of the
      ;; `or`. A published Recipe is never the agents' to write, however the gate reads,
      ;; so an all-machine Recipe the owner has published proposes like any other. Had
      ;; this been written as 'ask the gate, then ask about the latch', that Recipe
      ;; would have taken the direct-write path below.
      (and (common/machine-caller? req)
           (or (published? current)
               (not (db.recipe/machine-only? ds user-id id)))
           (db.recipe/content-would-change? current (select-keys body writable-fields)))
      (cond
        ;; The `modified_at` guard first: an agent proposing against text that has
        ;; already moved is told so before anything is written, which is the same
        ;; answer this route has always given, now with its reason named.
        (and modified_at (not= modified_at (:modified_at current)))
        (stale-write-response ds user-id id)

        ;; Nothing is written when a proposal is already pending and the caller did
        ;; not say to replace it — not even the filing fields, deliberately. A
        ;; half-applied request answered with an error is worse than a refused one,
        ;; and the agent's next call carries `?overwrite=true` and lands both.
        (and (db.proposal/pending-for ds user-id id) (not (overwrite? req)))
        {:status 409
         :body {:error "A proposal for this Recipe is already waiting to be approved"
                :reason "proposal-pending"
                :pending (pending-body (db.proposal/pending-for ds user-id id))}}

        :else
        (let [;; The filing is applied straight away — tags and Scopes are not the
              ;; text he wrote, so they are not what needs approving. This is a
              ;; filing-only save as far as `update-recipe` is concerned, which is
              ;; why it makes no version and no inbox entry.
              filing (select-keys body [:tags :scope_ids])
              _ (when (seq filing)
                  (db.recipe/update-recipe ds user-id id filing nil
                                           {:human? (human-write? req)}))
              ;; **The proposal is a whole version, merged by the one function that
              ;; owns the absent-keeps rule** — not by a `merge` written here over
              ;; whichever keys this handler's read happened to select. A proposal is a
              ;; proposed *version*, so all three fields have to be in it, and the ones
              ;; a partial PUT did not send come off `current` by exactly the rule
              ;; `update-recipe` would have applied had this been a direct write. That
              ;; is the point: the same PUT means the same thing whether it lands or
              ;; waits. `merge-content` also trims the title, like every other write.
              ;; **The explanation is taken off the body and never merged from
              ;; `current`.** `merge-content` exists because an omitted field keeps
              ;; its value; the pair beside it must do the opposite, for the reason
              ;; `update-recipe` gives one file over — a reason inherited from the
              ;; version being proposed against would read as this proposal's own
              ;; account of itself while describing somebody else's write. The 400
              ;; above is what makes an omission unreachable here for a machine.
              proposal (db.proposal/propose! ds user-id id (:version current)
                                             (assoc (db.recipe/merge-content current body)
                                                    :reason (:reason body)
                                                    :context (:context body)))]
          {:status 202
           :body {:pending (pending-body proposal)
                  :recipe (db.recipe/get-recipe ds user-id id {:lean? false :scopes? true})}}))

      :else
      (if-let [result (db.recipe/update-recipe ds user-id id
                                               (select-keys body writable-fields)
                                               modified_at
                                               {:human? (human-write? req)})]
        ;; **`caution` rides back exactly when the save made a version**, which is the
        ;; same thing as saying: exactly when the split the caller was holding has just
        ;; died. A client that is not sent one must not draw one, so without this a save
        ;; left the reader with a correct refusal and no way to get past it but a second
        ;; read.
        ;;
        ;; Not on every PUT, and the reason is `caution-body`'s own docstring: it costs
        ;; a second read and a fold over the whole history. A filing PUT is what a Scope
        ;; chip sends, one per click, and it changes no version — so paying for that
        ;; fold there would buy an answer the caller already has. Same for a no-op.
        ;;
        ;; **Two rows the handler is already holding answer the question**, so there is
        ;; nothing new to track: `current` was read before the write and `result` comes
        ;; back from it, and the content branch is the only one that increments
        ;; `:version`. A flag threaded out of `update-recipe` would be a second answer
        ;; to a question these two already settle.
        ;;
        ;; **After the write, necessarily.** `caution-body` re-reads `list-versions`, so
        ;; being called here is the whole of what 'reflects the new version' requires —
        ;; and a call moved above the write would hand back the displaced version's
        ;; split and look entirely plausible doing it. There is a test that can tell.
        (let [split (when (not= (:version result) (:version current))
                      (caution-body ds req id))]
          {:status 200 :body (cond-> result split (assoc :caution split))})
        (stale-write-response ds user-id id)))))

(defn delete-recipe-handler
  "DELETE /api/recipes/:id — delete a recipe. 404 when the id matches nothing you
  own, and the same 404 for one you have already deleted: deleting twice is not a
  second delete.

  **It is a tombstone and not a removal** (migration 012). The row, its whole
  version history and its filing all stay; `deleted_at` is stamped, and that takes
  it off every read at once — the shelf, the search, this path at its own id, the
  Scope counts. What that buys is the reason it was asked for: the `deleted` entry
  in the owner's queue can be *opened*, because there is still text behind it. GET
  /api/recipes/:id/versions is the one read that sees a tombstone, deliberately.

  A deleted Recipe is **not writable**: no save, no publish, no approving a
  proposal against it. That is not a rule any of those paths carries — they find
  their row the same way every read does, and a tombstone is not there.

  A pending proposal is resolved as it always was: a question about a Recipe that
  has been deleted cannot be answered, and left pending it would block the agent
  that filed it.

  **A machine's delete leaves an entry in the owner's inbox** (see GET /api/inbox)
  saying which Recipe went and at which version; his own delete leaves none, like
  every other write of his. The Recipe's *events* are the one thing no delete has
  ever taken with it — an event is the record that something happened, and it did —
  so an agent cannot create a Recipe and delete it again to leave no trace.

  The removal this used to be is now DELETE /api/deleted/:id, on a tombstone, asked
  for by name."
  [req]
  (let [user-id (common/get-user-id req)
        id (common/recipe-id req)
        result (when id (db.recipe/delete-recipe (common/ensure-ds) user-id id
                                                 {:human? (human-write? req)}))]
    (if (:success result)
      {:status 200 :body result}
      {:status 404 :body {:error "Recipe not found"}})))

(def ^:private deleted-forbidden
  "One refusal for both tombstone routes, so they cannot come to word it
  differently. A machine may delete a Recipe — that is a write it is allowed and
  the queue records it — and it may neither *list* what is deleted nor purge any of
  it. Listing would tell an agent what the owner has thrown away and not yet dealt
  with, which is the inbox's argument one table along; purging is the one
  irreversible act in this app, and the whole point of the tombstone is that a
  person decides it."
  {:status 403 :body {:error "Deleted Recipes are the owner's: a machine caller cannot list or purge them"}})

(defn list-deleted-handler
  "GET /api/deleted — the owner's deleted Recipes, **most recently deleted first**:
  the lean row plus `deleted_at` and `scopes`, and no description.

  Since 012 a delete is a tombstone (see DELETE /api/recipes/:id), so this is a list
  of Recipes that still exist and are on no shelf — *we can have a page bringing us
  to revisit and hard delete data*. Each one can be read at GET
  /api/recipes/:id/versions, which is the one read that sees a tombstone, and
  destroyed for good at DELETE /api/deleted/:id.

  **A sibling of /api/recipes and so outside both recipe guards**, like /api/scopes
  and /api/inbox, and for their reason: those guards are about a recipe id in the
  path and the publish latch, and this path has neither. It asks
  `common/owner-caller?` for itself, which is stricter than what the recipes context
  would give it — 403 for a machine token and for a caller with no credentials.

  There is no `?detail=full` here and no way to ask for one. A tombstone's text is
  read through its versions, which is where the version it was deleted on is named
  as such; widening this listing would be a second way to read a body, and the one
  that counts consumption is deliberately the only one."
  [req]
  (if (common/owner-caller? req)
    {:status 200 :body (db.recipe/list-deleted (common/ensure-ds) (common/get-user-id req))}
    deleted-forbidden))

(defn purge-recipe-handler
  "DELETE /api/deleted/:id — destroy a tombstone for good: the row, its whole
  version history and every association to a Scope. 404 when the id matches no
  deleted Recipe of yours — **including a live one**, so this can never be the
  delete a caller did not mean to make.

  This is what DELETE /api/recipes/:id used to do, and it is now a second,
  deliberate step on something already deleted. There is no undo and nothing serves
  the text again afterwards.

  **The events survive**, as they always have: an event is the record that something
  happened to a Recipe, not a part of it, and it carries the title it had at the time
  so it stays readable with nothing to join to. So a purged Recipe's queue entries go
  on naming it, and go back to being un-openable — which after this is the honest
  answer.

  Nothing is written to the queue: purging is not a change to the shelf, which is
  what the queue is about, and the `deleted` entry that brought the owner here is
  already in it.

  Owner-only, like the listing, and 403 for a machine caller for the reason given
  there."
  [req]
  (if (common/owner-caller? req)
    (let [id (common/recipe-id req)
          result (when id (db.recipe/purge-recipe! (common/ensure-ds)
                                                   (common/get-user-id req) id))]
      (if (:success result)
        {:status 200 :body result}
        {:status 404 :body {:error "Deleted Recipe not found"}}))
    deleted-forbidden))

(defn publish-recipe-handler
  "POST /api/recipes/:id/publish — publish a recipe: it becomes visible to
  anyone, and the owner has put his name to it.

  **One way.** There is no unpublish route, deliberately. Publishing an already
  published recipe is a 200 no-op that leaves the original `published_at` where
  it was — the first publish is the fact being recorded. It is not a content
  change: no version bump, no history row, and neither `has_human_edit` nor
  `source` is touched — putting your name to text is not writing it. 404 when the
  id matches nothing you own.

  **What you publish is the version you have approved.** This is allowed while an
  agent's proposal is waiting on the Recipe, and it publishes the row — which is the
  last approved state, because a proposal is not a version and no read of a Recipe
  consults one. So a pending rewrite cannot ride out to the public on the back of a
  publish: what a visitor is shown is the last approved version, always. A machine may
  propose against the Recipe afterwards as well, and the same holds — see PUT
  /api/recipes/:id."
  [req]
  (let [user-id (common/get-user-id req)
        id (common/recipe-id req)
        result (when id (db.recipe/publish-recipe (common/ensure-ds) user-id id))]
    (if result
      {:status 200 :body result}
      {:status 404 :body {:error "Recipe not found"}})))

(defn recipe-versions-handler
  "GET /api/recipes/:id/versions — every version of a recipe, newest first, each
  with its `version`, all three fields, `created_at` and `source`. The newest entry
  is the current row and carries `current: true`; the rest are the superseded
  states, which is what makes 'how did this read back then' answerable. Not
  affected by ?detail — a history is the content or it is nothing. No entry
  carries `tags`, the current one included: tags are not versioned, so there is no
  such thing as what a Recipe's tags were at v2.

  **`source` says where that one version came from**: `ui` for a save by the
  owner, `machine` for one by an agent token, and those are the only two values —
  every version of every Recipe carries one. Each label belongs to the version it
  sits on and not to the save that displaced it, so a version's label never changes
  after the fact. The same two values are counted per Recipe on GET /api/recipes.

  There used to be a third answer, null, for versions written before cookbook
  recorded this at all. The owner settled what those were — his — so they read `ui`
  now and nothing in the API returns a null here any more.

  The history is the owner's: an anonymous visitor gets a 404 for every id,
  published or not. Publishing puts today's text in public, not every draft
  behind it."
  [req]
  (let [id (common/recipe-id req)
        result (when (and id (common/authenticated? req))
                 (db.recipe/list-versions (common/ensure-ds) (common/get-user-id req) id))]
    (if result
      {:status 200 :body result}
      {:status 404 :body {:error "Recipe not found"}})))
