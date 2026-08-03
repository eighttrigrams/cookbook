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
  fixed set of identifiers rather than anything an author typed."
  [token]
  (let [text (or (.-text token) "")
        lang (-> (or (.-lang token) "") (str/split #"\s+") first str/lower-case)]
    (if (and (seq lang) (.getLanguage hljs lang))
      (str "<pre><code class=\"hljs language-" lang "\">"
           (.-value (.highlight hljs text #js {:language lang}))
           "</code></pre>")
      (str "<pre><code>" (escape-html text) "</code></pre>"))))

(defn- configure!
  "Clojure first — `clj` and `edn` come with it as aliases — then bash, which is
  what the ops recipes are full of. Anything else falls back to an unhighlighted
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
  (.use marked #js {:breaks true
                    :renderer #js {:code render-code}}))

;; Once, not once per hot reload: `marked.use` stacks renderers rather than
;; replacing them. The var exists to hold that single call, so nothing reads it.
#_{:clj-kondo/ignore [:unused-private-var]}
(defonce ^:private configured (configure!))

(def ^:private purify-opts
  "`class` has to survive on `pre` and `code`, or sanitizing strips the very
  `language-clojure` hook the highlighter just emitted and highlighting silently
  stops working. DOMPurify's defaults do allow `class`; naming it is what keeps a
  future default change from being invisible. The html profile is the whole
  allowlist markdown needs — it produces no SVG and no MathML."
  #js {:USE_PROFILES #js {:html true}
       :ADD_ATTR #js ["class"]})

(defn- clean [html]
  (.sanitize DOMPurify html purify-opts))

(defn render
  "The full block parser, with highlighting: for the description."
  [text]
  [:div.markdown-content
   {:dangerouslySetInnerHTML (r/unsafe-html (clean (marked (or text ""))))}])

(defn render-inline
  "Emphasis, inline code and links, but no block elements — for the title and
  the useful-when line. Those two are phrases holding a place in the card's
  layout rather than documents; a `#` heading or a bulleted list in a title would
  break it. `parseInline` cannot produce one, so the layout is safe by
  construction rather than by CSS that has to keep winning."
  [text]
  [:span.markdown-content.markdown-inline
   {:dangerouslySetInnerHTML (r/unsafe-html (clean (.parseInline marked (or text ""))))}])
