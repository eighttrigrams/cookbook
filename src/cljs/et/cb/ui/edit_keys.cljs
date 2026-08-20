(ns et.cb.ui.edit-keys
  "⌥9 saves the Recipe you are editing, without leaving the editor.

  *when i press option+9 anywhere on that page no matter where my cursor is, it
  saves (doesnt exit).*

  **`anywhere on that page` is why this is on the document and not on a field.** The
  body is a CodeMirror, the three short fields are inputs, and the top bar is neither
  — a handler hung on the form would be a chord that works in three places out of
  five and looks broken in the other two. One listener while the editor is mounted,
  in the **capture** phase so it is ahead of CodeMirror's own keymaps, is the same
  shape `ui.codemirror` uses for the scheme itself one level down.

  **Keyed on `e.code`, never `e.key`, and that is the trap this app would otherwise
  have walked into.** On macOS Option is a compose modifier: ⌥9 arrives with
  `e.key` of `\"ª\"`, so an `e.key` test would simply never fire, and it would look
  like the listener was not attached rather than like the wrong field was read.
  `@eighttrigrams/kw-codemirror` opens its own `chord` function with that warning,
  and it is the reason every sibling's save chord is written `(= \"Digit9\"
  (.-code e))`.

  **⌘9 does the same thing, which is rhizome's answer and not an invention here.**
  Its `ui.modals.key-handler` takes `(or meta-pressed? alt-pressed?)` on `Digit9` for
  exactly this act — `save-description-and-leave-open!` — while tracker, treina and
  music take ⌘9 alone for save-and-close. Accepting both costs nothing, since
  `Digit9` is free in the scheme under every modifier, and it means a hand that has
  learnt the chord in any of the four apps finds it here.

  **`Digit9` and not `Numpad9`**: the sibling apps all name the row above the letters
  and nothing in the scheme claims the keypad, so this is their spelling rather than
  a decision of its own."
  (:require [reagent.core :as r]
            [et.cb.ui.state :as state]))

(defn- save-chord? [^js e]
  (and (= "Digit9" (.-code e))
       (or (.-altKey e) (.-metaKey e))
       ;; Neither sibling's chord carries these, and a stray ⌥⇧9 or ⌃⌥9 should fall
       ;; through to whatever else wants it rather than save.
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
