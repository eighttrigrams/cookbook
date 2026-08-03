(ns et.cb.server.recipe-handler
  (:require [clojure.string :as str]
            [et.cb.server.common :as common]
            [et.cb.db.recipe :as db.recipe]))

(defn- lean?
  "`?detail=full` is the only thing that widens the projection. Anything else,
  including no param at all, gets the retrieval index."
  [req]
  (not= "full" (common/query-param req "detail")))

(defn- read-scope
  "Owner or visitor, decided once per read. A visitor is deliberately *not*
  described by a user-id: `common/get-user-id` gives nil for one, and the db
  layer reads a nil user-id as `user_id IS NULL`, which is a real owner in this
  schema — so the visitor path never asks for a user-id at all."
  [req]
  (if (common/authenticated? req)
    (common/get-user-id req)
    db.recipe/visitor-scope))

(defn list-recipes-handler
  "GET /api/recipes — the caller's recipes, most recently saved first, optionally
  narrowed by ?search over title and useful-when.

  **Lean by default**: the response carries no `description` key at all. Pass
  ?detail=full to include it. The two short fields are meant as a retrieval
  index — scan them, decide which recipe you want, then fetch that one body.

  An anonymous visitor is served the **published** recipes instead of anybody's
  private ones. An unpublished recipe is absent from that listing rather than
  redacted in it: no title, no id, and nothing that reveals it is there."
  [req]
  {:status 200
   :body (db.recipe/list-recipes (common/ensure-ds) (read-scope req)
                                 {:search-term (common/query-param req "search")
                                  :lean? (lean? req)})})

(defn get-recipe-handler
  "GET /api/recipes/:id — one recipe. Lean by default like the listing;
  ?detail=full adds the description. 404 when the id matches nothing you own.

  For an anonymous visitor only a published recipe matches, and an unpublished
  one is the same 404 as an id that does not exist. `?detail=full` then shows a
  visitor all three fields: the collapse is about verbosity, the privacy
  boundary is the publish latch itself."
  [req]
  (let [id (common/recipe-id req)
        recipe (when id (db.recipe/get-recipe (common/ensure-ds) (read-scope req) id
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
        id (common/recipe-id req)
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
        id (common/recipe-id req)
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
        id (common/recipe-id req)
        result (when id (db.recipe/publish-recipe (common/ensure-ds) user-id id))]
    (if result
      {:status 200 :body result}
      {:status 404 :body {:error "Recipe not found"}})))

(defn recipe-versions-handler
  "GET /api/recipes/:id/versions — every version of a recipe, newest first, each
  with its `version`, all three fields and `created_at`. The newest entry is the
  current row and carries `current: true`; the rest are the superseded states,
  which is what makes 'how did this read back then' answerable. Not affected by
  ?detail — a history is the content or it is nothing.

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
