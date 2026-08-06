(ns et.cb.server.recipe-handler
  (:require [clojure.string :as str]
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
  body, and a key nobody selected is a key nobody can smuggle in."
  [:title :useful_when :description :tags :scope_ids])

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
  the agent itself last wrote or a revision it has since forgotten."
  [proposal]
  (select-keys proposal [:title :useful_when :description :base_version
                         :created_at :modified_at]))

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

(defn list-recipes-handler
  "GET /api/recipes — the caller's recipes, **ranked by how much they are used**,
  optionally narrowed by ?search over the **title and the tags**.

  **The order is a weighted sum: `0.7 × view_count + 0.3 × version`, highest
  first**, then most recently modified, then highest id. `view_count` is how often
  the Recipe's description was actually fetched (see GET /api/recipes/:id) and
  `version` is how many times it has been edited, so the shelf leads with what has
  proved useful rather than with whatever was touched last. The same order is
  served to every caller — this UI, an agent, an anonymous visitor — so a listing
  is a recommendation and not just an inventory: the first entries are the ones
  somebody has kept coming back to. The weights are on the raw counts, so once a
  Recipe has been read a few dozen times the version term stops being able to move
  it. There is no ?sort parameter; if you want a different order, sort the rows
  you were given.

  **?search is a word-prefix match, AND across terms.** The search splits on
  whitespace, and a recipe matches when every term is the prefix of some word in
  its title *or its tags*, case-insensitively: `?search=ab cd` finds `abc cde` but
  not `ad cd`, and `?search=cd` does not find `abcd` — a prefix is not a
  substring. A word is a run of letters and digits, so `heating` finds
  `Re-heating` and `start` finds `make/start`. The terms need not all land in the
  same column: a recipe titled `Sourdough starter` tagged `bread baking` is found
  by `sour bak`. Nothing else is searched: not useful-when, not the description.
  `%` and `_` are ordinary characters here, not wildcards.

  **Tags are searched for every caller and sent only to the owner.** An anonymous
  visitor's rows carry no `tags` key at all — the column is not in their
  projection — while their ?search still matches against it, so a term returns the
  same recipes whoever asks. That is deliberate: one search behaves one way, and
  columns that shifted with the caller would make the same query mean two things.
  The consequence, stated rather than left to be discovered: a visitor can find out
  that a published Recipe carries some word by probing search terms, though the
  tags themselves are never readable. A machine token reads in the owner's audience,
  so an agent both reads and writes tags — cookbook is an agentic memory store and
  a curated retrieval index is most of what an agent gets out of one. The boundary
  here is around anonymous readers, not machines.

  **?human=true narrows to the Recipes a human has edited**, and only the exact
  value `true` does: absent, `false` or anything else leaves the listing alone.
  A Recipe carries `has_human_edit` once it has been created or saved by a caller
  holding something other than a machine token — the web UI, in practice, since
  `cookbook-tui` authenticates as the machine user. Publishing does not count and
  neither does a save that changed nothing. The bit is only recorded going
  forward from the migration that introduced it, so a Recipe written before that
  and not saved since reads as not-human-edited even if the owner wrote every word
  of it: what was never recorded is not asserted. It composes with ?search.

  **?exclude-scopes=3,7 hides the Recipes filed under those Scopes** — a
  comma-separated list of **Scope ids**, from GET /api/scopes. It is a *negative*
  filter and the only one here: there is no way to ask for the Recipes *of* a
  Scope, because the owner asked to hide rather than to select. Several ids take
  more away and never less — a Recipe survives only if it carries none of them, so
  one carrying an excluded Scope alongside a kept one is still gone. **A Recipe
  filed under no Scope at all is never hidden by this**, which is the case worth
  saying rather than leaving to be discovered. It composes with ?search and
  ?human=true, because all three are clauses on the one query.

  Junk narrows by nothing rather than being refused: a non-numeric id, an empty
  list, and an id you do not own all answer 200 with the listing unchanged. That
  last one is deliberate and not an oversight — a 404 for it would say which ids
  exist, which is the same call `scope_ids` already gets on a write.

  **An anonymous visitor's ?exclude-scopes is ignored entirely**, and that is a
  refusal rather than the filter applied to fewer rows. A visitor is sent no
  `scopes` key on anything, and — unlike the tags, whose presence is testable
  through ?search — the Scopes' presence is not testable either, because nothing
  searches them. Honouring this for a visitor would hand that back: rows vanishing
  on request is a way to ask which published Recipes carry Scope 4, one id at a
  time. Scopes are a stronger boundary than the tags on purpose, and the owner said
  so in as many words: *to logged in users only, no matter what*. A machine token
  reads in the owner's audience and is honoured, like every other Scope read.

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
   ;; The exclusion is passed for every caller and the db layer is what refuses a
   ;; visitor it — `list-recipes` decides that off the audience, the way
   ;; `with-scopes` decides whether the Scopes are attached at all. Asking the
   ;; question here as well would be two places answering it, which is how they
   ;; come to disagree; the flag is a request and the audience is the answer.
   :body (db.recipe/list-recipes (common/ensure-ds) (read-audience req)
                                 {:search-term (common/query-param req "search")
                                  :human-only? (human-only? req)
                                  :excluded-scope-ids (excluded-scope-ids req)
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
  their presence is not testable either: nothing searches them, and the one
  parameter that could have made them testable by omission — ?exclude-scopes on
  the listing — is ignored outright for an anonymous caller rather than applied to
  their published rows. Watching rows vanish is a way of asking, so it is refused
  as one.

  **`pending`** rides along here too, 1 when a proposal is waiting on this Recipe —
  see GET /api/recipes for what it does and does not say. A visitor gets no such key.
  Note what a pending proposal does *not* change: this response is the Recipe as it
  reads now, at the last approved version, whatever an agent has queued against it.
  **That holds at `?detail=full` and it holds for a visitor**, which is the case worth
  stating outright: a published Recipe with an unapproved rewrite waiting on it hands an
  anonymous reader the approved text and no part of the proposal. What a visitor is
  shown is the last approved version, always.

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
  number alone."
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
        (when full? (db.recipe/record-view! ds id))
        {:status 200 :body recipe})
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
  record of what the agents did, not a change log."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [title] :as body} (:body req)]
    (cond
      (str/blank? (str title))
      {:status 400 :body {:error "title is required"}}

      (bad-scope-ids? body)
      bad-scope-ids-response

      :else
      {:status 201
       :body (db.recipe/create-recipe (common/ensure-ds) user-id
                                      (select-keys body writable-fields)
                                      {:human? (human-write? req)})})))

(defn update-recipe-handler
  "PUT /api/recipes/:id — save {:title :useful_when :description :tags
  :scope_ids}. A field you leave out keeps its current value, so an edit meant for
  one field cannot silently clear the others; a blank title is refused with 400.

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
              proposal (db.proposal/propose! ds user-id id (:version current)
                                             (db.recipe/merge-content current body))]
          {:status 202
           :body {:pending (pending-body proposal)
                  :recipe (db.recipe/get-recipe ds user-id id {:lean? false :scopes? true})}}))

      :else
      (if-let [result (db.recipe/update-recipe ds user-id id
                                               (select-keys body writable-fields)
                                               modified_at
                                               {:human? (human-write? req)})]
        {:status 200 :body result}
        (stale-write-response ds user-id id)))))

(defn delete-recipe-handler
  "DELETE /api/recipes/:id — remove a recipe together with its whole version
  history. 404 when the id matches nothing you own.

  **A machine's delete leaves an entry in the owner's inbox** (see GET /api/inbox)
  saying which Recipe went and at which version; his own delete leaves none, like
  every other write of his. The Recipe's *events* are the one thing a delete does
  not take with it — an event is the record that something happened, and it did —
  so an agent cannot create a Recipe and delete it again to leave no trace."
  [req]
  (let [user-id (common/get-user-id req)
        id (common/recipe-id req)
        result (when id (db.recipe/delete-recipe (common/ensure-ds) user-id id
                                                 {:human? (human-write? req)}))]
    (if (:success result)
      {:status 200 :body result}
      {:status 404 :body {:error "Recipe not found"}})))

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
