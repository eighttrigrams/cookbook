(ns et.cb.scope-tags-migration-test
  "Migration 014: the `tags` column on `scopes`, where it went and where it
  deliberately did not.

  Three decisions are pinned here rather than left to the DDL. It lands on
  `scopes` and **not** on `recipe_scopes`, because the tags describe the Scope and
  not one Recipe's membership of it — so tagging `utwig` with `backend` is one
  write that relabels everything filed there, and 'the Scope's tags' stays a
  question with one answer. Every existing Scope comes out `''`, which like 006's
  default is not a fact being withheld: the column records what the owner curated,
  and 'nothing yet' is simply true of a Scope nobody has tagged. And a Scope that
  predates the column is still searched by its **title**, so migrating forward can
  only ever add search hits and never take one away.

  The 006 twin of this file is `et.cb.tags-migration-test`, and the pair reads the
  same way on purpose: same column shape, same default, same argument about it."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.cb.db :as db]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.db.scope :as db.scope]
            [et.cb.migrations :as migrations]))

;; A file database of its own, like the 003, 004, 005 and 006 rollback tests:
;; rolling back the suite's shared in-memory schema would take every other test
;; with it.

(defn- temp-file-db [label]
  (let [dir (java.nio.file.Files/createTempDirectory
              label (into-array java.nio.file.attribute.FileAttribute []))
        ds (db/init-conn {:type :sqlite-file :path (str dir "/" label ".db")})]
    [ds (fn []
          (when-let [pc (:persistent-conn ds)] (.close pc))
          (doseq [f (reverse (file-seq (io/file (str dir))))] (.delete f)))]))

(defn- columns [ds table]
  (set (map :name (jdbc/execute! (db/get-conn ds) [(str "PRAGMA table_info(" table ")")]
                                 db/jdbc-opts))))

(defn- insert-scope!
  "Straight at the table, in whatever schema is current — so this still works with
  014 rolled back, which is the point. `db.scope/create-scope` names the `tags`
  column and would fail there."
  [ds title]
  (:id (jdbc/execute-one! (db/get-conn ds)
         (sql/format {:insert-into :scopes
                      :values [{:title title :description ""}]
                      :returning [:id]})
         db/jdbc-opts)))

(defn- insert-recipe!
  "A Recipe filed under `scope-id`, both written straight at the tables for the
  reason `insert-scope!` is. `source` is `'ui'` because the column is `NOT NULL`
  from migration 010; the value is arbitrary here."
  [ds title scope-id]
  (let [id (:id (jdbc/execute-one! (db/get-conn ds)
                  (sql/format {:insert-into :recipes
                               :values [{:title title :useful_when "" :description ""
                                         :version 1 :source "ui"}]
                               :returning [:id]})
                  db/jdbc-opts))]
    (jdbc/execute-one! (db/get-conn ds)
      (sql/format {:insert-into :recipe_scopes
                   :values [{:recipe_id id :scope_id scope-id}]}))
    id))

(defn- tags-of [ds id]
  (:tags (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [:tags] :from [:scopes] :where [:= :id id]})
           db/jdbc-opts)))

(defn- hits [ds term]
  (set (map :title (db.recipe/list-recipes ds nil {:search-term term}))))

(deftest migration-014-adds-tags-to-the-scope-and-not-to-the-association
  (let [[ds clean!] (temp-file-db "cb-scope-tags-up")]
    (try
      (is (contains? (columns ds "scopes") "tags"))
      (testing "and not to `recipe_scopes`, which carries nothing of its own — the
                tags are the Scope's, so a copy per membership could only ever be
                the one that goes stale"
        (is (not (contains? (columns ds "recipe_scopes") "tags"))))
      (testing "the columns it sits beside are untouched"
        (is (= #{"id" "title" "description" "user_id" "tags"} (columns ds "scopes"))))
      (testing "and the Recipe's own tags column is still the one 006 made"
        (is (contains? (columns ds "recipes") "tags")))
      (finally (clean!)))))

(deftest scopes-that-predate-the-column-read-as-untagged
  ;; The shape of the owner's dev database: Scopes made before they had tags.
  ;; Made the way his database will actually run it — roll 014 back, write into
  ;; the old schema, migrate forward.
  (let [[ds clean!] (temp-file-db "cb-scope-tags-existing")]
    (try
      (migrations/rollback! (:conn ds) "013-read-attribution")
      (is (not (contains? (columns ds "scopes") "tags")) "014 is rolled back")

      (let [older (insert-scope! ds "utwig")
            _recipe (insert-recipe! ds "abc def" older)]
        (migrations/migrate! (:conn ds))
        (testing "the column is back"
          (is (contains? (columns ds "scopes") "tags")))
        (testing "and the Scope that predates it reads the empty string — untagged,
                  which is the true state of a Scope nobody has tagged rather than
                  a fact being withheld"
          (is (= "" (tags-of ds older)))
          (is (= "" (:tags (db.scope/get-scope ds nil older)))))
        (testing "so migrating forward can only add search hits: the Recipe filed
                  under it is still found by the Scope's title and by its own, and
                  the empty column matches nothing"
          (is (= #{"abc def"} (hits ds "utwig")))
          (is (= #{"abc def"} (hits ds "abc utw")))
          (is (empty? (hits ds "backend")))))
      (finally (clean!)))))

(deftest migration-014-down-really-reverses
  (let [[ds clean!] (temp-file-db "cb-scope-tags-down")]
    (try
      (let [utwig (:id (db.scope/create-scope ds nil {:title "utwig"
                                                      :tags "backend tag2 tag3"}))
            _recipe (insert-recipe! ds "abc def" utwig)]
        (is (= #{"abc def"} (hits ds "backend")) "the words are searched before the
                                                  rollback, so what follows is a
                                                  change and not a constant")

        (migrations/rollback! (:conn ds) "013-read-attribution")

        (testing "the column is gone and it took nothing else with it"
          (is (not (contains? (columns ds "scopes") "tags")))
          (is (= #{"id" "title" "description" "user_id"} (columns ds "scopes")))
          (is (= "utwig" (:title (jdbc/execute-one! (db/get-conn ds)
                                   (sql/format {:select [:title] :from [:scopes]
                                                :where [:= :id utwig]})
                                   db/jdbc-opts))))
          (testing "and the associations survive it — a rollback of the tags is not
                    an unfiling"
            (is (= 1 (:n (jdbc/execute-one! (db/get-conn ds)
                           (sql/format {:select [[[:count :*] :n]]
                                        :from [:recipe_scopes]})
                           db/jdbc-opts))))))

        (testing "and 014 re-applies with the tags back at empty: a rollback drops
                  them, so re-migrating cannot restore words nobody stored"
          (migrations/migrate! (:conn ds))
          (is (contains? (columns ds "scopes") "tags"))
          (is (= "" (tags-of ds utwig)))
          (is (empty? (hits ds "backend")))
          (testing "while the title the Scope kept is a search term again"
            (is (= #{"abc def"} (hits ds "utwig"))))))
      (finally (clean!)))))
