(ns et.cb.ui.url
  "The address bar, as five small functions — tracker's `et.tr.ui.url`, minus the
  part cookbook does not need.

  **There is no routing library in this workspace and this is not the beginning of
  one.** Neither cookbook, tracker nor personalist depends on reitit, bidi,
  secretary, accountant or pushy; the two siblings that put a thing's identity in
  the URL hand-roll it over `js/history`, and this is the same shape so that a
  reader who has met one has met all three.

  It was four functions until a Recipe's page grew a second mode. `editing?` is the
  fifth and it is **one predicate about one flag**, not a query parser returning a
  map: there is one query param in this app, it has one value that means anything,
  and a `params` map would be the thing the paragraph above says this is not — plus
  a rewrite of every existing caller to take it. `recipe-path` grew an arity for the
  same reason rather than a sibling function.

  **One entity, so no type prefix.** Tracker's slug carries three letters
  (`/item/tsk42`) because five kinds of thing share one route over there and the
  path has to say which. Cookbook has Recipes. So the path is `/recipe/42`, bare —
  and no title in it either: the owner asked for `/recipe/<id>` and a title in a
  slug is a second name for a Recipe that can go stale the moment one is renamed.

  Nothing here validates an id beyond reading it. Whether 42 exists, and whether
  this caller may see it, is the API's answer — the server's route deliberately
  does not ask either question, and `views.recipe` is where the answer is
  rendered."
  (:require [clojure.string :as str]))

(defn parse-recipe-path
  "The Recipe id in `pathname`, as a number, or nil for any other path.

  `re-matches` and not `re-find`, so `/recipe/42/edit` is not a Recipe page that
  happens to have something after it — a path this app did not write is one it does
  not claim. The `js/isNaN` guard is tracker's and is kept even though `\\d+` has
  already done most of the work: a run of digits long enough to overflow parses to
  something that is not a Recipe id either."
  [pathname]
  (when-let [[_ id-str] (re-matches #"/recipe/(\d+)" (str pathname))]
    (let [id (js/parseInt id-str 10)]
      (when-not (js/isNaN id)
        id))))

(defn recipe-path
  "A Recipe's address, reading or editing.

  `?edit=true` and not `/recipe/42/edit`, because he asked for the query param —
  *instead of an edit modal, lets go to a separate page, with ?edit=true query
  param* — and because the path form would have to get past
  `parse-recipe-path`'s `re-matches`, which deliberately refuses a path with
  anything after the id. The flag says *how you are looking at* Recipe 42; the path
  says *which Recipe*, and one of those is the thing's identity."
  ([id] (str "/recipe/" id))
  ([id edit?] (if edit? (str (recipe-path id) "?edit=true") (recipe-path id))))

(defn editing?
  "Whether the address is asking for a Recipe's editor rather than its reading.

  `URLSearchParams` and not a regex over `.-search`: it is a browser built-in, so
  it is not a dependency, and it gets `?a=1&edit=true` and `?edited=true` right
  without anybody having to think about the second one.

  **`edit=true` exactly**, so `?edit=1` and a bare `?edit` are not the editor. One
  spelling, since the only thing that ever writes this is `recipe-path` — and an
  address a person typed by hand that misses it lands on the reading, which is the
  safe half of the two.

  Reads `js/location` itself, the way `current-path` does and for its reason: one
  function so that everything asking the question asks it the same way."
  []
  (= "true" (.get (js/URLSearchParams. (.-search js/location)) "edit")))

(defn push-state!
  "A new entry in the history: this is a move the reader made, and Back should
  return them to where they were."
  [path]
  (.pushState js/history nil "" path))

(defn replace-state!
  "The same address written over the current entry rather than after it — for
  correcting a bar that says something the app is not showing, where an extra Back
  step would be a step into a state that never existed."
  [path]
  (.replaceState js/history nil "" path))

(defn current-path
  "Where the browser says it is. One function so that everything asking the
  question asks it the same way, and `str/blank?` cannot creep in as three
  different fallbacks."
  []
  (let [p (.-pathname js/location)]
    (if (str/blank? p) "/" p)))
