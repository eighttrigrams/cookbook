(ns et.cb.middleware.rate-limit)

(def ^:private request-log (atom []))

(defn- current-time-ms []
  (System/currentTimeMillis))

(defn- prune-old-requests [logs window-ms]
  (let [cutoff (- (current-time-ms) window-ms)]
    (filterv #(> % cutoff) logs)))

(defn- allowed? [max-requests window-ms]
  (let [now (current-time-ms)
        pruned (prune-old-requests @request-log window-ms)]
    (reset! request-log pruned)
    (if (< (count pruned) max-requests)
      (do
        (swap! request-log conj now)
        true)
      false)))

(defn reset-rate-limit! []
  (reset! request-log []))

(defn wrap-rate-limit
  [handler max-requests window-seconds]
  (let [window-ms (* window-seconds 1000)]
    (fn [request]
      (if (allowed? max-requests window-ms)
        (handler request)
        {:status 429
         :headers {"Content-Length" "0"}
         :body ""}))))
