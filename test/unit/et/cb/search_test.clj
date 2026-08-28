(ns et.cb.search-test
  "What `?search=` means: the title, the tags and the words of the Scopes a Recipe
  is filed under, by word-prefix, AND across terms.

  Written at the db layer on purpose. The whole thing is a `:where` clause, so
  SQLite is what evaluates it — a test of the clause *shape* would pass while
  the query returned the wrong rows. The HTTP end of it, query-string encoding
  included, is in `et.cb.recipes-integration-test`.

  **Three searched places, and the third one is not on the row.** A Scope carries
  tags of its own, and its title and its tags are searched through the filing — so
  the last section here is about a join, and about the one audience rule the two
  kinds of tag do not share: a Recipe's tags are searched for everybody, a Scope's
  words only for a caller who may see the filing at all."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.db.scope :as db.scope]
            [et.cb.test-helpers :as h]))

(use-fixtures :each h/with-in-memory-db)

(defn- create!
  "A recipe whose useful-when and description hold words the title does not, so
  every assertion below also says something about *where* search looks."
  ([title] (create! title nil))
  ([title tags]
   (db.recipe/create-recipe h/*ds* h/*user-id*
                            (cond-> {:title title
                                     :useful_when "when testing zzz"
                                     :description "body mentioning qqq"}
                              tags (assoc :tags tags)))))

(defn- titles-for [search-term]
  (set (map :title (db.recipe/list-recipes h/*ds* h/*user-id*
                                           {:search-term search-term}))))

(defn- scope!
  "A Scope with tags of its own — the second column this namespace's last section
  is about."
  ([title] (scope! title nil))
  ([title tags]
   (:id (db.scope/create-scope h/*ds* h/*user-id* {:title title :tags tags}))))

(defn- filed!
  "A Recipe filed under Scopes, otherwise `create!`'s Recipe: the same
  useful-when and description, so a hit through the filing is still never a hit
  on prose."
  ([title scope-ids] (filed! title nil scope-ids))
  ([title tags scope-ids]
   (db.recipe/create-recipe h/*ds* h/*user-id*
                            (cond-> {:title title
                                     :useful_when "when testing zzz"
                                     :description "body mentioning qqq"
                                     :scope_ids scope-ids}
                              tags (assoc :tags tags)))))

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

(deftest only-the-title-and-the-tags-are-searched
  (create! "Risotto" "rice arborio")
  (testing "useful-when is not searched — every recipe here has zzz in it"
    (is (empty? (titles-for "zzz"))))
  (testing "nor the description, which a lean read never even loads"
    (is (empty? (titles-for "qqq"))))
  (testing "while the title still narrows"
    (is (= #{"Risotto"} (titles-for "riso"))))
  (testing "and so do the tags, which is the point of them: a word that is
            nowhere in the title finds it"
    (is (= #{"Risotto"} (titles-for "arbo")))
    (is (= #{"Risotto"} (titles-for "rice")))))

;; ---------------------------------------------------------------------------
;; the tags column, and the fact that the two searched columns are one condition

(deftest a-term-may-land-in-either-column
  (create! "Sourdough starter" "bread baking")
  (create! "Risotto" "rice")
  (testing "the order's own example: one term prefixes a word of the title and
            the other a word of the tags"
    (is (= #{"Sourdough starter"} (titles-for "sour bak"))))
  (testing "either way round, and either column alone"
    (is (= #{"Sourdough starter"} (titles-for "bak sour")))
    (is (= #{"Sourdough starter"} (titles-for "star sour")))
    (is (= #{"Sourdough starter"} (titles-for "bread bak"))))
  (testing "the terms are still ANDed across the pair — a term that prefixes
            nothing in *either* column fails the row"
    (is (empty? (titles-for "sour rice")))
    (is (empty? (titles-for "bak zzz")))))

(deftest the-word-rules-are-the-same-in-the-tags
  ;; Not tracker's `LIKE 'term%'` over the whole column: cookbook's word
  ;; semantics apply to the second column exactly as they do to the first.
  (create! "Nothing in the title" "re-heating make/start Käse 100 % a_b")
  (testing "punctuation ends a word there too"
    (is (= 1 (count (titles-for "heating"))))
    (is (= 1 (count (titles-for "start"))))
    (is (= 1 (count (titles-for "re")))))
  (testing "a prefix is not a substring"
    (is (empty? (titles-for "eating"))))
  (testing "non-ASCII is a word character"
    (is (= 1 (count (titles-for "kä"))))
    (is (empty? (titles-for "se"))))
  (testing "and % and _ are ordinary characters rather than wildcards"
    (is (= 1 (count (titles-for "%"))))
    (is (= 1 (count (titles-for "a_b"))))
    (is (empty? (titles-for "axb")))))

(deftest an-untagged-recipe-is-searched-as-before
  (create! "Sourdough starter")
  (is (= "" (:tags (first (db.recipe/list-recipes h/*ds* h/*user-id*)))))
  (testing "an empty tags column narrows nothing and matches nothing"
    (is (= #{"Sourdough starter"} (titles-for "sour")))
    (is (empty? (titles-for "bak")))))

(deftest a-visitor-searches-tags-too-and-still-cannot-read-them
  ;; **The owner's decision, pinned both ways in one place.** The searched
  ;; columns do not depend on the caller — "this way we have a uniform
  ;; expectation about search hits" — while the projection does. So a visitor can
  ;; find a published Recipe by a word only its tags carry, and never learn what
  ;; the tags say. The HTTP end of the same pair is in the integration namespace.
  (let [{signed :id} (create! "Signed" "sekrit filing")
        {drafted :id} (create! "Draft" "sekrit filing")]
    (db.recipe/publish-recipe h/*ds* h/*user-id* signed)
    (let [hits (db.recipe/list-recipes h/*ds* db.recipe/visitor-audience
                                       {:search-term "sekrit"})]
      (testing "the term is in neither title, and it finds the published one"
        (is (= [signed] (map :id hits))))
      (testing "and what comes back still carries no tags key"
        (is (false? (contains? (first hits) :tags)))))
    (testing "the search cannot widen the audience either — the draft carries the
              same tags and stays invisible, however the terms are put"
      (is (= [signed] (map :id (db.recipe/list-recipes h/*ds* db.recipe/visitor-audience
                                                       {:search-term "filing sekrit"}))))
      (is (= #{signed drafted}
             (set (map :id (db.recipe/list-recipes h/*ds* h/*user-id*
                                                   {:search-term "sekrit"}))))))))

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

;; ---------------------------------------------------------------------------
;; the Scopes' words: the third searched place, and the only one not on the row

(deftest the-orders-own-example-through-a-scope
  ;; *say, i have a recipe which is taged with a scope "utwig", and "utwig" has tag
  ;; "backend tag2 tag3", then searching for recipes will not only match recipe
  ;; titles and recipe tags, but also will be a search hit for "utwig" and
  ;; "backend" and "tag2" "tag3" as if those would be part of the recipes title …
  ;; i can hit a title of a recipe "abc def", scoped as "utwig", by entering
  ;; "ab utw".*
  (let [utwig (scope! "utwig" "backend tag2 tag3")]
    (filed! "abc def" [utwig])
    (create! "unfiled")
    (testing "the Scope's own title is one of the Recipe's search terms"
      (is (= #{"abc def"} (titles-for "utwig")))
      (is (= #{"abc def"} (titles-for "utw"))))
    (testing "and so is every word of the Scope's tags, as if it were in the title"
      (is (= #{"abc def"} (titles-for "backend")))
      (is (= #{"abc def"} (titles-for "tag2")))
      (is (= #{"abc def"} (titles-for "tag3")))
      (is (= #{"abc def"} (titles-for "back"))))
    (testing "the order's own example: one term off the title, one off the Scope"
      (is (= #{"abc def"} (titles-for "ab utw"))))
    (testing "in either order, and a term from the Scope's tags counts the same as
              one from its title — where each term lands is its own business"
      (is (= #{"abc def"} (titles-for "utw ab")))
      (is (= #{"abc def"} (titles-for "abc backend")))
      (is (= #{"abc def"} (titles-for "tag3 def")))
      (is (= #{"abc def"} (titles-for "utw backend"))))
    (testing "still ANDed rather than ORed: a term that prefixes nothing in any of
              the three places fails the row, however well the others match"
      (is (empty? (titles-for "ab zzz")))
      (is (empty? (titles-for "utwig qqq"))))
    (testing "and a Scope's words reach only the Recipes filed under it"
      (is (false? (contains? (titles-for "utwig") "unfiled")))
      (is (empty? (titles-for "utwig unfiled"))))))

(deftest a-scopes-words-are-inherited-and-not-copied
  ;; What makes them worth having on the Scope: one write labels the whole shelf
  ;; of them, where a Recipe's own tags have to be typed onto each Recipe.
  (let [utwig (scope! "utwig" "backend")]
    (filed! "abc def" [utwig])
    (filed! "something else" [utwig])
    (create! "on its own")
    (testing "every Recipe filed there answers to the Scope's word"
      (is (= #{"abc def" "something else"} (titles-for "backend"))))
    (testing "and only those"
      (is (false? (contains? (titles-for "utwig") "on its own"))))
    (testing "retagging the Scope relabels all of them at once, in one write"
      (db.scope/update-scope h/*ds* h/*user-id* utwig {:tags "frontend"})
      (is (empty? (titles-for "backend")))
      (is (= #{"abc def" "something else"} (titles-for "frontend"))))
    (testing "while renaming it moves the title-word with it and takes nothing
              else, since the filing is by id"
      (db.scope/update-scope h/*ds* h/*user-id* utwig {:title "zwutig"})
      (is (empty? (titles-for "utwig")))
      (is (= #{"abc def" "something else"} (titles-for "zwutig")))
      (is (= #{"abc def" "something else"} (titles-for "frontend"))))))

(deftest unfiling-takes-the-scopes-words-back
  (let [utwig (scope! "utwig" "backend")
        {:keys [id]} (filed! "abc def" [utwig])]
    (is (= #{"abc def"} (titles-for "backend")))
    (db.recipe/update-recipe h/*ds* h/*user-id* id {:scope_ids []} nil)
    (testing "the words were the Scope's and never the Recipe's, so they leave with
              the filing rather than staying behind on the row"
      (is (empty? (titles-for "backend")))
      (is (empty? (titles-for "utwig"))))
    (testing "and the Recipe's own words are untouched by the unfiling"
      (is (= #{"abc def"} (titles-for "abc"))))))

(deftest several-scopes-are-an-or-and-a-term-may-land-in-any-of-them
  (let [utwig (scope! "utwig" "backend")
        baking (scope! "baking" "bread sourdough")]
    (filed! "abc def" [utwig baking])
    (filed! "only baking" [baking])
    (testing "a word from either Scope finds the Recipe filed under both — `EXISTS`
              asks about *any* row of the filing, so there is nothing per-Scope"
      (is (contains? (titles-for "backend") "abc def"))
      (is (contains? (titles-for "sourdough") "abc def")))
    (testing "and two terms may land in two different Scopes of the same Recipe"
      (is (= #{"abc def"} (titles-for "backend sourdough"))))
    (testing "while the Recipe filed under only one of them fails that pair"
      (is (= #{"abc def"} (titles-for "backend bread"))))))

(deftest a-term-may-land-in-any-of-the-three
  (let [utwig (scope! "utwig" "backend")]
    (filed! "Sourdough starter" "bread baking" [utwig])
    (create! "Risotto" "rice")
    (testing "title, own tags, Scope title, Scope tags — one term each, all ANDed"
      (is (= #{"Sourdough starter"} (titles-for "sour bak utw back"))))
    (testing "and any pair of them on its own"
      (is (= #{"Sourdough starter"} (titles-for "star bread")))
      (is (= #{"Sourdough starter"} (titles-for "sour utwig")))
      (is (= #{"Sourdough starter"} (titles-for "baking backend")))
      (is (= #{"Sourdough starter"} (titles-for "utwig backend"))))
    (testing "the other Recipe is in no Scope, so the Scope words never rescue it"
      (is (empty? (titles-for "rice utwig"))))))

(deftest the-word-rules-are-the-same-in-a-scopes-words
  ;; The same clause builder as the row's two columns
  ;; (`db/word-prefix-term-clause`), which is why this section is short: what a
  ;; word is is defined once and applied in two tables.
  (let [odd (scope! "Käse-Zeug" "re-heating make/start 100 % a_b")]
    (filed! "Nothing in the title" [odd])
    (testing "punctuation ends a word in a Scope's title"
      (is (= 1 (count (titles-for "käse"))))
      (is (= 1 (count (titles-for "zeug")))))
    (testing "and in a Scope's tags"
      (is (= 1 (count (titles-for "heating"))))
      (is (= 1 (count (titles-for "start"))))
      (is (= 1 (count (titles-for "re")))))
    (testing "a prefix is not a substring, here either"
      (is (empty? (titles-for "eating")))
      (is (empty? (titles-for "äse"))))
    (testing "non-ASCII is a word character, so `se` does not find `Käse-Zeug`"
      (is (empty? (titles-for "se"))))
    (testing "and % and _ stand for themselves rather than for wildcards"
      (is (= 1 (count (titles-for "%"))))
      (is (= 1 (count (titles-for "a_b"))))
      (is (empty? (titles-for "axb"))))))

(deftest a-scopes-description-is-not-searched
  (let [utwig (scope! "utwig" "backend")]
    (db.scope/update-scope h/*ds* h/*user-id* utwig
                           {:description "everything about zzzprose belongs here"})
    (filed! "abc def" [utwig])
    (testing "the line saying what belongs in a Scope is prose, and prose is not a
              retrieval key here — the call useful-when and the body already got"
      (is (empty? (titles-for "zzzprose")))
      (is (empty? (titles-for "belongs"))))
    (testing "while the title and the tags of that same Scope do find it, so the
              two above are a choice of columns and not a broken join"
      (is (= #{"abc def"} (titles-for "utwig")))
      (is (= #{"abc def"} (titles-for "backend"))))))

(deftest a-scope-with-no-tags-still-lends-its-title
  (let [plain (scope! "Deployment")]
    (filed! "Rollback" [plain])
    (is (= "" (:tags (first (db.scope/list-scopes h/*ds* h/*user-id*)))))
    (testing "an empty tags column narrows nothing and matches nothing, exactly as
              an untagged Recipe's does"
      (is (= #{"Rollback"} (titles-for "deploy")))
      (is (= #{"Rollback"} (titles-for "roll deploy")))
      (is (empty? (titles-for "zzz"))))))

(deftest an-unfiled-recipe-is-searched-as-before
  ;; **The filed Recipe is the control and not scenery.** Every assertion below
  ;; except the ones naming it would pass with the whole feature reverted — an
  ;; unfiled Recipe behaving as it always did is exactly what a missing clause
  ;; looks like — so the Scope has something in it, and the pairs say *this one and
  ;; not that one* rather than merely *not that one*.
  (let [utwig (scope! "utwig" "backend")]
    (create! "Sourdough starter")
    (filed! "Filed away" [utwig])
    (testing "a Recipe in no Scope drops out of the `EXISTS` and is left to its own
              two columns, which is the wanted silence rather than an exception"
      (is (= #{"Sourdough starter"} (titles-for "sour")))
      (is (= #{"Sourdough starter"} (titles-for "sour star"))))
    (testing "and a Scope's words reach the Recipe filed under it and no other"
      (is (= #{"Filed away"} (titles-for "utwig")))
      (is (= #{"Filed away"} (titles-for "backend")))
      (is (empty? (titles-for "sour utwig")))
      (is (empty? (titles-for "star backend"))))))

(deftest a-scope-nothing-is-filed-under-lends-its-words-to-nobody
  (create! "Sourdough starter")
  (scope! "utwig" "backend")
  (testing "an empty Scope is not a way to match every Recipe, nor to match none:
            the `EXISTS` is about this Recipe's own filing, and there is none"
    (is (empty? (titles-for "utwig")))
    (is (empty? (titles-for "backend"))))
  (testing "while the shelf is still there to be searched, so the two above are the
            join finding nothing rather than the search having broken"
    (is (= #{"Sourdough starter"} (titles-for "sour")))))

(deftest a-scope-the-caller-does-not-own-lends-no-words
  ;; The join narrows on `scopes.user_id` like every other read of a Scope, so a
  ;; stranger's Scope is not a back door into the search. Written straight at the
  ;; join table, like `scope-exclusion-db-test`'s twin of this: no request can file
  ;; the owner's Recipe under somebody else's Scope, so the *read* is what is under
  ;; test — a clause that skipped the join through `scopes` would honour this row.
  (let [stranger (inc h/*user-id*)
        {theirs :id} (db.scope/create-scope h/*ds* stranger
                                            {:title "Theirs" :tags "strangerword"})
        mine (scope! "mine" "myword")
        {:keys [id]} (filed! "Sourdough" [mine])]
    (h/insert-scope-row! id theirs)
    (testing "the stranger's title and tags are not the owner's search terms"
      (is (empty? (titles-for "theirs")))
      (is (empty? (titles-for "strangerword"))))
    (testing "while the owner's own Scope lends its words, so the two above are the
              ownership narrowing rather than a clause that does nothing at all"
      (is (= #{"Sourdough"} (titles-for "myword")))
      (is (= #{"Sourdough"} (titles-for "sour myword"))))))

(deftest a-visitors-search-does-not-reach-the-filing
  ;; **The one place the two kinds of tag part company, pinned in the same file as
  ;; the rule it departs from** — see `a-visitor-searches-tags-too-and-still-cannot-
  ;; read-them` above. A Recipe's tags are searched for every audience; a Scope's
  ;; words are the owner's alone, because a visitor is refused the filing outright
  ;; (no `scopes` key, and neither Scope filter honoured) and a search that matched
  ;; a Scope's title would hand back the very inference those refusals prevent.
  ;;
  ;; Built so that honouring it *would* change the answer: the Recipe below is
  ;; published, and the owner's assertions are what say the words are really there.
  (let [utwig (scope! "utwig" "sekritword")
        {signed :id} (filed! "Signed" "ownword" [utwig])
        as-visitor (fn [term]
                     (map :id (db.recipe/list-recipes h/*ds* db.recipe/visitor-audience
                                                      {:search-term term})))]
    (db.recipe/publish-recipe h/*ds* h/*user-id* signed)
    (testing "the owner finds it by the Scope's title and by the Scope's tags"
      (is (= #{"Signed"} (titles-for "utwig")))
      (is (= #{"Signed"} (titles-for "sekritword")))
      (is (= #{"Signed"} (titles-for "utw ownword"))))
    (testing "a visitor is served the published Recipe and finds it by its title"
      (is (= [signed] (as-visitor "signed"))))
    (testing "and by the tags on its row, which are searched for everybody"
      (is (= [signed] (as-visitor "ownword"))))
    (testing "but not by a word of the Scope it is filed under — the third disjunct
              is not built for a visitor at all"
      (is (empty? (as-visitor "utwig")))
      (is (empty? (as-visitor "sekritword")))
      (is (empty? (as-visitor "utw"))))
    (testing "so a Scope word cannot be used to test the filing even beside a term
              that would have matched on its own"
      (is (empty? (as-visitor "signed utwig")))
      (is (empty? (as-visitor "ownword sekritword"))))))

(deftest search-cannot-widen-the-visitor-audience
  (let [{drafted :id} (create! "Draft pizza")
        {signed :id} (create! "Signed pizza")]
    (db.recipe/publish-recipe h/*ds* h/*user-id* signed)
    (testing "a term that matches both only ever yields the published one"
      (is (= #{signed}
             (set (map :id (db.recipe/list-recipes h/*ds* db.recipe/visitor-audience
                                                   {:search-term "pizza"}))))))
    (testing "and a term aimed straight at the draft yields nothing"
      (is (empty? (db.recipe/list-recipes h/*ds* db.recipe/visitor-audience
                                          {:search-term "draft"})))
      (is (empty? (db.recipe/list-recipes h/*ds* db.recipe/visitor-audience
                                          {:search-term "draft pizza"}))))
    (testing "the owner sees both, so the narrowing above is the audience and not
              the search failing"
      (is (= #{drafted signed} (set (map :id (db.recipe/list-recipes h/*ds* h/*user-id*
                                                                     {:search-term "pizza"}))))))))
