(ns et.cb.ui.key-store
  "Where the browser keeps the Cookbook key.

  The key is the one credential in this app that is *not* the same kind of thing
  as the password. A machine token says who may **write**; the key says who may
  **read prose**. Cookbook is served from fly and the key is never sent there, so
  the browser has to hold its own copy, and how it holds it is the whole
  difference between end-to-end encryption and a decoration.

  ## Non-extractable, in IndexedDB

  The key is imported once as a `CryptoKey` with `extractable` **false** and
  stored in IndexedDB. Two properties follow, and both are the point:

  - **The page can use it and cannot read it.** `crypto.subtle.encrypt` works;
    `exportKey` throws. So a cross-site scripting bug on this page can seal and
    unseal while the tab is open — nothing prevents that, and nothing could — but
    it cannot take the key away with it. A base64 string in `localStorage` is one
    `JSON.stringify` from being posted somewhere.
  - **It survives a reload** without the key material ever having been a string
    the app kept. The pasted text lives in one component-local atom for as long as
    the paste takes and is cleared on import.

  IndexedDB is what makes both true at once: it is the only browser store that
  holds a live `CryptoKey` object rather than text.

  ## Getting it in

  Paste it into the ⚙ panel, once per browser. On a phone the same panel takes
  the same base64. There is deliberately **no** import path through a URL
  fragment, a query parameter or a message from an agent: the two decrypt
  surfaces are this page and `plurama-cli`, and an agent that could hand a key to
  a browser is a fourth way to lose one.

  ## No key is a legitimate state

  A visitor has no key and must never have one; the owner on a new browser has
  not pasted it yet. Both read cookbook fine — everything that is not prose is in
  the clear, and prose shows as `enc:v1:…`, which is what an unreadable value
  honestly looks like. Nothing here blocks the app on a key."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.cb.seal :as seal]))

(def ^:private db-name "cookbook")
(def ^:private store-name "seal-key")
(def ^:private record-key "current")

(defonce state
  ;; `{:status :unknown|:absent|:present, :key CryptoKey|nil, :fingerprint str|nil}`.
  ;;
  ;; A reagent atom because the ⚙ panel renders off it. **The `CryptoKey` in here is
  ;; not a secret the page can spill** — it is non-extractable, so what is being held
  ;; is a handle rather than key material.
  (r/atom {:status :unknown :key nil :fingerprint nil}))

(defn current-key
  "The key, or `nil` when there is none — which every function in `et.cb.seal`
  takes to mean *sealing is off*."
  []
  (:key @state))

(defn- open-db []
  (js/Promise.
   (fn [resolve reject]
     (let [req (.open js/indexedDB db-name 1)]
       (set! (.-onupgradeneeded req)
             (fn [_]
               (let [db (.-result req)]
                 (when-not (.contains (.-objectStoreNames db) store-name)
                   (.createObjectStore db store-name)))))
       (set! (.-onsuccess req) (fn [_] (resolve (.-result req))))
       (set! (.-onerror req) (fn [_] (reject (.-error req))))))))

(defn- tx-request
  "One IndexedDB request, as a promise. `f` is handed the object store and returns
  the request to wait on."
  [mode f]
  (.then (open-db)
         (fn [db]
           (js/Promise.
            (fn [resolve reject]
              (let [store (.objectStore (.transaction db #js [store-name] mode) store-name)
                    req (f store)]
                (set! (.-onsuccess req) (fn [_] (resolve (.-result req))))
                (set! (.-onerror req) (fn [_] (reject (.-error req))))))))))

(defn- fingerprint
  "Eight hex characters of SHA-256 over the raw key, computed **before** the key is
  imported and the raw bytes are dropped.

  It is here so two devices can be told they hold the same key without either of
  them being able to say what it is — the same job an ssh key fingerprint does.
  Half a truncated hash of 256 bits of entropy identifies a key and does not help
  anyone find one."
  [raw]
  (.then (.digest (.-subtle js/crypto) "SHA-256" raw)
         (fn [digest]
           (->> (take 4 (array-seq (js/Uint8Array. digest)))
                (map #(.padStart (.toString % 16) 2 "0"))
                (str/join)))))

(defn load!
  "Read the key back out of IndexedDB into `state`. Called once, before the app's
  first request goes out — a fetch that raced this would hand its handler
  ciphertext and cache it.

  Any failure lands on `:absent`. Private browsing, a blocked IndexedDB, a first
  visit: all of them mean *this browser has no key*, which is a legitimate state
  and not an error to put in front of anybody."
  []
  (-> (tx-request "readonly" #(.get % record-key))
      (.then (fn [record]
               (if record
                 (reset! state {:status :present
                                :key (.-key record)
                                :fingerprint (.-fingerprint record)})
                 (reset! state {:status :absent :key nil :fingerprint nil}))))
      (.catch (fn [_] (reset! state {:status :absent :key nil :fingerprint nil})))))

(defn import-key!
  "The panel's action: validate, fingerprint, import non-extractably, store, and
  swap it in. The pasted text is never written anywhere but the `CryptoKey` this
  produces; the caller clears its own input."
  [text]
  (let [raw (try (let [bin (js/atob (str/trim text))
                       out (js/Uint8Array. (.-length bin))]
                   (dotimes [i (.-length bin)] (aset out i (.charCodeAt bin i)))
                   out)
                 (catch :default _ nil))]
    (cond
      (nil? raw) (js/Promise.reject (js/Error. "That is not base64."))
      (not= 32 (.-length raw)) (js/Promise.reject
                                (js/Error. (str "A Cookbook key is 32 bytes; that one is "
                                                (.-length raw) ".")))
      :else
      (-> (js/Promise.all #js [(seal/import-key raw) (fingerprint raw)])
          (.then (fn [[k fp]]
                   (.then (tx-request "readwrite"
                                      #(.put % #js {:key k :fingerprint fp} record-key))
                          (fn [_]
                            (reset! state {:status :present :key k :fingerprint fp})
                            fp))))))))

(defn forget!
  "Drop the key from this browser. It is not a delete of anything — the key lives
  in `secrets.yaml` and on paper, and the shelf stays sealed — it is this device
  forgetting. Useful on a borrowed machine, and useful for seeing the app as a
  reader without the key sees it."
  []
  (-> (tx-request "readwrite" #(.delete % record-key))
      (.then (fn [_] (reset! state {:status :absent :key nil :fingerprint nil})))
      (.catch (fn [_] (reset! state {:status :absent :key nil :fingerprint nil})))))
