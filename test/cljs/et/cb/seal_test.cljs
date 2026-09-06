(ns et.cb.seal-test
  "The ClojureScript half of the drift control.

  Everything textual in here comes out of `test/fixtures/seal-vectors.edn`, which
  plurama-cli's Clojure suite reads as well. Nothing in this file invents a
  ciphertext: if the two implementations ever disagree about the envelope, one of
  them goes red here.

  It runs under node (`make test-cljs`), not in a browser, because WebCrypto is
  native to node and the envelope has nothing browser-shaped about it. What *is*
  browser-shaped — the non-extractable key in IndexedDB — cannot be tested here
  and is checked in the browser instead."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [et.cb.seal :as seal]))

(def ^:private vectors-path
  "Relative to the repo root, which is where `make test-cljs` runs node from."
  "test/fixtures/seal-vectors.edn")

(def ^:private fixture
  (delay (reader/read-string (.readFileSync (js/require "fs") vectors-path "utf8"))))

(defn- b64->bytes [s]
  (let [bin (js/atob s)
        out (js/Uint8Array. (.-length bin))]
    (dotimes [i (.-length bin)] (aset out i (.charCodeAt bin i)))
    out))

(defn- test-key [] (seal/import-key (b64->bytes (:key-base64 @fixture))))

(defn- other-key [] (seal/import-key (b64->bytes (:other-key-base64 @fixture))))

(defn- rejects?
  "A promise of true when `p` fails, false when it succeeds. GCM's whole promise is
  that a tampered envelope does not quietly decrypt to something."
  [p]
  (.then (.then p (constantly false)) identity (constantly true)))

;; ---------------------------------------------------------------------------

(deftest the-fixture-describes-the-envelope-this-file-implements
  (let [{:keys [prefix nonce-bytes tag-bits key-bytes]} (:envelope @fixture)]
    (is (= prefix seal/envelope-prefix))
    (is (= 12 nonce-bytes))
    (is (= 128 tag-bits))
    (is (= 32 key-bytes))
    (is (= 32 (.-length (b64->bytes (:key-base64 @fixture)))))))

(deftest the-binding-is-the-one-the-fixture-names
  (testing "three tables under one name, because the server copies between them"
    (is (= (:binding @fixture)
           (into {} (for [[table binding] seal/bound-as] [table (name binding)]))))
    (is (= (set (keys seal/sealed-columns)) (set (keys seal/bound-as)))
        "every sealed table has a binding, and nothing else does")))

(deftest the-published-surface-is-the-one-the-fixture-names
  (testing "three clients implement the publish interlock; one of them widening
    while another did not is how a sealed column stays reachable through the
    narrower one. So the pair is in the fixture, like the binding and the
    inventory, and each client asserts its own list against it."
    (is (= (:published-surface @fixture) (mapv name seal/published-surface)))
    (testing "and it is a subset of what is actually sealed"
      (is (every? (set (:recipes seal/sealed-columns)) seal/published-surface)))
    (testing "the reason/context pair is not in it — a visitor is served neither"
      (is (not-any? #{:reason :context} seal/published-surface)))))

(deftest a-value-travels-between-the-three-recipe-tables
  (testing "archive! and approve-proposal! copy verbatim and hold no key"
    (async done
      (-> (test-key)
          (.then (fn [k]
                   (-> (seal/seal k :recipes :description "the body, as saved")
                       (.then (fn [sealed]
                                (js/Promise.all
                                 (into-array
                                  [(seal/unseal k :recipes :description sealed)
                                   (seal/unseal k :recipe_history :description sealed)
                                   (seal/unseal k :recipe_proposals :description sealed)
                                   (rejects? (seal/unseal-text k (seal/aad :recipes :useful_when) sealed))
                                   (rejects? (seal/unseal-text k (seal/aad :scopes :description) sealed))]))))
                       (.then (fn [[a b c as-useful as-scope]]
                                (is (= "the body, as saved" a))
                                (is (= "the body, as saved" b)
                                    "a save archives this value into recipe_history untouched")
                                (is (= "the body, as saved" c))
                                (testing "and still refuses the moves the server never makes"
                                  (is (true? as-useful))
                                  (is (true? as-scope))))))))
          (.then done)))))

(deftest every-vector-seals-to-exactly-the-recorded-ciphertext
  (async done
    (-> (test-key)
        (.then (fn [k]
                 (js/Promise.all
                  (into-array
                   (for [{:keys [name aad nonce plaintext sealed]} (:vectors @fixture)]
                     (.then (seal/seal-text-with-nonce k aad plaintext (b64->bytes nonce))
                            (fn [out]
                              (testing name
                                (is (= sealed out)
                                    "same key, same nonce, same AAD must give the same envelope")))))))))
        (.then done))))

(deftest every-vector-unseals-back-to-its-plaintext
  (async done
    (-> (test-key)
        (.then (fn [k]
                 (js/Promise.all
                  (into-array
                   (for [{:keys [name aad plaintext sealed]} (:vectors @fixture)]
                     (.then (seal/unseal-text k aad sealed)
                            (fn [out] (testing name (is (= plaintext out))))))))))
        (.then done))))

(deftest the-column-api-agrees-with-the-raw-one
  (async done
    (-> (test-key)
        (.then (fn [k]
                 (js/Promise.all
                  (into-array
                   (for [{:keys [name table column plaintext sealed]} (:vectors @fixture)]
                     (.then (seal/unseal k table column sealed)
                            (fn [out]
                              (testing name
                                (is (= plaintext out)
                                    "unseal derives the AAD from the column it was handed"))))))))) 
        (.then done))))

(deftest blank-is-never-sealed
  (async done
    (-> (test-key)
        (.then (fn [k]
                 (js/Promise.all
                  (into-array
                   (cons (.then (seal/seal k :recipes :reason nil)
                                (fn [out]
                                  (testing "nil stays nil — 'not recorded' is not 'recorded, and nothing'"
                                    (is (nil? out)))))
                         (for [blank (:blank @fixture)]
                           (.then (seal/seal k :recipes :description blank)
                                  (fn [out]
                                    (testing (pr-str blank)
                                      (is (= blank out) "byte-identical, not normalised"))))))))))
        (.then done))))

(deftest unseal-passes-through-anything-without-the-prefix
  (async done
    (-> (test-key)
        (.then (fn [k]
                 (js/Promise.all
                  (into-array
                   (cons (.then (seal/unseal k :recipes :description nil) #(is (nil? %)))
                         (for [v (:passthrough @fixture)]
                           (.then (seal/unseal k :recipes :description v)
                                  (fn [out]
                                    (testing (pr-str v)
                                      (is (= v out))
                                      (is (false? (seal/sealed? v)))))))))))) 
        (.then done))))

(deftest a-prefixed-value-that-will-not-open-is-handed-back-not-thrown
  (testing "rule 3 is about the prefix, and the prefix is not a promise that it opens"
    (async done
      (-> (test-key)
          (.then (fn [k]
                   (js/Promise.all
                    (into-array
                     (for [v (:unopenable @fixture)]
                       (-> (js/Promise.all
                            (into-array
                             ;; Both of these threw synchronously before the fix,
                             ;; escaping every catch in the chain and taking the
                             ;; whole response with them.
                             [(seal/unseal k :recipes :description v)
                              (.then (seal/seal k :recipes :description "the new text" v)
                                     (fn [out] (seal/unseal k :recipes :description out)))
                              ;; The shape the two clients diverged on: `v` and
                              ;; `stored` both the unopenable value. A wrong or
                              ;; rotated key hands a client enc:v1: where prose
                              ;; should be, and a save of "keep everything" writes
                              ;; exactly that back. Sealing it would store
                              ;; enc_new(enc_old(…)), nesting once per cycle.
                              (seal/seal k :recipes :description v v)]))
                           (.then (fn [[back written unchanged]]
                                    (testing (pr-str v)
                                      (is (= v back)
                                          "handed back, not thrown")
                                      (is (= "the new text" written)
                                          "and it does not fail a write either")
                                      (is (= v unchanged)
                                          "and writing it back unchanged is a no-op"))))))))))
          (.then done)))))

(deftest a-tampered-envelope-fails-to-open
  (async done
    (-> (js/Promise.all
         (into-array
          (for [{:keys [name aad sealed key-base64]} (:tamper @fixture)]
            (-> (seal/import-key (b64->bytes key-base64))
                (.then (fn [k] (rejects? (seal/unseal-text k aad sealed))))
                ;; A truncated envelope can fail before WebCrypto is reached at
                ;; all — a bad base64 length throws synchronously. Either way it
                ;; must not come back as text.
                (.catch (constantly true))
                (.then (fn [failed?] (testing name (is (true? failed?)))))))))
        (.then done))))

(deftest unseal-of-a-value-that-will-not-open-hands-it-back
  (testing "a wrong key must not take the page down; it must be visibly unreadable"
    (async done
      (-> (other-key)
          (.then (fn [k]
                   (let [{:keys [sealed]} (first (:vectors @fixture))]
                     (-> (seal/seal k :recipes :useful_when "this one opens")
                         (.then (fn [readable]
                                  (js/Promise.all
                                   (into-array
                                    [(seal/unseal k :recipes :description sealed)
                                     ;; and a whole body is unsealed as far as it
                                     ;; can be, not abandoned at the first failure
                                     (seal/unseal-body k {:id 1 :version 2
                                                          :description sealed
                                                          :useful_when readable})]))))
                         (.then (fn [[one body]]
                                  (is (= sealed one))
                                  (is (= sealed (:description body)))
                                  (is (= "this one opens" (:useful_when body)))))))))
          (.then done)))))

(deftest a-round-trip-holds-for-a-fresh-nonce
  (async done
    (-> (test-key)
        (.then (fn [k]
                 (js/Promise.all
                  (into-array
                   (for [plaintext (:round-trip @fixture)]
                     (-> (seal/seal-text k (seal/aad :recipes :description) plaintext)
                         (.then (fn [sealed]
                                  (is (seal/sealed? sealed))
                                  (seal/unseal-text k (seal/aad :recipes :description) sealed)))
                         (.then (fn [out] (testing (pr-str plaintext) (is (= plaintext out)))))))))))
        (.then done))))

(deftest the-same-sentence-seals-differently-every-time
  (async done
    (let [text "Two Recipes may legitimately say the same thing."]
      (-> (test-key)
          (.then (fn [k] (js/Promise.all (into-array [(seal/seal k :recipes :description text)
                                                      (seal/seal k :recipes :description text)]))))
          (.then (fn [[a b]]
                   (is (not= a b) "a fresh nonce per value is what stops equality leaking")))
          (.then done)))))

(deftest an-unchanged-value-is-not-re-sealed
  (testing "the rule that keeps content-would-change? working, server-side and untouched"
    (async done
      (-> (test-key)
          (.then (fn [k]
                   (-> (seal/seal k :recipes :description "The text as it stands.")
                       (.then (fn [stored]
                                (js/Promise.all
                                 (into-array
                                  [(js/Promise.resolve stored)
                                   (seal/seal k :recipes :description "The text as it stands." stored)
                                   (seal/seal k :recipes :description "Something else." stored)]))))
                       (.then (fn [[stored same changed]]
                                (is (= stored same)
                                    "byte-identical echo, so the server's equality still sees a no-op")
                                (is (not= stored changed) "a genuine change gets a fresh nonce"))))))
          (.then done)))))

(deftest an-unchanged-value-on-an-unmigrated-row-stays-plaintext
  (testing "the mixed-state window: clients deployed first, data sealed later"
    (async done
      (let [text "unchanged since before the migration"]
        (-> (test-key)
            (.then (fn [k]
                     (js/Promise.all
                      (into-array
                       [(seal/seal k :recipes :description text text)
                        (seal/seal k :recipes :description "edited at last" text)
                        ;; a migration pass hands in no stored, and does seal
                        (seal/seal k :recipes :description text)]))))
            (.then (fn [[no-op edited migrated]]
                     (is (= text no-op)
                         "a no-op stays a no-op — the server writes no version for it")
                     (is (not (seal/sealed? no-op))
                         "and the row is left as it was, to seal on its next real edit")
                     (is (seal/sealed? edited))
                     (is (seal/sealed? migrated))))
            (.then done))))))

(deftest no-key-means-no-sealing
  (testing "cookbook's behaviour before any of this existed, reachable by config"
    (async done
      (-> (js/Promise.all
           (into-array [(seal/seal nil :recipes :description "plain")
                        (seal/unseal nil :recipes :description "enc:v1:whatever")
                        (seal/unseal-body nil [{:version 1 :description "plain"}])]))
          (.then (fn [[a b c]]
                   (is (= "plain" a))
                   (is (= "enc:v1:whatever" b))
                   (is (= [{:version 1 :description "plain"}] c))))
          (.then done)))))

;; ---------------------------------------------------------------------------
;; The inventory and the shapes.

(deftest the-inventory-is-thirteen-columns
  (is (= 13 (reduce + (map count (vals seal/sealed-columns)))))
  (is (= {:recipes [:description :useful_when :reason :context]
          :recipe_history [:description :useful_when :reason :context]
          :recipe_proposals [:description :useful_when :reason :context]
          :scopes [:description]}
         seal/sealed-columns))
  (testing "title and tags are the search surface and are never in it"
    (doseq [[_ columns] seal/sealed-columns]
      (is (not-any? #{:title :tags} columns)))))

(deftest a-lean-row-gains-no-keys
  (testing "cookbook's listing carries no description at all, and must not grow one"
    (async done
      (-> (test-key)
          (.then (fn [k] (seal/unseal-recipe k {:id 1 :version 2 :title "T"})))
          (.then (fn [out] (is (not (contains? out :description)))))
          (.then done)))))

(deftest a-version-list-unseals-against-two-tables
  (async done
    (-> (test-key)
        (.then (fn [k]
                 (-> (js/Promise.all
                      (into-array [(seal/seal k :recipes :description "now")
                                   (seal/seal k :recipes :reason "because")
                                   (seal/seal k :recipe_history :description "before")]))
                     (.then (fn [[now because before]]
                              (js/Promise.all
                               (into-array
                                [(seal/unseal-versions
                                  k {:total 2
                                     :versions [{:version 2 :current true :description now :reason because}
                                                {:version 1 :description before :reason nil}]})
                                 ;; The history *is* the current row, copied by the
                                 ;; server on the next save. A per-table binding sealed
                                 ;; exactly this list shut, which is how the grouping
                                 ;; came to be written down.
                                 (seal/unseal k :recipe_history :description now)]))))
                     (.then (fn [[out archived]]
                              (is (= "now" (get-in out [:versions 0 :description])))
                              (is (= "because" (get-in out [:versions 0 :reason])))
                              (is (= "before" (get-in out [:versions 1 :description])))
                              (is (nil? (get-in out [:versions 1 :reason])))
                              (is (= "now" archived)))))))
        (.then done))))

(deftest an-inbox-entry-carries-both-texts-and-they-come-from-different-tables
  (async done
    (-> (test-key)
        (.then (fn [k]
                 (-> (js/Promise.all
                      (into-array [(seal/seal k :scopes :description "safe agentic coding")
                                   (seal/seal k :recipe_proposals :description "what the agent wants")
                                   (seal/seal k :recipe_proposals :reason "the example went stale")
                                   (seal/seal k :recipes :description "what it says now")
                                   (seal/seal k :recipes :useful_when "when you need it")]))
                     (.then (fn [[scope proposed why current-d current-u]]
                              (seal/unseal-inbox-entry
                               k {:id 9 :kind "proposed" :recipe_title "A title, in the clear"
                                  :scopes [{:id 3 :title "sandboxing" :description scope}]
                                  :proposal {:title "A title, in the clear"
                                             :description proposed
                                             :reason why
                                             :current_description current-d
                                             :current_useful_when current-u}})))
                     (.then (fn [out]
                              (is (= "what the agent wants" (get-in out [:proposal :description])))
                              (is (= "the example went stale" (get-in out [:proposal :reason])))
                              (is (= "what it says now" (get-in out [:proposal :current_description])))
                              (is (= "when you need it" (get-in out [:proposal :current_useful_when])))
                              (is (= "safe agentic coding" (get-in out [:scopes 0 :description])))
                              (is (= "A title, in the clear" (:recipe_title out))))))))
        (.then done))))

(deftest unseal-proposal-means-the-same-thing-in-both-clients
  (testing "the agent's own text and, where a shape carries them, the current_ aliases"
    (async done
      (-> (test-key)
          (.then (fn [k]
                   (-> (js/Promise.all
                        (into-array [(seal/seal k :recipe_proposals :description "proposed")
                                     (seal/seal k :recipe_proposals :reason "why")
                                     (seal/seal k :recipes :description "as it reads now")
                                     (seal/seal k :recipes :useful_when "when you need it")]))
                       (.then (fn [[proposed why now when-s]]
                                (js/Promise.all
                                 (into-array
                                  [(seal/unseal-proposal k {:base_version 2
                                                            :description proposed
                                                            :reason why
                                                            :current_description now
                                                            :current_useful_when when-s})
                                   ;; and a shape without them is untouched by that half
                                   (seal/unseal-proposal k {:base_version 2 :description proposed})]))))
                       (.then (fn [[full bare]]
                                (is (= "proposed" (:description full)))
                                (is (= "why" (:reason full)))
                                (is (= "as it reads now" (:current_description full)))
                                (is (= "when you need it" (:current_useful_when full)))
                                (is (= "proposed" (:description bare)))
                                (is (not (contains? bare :current_description))))))))
          (.then done)))))

(deftest unseal-body-recognises-the-shapes-the-api-answers-with
  (async done
    (-> (test-key)
        (.then (fn [k]
                 (-> (js/Promise.all
                      (into-array [(seal/seal k :recipes :description "body")
                                   (seal/seal k :scopes :description "coding with AI")
                                   (seal/seal k :recipe_proposals :description "queued text")]))
                     (.then (fn [[body scope queued]]
                              (js/Promise.all
                               (into-array
                                [(seal/unseal-body k {:id 1 :version 3 :description body})
                                 (seal/unseal-body k [{:id 1 :version 1 :description body}])
                                 (seal/unseal-body k [{:id 1 :title "agentic engineering" :description scope}])
                                 (seal/unseal-body k {:error "…" :reason "modified-elsewhere"
                                                      :current {:id 1 :version 4 :description body}})
                                 (seal/unseal-body k {:error "…" :reason "proposal-pending"
                                                      :pending {:base_version 2 :description queued}})
                                 (seal/unseal-body k {:pending {:description queued}
                                                      :recipe {:id 1 :version 2 :description body}})
                                 (seal/unseal-body k {:success true})
                                 ;; `pending` means two things in this API — 0/1 on a
                                 ;; row, and a proposal body on a 409 or a 202 — and
                                 ;; reading the flag as the proposal left every
                                 ;; ordinary save sealed. Found end to end, pinned here.
                                 (seal/unseal-body k {:id 1 :version 3 :pending 0
                                                      :description body})]))))
                     (.then (fn [[one listing scopes moved pending filed plain flagged]]
                              (testing "a single Recipe, from a read, a create, a save or a publish"
                                (is (= "body" (:description one))))
                              (testing "a listing"
                                (is (= ["body"] (mapv :description listing))))
                              (testing "the Scopes listing, which carries no version"
                                (is (= ["coding with AI"] (mapv :description scopes))))
                              (testing "the 409 that says the Recipe moved"
                                (is (= "body" (get-in moved [:current :description]))))
                              (testing "the 409 that says a proposal is pending"
                                (is (= "queued text" (get-in pending [:pending :description]))))
                              (testing "the 202 that says one was just filed"
                                (is (= "queued text" (get-in filed [:pending :description])))
                                (is (= "body" (get-in filed [:recipe :description]))))
                              (testing "a body with no prose in it at all"
                                (is (= {:success true} plain)))
                              (testing "a Recipe row carrying its own pending flag is still a Recipe"
                                (is (= "body" (:description flagged)))))))))
        (.then done))))

(deftest a-recipe-write-seals-the-prose-and-nothing-else
  (async done
    (-> (test-key)
        (.then (fn [k]
                 (-> (js/Promise.all
                      (into-array [(seal/seal k :recipes :description "the body as stored")
                                   (seal/seal k :recipes :useful_when "unchanged")]))
                     (.then (fn [[stored-d stored-u]]
                              (.then (seal/seal-recipe-write
                                      k {:title "A title"
                                         :tags "one two three"
                                         :scope_ids [1 2]
                                         :modified_at "2026-09-05 10:00:00"
                                         :useful_when "unchanged"
                                         :description "the body, edited"
                                         :reason "because it was wrong"
                                         :context "steps 1-4"}
                                      {:description stored-d :useful_when stored-u})
                                     (fn [out] [stored-d stored-u out]))))
                     (.then (fn [[stored-d stored-u out]]
                              (is (= "A title" (:title out)) "the title is the search surface")
                              (is (= "one two three" (:tags out)))
                              (is (= [1 2] (:scope_ids out)))
                              (is (= "2026-09-05 10:00:00" (:modified_at out)))
                              (is (= stored-u (:useful_when out))
                                  "unchanged, so the stored ciphertext is echoed")
                              (is (seal/sealed? (:description out)))
                              (is (not= stored-d (:description out)))
                              (js/Promise.all
                               (into-array [(seal/unseal k :recipes :description (:description out))
                                            (seal/unseal k :recipes :reason (:reason out))
                                            (seal/unseal k :recipes :context (:context out))]))))
                     (.then (fn [[d r c]]
                              (is (= "the body, edited" d))
                              (is (= "because it was wrong" r))
                              (is (= "steps 1-4" c)))))))
        (.then done))))

(deftest a-partial-write-seals-only-what-it-carries
  (testing "an omitted field keeps its value, server-side; the seal must not send one"
    (async done
      (-> (test-key)
          (.then (fn [k] (seal/seal-recipe-write k {:scope_ids [3] :modified_at "…"})))
          (.then (fn [out] (is (= {:scope_ids [3] :modified_at "…"} out))))
          (.then done)))))

(deftest the-prefix-is-not-matched-loosely
  (is (true? (seal/sealed? "enc:v1:x")))
  (is (false? (seal/sealed? "enc:v1")))
  (is (false? (seal/sealed? nil)))
  (is (false? (seal/sealed? 7)))
  (is (false? (seal/sealed? (str/upper-case "enc:v1:x")))))

;; ---------------------------------------------------------------------------
;; Remembering what was opened.

(deftest sealed-index-finds-the-rows-a-client-writes-and-no-others
  (async done
    (-> (test-key)
        (.then (fn [k]
                 (-> (js/Promise.all
                      (into-array [(seal/seal k :recipes :description "a body")
                                   (seal/seal k :recipes :useful_when "when")
                                   (seal/seal k :scopes :description "a Scope's prose")
                                   (seal/seal k :recipe_history :description "an older draft")
                                   (seal/seal k :recipe_proposals :description "a proposal")]))
                     (.then (fn [[body when-s scope older proposed]]
                              [(seal/sealed-index
                                {:id 7 :version 3 :description body :useful_when when-s
                                 :scopes [{:id 2 :title "sandboxing" :description scope}]})
                               (seal/sealed-index
                                {:versions [{:version 2 :current true :description body}
                                            {:version 1 :description older}]})
                               (seal/sealed-index
                                [{:id 9 :kind "proposed" :version 4 :recipe_title "T"
                                  :proposal {:description proposed}}])
                               body when-s scope]))
                     (.then (fn [[recipe-index versions-index inbox-index body when-s scope]]
                              (testing "a Recipe and the Scopes hanging off it"
                                (is (= {[:recipes 7 :description] body
                                        [:recipes 7 :useful_when] when-s
                                        [:scopes 2 :description] scope}
                                       recipe-index)))
                              (testing "a version ladder indexes only the current row, which has the id"
                                (is (= {} versions-index)
                                    "history rows carry no id, and no client writes one"))
                              (testing "an inbox entry indexes nothing"
                                (is (= {} inbox-index)
                                    "a proposal is served without its id and is never written")))))))
        (.then done))))

(deftest stored-row-is-what-seal-row-wants
  (async done
    (-> (test-key)
        (.then (fn [k]
                 (-> (seal/seal k :recipes :description "a body")
                     (.then (fn [body]
                              (let [index (seal/sealed-index {:id 7 :version 1 :description body})]
                                [body
                                 (seal/stored-row index :recipes 7)
                                 (seal/stored-row index :recipes 8)])))
                     (.then (fn [[body seven eight]]
                              (is (= {:description body} seven))
                              (is (= {} eight)
                                  "a Recipe this client has not read the body of echoes nothing"))))))
        (.then done))))

(deftest what-a-client-can-tell-about-a-row-it-is-holding
  (testing "the question behind the publish interlock and the provenance withholding"
    (async done
      (-> (test-key)
          (.then (fn [k] (seal/seal k :recipes :description "sealed body")))
          (.then
           (fn [sealed]
             (testing "a client with the key: the ciphertext survives only in the index"
               (let [stored {:description sealed}
                     cached {:id 7 :description "sealed body" :useful_when "in the clear"}]
                 (is (true? (seal/arrived-sealed? stored cached :description)))
                 (is (false? (seal/arrived-sealed? stored cached :useful_when)))
                 (is (true? (seal/any-arrived-sealed? stored cached seal/published-surface)))))
             (testing "a client with no key: the row itself is the evidence"
               (let [cached {:id 7 :description sealed :useful_when "in the clear"}]
                 (is (true? (seal/arrived-sealed? {} cached :description)))
                 (is (true? (seal/any-arrived-sealed? {} cached seal/published-surface)))))
             (testing "a sealed useful-when alone is still a published surface"
               (is (true? (seal/any-arrived-sealed? {:useful_when sealed} {} seal/published-surface)))
               (is (false? (seal/arrived-sealed? {:useful_when sealed} {} :description))))
             (testing "an unmigrated row is not sealed, however it is held"
               (let [cached {:id 7 :description "plain" :useful_when "plain"}]
                 (is (false? (seal/any-arrived-sealed? {} cached seal/published-surface)))))
             (testing "reason and context are not the published surface"
               (is (= [:description :useful_when] seal/published-surface)))
             (testing "and it fails open on a row nothing has been read for"
               (is (false? (seal/arrived-sealed? {} nil :description)))
               (is (false? (seal/any-arrived-sealed? {} nil seal/published-surface))))))
          (.then done)))))

(deftest the-write-path-reaches-the-echo-rule-on-an-unmigrated-row
  (testing "sealed-index -> stored-for-write -> seal-recipe-write, which is the wiring
    et.cb.ui.api and et.cb.ui.state stand on. `seal` echoing correctly is not the
    same claim as the browser reaching it: for a whole round it did not, because
    sealed-index remembers a column only when it arrived sealed, so on exactly the
    unmigrated rows the rule was written for there was nothing to echo and every
    no-op Save shipped a fresh envelope."
    (async done
      (let [;; what GET /api/recipes/:id?detail=full answers for a row nobody has
            ;; migrated: prose in the clear
            response {:id 7 :version 2 :title "T" :pending 0
                      :description "the body, unmigrated"
                      :useful_when "the line, unmigrated"}
            index (seal/sealed-index response)]
        (is (= {} index) "nothing sealed arrived, so nothing is remembered")
        (-> (test-key)
            (.then (fn [k]
                     (-> (seal/unseal-recipe k response)
                         (.then (fn [cached]
                                  (let [stored (seal/stored-for-write index :recipes 7 cached)]
                                    (is (= {:description "the body, unmigrated"
                                            :useful_when "the line, unmigrated"}
                                           stored)
                                        "the cached row is what answers for an unsealed column")
                                    (js/Promise.all
                                     (into-array
                                      [;; the Save that changed nothing
                                       (seal/seal-recipe-write
                                        k {:title "T"
                                           :description "the body, unmigrated"
                                           :useful_when "the line, unmigrated"}
                                        stored)
                                       ;; and one that changed the body
                                       (seal/seal-recipe-write
                                        k {:title "T"
                                           :description "edited at last"
                                           :useful_when "the line, unmigrated"}
                                        stored)])))))
                         (.then (fn [[no-op edited]]
                                  (is (= "the body, unmigrated" (:description no-op))
                                      "a no-op Save writes the same value the server already has")
                                  (is (= "the line, unmigrated" (:useful_when no-op)))
                                  (is (not (seal/sealed? (:description no-op))))
                                  (is (seal/sealed? (:description edited))
                                      "and a real edit is what seals the row")
                                  (is (= "the line, unmigrated" (:useful_when edited))
                                      "while the field it did not touch stays a no-op"))))))
            (.then done))))))

(deftest the-write-path-still-echoes-ciphertext-on-a-sealed-row
  (testing "the same wiring on the other side of the migration"
    (async done
      (-> (test-key)
          (.then (fn [k]
                   (-> (js/Promise.all
                        (into-array [(seal/seal k :recipes :description "the body")
                                     (seal/seal k :recipes :useful_when "the line")]))
                       (.then (fn [[d u]]
                                (let [response {:id 7 :version 2 :title "T" :description d :useful_when u}
                                      index (seal/sealed-index response)]
                                  (is (= 2 (count index)))
                                  (.then (seal/unseal-recipe k response)
                                         (fn [cached]
                                           (let [stored (seal/stored-for-write index :recipes 7 cached)]
                                             (is (= {:description d :useful_when u} stored)
                                                 "the index wins wherever it has an entry")
                                             (.then (seal/seal-recipe-write
                                                     k {:title "T" :description "the body" :useful_when "the line"}
                                                     stored)
                                                    (fn [out] [d u out]))))))))
                       (.then (fn [[d u out]]
                                (is (= d (:description out)) "byte-identical echo")
                                (is (= u (:useful_when out))))))))
          (.then done)))))

(deftest a-scope-write-takes-the-other-source-on-purpose
  (testing "the fourth wiring case, and the one that goes the other way.

    `save-scope` asks `stored-row` where `update-recipe` asks `stored-for-write`,
    and the difference is not an oversight. The echo rule exists for the server's
    content-would-change? — the version, the history row, the proposal — and a
    Scope has none of those. What it does have is no `modified_at` at all, so
    nothing on either side refuses a stale write. Echoing there would let a tab
    holding a pre-migration listing write a plaintext description straight over an
    envelope, on a save that only meant to change the title.

    So on a Scope an unchanged description gets a fresh envelope: content
    preserved, still sealed, which is the safe direction. This pins the choice,
    because the two shapes are one word apart and the next reader will be tempted
    to make them match."
    (async done
      (-> (test-key)
          (.then
           (fn [k]
             (-> (seal/seal k :scopes :description "prose the migration has reached")
                 (.then
                  (fn [sealed]
                    (let [unmigrated {:id 3 :title "Scratch"
                                      :description "prose nobody has sealed yet" :tags ""}
                          migrated {:id 91 :title "sandboxing" :description sealed :tags ""}
                          index (seal/sealed-index [unmigrated migrated])]
                      (is (= {[:scopes 91 :description] sealed} index)
                          "only the sealed one is remembered, which is what stored-row answers from")
                      (is (= {} (seal/stored-row index :scopes 3)))
                      (is (= {:description sealed} (seal/stored-row index :scopes 91)))
                      (testing "and this is the value save-scope must NOT use"
                        (is (= {:description "prose nobody has sealed yet"}
                               (seal/stored-for-write index :scopes 3 unmigrated))
                            "stored-for-write hands back the plaintext — right for a Recipe, and the stale-write hole for a Scope"))
                      (js/Promise.all
                       (into-array
                        [;; what save-scope actually builds for the unsealed Scope
                         (seal/seal-scope-write k {:title "Scratch"
                                                   :description "prose nobody has sealed yet"}
                                                (seal/stored-row index :scopes 3))
                         ;; and for the sealed one
                         (seal/seal-scope-write k {:title "sandboxing"
                                                   :description "prose the migration has reached"}
                                                (seal/stored-row index :scopes 91))
                         (js/Promise.resolve sealed)])))))
                 (.then (fn [[fresh echoed sealed]]
                          (is (seal/sealed? (:description fresh))
                              "an unchanged Scope description seals rather than echoing")
                          (is (= "Scratch" (:title fresh))
                              "and the title is still the search surface")
                          (is (= sealed (:description echoed))
                              "while a Scope this client read sealed still echoes byte-identical"))))))
          (.then done)))))

(deftest a-row-this-client-has-not-read-echoes-nothing
  (testing "the third case, which must not become an accidental no-op"
    (async done
      (-> (test-key)
          (.then (fn [k]
                   (let [stored (seal/stored-for-write {} :recipes 7 nil)]
                     (is (= {} stored))
                     (seal/seal-recipe-write k {:title "T" :description "a body"} stored))))
          (.then (fn [out] (is (seal/sealed? (:description out)))))
          (.then done)))))

(deftest an-unchanged-save-through-the-index-echoes
  (testing "the whole point, end to end: read a Recipe, save it untouched, send back the same bytes"
    (async done
      (-> (test-key)
          (.then (fn [k]
                   (-> (js/Promise.all
                        (into-array [(seal/seal k :recipes :description "the body")
                                     (seal/seal k :recipes :useful_when "the line")]))
                       (.then (fn [[body line]]
                                (let [response {:id 7 :version 3 :title "T"
                                                :description body :useful_when line}
                                      index (seal/sealed-index response)]
                                  (.then (seal/unseal-recipe k response)
                                         (fn [read-back]
                                           [body line index read-back])))))
                       (.then (fn [[body line index read-back]]
                                (is (= "the body" (:description read-back)))
                                (.then (seal/seal-recipe-write
                                        k {:title (:title read-back)
                                           :description (:description read-back)
                                           :useful_when (:useful_when read-back)}
                                        (seal/stored-row index :recipes 7))
                                       (fn [out] [body line out]))))
                       (.then (fn [[body line out]]
                                (is (= body (:description out)))
                                (is (= line (:useful_when out)))
                                (is (= "T" (:title out))))))))
          (.then done)))))
