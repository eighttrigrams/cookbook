(ns et.cb.ui.edit-keys
  "⌘9 saves the Recipe you are editing, without leaving the editor.

  *when i press [command]+9 anywhere on that page no matter where my cursor is, it
  saves (doesnt exit).*

  **`anywhere on that page` is why this is on the document and not on a field.** The
  body is a CodeMirror, the three short fields are inputs, and the top bar is neither
  — a handler hung on the form would be a chord that works in three places out of
  five and looks broken in the other two. One listener while the editor is mounted,
  in the **capture** phase so it is ahead of CodeMirror's own keymaps, is the same
  shape `ui.codemirror` uses for the scheme itself one level down.

  **Keyed on `e.code`, never `e.key`.** `Digit9` is the same physical key whatever a
  modifier does to the character it would produce, and `@eighttrigrams/kw-codemirror`
  opens its own `chord` function with the warning that makes this non-negotiable
  across the scheme: on macOS Option is a compose modifier, so an `e.key` map fails
  silently for exactly the chords that carry it. Every sibling's save chord is
  written `(= \"Digit9\" (.-code e))` and this is no exception.

  **⌘ and *only* ⌘, which is a narrowing worth recording.** Rhizome's
  `ui.modals.key-handler` takes `(or meta-pressed? alt-pressed?)` on `Digit9` for
  this very act — `save-description-and-leave-open!` — and this took both for a
  while on that precedent. It should not, and the reason is the compose behaviour
  above read the other way round: ⌥9 on a Mac *types* `ª`, the ordinal indicator
  Portuguese writes `1.ª` with. Rhizome can afford to spend it on a short
  single-line modal field; the thing under this listener is a long-form markdown
  body, where a chord that swallows a character the writer might want is a chord
  taking something away. Tracker, treina, music and blog all take ⌘9 alone, so this
  is the majority spelling as well as the safe one.

  **`Digit9` and not `Numpad9`**: the sibling apps all name the row above the
  letters and nothing in the scheme claims the keypad, so this is their spelling
  rather than a decision of its own."
  (:require [reagent.core :as r]
            [et.cb.ui.state :as state]))

(defn- save-chord? [^js e]
  (and (= "Digit9" (.-code e))
       (.-metaKey e)
       ;; No sibling's chord carries these, and a stray ⌘⇧9 or ⌃⌘9 should fall
       ;; through to whatever else wants it rather than save. `altKey` is among them
       ;; deliberately: ⌥ is not a second way to press this, so ⌘⌥9 is not it either.
       (not (.-altKey e))
       (not (.-ctrlKey e))
       (not (.-shiftKey e))))

(defn- on-key [^js e]
  (when (save-chord? e)
    ;; Both, and not just `preventDefault`: stopping it here is what keeps
    ;; CodeMirror from seeing a chord the scheme does not claim and doing nothing
    ;; visible with it.
    (.preventDefault e)
    (.stopPropagation e)
    (state/save-recipe-edit-in-place)))

(def while-editing
  "Holds the chord for as long as it is in the tree, and draws nothing — the shape
  `page-lock/while-mounted` uses, and a `def` for the same reason it is one there: a
  fresh class per render would detach and re-attach the listener on every keystroke.

  Mounted by `views.recipe/editor`, so the chord exists exactly while the editor
  does. The alternative — one listener for the app's whole life, asking the page and
  the mode on every keypress — is a handler that has to be right about a condition
  instead of simply not being there.

  **`on-key` is a var and not a closure**, which is what makes the `removeEventListener`
  match the `addEventListener`: the two get the same reference without anything having
  to be stashed on the instance between them. The one cost is a dev-only oddity —
  after hot-reloading *this* namespace the mounted editor keeps calling the previous
  definition, because React reconciles the component rather than remounting it and so
  neither hook runs. Leave the editor and come back and it is the new one."
  (r/create-class
   {:display-name "edit-keys"
    :component-did-mount
    (fn [_] (.addEventListener js/document "keydown" on-key true))
    :component-will-unmount
    (fn [_] (.removeEventListener js/document "keydown" on-key true))
    :reagent-render (fn [] nil)}))
