(ns et.cb.read-attribution-integration-test
  "Who the API takes a reader to be, over HTTP.

  > and break the reads down by human/machine as well

  `view-count-integration-test` holds *which request* counts — full read yes, lean
  read no, listing no — and none of that changes here. What is here is the question
  013 added: given a request that counts, which bucket does it land in, and who is
  told the answer.

  **Three kinds of caller and two buckets**, which is the whole of it: the owner
  through the UI, an agent with a machine token, and an anonymous stranger reading a
  published Recipe. The machine token is the machine bucket; the other two are the
  human one. That third case is the one worth having an HTTP test for at all — the db
  layer takes a boolean and cannot tell an owner from a visitor, so *only* a request
  can show that a caller with no token at all is counted as a person.

  It is also the case a plausible refactor breaks silently: the write paths read the
  same absence as 'a machine' (`source-of`), so a later unification would file every
  stranger's read under the agents, with nothing failing anywhere except the number
  on a badge."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.db.user :as db.user]
            [et.cb.integration-helpers :as h :refer [GET-json POST-json]]))

(use-fixtures :each h/with-integration-db)

(defn- create! [title]
  (:body (POST-json "/api/recipes"
                    {:title title :useful_when "when testing" :description "body v1"})))

(defn- counts
  "Off the listing, which is where the card reads them too."
  [id]
  (-> (first (filter #(= id (:id %)) (:body (GET-json "/api/recipes"))))
      (select-keys [:view_count :human_reads :machine_reads])))

(defn- machine-token! []
  (db.user/set-machine-user-password! h/*ds* h/*user-id* "machine-secret")
  (:token (:body (POST-json "/api/auth/login" {:username "machine-user"
                                               :password "machine-secret"}))))

(deftest the-owners-read-is-a-humans-and-an-agents-is-a-machines
  (let [id (:id (create! "Sourdough"))
        path (str "/api/recipes/" id "?detail=full")]
    (is (= {:view_count 0 :human_reads 0 :machine_reads 0} (counts id)))
    (testing "the fixture's own requests carry no machine token, so they are his"
      (GET-json path)
      (is (= {:view_count 1 :human_reads 1 :machine_reads 0} (counts id))))
    (testing "and a read carrying one is the agents'"
      (h/API :get path {:token (machine-token!)})
      (is (= {:view_count 2 :human_reads 1 :machine_reads 1} (counts id))))))

(deftest an-anonymous-read-of-a-published-recipe-counts-as-a-humans
  ;; **The test this file exists for.** 008 counts a stranger's read on purpose —
  ;; *a published Recipe read by a stranger was used* — and 013 has two buckets to
  ;; put three kinds of reader in. A person read it, so it is the human one.
  ;;
  ;; Note what would happen under the write paths' rule, which reads the same
  ;; silence as 'a machine': this assertion would come back
  ;; `{:human_reads 0 :machine_reads 1}` and every visitor on his published shelf
  ;; would be counted as an agent.
  (let [id (:id (create! "Sourdough"))]
    (POST-json (str "/api/recipes/" id "/publish") {})
    (h/with-real-auth
      (h/API :get (str "/api/recipes/" id "?detail=full") {:anonymous? true}))
    (is (= {:view_count 1 :human_reads 1 :machine_reads 0} (counts id))
        "no token at all is a person, not an agent")))

(deftest the-two-buckets-and-the-total-agree-over-a-mixed-run
  (let [id (:id (create! "Sourdough"))
        path (str "/api/recipes/" id "?detail=full")
        token (machine-token!)]
    (dotimes [_ 3] (GET-json path))
    (dotimes [_ 2] (h/API :get path {:token token}))
    ;; Published **outside** `with-real-auth`, and the first version of this test had
    ;; it inside: with skip-logins off an unauthenticated POST is a 401, so the latch
    ;; never flipped and the anonymous read below took a 404 — which does not count,
    ;; so the total came back one short with nothing saying why. The macro is for the
    ;; requests whose *anonymity* is the point, and a write is never one of them.
    (POST-json (str "/api/recipes/" id "/publish") {})
    (h/with-real-auth
      (h/API :get path {:anonymous? true}))
    (let [{:keys [view_count human_reads machine_reads]} (counts id)]
      (is (= 6 view_count))
      (is (= 4 human_reads) "three of his and one stranger's")
      (is (= 2 machine_reads))
      (is (= view_count (+ human_reads machine_reads))
          "nothing here predates the split, so the buckets account for the total"))))

(deftest the-reads-that-do-not-count-do-not-attribute-either
  ;; The sibling's triple, asked of the buckets: a lean read, a listing and a 404
  ;; leave the total alone, and they must leave the split alone too. A bucket that
  ;; moved on a request the total ignored would be a second definition of what a
  ;; read is.
  (let [id (:id (create! "Sourdough"))
        token (machine-token!)]
    (GET-json (str "/api/recipes/" id "?detail=full"))
    (let [before (counts id)]
      (GET-json (str "/api/recipes/" id))
      (GET-json "/api/recipes?detail=full")
      (h/API :get (str "/api/recipes/" id) {:token token})
      (GET-json "/api/recipes/999999?detail=full")
      (h/API :get "/api/recipes/999999?detail=full" {:token token})
      (is (= before (counts id))
          "a lean read, a listing and two 404s moved neither the total nor a bucket"))))

(deftest a-visitor-is-sent-the-total-and-not-the-split
  ;; **The audience decision.** `view_count` is in a visitor's projection
  ;; deliberately — it explains the order of the shelf they are looking at. The
  ;; split explains nothing about that order and would say instead how much of the
  ;; owner's traffic is his own agents, so the columns are not named for them: the
  ;; keys are **absent** rather than 0, which is the shape `tags` and `pending`
  ;; already take. Absent and 0 are different answers.
  (let [id (:id (create! "Sourdough"))
        token (machine-token!)]
    (POST-json (str "/api/recipes/" id "/publish") {})
    (GET-json (str "/api/recipes/" id "?detail=full"))
    (h/API :get (str "/api/recipes/" id "?detail=full") {:token token})
    (h/with-real-auth
      (let [listed (first (:body (h/API :get "/api/recipes" {:anonymous? true})))
            full (:body (h/API :get (str "/api/recipes/" id "?detail=full")
                               {:anonymous? true}))]
        (testing "the listing: the total, and neither bucket"
          (is (= 2 (:view_count listed)))
          (is (false? (contains? listed :human_reads)))
          (is (false? (contains? listed :machine_reads))))
        (testing "and the single read at ?detail=full, since verbosity and audience
                  are different axes — that read is the third one, so the total it
                  reports is the two before it"
          (is (= 2 (:view_count full)))
          (is (false? (contains? full :human_reads)))
          (is (false? (contains? full :machine_reads))))))
    (testing "while the owner is sent both, so the absences above are the audience
              and not a projection that lost them for everybody"
      (let [{:keys [human_reads machine_reads]} (counts id)]
        (is (= 2 human_reads) "his own read, and the visitor's from this test")
        (is (= 1 machine_reads))))
    (testing "and a machine token reads in the owner's audience, like every other
              read in this API"
      (let [row (first (filter #(= id (:id %))
                               (:body (h/API :get "/api/recipes" {:token token}))))]
        (is (number? (:human_reads row)))
        (is (number? (:machine_reads row)))))))

(deftest the-catalogue-an-agent-reads-says-all-of-it
  ;; An agent reads its own reads back out of /api/describe, so the three things it
  ;; cannot guess have to be in there: which bucket a token lands in, that a
  ;; stranger is a person, and that the two need not sum to the total.
  (let [doc (:doc (first (filter #(= ["GET" "/api/recipes/:id"] ((juxt :method :path) %))
                                 (h/describe-endpoints))))]
    (is (re-find #"human_reads" doc))
    (is (re-find #"machine_reads" doc))
    (testing "that a machine token is what makes a read the machine's"
      (is (re-find #"(?i)machine\s+token" doc)))
    (testing "that an anonymous reader is counted as a person — the one a caller
              would otherwise guess the other way"
      (is (re-find #"(?i)anonymous\s+reader" doc)))
    (testing "and that the buckets need not add up to the total"
      (is (re-find #"(?i)do\s+not\s+necessarily\s+sum" doc)))))
