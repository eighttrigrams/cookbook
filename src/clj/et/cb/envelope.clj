(ns et.cb.envelope
  "**Everything the server knows about the seal, which is one thing: a sealed
  value says so.**

  Cookbook's prose is end-to-end encrypted in the clients — `et.cb.seal` in the
  browser on WebCrypto, `cookbook_seal.clj` in `plurama-cli` on `javax.crypto` —
  and this process holds no key and never will. Putting the key on fly is the one
  move that would make the whole arrangement theatre with no error message to say
  so.

  But *detecting* ciphertext needs no key at all. The envelope is
  self-describing — `enc:v1:<base64(nonce ‖ ciphertext ‖ tag)>` — and that prefix
  is what makes a half-migrated database legal, what lets a client hand back a
  value it cannot open, and what this namespace reads. Nothing here decrypts,
  encrypts, or wants a key: `sealed?` is `starts-with?` and `prose-columns` is a
  list of column names.

  **Why the server needs even this much.** Publishing is a one-way unseal: the
  owner's browser opens every sealed value in a Recipe's trail and hands the
  plaintext back with the publish, and the latch is only allowed to close if
  nothing behind it is still an envelope. A visitor has no key and must never have
  one, so `enc:v1:…` on a public page is the failure this check exists to make
  impossible — and there is no unpublish to take it back. The server cannot verify
  that the plaintext it was handed is *what the ciphertext said*; only that no
  ciphertext is left. That asymmetry is inherent to holding no key, and it is
  the right way round: the owner is the one publishing, and what he must not be
  able to do by accident is publish something nobody can read.

  **The lists are pinned to `test/fixtures/seal-vectors.edn`**, the same file the
  two client suites read, by `et.cb.envelope-test`. Not decoration: if this
  namespace's idea of which columns hold prose were narrower than a client's, a
  sealed value would ride out onto a public page through the column the guard did
  not know to look at. The fixture is what stops three implementations of one
  inventory from drifting, and it is now three."
  (:require [clojure.string :as str]))

(def prefix
  "What a sealed value starts with. Versioned so that a second envelope can exist
  beside this one, and self-describing so that mixed state — some rows sealed,
  some not — is legal rather than a migration deadline."
  "enc:v1:")

(defn sealed?
  "Whether this value is an envelope. A prefix test and nothing more: `enc:v0:`,
  `ENC:V1:` and a leading space are all plaintexts that happen to look like one,
  exactly as they are to both clients — `:passthrough` in the fixture, asserted
  here against the same list.

  It says nothing about whether the envelope *opens*. `enc:v1:` followed by
  nonsense is sealed as far as this is concerned, which is the honest answer for
  the one caller there is: a value nobody can read is precisely what must not be
  published, whether it is unreadable because it is encrypted or because it is
  damaged."
  [v]
  ;; No `boolean` around it: both branches already answer one — `string?` for a
  ;; nil or a number, `starts-with?` otherwise — and clj-kondo says so.
  (and (string? v) (str/starts-with? v prefix)))

(def prose-columns
  "The columns of a Recipe's trail that hold prose, and therefore the ones a
  publish has to find in the clear. Twelve, over the three tables that are three
  storage places for one per-version field: `recipes` is the current version,
  `recipe_history` the superseded ones, `recipe_proposals` the machine writes that
  have not become versions.

  **`scopes.description` is sealed too and is deliberately not here.** A published
  Recipe's Scopes stay the owner's — *to logged in users only, no matter what* —
  so a Scope's prose is never served to the audience publishing creates, and
  unsealing it would be leaking something in order to publish something else. The
  client inventories carry all thirteen columns because they seal all thirteen;
  this list is the twelve a *visitor's* existence can reach.

  `title` and `tags` are not in it and never can be: they are the search surface,
  they are what the model keeps in the clear, and the seal was fitted to that seam
  rather than across it."
  {:recipes          [:description :useful_when :reason :context]
   :recipe_history   [:description :useful_when :reason :context]
   :recipe_proposals [:description :useful_when :reason :context]})

(defn sealed-in
  "The prose columns of `row` that are still envelopes, in inventory order. Empty
  for a row with nothing sealed in it, which is the answer a publish needs."
  [table row]
  (filterv #(sealed? (get row %)) (get prose-columns table)))
