(ns et.cb.seal
  "The Cookbook envelope in the browser, and the inventory of what goes inside it.

  Cookbook's security model draws one line: **prose is sealed, names and curated
  words are not.** Description, useful-when and the `reason`/`context` pair an
  agent writes about its own change are the prose; title, tags, Scopes and Scope
  tags are how you find things, and they stay clear. That line is exactly the one
  the search already draws, so sealing costs the search nothing at all.

  The app is served from fly and the key never goes there, so there is no trusted
  middle process to put the seal in: it lives in the clients, and there are two of
  them. This is the browser one, on WebCrypto. The other is `cookbook_seal.clj` in
  the `plurama-cli` repo, on `javax.crypto`, which is how every agent reads and
  writes.

  **Two implementations of one envelope is the drift this codebase spends its
  docstrings warning about, and the answer is not discipline, it is a fixture.**
  `test/fixtures/seal-vectors.edn` holds a known key, known nonces, known
  plaintexts and the exact ciphertext each must produce. Both suites read that one
  file — `test/cljs/et/cb/seal_test.cljs` here, `test/cookbook_seal_test.clj`
  there. A divergence is a red test rather than a Recipe nobody can open six
  months from now.

  ## The envelope

      enc:v1:<base64(nonce ‖ ciphertext ‖ tag)>

  AES-256-GCM, a fresh random 96-bit nonce per value, a 128-bit tag, standard
  base64 with padding. The value is stored as text in the column it came from, so
  the seal needs no schema migration, and the `enc:v1:` prefix is what lets a
  half-migrated database work. Mixed state is legal everywhere, permanently.

  **The AAD binds a value to what its column means** — `recipe/description`,
  `recipe/reason`, `scope/description` — so a ciphertext cannot be lifted from one
  column and dropped into another by anyone holding the database file. It binds
  the *meaning* rather than the table because the server copies these values
  between `recipes`, `recipe_history` and `recipe_proposals` verbatim and without
  a key; `bound-as` says that at length. It does not bind the row either: ids are
  assigned on insert, so at the moment of sealing there is nothing to bind to.
  That gap is deliberate.

  ## Three rules, and they live here rather than at the call sites

  1. **Never seal a blank value.** `nil` stays `nil`, `\"\"` stays `\"\"`, and so
     does whitespace-only. Ciphertext is never NULL and never empty, so without
     this rule every historical row grows a *\"not recorded\"* that reads as
     *\"recorded, and empty\"* — and `reason`/`context` are nullable precisely
     because those two mean different things. It is also what leaves the server's
     mandatory-`reason` check able to see a blank reason at all.

  2. **Never re-seal an unchanged value.** A fresh nonce per value is what stops
     equality leaking, but cookbook's server compares prose *values* to decide
     no-op versus version bump versus history row. Seal naively and a save that
     changed one field would bump a version for all of them and pile up history
     rows, corrupting the ladder that `caution` reads. So `seal` takes the value
     currently stored in that column and, when the plaintext has not moved, hands
     that stored ciphertext back byte for byte. The server's equality then keeps
     working verbatim, with no server change at all.

  3. **`unseal` is prefix-driven.** A value without `enc:v1:` passes through
     untouched — which is what makes rule 1 safe and a migration resumable.

  ## Everything here is a Promise

  WebCrypto is asynchronous and there is no synchronous way to reach AES-GCM in a
  browser. So `seal`, `unseal` and everything built on them answer a promise, and
  `et.cb.ui.api` is where that meets the app: a response is unsealed before the
  handler sees it, and a write is sealed before it is sent. **A `nil` key means
  sealing is off** — the promise resolves to the value untouched, which is
  cookbook's behaviour before any of this existed and is also, honestly, what a
  reader without the key sees of a sealed shelf: the ciphertext, because inventing
  text would be worse."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; The envelope.

(def envelope-prefix
  "Self-describing and versioned, so a reader can tell a sealed value from a plain
  one without being told which columns are sealed."
  "enc:v1:")

(def ^:private nonce-length 12)   ; 96 bits, the GCM standard
(def ^:private tag-bits 128)
(def ^:private key-length 32)     ; AES-256

(defn- subtle [] (.-subtle js/crypto))

(defn random-bytes [n]
  (.getRandomValues js/crypto (js/Uint8Array. n)))

(defn- utf8 [s] (.encode (js/TextEncoder.) s))

(defn- from-utf8 [buf] (.decode (js/TextDecoder. "utf-8") buf))

(defn- bytes->b64
  "Base64 of raw bytes, in chunks: `String.fromCharCode` is variadic and a whole
  Recipe's ciphertext passed as one argument list overflows the stack on the
  bodies that most deserve to be sealed."
  [u8]
  (let [n (.-length u8)]
    (loop [i 0 acc ""]
      (if (< i n)
        (recur (+ i 0x8000)
               (str acc (.apply js/String.fromCharCode nil (.subarray u8 i (min n (+ i 0x8000))))))
        (js/btoa acc)))))

(defn- b64->bytes [s]
  (let [bin (js/atob s)
        n (.-length bin)
        out (js/Uint8Array. n)]
    (dotimes [i n] (aset out i (.charCodeAt bin i)))
    out))

(defn import-key
  "A `CryptoKey` from 32 raw bytes. **Non-extractable**: usable by the page,
  not readable by it, which is the whole reason the browser half of this is worth
  more than a string in `localStorage`. Nothing here ever asks for `exportKey`,
  and with `extractable` false nothing could."
  [raw]
  (when-not (= key-length (.-length raw))
    (throw (ex-info (str "the key must be " key-length " bytes, got " (.-length raw))
                    {:length (.-length raw)})))
  (.importKey (subtle) "raw" raw #js {:name "AES-GCM"} false #js ["encrypt" "decrypt"]))

(defn import-key-from-base64
  "The shape a key is pasted in. Rejects anything that is not 32 bytes of base64
  rather than padding or truncating it: a key of the wrong length is a mistake,
  and the only useful moment to hear about it is the first one."
  [s]
  (let [raw (try (b64->bytes (str/trim s))
                 (catch :default _
                   (throw (ex-info "that is not valid base64" {}))))]
    (import-key raw)))

(defn generate-key-base64
  "A fresh key, base64. For tests, and for the one time a real one is minted —
  which is not a thing this app does on its own: where the key is kept is
  `secrets.yaml` and a sheet of paper."
  []
  (bytes->b64 (random-bytes key-length)))

(def bound-as
  "Which name a table's values are bound under — the AAD's first half.

  **Three tables share one binding, and that is the schema being told the truth
  rather than a shortcut.** `recipes`, `recipe_history` and `recipe_proposals` are
  three places one per-version field lives, and the server *moves values between
  them, verbatim, without a key*: `archive!` copies the outgoing row's four
  columns into `recipe_history` on every save, `approve-proposal!` copies a
  proposal's into `recipes`, and `merge-content` fills a partial proposal from the
  current row.

  Bind a ciphertext to `recipes/description` and the first save archives it into a
  column it can no longer be read from. That is not hypothetical — it is what the
  first end-to-end run did, and the whole version ladder came back sealed with
  nothing anywhere to say why. The server cannot re-seal on the way past; it has
  no key, which is the entire point.

  **What it refuses is cross-*column* moves, and nothing else**: a description
  read as a useful-when, a reason read as a context, a Scope's prose read as a
  Recipe's. It permits every cross-table move — that is the point of the grouping
  — and every cross-*row* move too, since ids are assigned on insert and there is
  nothing to bind to at sealing time.

  That is a smaller guarantee than it first sounds, and it is worth saying why it
  is enough. Every column that carries provenance — `source`, `version`,
  `has_human_edit` — is clear and unauthenticated, so somebody holding the
  database file can forge a version's authorship outright by editing the label
  beside the text. The AAD was never what protected provenance from that reader.
  What it protects is the one thing an attacker could otherwise do *without*
  touching a label: silently make a Recipe's body read as its useful-when."
  {:recipes :recipe
   :recipe_history :recipe
   :recipe_proposals :recipe
   :scopes :scope})

(defn aad
  "What a ciphertext is bound to: the *meaning* of the column it belongs in, as
  `binding/column` — `recipe/description`, `scope/description`. See `bound-as` for
  why this is not the table name."
  [table column]
  (str (name (or (bound-as table)
                 (throw (ex-info (str "no seal binding for table " table) {:table table}))))
       "/" (name column)))

(defn sealed?
  "Whether a value read out of a sealed column is actually sealed. Only ever a
  question about the prefix."
  [v]
  (boolean (and (string? v) (str/starts-with? v envelope-prefix))))

(defn- blank-value?
  "The values rule 1 refuses to seal: `nil`, `\"\"`, whitespace-only, and anything
  that is not a string at all."
  [v]
  (or (nil? v) (not (string? v)) (str/blank? v)))

(defn- gcm-params [nonce aad-str]
  #js {:name "AES-GCM" :iv nonce :additionalData (utf8 aad-str) :tagLength tag-bits})

(defn seal-text-with-nonce
  "**The fixture's arity, and nothing else's** — which is why it has a name you
  have to type rather than an overload you can fall into.

  A nonce supplied by a caller is a nonce that can be supplied twice, and in GCM
  two values sealed under one key and one nonce is not a weakening, it is a total
  break: the keystream cancels between them and the authentication key itself
  falls out. Nothing in this application has any reason to choose one. The test
  vectors do, because pinning an exact ciphertext is the whole point of them.

  Every other caller wants `seal-text`, which takes one from the CSPRNG."
  [k aad-str plaintext nonce]
  (.then (.encrypt (subtle) (gcm-params nonce aad-str) k (utf8 plaintext))
         (fn [ct]
           (let [ct (js/Uint8Array. ct)
                 out (js/Uint8Array. (+ (.-length nonce) (.-length ct)))]
             (.set out nonce 0)
             (.set out ct (.-length nonce))
             (str envelope-prefix (bytes->b64 out))))))

(defn seal-text
  "The envelope itself: plaintext in, a promise of `enc:v1:…` out. No rules, no
  inventory, no opinion about blanks — `seal` below is what call sites use.

  A fresh 96-bit nonce per value, from the CSPRNG, every time."
  [k aad-str plaintext]
  (seal-text-with-nonce k aad-str plaintext (random-bytes nonce-length)))

(defn unseal-text
  "The inverse, for a value known to carry the prefix. **Always a promise**, and
  it rejects rather than throws for everything: a tampered ciphertext, one moved
  to another column, the wrong key — and a value that carries the prefix and is
  not base64 at all.

  That last one is why the `try` is here. `js/atob` throws *synchronously*,
  before any promise exists, so without this the `.catch` that `unseal` installs
  is never reached and the throw escapes the whole chain into the ajax handler:
  one such value drops an entire response, with no error banner and nothing on
  screen to say why. And a Recipe whose description begins with `enc:v1:` is not
  a contrived input — it is a Recipe about this envelope, which the README now
  invites somebody to write.

  The Clojure half caught this from the start, which made the two clients
  disagree about one class of input that the fixture could not see, because every
  vector in it is well-formed base64. `:unopenable` in the fixture is that class,
  and both suites read it now."
  [k aad-str value]
  (try
    (let [raw (b64->bytes (subs value (count envelope-prefix)))
          nonce (.slice raw 0 nonce-length)
          body (.slice raw nonce-length)]
      (.then (.decrypt (subtle) (gcm-params nonce aad-str) k body) from-utf8))
    (catch :default e (js/Promise.reject e))))

;; ---------------------------------------------------------------------------
;; Promise plumbing, kept in one place so the shapes below read like the clj half.

(defn- resolved [v] (js/Promise.resolve v))

(defn- p-all [ps] (.then (js/Promise.all (into-array ps)) vec))

(defn- p-map [f coll] (p-all (map f coll)))

;; ---------------------------------------------------------------------------
;; The three rules.

(defn unseal
  "Read one value out of one column. Prefix-driven: anything without `enc:v1:`
  comes back exactly as it went in, and so does everything when there is no key.

  **A value that will not open comes back as it is**, ciphertext and all, rather
  than rejecting. A wrong key or a corrupted row must not take a whole page down
  with it; what it must do is be visibly unreadable, which `enc:v1:…` on screen
  is. `caution` and the diff would go wrong on it, and they are step 5's."
  [k table column v]
  (if (and k (sealed? v))
    (.catch (unseal-text k (aad table column) v) (fn [_] v))
    (resolved v)))

(defn seal
  "Write one value into one column, under all three rules.

  `stored` is what that column holds right now — the ciphertext this client read
  a moment ago, a plaintext on a row not yet migrated, or `nil` for a row that
  does not exist yet.

  **When the value has not changed, `stored` comes back byte for byte, whichever
  of those it is.** That is the whole echo rule, and it is one line: *does this
  column already say what I am about to write?* `unseal` is what asks it, and
  `unseal` answers for all three shapes at once — it opens a ciphertext, hands a
  plaintext straight back, and hands back an envelope it cannot open as well. So
  an unchanged value is a no-op on a sealed row, on an unmigrated row, and on a
  row this client cannot read.

  What that keeps working is the server's `content-would-change?`, and with it the
  version, the history row and — for a machine write — the difference between a
  direct write and a proposal.

  The unmigrated case is not an optimisation, it is the window the rollout
  mandates: clients are deployed first and the data is sealed later, so for a
  while every row is unmigrated. Sealing an unchanged plaintext there would make
  an idempotent re-save a version bump, a history row and an inbox entry — audit
  note A's corruption arriving through the very door the echo rule was built to
  close. The row seals on its next real edit.

  The unreadable case is the one a wrong or rotated key produces. Sealing there
  would write `enc_new(enc_old(…))`, and the *next* no-op would nest it again,
  once per cycle without bound, on a Recipe nobody can read to notice.

  **A migration pass must therefore pass `nil` as `stored`**, and this is the one
  place that trap is written down: a walker that handed the plaintext it just read
  in as `stored` would be told, correctly, that nothing changed, and would seal
  nothing at all."
  ([k table column v] (seal k table column v nil))
  ([k table column v stored]
   (cond
     (nil? k) (resolved v)
     (blank-value? v) (resolved v)
     :else (.then (unseal k table column stored)
                  (fn [was] (if (= was v) stored (seal-text k (aad table column) v)))))))

;; ---------------------------------------------------------------------------
;; The inventory.
;;
;; Thirteen columns, in one place. Both clients carry this list and nothing else
;; decides what is sealed; a rule written down twice is a rule that will drift.

(def sealed-columns
  "Table → the columns of it that hold prose.

  `recipes` is the current version, `recipe_history` the superseded ones, and
  `recipe_proposals` the machine writes that have not become versions yet — a
  per-version field has to be on all three or it vanishes on the next save.
  `scopes.description` is here on the model's own logic: it is prose, it is never
  searched, and a Scope's *title* and *tags* — which are the search surface —
  stay clear beside it.

  `recipe_scopes`, `users` and `recipe_events` hold no prose.
  `recipe_events.recipe_title` is a denormalised title, and titles are clear."
  {:recipes          [:description :useful_when :reason :context]
   :recipe_history   [:description :useful_when :reason :context]
   :recipe_proposals [:description :useful_when :reason :context]
   :scopes           [:description]})

(defn- over-columns
  "Apply `f` to each sealed column of `table` that `row` actually carries, and put
  the answers back. A key the row does not have stays absent: cookbook's
  projections are lean on purpose, and an unseal must not invent an empty
  description on a listing that deliberately has none."
  [f k table row]
  (if (or (nil? k) (nil? row))
    (resolved row)
    (let [columns (filterv #(contains? row %) (get sealed-columns table))]
      (.then (p-map #(f k table % (get row %)) columns)
             (fn [values] (into row (map vector columns values)))))))

(defn unseal-row [k table row] (over-columns unseal k table row))

(defn seal-row
  "Seal every sealed column present in `row`, echoing `stored`'s ciphertext for
  any value that has not changed — see `seal`."
  ([k table row] (seal-row k table row nil))
  ([k table row stored]
   (over-columns (fn [k table column v] (seal k table column v (get stored column)))
                 k table row)))

;; ---------------------------------------------------------------------------
;; The shapes cookbook's API actually hands back.
;;
;; A row is not always one table's. A version list interleaves the current
;; `recipes` row with `recipe_history`; an inbox entry carries a proposal *and*
;; the Recipe's current text beside it, under `current_` aliases; Scopes ride
;; along on nearly everything. Since the AAD names the column a value came out
;; of, unsealing has to know which table each came from — so the mapping is
;; written down here, once, beside the inventory it uses.

(defn- unseal-scopes [k row]
  (if (sequential? (:scopes row))
    (.then (p-map #(unseal-row k :scopes %) (:scopes row))
           (fn [scopes] (assoc row :scopes scopes)))
    (resolved row)))

(defn unseal-recipe
  "One `recipes` row, with the Scopes attached to it."
  [k row]
  (.then (unseal-row k :recipes row) #(unseal-scopes k %)))

(defn unseal-versions
  "`GET /api/recipes/:id/versions`. The newest entry is the `recipes` row itself
  and is flagged `:current`; everything below it is a `recipe_history` row.

  Both open, because the two tables share a binding — see `bound-as`, and note
  that this list is exactly where a per-table binding was found to be wrong: the
  history is made *by copying the current row*, so a ladder sealed per table is a
  ladder that cannot be climbed. The table is still passed rather than assumed,
  because it is the truth about where the row came from."
  [k body]
  (if (sequential? (:versions body))
    (.then (p-map #(unseal-row k (if (:current %) :recipes :recipe_history) %) (:versions body))
           (fn [versions] (assoc body :versions versions)))
    (resolved body)))

(def ^:private current-aliases
  "The inbox entry's second copy of the text: the Recipe as it reads *now*, joined
  in under `current_` names. They are `recipes` columns wearing an alias, and the
  alias is what has to be undone: the *column* is what a value is bound to, so a
  `current_description` is a `description`, and reading it as a `useful_when`
  fails the tag check."
  {:current_useful_when :useful_when
   :current_description :description})

(defn unseal-proposal
  "A proposal as the inbox and the 409/202 bodies render it: the agent's own text
  out of `recipe_proposals`, and — in the inbox — the Recipe's current text
  beside it."
  [k p]
  (.then (unseal-row k :recipe_proposals p)
         (fn [p]
           (let [present (filterv #(contains? p %) (keys current-aliases))]
             (.then (p-map #(unseal k :recipes (current-aliases %) (get p %)) present)
                    (fn [values] (into p (map vector present values))))))))

(defn unseal-inbox-entry
  "One queue entry. `recipe_title` and `kind` are clear; a `proposed` entry
  carries a `proposal`, which holds both texts."
  [k entry]
  (.then (unseal-scopes k entry)
         (fn [entry]
           (if (map? (:proposal entry))
             (.then (unseal-proposal k (:proposal entry))
                    (fn [p] (assoc entry :proposal p)))
             (resolved entry)))))

(defn unseal-body
  "Everything cookbook can answer with, unsealed by shape. One function, so
  `et.cb.ui.api` can put every response through it and no call site has to
  remember an unseal — and so that a new endpoint means a clause here rather than
  a forgotten one somewhere else.

  Dispatch is on the shape rather than on the path: the same Recipe row comes
  back from a create, a save, a publish, a read and the `:current` of a 409, and a
  shape test recognises all five without a path table that has to be kept in step
  with the routes."
  [k body]
  (cond
    (nil? k) (resolved body)

    (sequential? body)
    ;; A listing: recipes, deleted recipes, scopes, or the inbox.
    (p-map (fn [row]
             (cond
               (contains? row :kind)    (unseal-inbox-entry k row)
               ;; A Recipe row carries a version on every projection cookbook
               ;; serves; a Scope has none.
               (contains? row :version) (unseal-recipe k row)
               :else                    (unseal-row k :scopes row)))
           body)

    (not (map? body)) (resolved body)

    ;; GET /api/recipes/:id/versions
    (contains? body :versions) (unseal-versions k body)

    ;; The 202 from a machine PUT, and the 409 that says a proposal is pending.
    ;;
    ;; **`map?`, not `contains?`.** A Recipe row carries a `pending` of its own —
    ;; 0 or 1, whether an agent has a rewrite waiting — so the key alone does not
    ;; say which shape this is, and reading a flag as a proposal is how every
    ;; ordinary save came back still sealed. The two meanings of `pending` are
    ;; both cookbook's and both correct; only the type tells them apart.
    (map? (:pending body))
    (.then (unseal-proposal k (:pending body))
           (fn [pending]
             (let [body (assoc body :pending pending)]
               (if (map? (:recipe body))
                 (.then (unseal-recipe k (:recipe body)) #(assoc body :recipe %))
                 (resolved body)))))

    ;; The 409 that says the Recipe moved under us.
    (map? (:current body))
    (.then (unseal-recipe k (:current body)) #(assoc body :current %))

    ;; A single Recipe: created, saved, read, published.
    (contains? body :version) (unseal-recipe k body)

    ;; A single Scope, from a create or a save.
    (and (contains? body :title) (contains? body :description))
    (unseal-row k :scopes body)

    :else (resolved body)))

;; ---------------------------------------------------------------------------
;; Writes.

(defn seal-recipe-write
  "The body of a `POST /api/recipes` or a `PUT /api/recipes/:id`. `stored` is the
  Recipe as this client last read it, or `nil` for a create — see `seal` for what
  it buys and why a write path that skips it corrupts the version ladder.

  `title`, `tags`, `scope_ids` and `modified_at` are not prose and are not
  touched."
  ([k body] (seal-recipe-write k body nil))
  ([k body stored] (seal-row k :recipes body stored)))

(defn seal-scope-write
  "The body of a `POST /api/scopes` or a `PUT /api/scopes/:id`."
  ([k body] (seal-scope-write k body nil))
  ([k body stored] (seal-row k :scopes body stored)))

;; ---------------------------------------------------------------------------
;; Remembering what was opened.

(defn- table-of
  "Which table a row came out of, for the two tables a client ever writes to.

  A Recipe row carries an `id` and a `version` on every projection cookbook
  serves; a Scope carries an `id` and a `title` and no version. Nothing else is
  claimed: a version-history entry has no `id`, a proposal is served with its `id`
  removed, and an inbox entry is excluded outright by its `kind` — none of the
  three is ever written by a client, so none of them needs to be recognised here."
  [row]
  (cond
    (not (map? row)) nil
    (contains? row :kind) nil
    (and (contains? row :id) (contains? row :version)) :recipes
    (and (contains? row :id) (contains? row :title)) :scopes))

(defn sealed-index
  "The ciphertexts a response carried, keyed by `[table id column]`.

  `unseal-body` throws the ciphertext away — that is its whole job — and the write
  path needs it back, because an unchanged value has to be sent as the very bytes
  already stored or the server's comparison sees a change that is not one. In a
  browser the read and the write are far apart in time, so what was opened has to
  be remembered; `et.cb.ui.api` keeps this index beside the app state.

  `plurama-cli` needs none of this and has none of it: a one-shot process reads
  exactly the row it is about to write, and hands it to `seal` directly. The
  envelope is the same in both — that is what the fixture pins — and the plumbing
  differs because the clients do."
  [body]
  (into {}
        (for [node (tree-seq coll? seq body)
              :let [table (table-of node)]
              :when table
              column (get sealed-columns table)
              :when (sealed? (get node column))]
          [[table (:id node) column] (get node column)])))

(defn stored-row
  "One row's columns as the index last saw them **sealed** — ciphertexts only.
  Empty for a Recipe this client has not read the body of, and empty for one that
  was never sealed in the first place, which is why `stored-for-write` exists."
  [index table id]
  (into {} (for [column (get sealed-columns table)
                 :let [v (get index [table id column])]
                 :when v]
             [column v])))

(defn stored-for-write
  "The `stored` a write should hand `seal`: **what this client believes that row's
  prose columns hold right now**, in whatever encoding they hold it.

  Two sources, and it takes both because neither alone is the answer. The index
  knows the ciphertext of a column that arrived sealed, which is the only place
  that survives — unsealing is what threw it away. `row` is the row as this client
  is holding it, which for a column that arrived *unsealed* is the value itself,
  and there is nowhere else to learn it.

  So the index wins where it has an entry, and the cached row answers everywhere
  else.

  **Without the second source the echo rule was unreachable from the browser for
  exactly the rows it was written for.** `sealed-index` records a column only when
  it arrived sealed, so an unmigrated row contributed nothing, `stored-row`
  returned `{}`, and every no-op Save during the mixed-state window the rollout
  mandates shipped a fresh envelope — a version, a history row, and a staled
  provenance split, on the owner's own shelf, in the client he uses.

  A column this client has never read is in neither source and is absent from the
  answer, which is the honest shape: nothing to echo, so it seals."
  [index table id row]
  (merge (select-keys row (get sealed-columns table))
         (stored-row index table id)))

;; ---------------------------------------------------------------------------
;; What a client can tell about a row it is holding.
;;
;; Pure, and here rather than in the UI, for the reason the inventory is here:
;; two surfaces ask these questions — the publish interlock and the provenance
;; withholding — and a rule written down twice is a rule that will drift. It
;; also puts them where the test build can reach them, which the UI namespaces
;; are not: cljs-ajax wants an npm xmlhttprequest at load time.

(def published-surface
  "The columns a visitor is served, and therefore the ones whose being sealed
  makes publishing wrong. Not the reason/context pair and not the history: those
  are the owner's at every `?detail`, so a stranger never meets them.

  **In the fixture, and asserted by both suites against it**, because the publish
  interlock is implemented three times — here, `cookbook-tui` and `plurama-cli` —
  and a client that widened its idea of the published surface while another did
  not would leave a sealed column reachable through the narrower one. Naming it
  once in this namespace would have made the two clients *look* like they agreed;
  the fixture is what makes them."
  [:description :useful_when])

(defn arrived-sealed?
  "Whether that column of that row **arrived sealed**, from the two things a client
  is holding after a read.

  `stored` is the ciphertext-only view — `stored-row` — which answers for a client
  that has the key and has already unsealed the value out of `row`. `row` is the
  row itself, which answers for a client that has no key and is looking at the
  ciphertext. Either alone is half the question.

  **It fails open**: a column in neither — a row this client has not read, or has
  read only leanly — answers `false`. That is the safe direction for what asks it
  today, since both callers guard something the server will refuse anyway, but it
  is a fail-open and a new caller should know it is holding one."
  [stored row column]
  (or (contains? stored column)
      (sealed? (get row column))))

(defn any-arrived-sealed?
  "`arrived-sealed?` over several columns — see `published-surface`."
  [stored row columns]
  (boolean (some #(arrived-sealed? stored row %) columns)))

(defn ladder-arrived-sealed?
  "Whether a version ladder — `(:versions …)` of `GET /api/recipes/:id/versions`,
  after `unseal-versions` has been over it — still holds an envelope in any
  version's body.

  **Which is the same as asking whether this client can read the ladder at all**,
  because unsealing has already happened by the time anybody sees one. A value
  that comes through still wearing `enc:v1:` is a value `unseal` declined to
  open: no key, the wrong key, or a row that will not decrypt. So this is the
  question the local `caution` has to be asked before it is computed, and the
  answer `true` means *withhold*, not *compute differently* — an assessment over
  base64 is not a rough answer, it is a confident wrong one, since base64 carries
  no newlines and the whole ladder collapses to a single line.

  Only the body is asked about. `reason` and `context` are sealed too and are not
  input to the split — the split is drawn over `description` and nothing else, per
  `et.cb.caution/ranges` — so a ladder whose reasons will not open is still a
  ladder whose provenance can be read."
  [versions]
  (boolean (some #(sealed? (:description %)) versions)))

(defn caution-over-ciphertext?
  "Whether the `caution` a response is carrying was computed over text the server
  could not read.

  The server assesses `recipe_history` on every `?detail=full` and on every PUT
  that made a version, and it holds no key. On a sealed Recipe what it produces
  is **wrong rather than incomplete**: the ladder reads as one line, and the
  single range that comes back carries the last writer's label onto line 1 of the
  plaintext — colouring the owner's own opening line as an agent's, which is the
  one direction this app exists to get right.

  So the value is dropped at the boundary, in `et.cb.ui.api`, and never enters
  the client's state at all. Dropping beats guarding at the two render sites,
  which is what this used to be: a guard has to be remembered by everyone who
  ever draws a split, and a dropped key is simply not there — the same argument
  `unseal-body` makes about doing the work once, at the door.

  **The current row's body stands in for the ladder**, as it must: the ladder is
  not in this response. Two shapes hide a sealed ladder behind a `description`
  that does not look sealed, and this misses both:

  - a Recipe sealed and then edited by a client with **no** key — a plaintext body
    over a sealed history, which the server assessed and read half of;
  - a Recipe whose current body is **blank**. `sealed?` of `\"\"` is false, because
    never-seal-blank means a blank value is stored as it is, so a split over
    `[enc, enc, \"\"]` passes straight through. Unlike the first, this is not a
    misconfiguration: emptying a body and saving is a thing an owner does.

  Neither is a live wrong number today, and the second never was: the reading
  mode's own `blank?` check takes the toggle away from a body with nothing in it,
  and the editor's `draft-cautions` sends lines it cannot place to `1.0`, which is
  the careful side. What catches both in practice is that `state/sealed-prose?` —
  which decides whether to go and fetch a ladder — asks about all four prose
  columns and not this one, so a Recipe sealed in any of them is recomputed
  whatever its description looks like.

  `et.cb.ui.state/fetch-versions` closes **half** of that shape from the other end
  — a ladder that will not open retires whatever split is being held — and the
  half it closes is the one that is actually reachable, since the client that made
  such a Recipe is a client with no key and it is that client that goes on reading
  it. What stays open is the same Recipe read by a client that *does* hold the
  key: the ladder opens, nothing notices, and the server's half-blind answer
  stands. Closing that too would mean knowing whether a version *arrived* sealed,
  which is gone by the time anybody holds the list — so it would take either a
  marker key on the response or recomputing on every ladder this client fetches,
  plaintext Recipes included. Neither was worth it for a state only a
  misconfiguration produces."
  [body]
  (boolean (and (map? body)
                (contains? body :caution)
                (sealed? (:description body)))))
