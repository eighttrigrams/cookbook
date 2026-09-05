(ns et.cb.ui.api
  "The client's four verbs, and the one place the seal meets them.

  **Every response goes through `seal/unseal-body` before its handler sees it**,
  and that is why the rest of this client needed almost no change when the prose
  was sealed: `diff.cljs`, `markdown.cljs`, `provenance.cljs`, the inbox and the
  Recipe page all go on being handed plaintext, because by the time a handler
  runs the ciphertext is gone. Unsealing is shape-dispatched, so the same
  arrangement covers the listing, a Recipe, a version ladder, the queue and the
  Scopes without a table of endpoints here that would have to be kept in step
  with the routes.

  **Sealing is not here**, and the asymmetry is deliberate. A read has nothing to
  decide: whatever came back is unsealed as what it is. A write has to know the
  Recipe as this client last read it, so that an unchanged value can be sent back
  as the very ciphertext already stored — the rule that keeps the server's value
  comparison, and with it the version ladder, working untouched. That knowledge
  is `state.cljs`'s, so the seal is applied there, at the four call sites that
  carry prose, and this namespace stays the transport it was.

  **Error bodies are not unsealed.** The two that carry prose — the 409 naming the
  Recipe that moved and the 409 naming a pending proposal — are read here for
  `:error` and nothing else; it is `plurama-cli` that prints those bodies to an
  agent, and it is `plurama-cli` that unseals them. Adding an async hop to every
  failure path to unseal text nobody displays would buy nothing and could delay an
  error banner.

  Unsealing is a promise, so a handler now runs a microtask later than it did.
  Order is preserved — promise callbacks queue in order — and with no key
  configured `unseal-body` resolves immediately with the body it was given, which
  is cookbook's behaviour before any of this existed."
  (:require [ajax.core :refer [GET POST PUT DELETE]]
            [et.cb.seal :as seal]
            [et.cb.ui.key-store :as key-store]))

(defonce ^:private stored-ciphertexts
  ;; What the last read of each row put in each sealed column, keyed by
  ;; `[table id column]`.
  ;;
  ;; It exists for one rule: **an unchanged value must be written back as the very
  ;; ciphertext already stored**, or the server's value comparison sees a change
  ;; where there is none and bumps a version, writes a history row and stales the
  ;; provenance split. Unsealing is what throws that ciphertext away, so this is
  ;; where it is caught on the way past.
  ;;
  ;; Only the two tables a client writes are in it — Recipes and Scopes. It is
  ;; keyed by row, not by text, on purpose: two Recipes may legitimately say the
  ;; same sentence, and echoing one's ciphertext into the other would leak exactly
  ;; the equality a fresh nonce per value is there to hide.
  ;;
  ;; Stale entries are harmless. A value another client has since changed unseals
  ;; to something that is not what is being written, so it does not match and the
  ;; write seals fresh; and the `modified_at` guard answers that race first anyway.
  (atom {}))

(defn stored-row
  "One row's sealed columns as this client last read them, for `seal-row`."
  [table id]
  (seal/stored-row @stored-ciphertexts table id))

(defn forget-stored!
  "Drop the lot. Called on sign-out with the rest of what was fetched — these are
  ciphertexts rather than text, but they are the owner's Recipes either way, and
  a signed-out client has no business holding a map of them."
  []
  (reset! stored-ciphertexts {}))

(defn- unsealing
  "Wrap a response handler so it is handed plaintext.

  A failure to unseal never reaches here: `seal/unseal` hands back a value it
  cannot open rather than rejecting, so a wrong key shows as `enc:v1:…` on the
  page instead of taking it down."
  [handler]
  (fn [body]
    (swap! stored-ciphertexts merge (seal/sealed-index body))
    (.then (seal/unseal-body (key-store/current-key) body) handler)))

(defn fetch-json
  "A GET, with the same optional `error-handler` the three writes below have and
  in the same `cond->` idiom.

  **It had no error path at all until the Recipe page needed one**, and that was
  right for as long as it lasted: every other GET in this client is one the caller
  is already known to be allowed to make — the listing, its own details, its own
  history — so a failure was a bug rather than an answer. `/api/recipes/:id` read
  from an address somebody typed is the first GET here that can legitimately 404,
  because `/recipe/999999` and a visitor's `/recipe/<unpublished>` are both a 404
  by design and both of them are the page's job to say out loud. Without a handler
  for it the page would sit on 'Loading…' forever, and nothing on screen would tell
  that apart from a slow network."
  ([endpoint headers handler]
   (fetch-json endpoint headers handler nil))
  ([endpoint headers handler error-handler]
   (GET endpoint
     (cond-> {:response-format :json
              :keywords? true
              :headers headers
              :handler (unsealing handler)}
       error-handler (assoc :error-handler error-handler)))))

(defn post-json
  ([endpoint params headers handler]
   (post-json endpoint params headers handler nil))
  ([endpoint params headers handler error-handler]
   (POST endpoint
     (cond-> {:params params
              :format :json
              :response-format :json
              :keywords? true
              :headers headers
              :handler (unsealing handler)}
       error-handler (assoc :error-handler error-handler)))))

(defn put-json
  ([endpoint params headers handler]
   (put-json endpoint params headers handler nil))
  ([endpoint params headers handler error-handler]
   (PUT endpoint
     (cond-> {:params params
              :format :json
              :response-format :json
              :keywords? true
              :headers headers
              :handler (unsealing handler)}
       error-handler (assoc :error-handler error-handler)))))

(defn delete-simple
  ([endpoint headers handler]
   (delete-simple endpoint headers handler nil))
  ([endpoint headers handler error-handler]
   (DELETE endpoint
     (cond-> {:format :json
              :response-format :json
              :keywords? true
              :headers headers
              :handler (unsealing handler)}
       error-handler (assoc :error-handler error-handler)))))
