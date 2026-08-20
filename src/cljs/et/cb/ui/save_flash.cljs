(ns et.cb.ui.save-flash
  "The green ✓ that says a save landed, for a save that leaves you where you are.

  **Only the in-between save needs it, which is what this is for.** Save & exit
  answers for itself — the editor closes and the reading comes up with the new text
  in it, so a mark on top of that would be telling you what you are already looking
  at. ⌥9 changes nothing on screen: the same editor with the same words in it, before
  and after. Without a mark there is no way to tell a save that landed from a
  keystroke that went nowhere, and the only honest reading of silence would be the
  pessimistic one.

  Tracker's `#save-flash` / `.save-flash-mark`, which blog's Zen mode already copied
  once (`blog/resources/public/blog/js/zen.js`, `mark()`), and this is the third of
  them. A copy and not a shared component, for the reason blog's is: three apps'
  builds share no code, and what is being kept the same here is nine lines and a
  colour. What matters is that the *mark* is the same mark — the reader has learnt it
  in tracker and in blog, and learns nothing new here.

  **Blog's has a ✗ arm and this does not.** There the save is a `fetch` whose status
  the handler reads, so failure is a thing it knows about at the moment it would
  draw. Cookbook's writes go through `state/update-recipe`, and a failed one already
  raises the error banner every other write in this app uses — a second, quieter
  channel for the same fact would be two things to keep in step and one of them
  easy to miss."
  (:require [reagent.core :as r]))

;; Kept in step with the .save-flash-mark animation duration in cookbook.css.
(def ^:private visible-ms 1500)

(defonce ^:private *state (r/atom {:visible? false :flashes 0 :timer nil}))

(defn flash! []
  (when-let [timer (:timer @*state)]
    (js/clearTimeout timer))
  (let [timer (js/setTimeout #(swap! *state assoc :visible? false :timer nil) visible-ms)]
    (swap! *state #(-> %
                       (assoc :visible? true :timer timer)
                       (update :flashes inc)))))

(defn indicator
  "Mounted once at the app root beside the overlays, for `recipe-modals/overlays`'
  own reason: `core/page-body` draws exactly one page, so a mark mounted inside the
  Recipe page would be a mark that only exists while that page is up. It happens
  that the only thing flashing it today *is* that page — but a fixed overlay that
  belongs to whichever page is up is root furniture, and putting it there now is
  what stops the second caller from having to move it."
  []
  (let [{:keys [visible? flashes]} @*state]
    (when visible?
      [:div#save-flash
       ;; The flash counter as :key remounts the mark, which is what restarts the
       ;; CSS animation when a save flashes while one is still on screen.
       [:span.save-flash-mark {:key flashes} "✓"]])))
