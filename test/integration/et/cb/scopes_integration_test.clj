(ns et.cb.scopes-integration-test
  "Scopes over HTTP: the CRUD surface, filing a Recipe from the Recipe's own write
  path, and who is told that any of it exists.

  The shape of the feature is a matrix, so most cases assert both halves at once:

  | caller  | reads Scopes | writes Scopes | sees them on a Recipe |
  |---------|--------------|---------------|-----------------------|
  | owner   | yes          | yes           | yes                   |
  | machine | yes          | yes           | yes                   |
  | anon    | **403**      | **403**       | **no key**            |

  A machine is on the owner's side of every line here. That is the app's default
  and not a decision made for Scopes — the README's *unsupervised writes* — and the
  two exceptions cookbook does have are both about the publish latch being
  irreversible, which none of these routes is.

  **The anonymous row is the one with teeth**, and the assertion for it is
  `contains?` rather than `= []`: an empty vector would tell a visitor 'this Recipe
  is filed under nothing', which is a claim about the owner's filing that he did
  not make. Published Recipes are the case that matters, because *no matter what*
  is what the owner said and publishing is the exception somebody would otherwise
  reach for.

  What is **not** here: the association semantics themselves — absent keeps,
  present replaces, empty clears — which are `et.cb.scope-db-test`'s, and the
  orphan-row deletes, which only a read of the join table can check."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [et.cb.db.user :as db.user]
            [et.cb.integration-helpers :as h :refer [GET-json POST-json PUT-json DELETE-json]]))

(use-fixtures :each h/with-integration-db)

(defn- scope! [title description]
  (:body (POST-json "/api/scopes" {:title title :description description})))

(defn- recipe!
  ([title] (recipe! title nil))
  ([title scope-ids]
   (:body (POST-json "/api/recipes"
                     (cond-> {:title title :useful_when "when testing"
                              :description "body v1"}
                       scope-ids (assoc :scope_ids scope-ids))))))

(defn- machine-token!
  "A machine user with a password set, and the token its login mints — the real
  one, so what is under test is the claim in it rather than a flag a test passed."
  []
  (db.user/set-machine-user-password! h/*ds* h/*user-id* "machine-secret")
  (:token (:body (POST-json "/api/auth/login" {:username "machine-user"
                                               :password "machine-secret"}))))

(defn- titles-on [recipe] (mapv :title (:scopes recipe)))

;; ---------------------------------------------------------------------------
;; the CRUD surface

(deftest a-scope-is-created-read-back-and-edited
  (let [created (scope! "Bread" "Anything with flour in it")]
    (is (= 201 (:status (POST-json "/api/scopes" {:title "Deployment"}))))
    (testing "the listing carries the title and the description of each"
      (let [resp (GET-json "/api/scopes")]
        (is (= 200 (:status resp)))
        (is (= [["Bread" "Anything with flour in it"] ["Deployment" ""]]
               (mapv (juxt :title :description) (:body resp))))))
    (testing "and how many Recipes are filed under it, which is what makes
              deleting one an informed decision"
      (recipe! "Sourdough" [(:id created)])
      (is (= {"Bread" 1 "Deployment" 0}
             (into {} (map (juxt :title :recipe_count)) (:body (GET-json "/api/scopes"))))))
    (testing "an edit that names one field keeps the other"
      (let [resp (PUT-json (str "/api/scopes/" (:id created)) {:description "Loaves"})]
        (is (= 200 (:status resp)))
        (is (= "Bread" (:title (:body resp))))
        (is (= "Loaves" (:description (:body resp))))))
    (testing "and a rename does not unfile anything, because the association is by
              id and not by title"
      (is (= 200 (:status (PUT-json (str "/api/scopes/" (:id created)) {:title "Baking"}))))
      (is (= ["Baking"] (titles-on (first (:body (GET-json "/api/recipes")))))))))

(deftest the-refusals-each-have-their-own-status
  (let [{:keys [id]} (scope! "Bread" "")]
    (testing "a blank title is a 400 on both writes"
      (is (= 400 (:status (POST-json "/api/scopes" {:title "   "}))))
      (is (= 400 (:status (PUT-json (str "/api/scopes/" id) {:title ""})))))
    (testing "a duplicate title is a 409 and not a 500 out of the unique index"
      (let [resp (POST-json "/api/scopes" {:title "Bread"})]
        (is (= 409 (:status resp)))
        (is (re-find #"(?i)already" (:error (:body resp))))))
    (testing "a rename onto another Scope's title is the same 409"
      (let [other (scope! "Deployment" "")]
        (is (= 409 (:status (PUT-json (str "/api/scopes/" (:id other)) {:title "Bread"}))))))
    (testing "and an id that matches nothing is a 404 on both the edit and the
              delete"
      (is (= 404 (:status (PUT-json "/api/scopes/99999" {:title "Ghost"}))))
      (is (= 404 (:status (DELETE-json "/api/scopes/99999")))))))

(deftest deleting-a-scope-keeps-the-recipe-and-drops-the-badge
  (let [{bread :id} (scope! "Bread" "")
        {deploy :id} (scope! "Deployment" "")
        {:keys [id]} (recipe! "Sourdough" [bread deploy])]
    (is (= ["Bread" "Deployment"] (titles-on (:body (GET-json (str "/api/recipes/" id))))))
    (is (= 200 (:status (DELETE-json (str "/api/scopes/" bread)))))
    (testing "the Recipe is still there, still readable, and keeps its other Scope
              — a Recipe loses a badge and not a word of itself"
      (let [after (:body (GET-json (str "/api/recipes/" id "?detail=full")))]
        (is (= "Sourdough" (:title after)))
        (is (= "body v1" (:description after)))
        (is (= ["Deployment"] (titles-on after)))))
    (testing "and the Scope is gone from the listing"
      (is (= ["Deployment"] (mapv :title (:body (GET-json "/api/scopes"))))))))

;; ---------------------------------------------------------------------------
;; filing, from the Recipe's own write path

(deftest scope-ids-file-a-recipe-on-create-and-on-save
  (let [{bread :id} (scope! "Bread" "")
        {deploy :id} (scope! "Deployment" "")
        created (recipe! "Sourdough" [bread])]
    (is (= ["Bread"] (titles-on created)))
    (testing "a save that says nothing about them leaves the filing alone"
      (is (= ["Bread"] (titles-on (:body (PUT-json (str "/api/recipes/" (:id created))
                                                   {:description "body v2"}))))))
    (testing "a present array replaces the whole set"
      (is (= ["Bread" "Deployment"]
             (titles-on (:body (PUT-json (str "/api/recipes/" (:id created))
                                         {:scope_ids [deploy bread]}))))))
    (testing "and an empty array clears it"
      (is (= [] (titles-on (:body (PUT-json (str "/api/recipes/" (:id created))
                                            {:scope_ids []}))))))))

(deftest filing-a-recipe-makes-no-version
  (let [{bread :id} (scope! "Bread" "")
        {:keys [id]} (recipe! "Sourdough")
        filed (:body (PUT-json (str "/api/recipes/" id) {:scope_ids [bread]}))]
    (testing "the association is written and the version stays where it was — a
              Scope is a way back to a Recipe, not part of it"
      (is (= ["Bread"] (titles-on filed)))
      (is (= 1 (:version filed))))
    (testing "and the version list has nothing new in it"
      (is (= 1 (:total (:body (GET-json (str "/api/recipes/" id "/versions")))))))))

(deftest scope-ids-that-are-not-an-array-of-ids-are-refused
  (let [{:keys [id]} (recipe! "Sourdough")]
    (testing "400 rather than a guess: a bare number would have to be read as a
              one-element array and a string as either an id or a title"
      (doseq [bad [5 "1" {:id 1} ["1"] [1.5]]]
        (is (= 400 (:status (PUT-json (str "/api/recipes/" id) {:scope_ids bad})))
            (str "PUT with scope_ids " (pr-str bad)))
        (is (= 400 (:status (POST-json "/api/recipes" {:title "x" :scope_ids bad})))
            (str "POST with scope_ids " (pr-str bad)))))
    (testing "while an id that simply is not the caller's is well-formed and drops
              out, because answering 404 for it would say which ids exist"
      (is (= 200 (:status (PUT-json (str "/api/recipes/" id) {:scope_ids [99999]}))))
      (is (= [] (titles-on (:body (GET-json (str "/api/recipes/" id)))))))))

;; ---------------------------------------------------------------------------
;; who is told that Scopes exist

(deftest an-anonymous-caller-is-refused-and-told-nothing
  (let [{bread :id} (scope! "Bread" "Anything with flour in it")
        {:keys [id]} (recipe! "Sourdough" [bread])]
    (POST-json (str "/api/recipes/" id "/publish") {})
    (h/with-real-auth
      (testing "403 on every Scope route, and the message names no Scope"
        (doseq [[method path] [[:get "/api/scopes"] [:post "/api/scopes"]
                               [:put (str "/api/scopes/" bread)]
                               [:delete (str "/api/scopes/" bread)]]]
          (let [resp (h/API method path {:anonymous? true :body {:title "Theirs"}})]
            (is (= 403 (:status resp)) (str method " " path))
            (is (false? (str/includes? (str (:body resp)) "Bread")) (str method " " path)))))
      (testing "the published Recipe is fully readable to them and carries **no
                scopes key at all** — not an empty one, at any ?detail"
        (doseq [path [(str "/api/recipes/" id) (str "/api/recipes/" id "?detail=full")]]
          (let [body (:body (h/API :get path {:anonymous? true}))]
            (is (= "Sourdough" (:title body)) path)
            (is (false? (contains? body :scopes)) path))))
      (testing "and neither does their listing"
        (let [rows (:body (h/API :get "/api/recipes" {:anonymous? true}))]
          (is (= 1 (count rows)))
          (is (false? (contains? (first rows) :scopes))))))
    ;; Outside `with-real-auth`, because inside it the dev header no longer
    ;; identifies the owner and this read would be one more 403.
    (testing "and none of those refusals wrote anything on its way to saying no"
      (is (= ["Bread"] (mapv :title (:body (GET-json "/api/scopes")))))
      (is (= ["Bread"] (titles-on (:body (GET-json (str "/api/recipes/" id)))))))))

(deftest a-machine-token-is-on-the-owners-side-of-this
  (let [{bread :id} (scope! "Bread" "Anything with flour in it")
        token (machine-token!)
        as-machine (fn [method path body]
                     (h/API method path (cond-> {:token token} body (assoc :body body))))]
    (testing "it reads the list, which is what lets an agent file a Recipe under
              the right Scope at all"
      (let [resp (as-machine :get "/api/scopes" nil)]
        (is (= 200 (:status resp)))
        (is (= ["Bread"] (mapv :title (:body resp))))))
    (testing "it files a Recipe it writes, and the Recipe is the owner's"
      (let [created (:body (as-machine :post "/api/recipes"
                                       {:title "By the agent" :scope_ids [bread]}))]
        (is (= ["Bread"] (titles-on created)))
        (is (= ["Bread"] (titles-on (:body (GET-json (str "/api/recipes/" (:id created)))))))))
    (testing "and it may make and delete a Scope of its own: unsupervised writes
              are the reason this app exists, and the two rules that do refuse a
              machine are both about the publish latch, which this is not"
      (let [made (as-machine :post "/api/scopes" {:title "By the agent"})]
        (is (= 201 (:status made)))
        (is (= 200 (:status (as-machine :delete (str "/api/scopes/" (:id (:body made)))
                                        nil))))
        (is (= ["Bread"] (mapv :title (:body (GET-json "/api/scopes")))))))))

;; ---------------------------------------------------------------------------
;; the catalogue an agent reads before it calls anything

(deftest describe-lists-the-scopes-at-the-end-for-a-signed-in-caller
  (scope! "Bread" "Anything with flour in it")
  (scope! "Deployment" "Getting it onto the box")
  (let [body (:body (GET-json "/api/describe"))]
    (testing "a map with named sections, tracker's keys, and the Scopes in it"
      (is (= #{:endpoints :scopes} (set (keys body))))
      (is (seq (:endpoints body))))
    (testing "every Scope with both its title and its description, generated from
              the table rather than maintained by hand"
      (is (= [["Bread" "Anything with flour in it"] ["Deployment" "Getting it onto the box"]]
             (mapv (juxt :title :description) (:scopes body)))))
    (testing "and with its id, since an agent that cannot name a Scope cannot file
              anything under it"
      (is (every? #(int? (:id %)) (:scopes body))))))

(deftest an-anonymous-describe-has-no-scopes-key-and-still-every-route
  (scope! "Bread" "Anything with flour in it")
  (h/with-real-auth
    (let [resp (h/API :get "/api/describe" {:anonymous? true})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (testing "the section is absent rather than empty: an empty list is still an
                answer about how the owner files his shelf"
        (is (false? (contains? body :scopes)))
        (is (= [:endpoints] (keys body))))
      (testing "and the API surface is still public, in full — discovering what you
                can call was never the thing being protected"
        (let [paths (set (map (juxt :method :path) (:endpoints body)))]
          (is (= (set (map (juxt :method :path) (h/describe-endpoints))) paths))
          (is (contains? paths ["GET" "/api/scopes"]))))
      (testing "including the Scope routes' own documentation, which says they need
                a caller — an agent is told the door is there and locked"
        (let [doc (:doc (first (filter #(= ["GET" "/api/scopes"] ((juxt :method :path) %))
                                       (:endpoints body))))]
          (is (re-find #"(?i)403|authenticated" doc)))))))

(deftest the-scope-rules-are-in-describe
  (let [doc-for (fn [method path]
                  (:doc (first (filter #(and (= path (:path %)) (= method (:method %)))
                                       (h/describe-endpoints)))))]
    (testing "the four routes are documented where an agent will read them"
      (doseq [[method path] [["GET" "/api/scopes"] ["POST" "/api/scopes"]
                             ["PUT" "/api/scopes/:id"] ["DELETE" "/api/scopes/:id"]]]
        (is (some? (doc-for method path)) (str method " " path))))
    (testing "the writes say how `scope_ids` behaves, including that an empty array
              clears — the one reading a caller could not have guessed"
      (is (re-find #"scope_ids" (doc-for "POST" "/api/recipes")))
      (is (re-find #"scope_ids" (doc-for "PUT" "/api/recipes/:id")))
      (is (re-find #"(?i)empty array" (doc-for "PUT" "/api/recipes/:id"))))
    (testing "the reads say a visitor is sent no key"
      ;; \s+ rather than a space: these docstrings are wrapped, so a literal " "
      ;; would be asserting where the line breaks fall
      (is (re-find #"(?i)no\s+`?scopes`?\s+key" (doc-for "GET" "/api/recipes/:id"))))
    (testing "and the delete says what it does to the Recipes filed under it"
      (is (re-find #"(?i)badge|not touched|untouched" (doc-for "DELETE" "/api/scopes/:id"))))
    (testing "and the one destructive route says it takes the Scopes with it: this
              catalogue is what an agent reads before calling something, and this
              route has been under-described in it once already"
      (is (re-find #"(?i)scope" (doc-for "POST" "/api/test/reset"))))))
