(ns et.cb.search-test
  "What `?search=` means: the title only, by word-prefix, AND across terms.

  Written at the db layer on purpose. The whole thing is a `:where` clause, so
  SQLite is what evaluates it — a test of the clause *shape* would pass while
  the query returned the wrong rows. The HTTP end of it, query-string encoding
  included, is in `et.cb.recipes-integration-test`."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.test-helpers :as h]))

(use-fixtures :each h/with-in-memory-db)

(defn- create!
  "A recipe whose useful-when and description hold words the title does not, so
  every assertion below also says something about *where* search looks."
  [title]
  (db.recipe/create-recipe h/*ds* h/*user-id*
                           {:title title
                            :useful_when "when testing zzz"
                            :description "body mentioning qqq"}))

(defn- titles-for [search-term]
  (set (map :title (db.recipe/list-recipes h/*ds* h/*user-id*
                                           {:search-term search-term}))))

(deftest the-owners-example
  (create! "abc cde")
  (create! "ad cd")
  (testing "\"ab cd\" matches the title \"abc cde\" — ab prefixes abc, cd prefixes cde"
    (is (contains? (titles-for "ab cd") "abc cde")))
  (testing "\"ab cd\" does NOT match the title \"ad cd\" — ab prefixes neither word"
    (is (false? (contains? (titles-for "ab cd") "ad cd"))))
  (testing "so the search returns the one and not the other"
    (is (= #{"abc cde"} (titles-for "ab cd")))))

(deftest a-prefix-is-not-a-substring
  (create! "abcd")
  (create! "abc cde")
  (testing "cd is a prefix of the word cde"
    (is (contains? (titles-for "cd") "abc cde")))
  (testing "but cd inside abcd is a substring, not a prefix of any word"
    (is (false? (contains? (titles-for "cd") "abcd"))))
  (is (= #{"abc cde"} (titles-for "cd"))))

(deftest a-prefix-of-any-word-not-just-the-first
  (create! "Sourdough starter")
  (is (= #{"Sourdough starter"} (titles-for "star")))
  (is (= #{"Sourdough starter"} (titles-for "sour")))
  (testing "and in either order — the terms are a set of conditions, not a phrase"
    (is (= #{"Sourdough starter"} (titles-for "star sour")))))

(deftest and-not-or
  (create! "Sourdough starter")
  (create! "Risotto")
  (testing "one failing term fails the row, however well the others match"
    (is (empty? (titles-for "sour zzz")))
    (is (empty? (titles-for "sour risotto"))))
  (testing "each term on its own would have matched something"
    (is (= #{"Sourdough starter"} (titles-for "sour")))
    (is (= #{"Risotto"} (titles-for "risotto")))))

(deftest case-insensitive-both-directions
  (create! "ABC CDE")
  (create! "fgh")
  (is (= #{"ABC CDE"} (titles-for "ab cd")))
  (is (= #{"fgh"} (titles-for "FGH")))
  (is (= #{"ABC CDE"} (titles-for "AbC cDe"))))

(deftest a-whole-word-still-matches
  (create! "abc cde")
  (is (= #{"abc cde"} (titles-for "abc")))
  (is (= #{"abc cde"} (titles-for "abc cde")))
  (testing "and one character past the end matches nothing"
    (is (empty? (titles-for "abcx")))))

(deftest a-blank-search-returns-everything
  (create! "Sourdough")
  (create! "Risotto")
  (is (= #{"Sourdough" "Risotto"} (titles-for nil)))
  (is (= #{"Sourdough" "Risotto"} (titles-for "")))
  (is (= #{"Sourdough" "Risotto"} (titles-for "   \t ")))
  (testing "and so does no :search-term key at all"
    (is (= 2 (count (db.recipe/list-recipes h/*ds* h/*user-id*))))))

(deftest only-the-title-is-searched
  (create! "Risotto")
  (testing "useful-when is not searched — every recipe here has zzz in it"
    (is (empty? (titles-for "zzz"))))
  (testing "nor the description, which a lean read never even loads"
    (is (empty? (titles-for "qqq"))))
  (testing "while the title still narrows"
    (is (= #{"Risotto"} (titles-for "riso")))))

(deftest punctuation-ends-a-word
  (create! "Re-heating pizza")
  (create! "make/start")
  (create! "Sourdough (starter)")
  (create! "config.edn")
  (testing "the two cases the order names"
    (is (= #{"Re-heating pizza"} (titles-for "heating")))
    (is (= #{"make/start" "Sourdough (starter)"} (titles-for "start"))))
  (testing "and the same rule everywhere else, since a word is letters and digits"
    (is (= #{"Sourdough (starter)"} (titles-for "starter")))
    (is (= #{"config.edn"} (titles-for "edn"))))
  (testing "the word before the separator is still a word of its own"
    (is (= #{"Re-heating pizza"} (titles-for "re")))
    (is (= #{"make/start"} (titles-for "make"))))
  (testing "a term may carry a separator, and then it has to be that one"
    (is (= #{"Re-heating pizza"} (titles-for "re-heat")))
    (is (empty? (titles-for "re/heat"))))
  (testing "while two terms are two conditions, so the same words split match"
    (is (= #{"Re-heating pizza"} (titles-for "re heat")))))

(deftest non-ascii-is-a-word-character
  (create! "Käse")
  (testing "ä does not end a word, so se is not a word-prefix here"
    (is (empty? (titles-for "se"))))
  (is (= #{"Käse"} (titles-for "kä")))
  (is (= #{"Käse"} (titles-for "käse"))))

(deftest like-wildcards-are-ordinary-characters
  (create! "100 % hydration")
  (create! "Plain title")
  (testing "a literal % searches for a % — the word that starts with one"
    (is (= #{"100 % hydration"} (titles-for "%"))))
  (testing "and is not a wildcard: it does not drag in every row"
    (is (false? (contains? (titles-for "%") "Plain title"))))
  (create! "a_b")
  (create! "axb")
  (testing "_ likewise stands for itself and not for any single character"
    (is (= #{"a_b"} (titles-for "a_b"))))
  (testing "and a search made only of wildcards still narrows"
    (is (= #{"100 % hydration"} (titles-for "% hyd")))
    (is (empty? (titles-for "% zzz")))))

(deftest search-cannot-widen-the-visitor-scope
  (let [{drafted :id} (create! "Draft pizza")
        {signed :id} (create! "Signed pizza")]
    (db.recipe/publish-recipe h/*ds* h/*user-id* signed)
    (testing "a term that matches both only ever yields the published one"
      (is (= #{signed}
             (set (map :id (db.recipe/list-recipes h/*ds* db.recipe/visitor-scope
                                                   {:search-term "pizza"}))))))
    (testing "and a term aimed straight at the draft yields nothing"
      (is (empty? (db.recipe/list-recipes h/*ds* db.recipe/visitor-scope
                                          {:search-term "draft"})))
      (is (empty? (db.recipe/list-recipes h/*ds* db.recipe/visitor-scope
                                          {:search-term "draft pizza"}))))
    (testing "the owner sees both, so the narrowing above is the scope and not
              the search failing"
      (is (= #{drafted signed} (set (map :id (db.recipe/list-recipes h/*ds* h/*user-id*
                                                                     {:search-term "pizza"}))))))))
