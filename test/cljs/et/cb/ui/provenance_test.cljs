(ns et.cb.ui.provenance-test
  "The browser's half of `caution`, which since the port is a half that exists.

  Two claims are pinned here and they are different claims. The first is that
  `et.cb.caution` — the adapter, `.cljc` since the port — **works on this host at
  all**: it is reached over `shadow-cljs.edn`'s `../us-vs-them/src`, a sibling
  checkout, and if that path ever stops resolving this file is what says so in
  cookbook's own voice rather than leaving it to the library's suite riding in
  over the same road. The second is `local-split`'s one decision: when to answer
  and when to refuse.

  What is *not* here is the arithmetic. `et.uvt.caution-test` is the
  specification of what the numbers mean and it runs in this same
  `make test-cljs`; repeating any of it here would be a second opinion about
  somebody else's function."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [et.cb.caution :as caution]
            [et.cb.ui.provenance :as provenance]))

(defn- version
  "A version as `GET /api/recipes/:id/versions` hands it over — newest first, so
  these are written newest first too. The same helper `et.cb.caution-test`
  defines on the JVM, deliberately spelled the same way: the two suites are
  asking the same adapter the same questions on two hosts."
  [n source description]
  {:version n :source source :description description
   :title "Sourdough" :useful_when "when baking"})

(deftest the-adapter-answers-on-this-host
  ;; The port's whole claim, in one assertion: cookbook's server has always been
  ;; able to ask this question, and now the browser can, from the same source
  ;; file. A red here means `../us-vs-them/src` is not on the ClojureScript
  ;; source paths, or `et.cb.caution` is back in `src/clj`.
  (testing "a lone version saved by hand is his, line for line"
    (is (= [{:from 1 :to 2 :caution 1.0}]
           (caution/ranges [(version 1 "ui" "his first line\nhis second line")]))))
  (testing "and the history is replayed oldest first, here as there"
    ;; The one mistake the adapter can make that still returns a well-formed
    ;; answer, asked on this host too because this host now has its own caller.
    (is (= [{:from 1 :to 1 :caution 1.0}
            {:from 2 :to 2 :caution 0.0}]
           (caution/ranges [(version 2 "machine" "his line\nthe agent's line")
                            (version 1 "ui" "his line")]))))
  (testing "and `ui` is us on this host too"
    (is (= #{"ui"} caution/ours))))

(deftest local-split-is-the-shape-the-api-sends
  ;; It has to be, because it is installed into `[:details id :caution]` beside
  ;; splits that came off the wire and is read by the same two render sites.
  (let [split (provenance/local-split [(version 1 "ui" "one\ntwo")])]
    (is (= #{:legend :ranges} (set (keys split))))
    (is (= caution/legend (:legend split))
        "the legend is the adapter's own, not a second wording of it")
    (is (= [{:from 1 :to 2 :caution 1.0}] (:ranges split)))))

(deftest local-split-refuses-a-ladder-it-cannot-read
  (let [sealed "enc:v1:AAAAAAAAAAAAAAAAAAAAAAAA"]
    (testing "nil, and not an empty vector"
      ;; `views.recipe` keys the *Show provenance* button off `(seq ranges)`. An
      ;; empty vector would be a claim that the body has no lines; nil is the
      ;; absence of an answer, which is what this is.
      (is (nil? (provenance/local-split
                 [(version 2 "ui" "plain") (version 1 "machine" sealed)])))
      (is (nil? (provenance/local-split [(version 1 "ui" sealed)]))))
    (testing "which is the same thing as having no key, since unseal hands back what it got"
      (is (nil? (provenance/local-split [(version 3 "ui" sealed)
                                         (version 2 "machine" sealed)
                                         (version 1 "ui" sealed)]))))
    (testing "an empty ladder, which is not the same refusal and needs its own"
      ;; `caution/ranges` answers `[]` for it — no versions, no lines, no ranges —
      ;; and `local-split` will not dress that as a split, because a
      ;; `{:legend … :ranges []}` is this client offering to draw one. The reason
      ;; both guards exist rather than one is
      ;; `et.uvt.hosts-test/a-history-with-no-text-in-it-parts-the-hosts`: the
      ;; library throws over an empty history on the JVM and invents a `0.00`
      ;; range in this host, and `0.00` means *written by an agent*.
      (is (nil? (provenance/local-split [])))
      (is (nil? (provenance/local-split nil)))
      (is (= [] (caution/ranges []))
          "the adapter's own answer, which is the honest one and still not a split"))
    (testing "and a version with no text at all, which the library would fabricate over"
      ;; `(str/split nil …)` throws on the JVM and yields `[\"\"]` here. The
      ;; adapter coalesces to `\"\"`, which both hosts agree is one empty line.
      (is (= [{:from 1 :to 1 :caution 1.0}]
             (caution/ranges [{:version 1 :source "ui" :description nil}]))))
    (testing "and one unreadable version is enough to refuse the whole ladder"
      ;; Not a partial answer over the versions that did open: a fold that skips
      ;; a version attributes its lines to whoever wrote the next one, which is
      ;; the confident inversion `et.cb.caution` warns about in the other
      ;; direction.
      (is (nil? (provenance/local-split
                 [(version 3 "ui" "plain") (version 2 "machine" sealed)
                  (version 1 "ui" "plain")]))))))

(deftest local-split-and-the-server-agree-on-a-plaintext-ladder
  ;; The two hosts are one function over one list, so this is not really a test
  ;; of agreement — it is a test that nothing in the client's own path (the
  ;; reversal, the `:ours` set, the choice of `description`) has been respelled
  ;; on the way in. It is the ladder
  ;; `caution-integration-test/the-lines-are-attributed-and-not-the-versions`
  ;; drives through the API on the JVM — an agent writes two lines, the owner adds
  ;; a third — asserted here against the same two ranges, computed in this host
  ;; from the list rather than fetched from a server.
  (is (= [{:from 1 :to 2 :caution 0.0}
          {:from 3 :to 3 :caution 1.0}]
         (:ranges (provenance/local-split
                   [(version 2 "ui" "a line\nanother line\nhis line")
                    (version 1 "machine" "a line\nanother line")])))))
