(ns et.cb.ui.codemirror
  "Cookbook's one keyboard scheme for writing a Recipe — the IJKL scheme, as every
  other plurama sibling has it.

  Navigation is IJKL instead of the arrow keys: ⌘I/K up/down, ⌘J/L left/right, with
  ⌥ for word and form steps, ⌃ for the markdown sentence motions, and +⇧ to select
  as far as each moves.

  **The scheme is not in this file and must never be copied into it.** It is
  `@eighttrigrams/kw-codemirror`, the library in the keyboard-wizardry repo that
  also holds the owner's VSCode and Obsidian keymaps of the same 47 chords. Tracker,
  rhizome and treina each carried a hand-written table of it once; they were deleted
  rather than merged, because three copies of one layout is three chances for the
  editor here to answer a chord differently from the editor he was in a minute ago.
  Cookbook arrived after that consolidation and simply never had one — a plain
  `<textarea>` stood where this goes, so this app alone answered the arrow keys and
  nothing else.

  The tarball is committed at `vendor/`, and `../vendor-editor.sh` is what keeps it
  in step with the library: the workspace's root script packs one copy and gives the
  same bytes to every consumer, checks each lockfile's hash of it, and checks that
  each Dockerfile which npm-installs also COPYs `vendor/` in. Adding cookbook to that
  script's `NPM_CONSUMERS` is what makes a stale copy here a failed check rather than
  a deploy that silently ships last month's chords.

  What stays here is cookbook's own: the theme, the placeholder, and reporting
  changes back to the caller."
  (:require ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/view" :refer [EditorView placeholder]]
            ["@codemirror/commands" :as commands]
            ["@eighttrigrams/kw-codemirror" :as ijkl]))

(defn create-editor
  "A CodeMirror on `element`, wearing what cookbook's CSS says a `textarea` looks
  like.

  **The theme restates the stylesheet rather than inheriting it**, and that is not a
  duplication that can be avoided: CodeMirror renders a `contenteditable` div, so the
  `textarea` rules in `css/cookbook.css` do not reach it, and the two would drift
  apart in exactly the way a field looking subtly unlike every other field on the
  page is noticed but not diagnosed. What it restates is written in the variables and
  not in values — `--input-border`, `--glass-bg-subtle`, `--accent` — so the light and
  dark palettes still each say it once, in `base.css`, and the amber focus ring is the
  same `rgba(180, 83, 9, 0.18)` the base `input:focus` and `textarea:focus` use.

  Height is `100%` and the sizing belongs to the host element, so the caller's CSS
  decides how tall the box is and can keep the `resize: vertical` grip a `textarea`
  has."
  [element {:keys [doc on-change placeholder-text]}]
  (let [doc (or doc "")
        theme
          (.theme EditorView
                  #js {"&" #js {:height "100%"
                                :border "1px solid var(--input-border)"
                                :borderRadius "10px"
                                :backgroundColor "var(--glass-bg-subtle)"
                                :fontFamily "inherit"
                                :fontSize "0.95em"
                                :color "var(--text-primary)"
                                :transition "all 0.2s ease"}
                       "&.cm-focused" #js {:outline "none"
                                           :borderColor "var(--accent)"
                                           :boxShadow "0 0 0 3px rgba(180, 83, 9, 0.18)"}
                       ".cm-scroller" #js {:overflow "auto"
                                           :fontFamily "inherit"
                                           :lineHeight "1.55"}
                       ".cm-content" #js {:padding "10px 14px"
                                          :fontFamily "inherit"
                                          :caretColor "var(--text-primary)"}
                       ".cm-line" #js {:padding "0"}
                       ".cm-gutters" #js {:display "none"}
                       ".cm-activeLine" #js {:backgroundColor "transparent"}
                       ".cm-activeLineGutter" #js {:display "none"}
                       ".cm-cursor" #js {:borderLeftColor "var(--text-primary)"}
                       ".cm-placeholder" #js {:color "var(--text-secondary)"}})
        line-wrapping (.-lineWrapping EditorView)
        update-listener
          (.of (.-updateListener EditorView)
               (fn [^js update]
                 (when (and (.-docChanged update) on-change)
                   (on-change (.. update -state -doc toString)))))
        extensions (cond-> #js [theme line-wrapping update-listener]
                     (seq placeholder-text) (doto (.push (placeholder placeholder-text))))
        state (.create EditorState #js {:doc doc :extensions extensions})
        view (new EditorView #js {:state state :parent element})]
    ;; The scheme. `install` puts a capture-phase keydown listener on the view's own
    ;; element, so these chords win before CodeMirror's own keymaps.
    (ijkl/install view commands)
    view))

(defn get-editor-value [view]
  (when view (.. view -state -doc toString)))

(defn set-editor-value
  "The whole document replaced. Used to bring the editor back in line with state
  that changed underneath it — see `ui.cm-textarea`, which is the only caller and
  explains when that happens."
  [view value]
  (when view
    (.dispatch view
               (.update (.-state view)
                        #js {:changes #js {:from 0
                                           :to (.. view -state -doc -length)
                                           :insert (or value "")}}))))
