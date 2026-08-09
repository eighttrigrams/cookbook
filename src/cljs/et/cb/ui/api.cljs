(ns et.cb.ui.api
  (:require [ajax.core :refer [GET POST PUT DELETE]]))

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
              :handler handler}
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
              :handler handler}
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
              :handler handler}
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
              :handler handler}
       error-handler (assoc :error-handler error-handler)))))
