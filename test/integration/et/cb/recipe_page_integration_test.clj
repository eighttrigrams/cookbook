(ns et.cb.recipe-page-integration-test
  "`/recipe/<id>` is an address a Recipe can be linked to, bookmarked and reloaded
  at, and this namespace is about the half of that the server owns: the wildcard
  route which answers every one of them with the index.

  **The client is what decides whether a Recipe exists.** The route matches any
  `/recipe/…` on purpose — the id is not looked at here, nothing touches the
  database, and a request for a Recipe that was never written still gets the app,
  which then asks the API and renders its not-found sentence. A router that
  adjudicated ids would have to load a Recipe in order to render a page it does not
  render, and it would answer 404 with a JSON body to a browser that asked for a
  page.

  So what is asserted here is only ever *what the router claims*: that
  `/recipe/anything` is the app, that the wildcard did not swallow the routes and
  the static files declared before it, and that it does not reach one path further
  than it was given."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.integration-helpers :as h :refer [POST-json]]))

(use-fixtures :each h/with-integration-db)

(defn- body-text
  "A response body as text, whichever of the two shapes it is. `route/resources`
  answers a File or an InputStream where every other route here answers a string,
  and a `slurp` written for the file case reads a *string* body as a filename — so
  a route ordering that handed the index to a static path would fail with a
  FileNotFoundException naming `<!DOCTYPE html>` rather than saying that the index
  came back."
  [resp]
  (let [b (:body resp)]
    (if (string? b) b (slurp b))))

(defn- index? [resp]
  (and (= 200 (:status resp))
       (boolean (re-find #"(?i)<title>Cookbook</title>" (body-text resp)))))

(deftest a-recipe-has-an-address
  (testing "a Recipe's own path serves the app, like / does"
    (let [{:keys [id]} (:body (POST-json "/api/recipes" {:title "Addressable"}))]
      (is (index? (h/API-raw :get (str "/recipe/" id) {})))))
  (testing "and so does an id nothing was ever written under — which Recipes exist
            is a question for the API, not for the router"
    (is (index? (h/API-raw :get "/recipe/999999" {}))))
  (testing "an id that is not a number is the app as well: the router does not
            adjudicate ids, and a browser that asked for a page must not be handed
            a JSON error"
    (is (index? (h/API-raw :get "/recipe/abc" {})))))

(deftest the-wildcard-does-not-eat-what-came-before-it
  ;; Raw here as well, and for the same reason the neighbours test is: a route
  ;; ordering that hands the index to `/api/recipes` would otherwise throw out of
  ;; jackson before any assertion ran, and an uncaught exception is a worse
  ;; description of the bug than `the index came back where JSON was asked for`.
  (testing "the API still answers JSON"
    (h/API-raw :post "/api/recipes" {:body {:title "Still listed"}})
    (let [resp (h/API-raw :get "/api/recipes" {})]
      (is (= 200 (:status resp)))
      (is (not (index? resp)))
      (is (re-find #"Still listed" (body-text resp)))))
  (testing "and a static file under route/resources still serves itself"
    (let [resp (h/API-raw :get "/favicon.svg" {})]
      (is (= 200 (:status resp)))
      (is (re-find #"(?i)<svg" (body-text resp))))))

(deftest it-claims-one-shape-and-not-its-neighbours
  ;; Read raw rather than through `GET-json`, so that a route which *has* claimed
  ;; one of these fails as a 200 carrying the index instead of blowing up in
  ;; jackson on the `<` of `<!DOCTYPE`. A stack trace says something went wrong; a
  ;; red assertion says what.
  (testing "the plural is not this route"
    (let [resp (h/API-raw :get "/recipes/1" {})]
      (is (= 404 (:status resp)))
      (is (not (index? resp)))))
  (testing "and neither is the bare word, with no Recipe named"
    (let [resp (h/API-raw :get "/recipe" {})]
      (is (= 404 (:status resp)))
      (is (not (index? resp))))))
