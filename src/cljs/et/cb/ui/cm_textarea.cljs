(ns et.cb.ui.cm-textarea
  "A multi-line text field backed by CodeMirror, so what is typed into it picks up
  the IJKL scheme (see `ui.codemirror`). The `<textarea>` this replaces took a
  `:value` and an `:on-change` and so does this, which is the whole of why it is a
  component: `recipe-fields/edit-fields` should not have to know that the body is
  now a `contenteditable` div rather than a form control.

  **Controlled from the outside, like the field it replaces.** Cookbook keeps the
  draft in app-state — `state/recipe-edit-fields` resolves it — and the editor is a
  view of that, never a second home for it. So `:value` is a plain string out of a
  render and not a ratom: the caller already holds the answer, and a ratom here
  would be the same text in two places.

  **Which makes `:component-did-update` load-bearing, and it needs the guard it
  has.** A keystroke goes editor → `on-change` → app-state → re-render, and the
  string coming back is the one the editor just produced; writing it back in would
  reset the document and put the cursor at the end of every character typed. So the
  write only happens when the incoming value differs from what the document already
  says — which is true exactly when something *other* than typing changed it. That
  is not hypothetical here: Cancel drops the draft, and the provenance toggle takes
  the editor off the page and brings it back, and both have to be seen.

  **The view is hung on the host node as `.cmEditorView`.** The browser checks drove
  the old field by setting `.value` and dispatching an `input` event — the trick for
  a React-controlled form control, and there is no form control left to play it on.
  Rather than give the tests a private hook, the editor they need to talk to is
  simply reachable from the element they already query for; `test/browser`'s
  `bodyEditor` helper is the reader of this, and nothing in the app is."
  (:require [reagent.core :as r]
            [et.cb.ui.codemirror :as cm]))

(defn cm-textarea
  "`:value` seeds the document and keeps it honest, `:on-change` is handed the whole
  text on every edit, `:class` goes on the host element, `:placeholder` shows while
  the document is empty, and `:focus?` — false by default, as a `<textarea>` on a
  page of four fields does not take the caret — focuses the editor at mount."
  [_]
  (let [view (atom nil)
        host (atom nil)
        ;; Raised around the one write below, and read by the listener the write
        ;; wakes. CodeMirror does not distinguish a dispatched change from a typed
        ;; one, so without this the sync would report itself back as an edit — and
        ;; the caller would record a *draft* for a field nobody touched. Cookbook
        ;; sends only touched fields (`state/recipe-edit-fields`), so that is not a
        ;; harmless extra render: it is a `description` in a PUT that the owner did
        ;; not write, and on a Recipe he has part-written that is the difference
        ;; between a save and a proposal in his inbox.
        syncing? (atom false)]
    (r/create-class
     {:display-name "cm-textarea"

      :component-did-mount
      (fn [this]
        (when-let [^js el @host]
          (let [{:keys [value on-change placeholder focus?]} (r/props this)
                v (cm/create-editor el {:doc (or value "")
                                        :placeholder-text placeholder
                                        :on-change #(when (and on-change (not @syncing?))
                                                      (on-change %))})]
            (reset! view v)
            (set! (.-cmEditorView el) v)
            (when focus? (.focus v)))))

      :component-did-update
      (fn [this _]
        (let [{:keys [value]} (r/props this)]
          (when-let [v @view]
            (when (not= (or value "") (cm/get-editor-value v))
              (reset! syncing? true)
              (try (cm/set-editor-value v value)
                   (finally (reset! syncing? false)))))))

      :component-will-unmount
      (fn [_]
        (when-let [v @view]
          (.destroy v)
          (when-let [^js el @host] (set! (.-cmEditorView el) nil))
          (reset! view nil)))

      :reagent-render
      (fn [{:keys [class]}]
        [:div {:class class
               :ref #(when % (reset! host %))}])})))
