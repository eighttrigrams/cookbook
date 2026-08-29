(ns et.cb.write-reason-integration-test
  "**Why a version exists**, and what its writer was doing: `reason` and `context`,
  required of a machine and of nobody else.

  *evry create or change needs a reason, that will be part of that verion and shown
  on the inbox when i look at the item, not in the inbox overview but on the item
  page for rreview, and then also on that versions page here … its ok when old
  entries doent have that yet, but new ones must have it.* And, one message later:
  *make it explicit. lets make two fields there, both mandatory. reason and
  context.*

  **Every body here is built by hand.** `integration-helpers/build-request` merges a
  default pair into a machine's write, because sixty tests about other things would
  otherwise be sixty 400s — but a test *about* the requirement must be able to send
  a body that lacks it, so nothing in this namespace goes through a helper that
  would fill it in. `raw` below is that discipline made structural rather than
  remembered.

  Four questions, and they fail independently:

  *Is it required* — a machine create or save without both is a 400 that writes
  nothing at all. *Is it stored* — the pair lands on the version the write makes and
  comes back on the version list. *Does it survive the detour through a proposal* —
  a machine write that has to be approved carries them on the proposal, and approval
  copies them onto the version, so the sentence read while deciding is the sentence
  the history keeps. *Is the owner exempt* — his own writes carry neither and are
  never asked for them."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [cheshire.core :as json]
            [ring.mock.request :as mock]
            [et.cb.integration-helpers :as h :refer [GET-json POST-json PUT-json]]))

(use-fixtures :each h/with-integration-db)

(defn- raw
  "A request whose body is **exactly** what is passed, machine token and all —
  `h/API`'s merge of the default explanation deliberately bypassed. Written here
  rather than exported from the helpers so that there is one place it can happen and
  it is the file that is about it."
  [method path body]
  (let [req (cond-> (mock/request method path)
              true (mock/header "Authorization"
                                (str "Bearer " (h/machine-token-for h/*user-id*)))
              body (-> (mock/header "Content-Type" "application/json")
                       (mock/body (json/generate-string body))))]
    (update (h/*app* req) :body #(when (seq %) (json/parse-string % true)))))

(def ^:private explained
  {:reason "the guard it names had no test"
   :context "reviewing the auth middleware in tracker"})

(defn- versions-of [id] (:versions (:body (GET-json (str "/api/recipes/" id "/versions")))))
(defn- newest [id] (first (versions-of id)))

;; ---------------------------------------------------------------------------
;; required of a machine, on both writes

(deftest a-machine-create-without-both-is-refused
  (doseq [[label body] [["neither" {:title "Nothing said"}]
                        ["only a reason" {:title "Half" :reason "because"}]
                        ["only a context" {:title "Half" :context "while working"}]
                        ["blank strings" {:title "Blank" :reason "" :context "   "}]]]
    (testing label
      (let [resp (raw :post "/api/recipes" body)]
        (is (= 400 (:status resp)))
        (testing "and it says which fields, so an agent can fix it without reading
                  anything else"
          (is (re-find #"(?i)reason|context" (:error (:body resp)))))
        (testing "**and nothing was written** — not the Recipe, and not the inbox
                  entry a machine create would otherwise append"
          (is (empty? (:body (GET-json "/api/recipes"))))
          (is (empty? (:body (GET-json "/api/inbox")))))))))

(deftest a-machine-save-without-both-is-refused-and-writes-nothing
  ;; The Recipe is the owner's, so a machine PUT on it would be a *proposal* — which
  ;; is the path that has the most to write before it could be refused: a filing, a
  ;; proposal row and an inbox entry.
  (let [{:keys [id]} (:body (POST-json "/api/recipes"
                                       {:title "His own" :description "body v1"}))
        path (str "/api/recipes/" id)]
    (let [resp (raw :put path {:description "body v2" :tags "smuggled"})]
      (is (= 400 (:status resp)))
      (testing "the content is untouched and no version was made"
        (is (= 1 (:version (:body (GET-json path)))))
        (is (= "body v1" (:description (:body (GET-json (str path "?detail=full")))))))
      (testing "**and the filing in the same body was not applied either** — a
                half-applied request answered with an error is worse than a refused
                one, which is this route's own rule about a pending proposal"
        (is (= "" (:tags (:body (GET-json path))))))
      (testing "and no proposal is waiting — `pending` on a Recipe read is the 0/1
                flag and not a proposal, so 0 is the shape of 'none'"
        (is (= 0 (:pending (:body (GET-json path)))))
        (is (empty? (:body (GET-json "/api/inbox"))))))))

(deftest the-requirement-holds-even-when-the-write-would-change-nothing
  ;; Whether a PUT is a no-op is only knowable after the comparison the answer would
  ;; depend on, so the rule is 'every machine write says why' rather than one that is
  ;; true except when it happens not to be.
  (let [{:keys [id]} (:body (raw :post "/api/recipes"
                                 (merge explained {:title "By the agent"
                                                   :description "body v1"})))]
    (is (= 400 (:status (raw :put (str "/api/recipes/" id) {:description "body v1"}))))
    (testing "while the same no-op with the pair goes through and is still a no-op"
      (let [resp (raw :put (str "/api/recipes/" id)
                      (merge explained {:description "body v1"}))]
        (is (= 200 (:status resp)))
        (is (= 1 (:version (:body resp))))))))

(deftest the-owners-own-writes-are-never-asked
  (let [{:keys [id]} (:body (POST-json "/api/recipes" {:title "His own"}))]
    (is (some? id) "a create with no reason at all")
    (is (= 200 (:status (PUT-json (str "/api/recipes/" id) {:description "his edit"}))))
    (testing "and the version carries neither — absent, so the page shows no line
              rather than an empty one"
      (let [v (newest id)]
        (is (nil? (:reason v)))
        (is (nil? (:context v)))))))

;; ---------------------------------------------------------------------------
;; stored on the version, and on the version list

(deftest a-machine-create-stores-both-on-version-1
  (let [{:keys [id]} (:body (raw :post "/api/recipes"
                                 (merge explained {:title "By the agent"})))
        v1 (newest id)]
    (is (= 1 (:version v1)))
    (is (= "the guard it names had no test" (:reason v1)))
    (is (= "reviewing the auth middleware in tracker" (:context v1)))
    (testing "beside the label saying who wrote it, which is the other half of the
              same question"
      (is (= "machine" (:source v1))))))

(deftest each-version-keeps-its-own-pair
  ;; The `archive!` property one field along: a version's explanation goes into
  ;; history with the version it is about, never with the save displacing it.
  (let [{:keys [id]} (:body (raw :post "/api/recipes"
                                 (merge explained {:title "By the agent"
                                                   :description "body v1"})))]
    (raw :put (str "/api/recipes/" id)
         {:description "body v2"
          :reason "the example was wrong"
          :context "answering a question about the deploy script"})
    (let [[v2 v1] (versions-of id)]
      (is (= 2 (:version v2)))
      (is (= "the example was wrong" (:reason v2)))
      (is (= "answering a question about the deploy script" (:context v2)))
      (testing "and version 1 still says what it always said, rather than being
                relabelled by the write that displaced it"
        (is (= 1 (:version v1)))
        (is (= "the guard it names had no test" (:reason v1)))
        (is (= "reviewing the auth middleware in tracker" (:context v1)))))))

(deftest a-later-write-does-not-inherit-an-earlier-explanation
  ;; The one place absent-keeps must *not* apply: an omitted title keeps the old
  ;; title, and an omitted reason must not keep the old reason — it would read as
  ;; this version's own account of itself while describing the one before it. The
  ;; owner is the caller here because he is the only one who may omit them.
  (let [{:keys [id]} (:body (raw :post "/api/recipes"
                                 (merge explained {:title "By the agent"
                                                   :description "body v1"})))]
    (PUT-json (str "/api/recipes/" id) {:description "his own edit"})
    (let [[v2 v1] (versions-of id)]
      (is (= "his own edit" (:description v2)))
      (is (nil? (:reason v2)) "his version explains nothing, and says so by absence")
      (is (nil? (:context v2)))
      (is (= "the guard it names had no test" (:reason v1))))))

;; ---------------------------------------------------------------------------
;; through a proposal, which is where they were asked for

(deftest a-proposal-carries-the-pair-to-the-page-that-decides-on-it
  (let [{:keys [id]} (:body (POST-json "/api/recipes"
                                       {:title "His own" :description "body v1"}))
        resp (raw :put (str "/api/recipes/" id)
                  {:description "the agent's rewrite"
                   :reason "the body contradicted the title"
                   :context "sweeping the recipes an agent wrote last week"})]
    (is (= 202 (:status resp)) "a Recipe he has written proposes rather than saves")
    (testing "the answer to the write carries them"
      (is (= "the body contradicted the title" (:reason (:pending (:body resp)))))
      (is (= "sweeping the recipes an agent wrote last week"
             (:context (:pending (:body resp))))))
    (testing "**and so does the `proposal` on the queue's own entry**, which is what
              the item page draws — it looks the entry up in the queue rather than
              fetching the proposal again, so this read *is* the review surface"
      (let [entry (first (:body (GET-json "/api/inbox")))
            proposal (:proposal entry)]
        (is (= "proposed" (:kind entry)))
        (is (= "the body contradicted the title" (:reason proposal)))
        (is (= "sweeping the recipes an agent wrote last week" (:context proposal)))))))

(deftest a-revision-replaces-the-explanation-with-the-text-it-explains
  (let [{:keys [id]} (:body (POST-json "/api/recipes" {:title "His own"}))]
    (raw :put (str "/api/recipes/" id)
         {:description "first attempt" :reason "first reason" :context "first context"})
    (let [resp (raw :put (str "/api/recipes/" id "?overwrite=true")
                    {:description "second attempt"
                     :reason "second reason" :context "second context"})]
      (testing "the pending proposal is whatever was last proposed, explanation and
                all — keeping the first attempt's sentences beside the third
                attempt's text is the mismatch this avoids"
        (is (= "second reason" (:reason (:pending (:body resp)))))
        (is (= "second context" (:context (:pending (:body resp)))))))))

(deftest approving-copies-the-pair-onto-the-version
  ;; **The point of the whole feature**: what he read while deciding is what the
  ;; version page says afterwards.
  (let [{:keys [id]} (:body (POST-json "/api/recipes"
                                       {:title "His own" :description "body v1"}))]
    (raw :put (str "/api/recipes/" id)
         {:description "the agent's rewrite"
          :reason "the body contradicted the title"
          :context "sweeping last week's Recipes"})
    (let [entry (first (:body (GET-json "/api/inbox")))]
      (is (= 200 (:status (POST-json (str "/api/inbox/" (:id entry) "/approve") {}))))
      (let [v2 (newest id)]
        (is (= 2 (:version v2)))
        (is (= "the agent's rewrite" (:description v2)))
        (is (= "machine" (:source v2)) "approving is letting it in, not writing it")
        (is (= "the body contradicted the title" (:reason v2)))
        (is (= "sweeping last week's Recipes" (:context v2)))))))

(deftest a-dismissed-proposal-leaves-no-explanation-behind
  (let [{:keys [id]} (:body (POST-json "/api/recipes"
                                       {:title "His own" :description "body v1"}))]
    (raw :put (str "/api/recipes/" id)
         {:description "the agent's rewrite" :reason "why" :context "where"})
    (let [entry (first (:body (GET-json "/api/inbox")))]
      (POST-json (str "/api/inbox/" (:id entry) "/dismiss") {})
      (testing "no version was made, so there is nothing for a reason to be about"
        (let [v1 (newest id)]
          (is (= 1 (:version v1)))
          (is (nil? (:reason v1))))))))

;; ---------------------------------------------------------------------------
;; what an agent is told about all this

(deftest the-two-fields-are-described-where-an-agent-reads
  (let [doc-for (fn [method path]
                  (:doc (first (filter #(and (= path (:path %)) (= method (:method %)))
                                       (h/describe-endpoints)))))]
    (doseq [[method path] [["POST" "/api/recipes"] ["PUT" "/api/recipes/:id"]]]
      (let [doc (doc-for method path)]
        (testing (str method " " path " names both fields")
          (is (re-find #"reason" doc))
          (is (re-find #"context" doc)))
        (testing "and says they are required of a machine"
          (is (re-find #"(?i)required|must" doc)))
        ;; Anchored on what `context` is *for*, because that is the half an agent
        ;; gets wrong by answering the first question twice — and a looser pattern
        ;; would have matched the docstring before this feature existed, which is
        ;; the trap `scopes-integration-test` records having fallen into once.
        (testing "**and says what `context` is**: what the agent was working on"
          (is (re-find #"(?i)what you were working on" doc)))))))
