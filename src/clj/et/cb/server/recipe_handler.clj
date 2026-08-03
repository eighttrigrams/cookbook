(ns et.cb.server.recipe-handler
  (:require [clojure.string :as str]
            [et.cb.server.common :as common]
            [et.cb.db.recipe :as db.recipe]))

(defn- lean?
  "`?detail=full` is the only thing that widens the projection. Anything else,
  including no param at all, gets the retrieval index."
  [req]
  (not= "full" (common/query-param req "detail")))

(defn list-recipes-handler
  "GET /api/recipes — the caller's recipes, most recently saved first, optionally
  narrowed by ?search over title and useful-when.

  **Lean by default**: the response carries no `description` key at all. Pass
  ?detail=full to include it. The two short fields are meant as a retrieval
  index — scan them, decide which recipe you want, then fetch that one body."
  [req]
  (let [user-id (common/get-user-id req)]
    {:status 200
     :body (db.recipe/list-recipes (common/ensure-ds) user-id
                                   {:search-term (common/query-param req "search")
                                    :lean? (lean? req)})}))

(defn get-recipe-handler
  "GET /api/recipes/:id — one recipe. Lean by default like the listing;
  ?detail=full adds the description. 404 when the id matches nothing you own."
  [req]
  (let [user-id (common/get-user-id req)
        id (common/parse-int-opt (get-in req [:params :id]))
        recipe (when id (db.recipe/get-recipe (common/ensure-ds) user-id id
                                              {:lean? (lean? req)}))]
    (if recipe
      {:status 200 :body recipe}
      {:status 404 :body {:error "Recipe not found"}})))

(defn add-recipe-handler
  "POST /api/recipes — create a recipe from {:title :useful_when :description}.
  The title is required and must be non-blank; the other two default to empty.

  The new recipe is version 1 with no history, and it is **private**:
  `published` is not accepted here, because publishing is its own deliberate
  act — POST /api/recipes/:id/publish. 201 with the created recipe in the full
  shape, 400 on a blank title."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [title] :as body} (:body req)]
    (if (str/blank? (str title))
      {:status 400 :body {:error "title is required"}}
      {:status 201
       :body (db.recipe/create-recipe (common/ensure-ds) user-id
                                      (select-keys body [:title :useful_when :description]))})))

(defn update-recipe-handler
  "PUT /api/recipes/:id — save {:title :useful_when :description}. A field you
  leave out keeps its current value, so an edit meant for one field cannot
  silently clear the other two; a blank title is refused with 400.

  Every save that changes something archives the outgoing state as a version and
  moves the row to the next one. A save that changes nothing is a no-op — same
  version, no history row. Pass `modified_at` from the last read to be told
  (409) when someone else saved in between. `published` is not writable here —
  POST /api/recipes/:id/publish is the only thing that sets it, and nothing
  clears it. 404 when the id matches nothing you own."
  [req]
  (let [ds (common/ensure-ds)
        user-id (common/get-user-id req)
        id (common/parse-int-opt (get-in req [:params :id]))
        {:keys [title modified_at] :as body} (:body req)]
    (cond
      (nil? (when id (db.recipe/get-recipe ds user-id id)))
      {:status 404 :body {:error "Recipe not found"}}

      (and (some? title) (str/blank? (str title)))
      {:status 400 :body {:error "title cannot be blank"}}

      :else
      (if-let [result (db.recipe/update-recipe ds user-id id
                                               (select-keys body [:title :useful_when :description])
                                               modified_at)]
        {:status 200 :body result}
        {:status 409 :body {:error "Recipe was modified elsewhere"
                            :current (db.recipe/get-recipe ds user-id id {:lean? false})}}))))

(defn delete-recipe-handler
  "DELETE /api/recipes/:id — remove a recipe together with its whole version
  history. 404 when the id matches nothing you own."
  [req]
  (let [user-id (common/get-user-id req)
        id (common/parse-int-opt (get-in req [:params :id]))
        result (when id (db.recipe/delete-recipe (common/ensure-ds) user-id id))]
    (if (:success result)
      {:status 200 :body result}
      {:status 404 :body {:error "Recipe not found"}})))

(defn publish-recipe-handler
  "POST /api/recipes/:id/publish — publish a recipe: it becomes visible to
  anyone, and the owner has put his name to it.

  **One way.** There is no unpublish route, deliberately. Publishing an already
  published recipe is a 200 no-op that leaves the original `published_at` where
  it was — the first publish is the fact being recorded. It is not a content
  change: no version bump and no history row. 404 when the id matches nothing
  you own."
  [req]
  (let [user-id (common/get-user-id req)
        id (common/parse-int-opt (get-in req [:params :id]))
        result (when id (db.recipe/publish-recipe (common/ensure-ds) user-id id))]
    (if result
      {:status 200 :body result}
      {:status 404 :body {:error "Recipe not found"}})))

(defn recipe-versions-handler
  "GET /api/recipes/:id/versions — every version of a recipe, newest first, each
  with its `version`, all three fields and `created_at`. The newest entry is the
  current row and carries `current: true`; the rest are the superseded states,
  which is what makes 'how did this read back then' answerable. Not affected by
  ?detail — a history is the content or it is nothing."
  [req]
  (let [user-id (common/get-user-id req)
        id (common/parse-int-opt (get-in req [:params :id]))
        result (when id (db.recipe/list-versions (common/ensure-ds) user-id id))]
    (if result
      {:status 200 :body result}
      {:status 404 :body {:error "Recipe not found"}})))
