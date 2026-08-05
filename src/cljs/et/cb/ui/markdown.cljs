(ns et.cb.ui.markdown
  "Everything an author writes into a Recipe — the title, the useful-when line,
  the body — is markdown, rendered through marked. `markdown.css` keeps headings
  at body size so a rendered block sits inside a card without shouting.

  Two things here have no precedent in the sibling apps.

  Fenced code blocks go through highlight.js, because a Recipe's body is mostly
  code and the recipes are about Clojure. It is imported as its core plus the
  grammars registered below rather than whole: the package carries ~190 of them
  and a bundle should only pay for the ones this app can actually meet.

  And marked's output is sanitized before it is injected. Every sibling injects
  it raw and each is right to — the owner is the only author and none of them is
  public. Cookbook breaks both halves of that: agents write here unsupervised,
  and a published Recipe is served to anonymous visitors, so \"an agent writes a
  description -> marked -> raw HTML -> public page\" is an XSS route the siblings
  do not have, with a plausible carrier rather than a hypothetical one. marked
  deliberately does not sanitize and tells you to bring your own."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            ["marked" :refer [marked]]
            ["highlight.js/lib/core" :as hljs]
            ["highlight.js/lib/languages/clojure" :as hljs-clojure]
            ["highlight.js/lib/languages/bash" :as hljs-bash]
            ["dompurify" :as DOMPurify]))

(defn- escape-html
  "The unhighlighted path emits the block's text verbatim, so it has to escape
  it itself. The highlighted path does not: highlight.js escapes everything it
  wraps."
  [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn- render-code
  "marked 18 hands a renderer one token — `#js {:text .. :lang .. :escaped ..}`
  — where older majors passed `(code, lang, escaped)`. A renderer written to the
  old shape still gets called, finds no language on its first argument and
  quietly emits unhighlighted code, so the version this is written against
  matters more than it looks.

  An info string can carry more than a language (```clojure {.line-numbers}), so
  only its first word is one. `getLanguage` answering is also what makes it safe
  to interpolate: it only answers for a registered name or alias, which is a
  fixed set of identifiers rather than anything an author typed.

  The unhighlighted branch emits no class at all, where marked's own renderer
  would emit `class=\"language-rockstar\"` from the author's info string. That is
  deliberate — nothing should be able to write a class name into the page — and
  the cost is only that CSS cannot target the language of a block this bundle
  could not read."
  [token]
  (let [text (or (.-text token) "")
        lang (-> (or (.-lang token) "") (str/split #"\s+") first str/lower-case)]
    (if (and (seq lang) (.getLanguage hljs lang))
      (str "<pre><code class=\"hljs language-" lang "\">"
           (.-value (.highlight hljs text #js {:language lang}))
           "</code></pre>")
      (str "<pre><code>" (escape-html text) "</code></pre>"))))

(defn- render-checkbox
  "A GFM task list (`- [x] step`) is the one thing marked emits that the
  allowlists below refuse: its `<input type=\"checkbox\">` is the tag a phishing
  prompt is built out of, and DOMPurify allows a tag or does not — it cannot
  allow `input` only when the type is a checkbox. Letting the element be stripped
  instead would render `- [ ]` and `- [x]` identically, which loses what the
  author wrote and loses it silently, so the marker becomes text. That is also
  what a body looked like on the `pre-wrap` card before anything here rendered
  markdown, so it reads as it always did."
  [token]
  (if (.-checked token) "[x] " "[ ] "))

(defn- configure!
  "Clojure first — `clj` and `edn` come with it as aliases — then bash, which is
  what the ops recipes are full of. `shell` and `console` are not aliases of bash
  in the package, and two of the three Recipes written so far are shell
  instructions, so an author typing ```shell would otherwise get a flat block and
  no hint as to why. Anything genuinely unknown falls back to an unhighlighted
  block, which is the honest rendering of a language this bundle cannot read.

  `:breaks true` because of what was already written here. Before anything
  rendered markdown, a body's newlines survived on a `pre-wrap` card, so the
  Recipes in the database use one line per step — and under CommonMark, where a
  single newline is just a space, those turn into one reflowed paragraph. That
  would be this change quietly rewriting the owner's existing bodies. An agent
  writing terse steps has the same habit, so honouring the newline is the rule
  that matches what authors here actually type."
  []
  (.registerLanguage hljs "clojure" hljs-clojure)
  (.registerLanguage hljs "bash" hljs-bash)
  (.registerAliases hljs #js ["shell" "console"] #js {:languageName "bash"})
  (.use marked #js {:breaks true
                    :renderer #js {:code render-code
                                   :checkbox render-checkbox}}))

;; Once, not once per hot reload: `marked.use` stacks renderers rather than
;; replacing them. The var exists to hold that single call, so nothing reads it.
#_{:clj-kondo/ignore [:unused-private-var]}
(defonce ^:private configured (configure!))

(def ^:private block-opts
  "A list of what markdown may emit, rather than DOMPurify's `html` profile with
  the dangerous parts subtracted.

  The profile is much broader than markdown: it keeps `<style>`, which the
  browser does not confine to the card, so one field of one published Recipe
  restyles the whole page and can `@import` an arbitrary origin on render; it
  keeps `<form>`, `<input>` and `<button>`, which is a credential prompt on a
  page an anonymous visitor arrived at legitimately; and it keeps the `style`
  attribute and `<textarea>`. Naming those in `FORBID_TAGS` would fix the four
  things somebody noticed, as a blocklist against a library default this app does
  not own — the next DOMPurify release may widen the profile, and nothing here
  would say so. An allowlist can only be wrong in the direction that shows: a
  forgotten entry stops something rendering, and someone sees that.

  Every tag below is one marked's renderer emits, read off marked 18.0.5 and
  confirmed by rendering a document that uses all of it, plus `span` — the only
  element highlight.js produces. Every attribute likewise: `class` carries the
  highlighter's `hljs-*` tokens and the `language-*` hook, `href`/`title` a link,
  `src`/`alt`/`title` an image, `align` a table cell, and `start` an `<ol>` that
  does not begin at 1. `<input>` is the one emission deliberately left out; see
  `render-checkbox`.

  `USE_PROFILES` is gone rather than narrowed, because DOMPurify's `_parseConfig`
  applies it *after* `ALLOWED_TAGS` and overwrites it — keeping it \"as well, to
  be safe\" would silently restore the whole broad profile. What DOMPurify still
  does for us, and should: its URL scheme check runs on `href` and `src` whatever
  the allowlist says, and its `FORBID_CONTENTS` default means a rejected
  `<style>` takes its CSS with it instead of hoisting it into the page as prose.
  `data-*` and `aria-*` attributes are permitted by default and markdown emits
  neither, so they are off too — the list should be the whole story."
  #js {:ALLOWED_TAGS #js ["p" "br" "hr" "blockquote"
                          "h1" "h2" "h3" "h4" "h5" "h6"
                          "ul" "ol" "li"
                          "table" "thead" "tbody" "tr" "th" "td"
                          "pre" "code" "span"
                          "strong" "em" "del" "a" "img"]
       :ALLOWED_ATTR #js ["class" "href" "title" "src" "alt" "align" "start"]
       :ALLOW_DATA_ATTR false
       :ALLOW_ARIA_ATTR false})

(def ^:private inline-opts
  "The short fields get their own, stricter list, and that is what makes
  `render-inline`'s promise true rather than nearly true.

  `parseInline` suppresses markdown's *block syntax*, so `# x` in a title stays
  text — but marked's inline tokenizer forwards raw HTML of any tag name, so
  under one shared config a title could still carry an `<h1>`, a `<table>` or a
  sized `<div>`, and a one-line field grew to 250 pixels. These six are what
  `parseInline`'s renderer can legitimately emit, less `img`: a title and the
  useful-when line are phrases holding a place in a layout, and an image is a box
  with a size of its own. Nothing here can introduce a block box at all."
  #js {:ALLOWED_TAGS #js ["strong" "em" "del" "code" "a" "br"]
       :ALLOWED_ATTR #js ["href" "title"]
       :ALLOW_DATA_ATTR false
       :ALLOW_ARIA_ATTR false})

(defn- clean [opts html]
  (.sanitize DOMPurify html opts))

(defn render
  "The full block parser, with highlighting: for the description."
  [text]
  [:div.markdown-content
   {:dangerouslySetInnerHTML (r/unsafe-html (clean block-opts (marked (or text ""))))}])

(defn render-inline
  "Emphasis, inline code and links, but no block elements — for the title and
  the useful-when line. Those two are phrases holding a place in the card's
  layout rather than documents; a `#` heading or a bulleted list in a title would
  break it. `parseInline` refuses markdown's block syntax and `inline-opts`
  refuses block *elements*, which is the half that raw HTML in a title walks
  straight through. Between them a title cannot open a box, whatever an author
  writes — a hard break is the one thing left that can add a line to one."
  [text]
  [:span.markdown-content.markdown-inline
   {:dangerouslySetInnerHTML (r/unsafe-html (clean inline-opts (.parseInline marked (or text ""))))}])
