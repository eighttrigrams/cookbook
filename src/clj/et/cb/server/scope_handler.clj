(ns et.cb.server.scope-handler
  "The Scopes API: a small CRUD surface over `et.cb.db.scope`.

  **These routes are not behind the recipe guards, and they must not assume they
  are.** `wrap-recipe-write-guard` and `wrap-machine-recipe-rules` sit inside the
  `/api/recipes` context in `et.cb.server`; these are siblings of that context, so
  nothing in front of them has asked who is calling. Each handler asks for itself,
  through `common/authenticated?` — the same shape the machine-user routes use, and
  for the same reason. Assuming a guard elsewhere covers you is exactly what a
  URL-encoding bug exploited in this codebase before.

  **Anonymous callers get a 403 and learn nothing else.** Not an empty list: an
  empty list is an answer about how the owner files his shelf, and the owner's
  instruction was *to logged in users only, no matter what*.

  **A machine token is accepted, here as everywhere but the publish latch.**
  Cookbook is an agentic memory store and its README says the machine writes
  unsupervised; the two exceptions that exist are both about the latch being
  irreversible, and none of these four routes is. An agent that could read the
  Scopes but not make one would be able to file a Recipe only where the owner had
  already thought to, which is the opposite of a curated retrieval index. The one
  destructive route here — DELETE — takes no Recipe with it: each one keeps every
  word of its text and loses a badge, and re-creating the Scope and refiling is a
  path back. That is why it is not treated like the publish latch.

  Every docstring below is `METHOD /path — explanation`, which is what
  `route-doc-re` matches; a handler documented any other way is missing from
  `/api/describe` entirely, and an agent reading that catalogue is the primary
  caller of this API."
  (:require [clojure.string :as str]
            [et.cb.server.common :as common]
            [et.cb.db.scope :as db.scope]))

(def ^:private forbidden
  "One refusal, so the four routes cannot come to word it differently — and it says
  nothing about whether any Scope exists."
  {:status 403 :body {:error "Scopes are the owner's: sign in to read or change them"}})

(defn- caller
  "The user-id to act as, or nil when nobody is signed in.

  `common/authenticated?` and not `owner-caller?`: a machine token acts in the
  owner's audience and is on his side of this line, so `get-user-id` gives the
  owner's id for it — which is why there is no resolution step here to forget.
  Note that a legitimate answer is **nil for the dev owner**, who has no `users`
  row, so this cannot be collapsed into `(when-let [id ...])`; that is the bug
  `visitor-audience` exists to prevent, one layer down."
  [req]
  (when (common/authenticated? req)
    (common/get-user-id req)))

(defn list-scopes-handler
  "GET /api/scopes — the owner's Scopes, by title, each with `id`, `title`,
  `description` and `recipe_count`.

  **A Scope is a category a Recipe can be filed under**: 0 to n of them per
  Recipe, written on the Recipe's own write path as `scope_ids` (see POST and PUT
  /api/recipes). `recipe_count` is how many Recipes are filed under it right now,
  which is what makes deleting one an informed decision rather than a guess.

  Authenticated callers only — a machine token included, since an agent that
  cannot read this list cannot file a Recipe under the right Scope. An anonymous
  caller gets 403 and is told nothing about whether any Scope exists: the filing is
  the owner's whether or not the Recipes are published. The same list is appended
  to GET /api/describe for the same caller, so an agent discovering this API reads
  the Scopes in the same breath as the routes."
  [req]
  (if (common/authenticated? req)
    {:status 200 :body (db.scope/list-scopes (common/ensure-ds) (caller req))}
    forbidden))

(defn add-scope-handler
  "POST /api/scopes — create a Scope from {:title :description}. The title is
  required and must be non-blank; the description defaults to empty.

  The title is trimmed and is **unique per owner**, so a second Scope by the same
  name is a 409 rather than a duplicate nobody can tell apart in a list. 201 with
  the created Scope, 400 on a blank title, 403 when nobody is signed in."
  [req]
  (if (common/authenticated? req)
    (let [{:keys [title description]} (:body req)]
      (if (str/blank? (str title))
        {:status 400 :body {:error "title is required"}}
        (if-let [created (db.scope/create-scope (common/ensure-ds) (caller req)
                                                {:title title :description description})]
          {:status 201 :body created}
          {:status 409 :body {:error "You already have a Scope with that title"}})))
    forbidden))

(defn update-scope-handler
  "PUT /api/scopes/:id — save {:title :description}. **A field you leave out keeps
  its current value**, the way it does for a Recipe, so an edit meant for the
  description cannot silently blank the title. A blank title is refused.

  Renaming a Scope does not touch which Recipes are filed under it: the
  association is by id, so the badges follow the rename. 200 with the saved Scope,
  400 on a blank title, 409 when the new title is one of your other Scopes', 404
  when the id matches nothing you own, 403 when nobody is signed in."
  [req]
  (if (common/authenticated? req)
    (let [ds (common/ensure-ds)
          user-id (caller req)
          id (common/path-id req)
          {:keys [title] :as body} (:body req)]
      (cond
        (nil? (when id (db.scope/get-scope ds user-id id)))
        {:status 404 :body {:error "Scope not found"}}

        (and (some? title) (str/blank? (str title)))
        {:status 400 :body {:error "title cannot be blank"}}

        :else
        (if-let [saved (db.scope/update-scope ds user-id id
                                              (select-keys body [:title :description]))]
          {:status 200 :body saved}
          {:status 409 :body {:error "You already have a Scope with that title"}})))
    forbidden))

(defn delete-scope-handler
  "DELETE /api/scopes/:id — remove a Scope **together with every association to
  it**.

  The Recipes filed under it are not touched: each keeps all of its text and
  simply loses a badge. Nothing enforces the foreign keys on this database, so the
  join rows are deleted in the same transaction rather than left to a cascade that
  does not fire — GET /api/scopes' `recipe_count` is how a caller finds out how
  many Recipes this will unfile before calling it.

  200 {:success true}, 404 when the id matches nothing you own, 403 when nobody is
  signed in."
  [req]
  (if (common/authenticated? req)
    (let [id (common/path-id req)
          result (when id (db.scope/delete-scope (common/ensure-ds) (caller req) id))]
      (if (:success result)
        {:status 200 :body result}
        {:status 404 :body {:error "Scope not found"}}))
    forbidden))
