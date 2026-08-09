(ns et.cb.caution-integration-test
  "The line-level provenance split over HTTP: which read carries it, who is served
  it, and that the numbers on it are about the text and not about the versions.

  The adapter's own unit test covers what the three statements are; what is only
  testable here is where the answer is attached — a `?detail=full` read of one
  Recipe, by the owner — and that is a boundary decision rather than an arithmetic
  one. The version *history* is the owner's (`/versions` 404s for a visitor), and
  these ranges are derived from it, so the same door has to be shut here.

  It is also where the legend is a testable claim rather than a def: the point of it
  is that it travels *with* every set of ranges, to a reader that may have fetched
  one Recipe and read no documentation at all."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.caution :as caution]
            [et.cb.integration-helpers :as h :refer [GET-json POST-json PUT-json]]))

(use-fixtures :each h/with-integration-db)

(defn- machine [method path & [body]]
  (h/API method path (cond-> {:token (h/machine-token-for h/*user-id*)}
                       body (assoc :body body))))

(defn- full [id] (:body (GET-json (str "/api/recipes/" id "?detail=full"))))

(defn- ranges
  "The ranges out of the response, which carries them under the legend that explains
  them. Every assertion about the numbers goes through here, so that the nesting is
  stated in one place rather than spelled out in each."
  [body]
  (:ranges (:caution body)))

;; ---------------------------------------------------------------------------
;; which read carries it

(deftest a-full-read-carries-the-split-and-a-lean-one-does-not
  (let [{:keys [id]} (:body (POST-json "/api/recipes"
                                       {:title "Sourdough" :description "one\ntwo"}))]
    (testing "?detail=full is the only read that hands back a description, so it
              is the only one with lines to say anything about"
      (is (= [{:from 1 :to 2 :caution 1.0}] (ranges (full id)))))
    (testing "and a lean read carries no body, so it carries no split either —
              absent, not empty"
      (is (not (contains? (:body (GET-json (str "/api/recipes/" id))) :caution))))))

(deftest the-listing-carries-no-split
  ;; The card already has `machine_versions`/`ui_versions`, which is a different
  ;; question and a cheap one. This is per-Recipe work over the whole history and
  ;; it has no business in a listing.
  (let [_ (POST-json "/api/recipes" {:title "Sourdough" :description "one\ntwo"})]
    (is (every? #(not (contains? % :caution))
                (:body (GET-json "/api/recipes?detail=full"))))))

;; ---------------------------------------------------------------------------
;; the legend that comes with them

(deftest every-full-read-carries-the-legend-beside-the-ranges
  ;; His reason for it: *it should, every time, give brief explanation in the return
  ;; body that the spectrum meaning*. `/api/describe` is not enough — the reader is an
  ;; agent that may have fetched exactly one Recipe, and a bare `0.0` beside a line
  ;; range is a number it has to already know how to read.
  (let [{:keys [id]} (:body (POST-json "/api/recipes"
                                       {:title "Sourdough" :description "one\ntwo"}))]
    (is (= caution/legend (:legend (:caution (full id))))
        "the one def, not a second wording of it")
    (testing "and on a Recipe with nothing of his in it, since the legend explains
              the scale and not this Recipe's answer"
      (let [{other :id} (:body (machine :post "/api/recipes"
                                        {:title "Theirs" :description "a\nb"}))]
        (is (= caution/legend (:legend (:caution (full other)))))))))

(deftest the-legend-and-the-ranges-are-one-key
  ;; Nested rather than a sibling `caution_legend`: neither half means anything
  ;; alone, and one key keeps the visitor rule a single omission rather than two
  ;; that could come apart.
  (let [{:keys [id]} (:body (POST-json "/api/recipes"
                                       {:title "Sourdough" :description "one\ntwo"}))
        body (full id)]
    (is (map? (:caution body)) "a map, not the bare vector this used to be")
    (is (= #{:legend :ranges} (set (keys (:caution body)))))
    (is (not-any? #(re-find #"caution" (name %)) (keys (dissoc body :caution)))
        "and nothing about it hangs off the response beside it")))

;; ---------------------------------------------------------------------------
;; what it says

(deftest the-lines-are-attributed-and-not-the-versions
  ;; The ladder that makes the point: an agent writes the Recipe, the owner adds a
  ;; line to it. Both versions exist, both labels are on the card — and the split
  ;; here says the first two lines are still the agent's while the third is his.
  (let [{:keys [id]} (:body (machine :post "/api/recipes"
                                     {:title "Written by an agent"
                                      :description "line one\nline two"}))]
    (PUT-json (str "/api/recipes/" id) {:description "line one\nline two\nline three"})
    (is (= [{:from 1 :to 2 :caution 0.0}
            {:from 3 :to 3 :caution 1.0}]
           (ranges (full id))))
    (testing "while the card's counts answer the other question — one version each"
      (let [listed (first (filter #(= id (:id %)) (:body (GET-json "/api/recipes"))))]
        (is (= {:version 2 :machine_versions 1 :ui_versions 1}
               (select-keys listed [:version :machine_versions :ui_versions])))))))

(deftest a-recipe-with-one-version-still-has-an-answer
  (let [{:keys [id]} (:body (machine :post "/api/recipes"
                                     {:title "Fresh" :description "a\nb\nc"}))]
    (is (= [{:from 1 :to 3 :caution 0.0}] (ranges (full id))))))

;; ---------------------------------------------------------------------------
;; who is served it

(deftest a-visitor-is-served-no-split
  ;; Derived from the history, so it goes where the history goes. Publishing puts
  ;; today's text in public; it does not say which parts of it he wrote.
  (let [{:keys [id]} (:body (POST-json "/api/recipes"
                                       {:title "Public" :description "one\ntwo"}))]
    (h/API :post (str "/api/recipes/" id "/publish") {})
    (h/with-real-auth
      (let [{:keys [status body]} (h/API :get (str "/api/recipes/" id "?detail=full")
                                         {:anonymous? true})]
        (is (= 200 status) "the Recipe itself is public")
        (is (= "one\ntwo" (:description body)) "and so is its text")
        (is (not (contains? body :caution))
            "the split is not — and the legend goes with it, being no use to
             somebody who is not being served the thing it explains")))))

(deftest a-machine-token-is-served-it
  ;; A machine reads in the owner's audience everywhere else in this app, and this
  ;; is the one number in it written *for* an agent: it is the thing that tells it
  ;; which lines to leave alone.
  (let [{:keys [id]} (:body (POST-json "/api/recipes"
                                       {:title "His" :description "one\ntwo"}))
        body (:body (machine :get (str "/api/recipes/" id "?detail=full")))]
    (is (= [{:from 1 :to 2 :caution 1.0}] (ranges body)))
    (testing "legend and all — it is the reader the legend was added for"
      (is (= caution/legend (:legend (:caution body)))))))
