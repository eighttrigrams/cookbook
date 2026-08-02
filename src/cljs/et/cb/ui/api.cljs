(ns et.cb.ui.api
  (:require [ajax.core :refer [GET POST PUT DELETE]]))

(defn fetch-json
  [endpoint headers handler]
  (GET endpoint
    {:response-format :json
     :keywords? true
     :headers headers
     :handler handler}))

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
