(ns et.cb.ui.page-lock
  "The page behind a surface, held still while the surface is up.

  **What this is for, in the words it was reported in.** *when i scroll with my
  mouse in the top section where the back button sits, the old page for the tray
  somehow hides still there and is scrollable* — the version viewer is a fixed
  overlay starting below the top bar (`.diff-overlay`, `top: 24 + 32 + 24`), the
  page it was opened from is still in flow underneath, and a wheel anywhere over
  the surface scrolled *that*. Two things came of it, and the second is the worse
  one:

  - the tray's own content slid up into the band above the overlay, which is why
    the surface looked, in his words, weirdly constructed; and
  - the top bar went with it — and the bar is where the way out lives
    (`views.diff/back-to-origin`, in `core/left-slot`), so scrolling the page
    behind a modal surface could carry its only exit off the screen.

  `inert` does not help with either: it takes the keyboard and the pointer off
  what is behind, and says nothing about scrolling.

  **`overflow: hidden` on the root, and nothing else.** The document stops being
  scrollable and **keeps the position it was at** — measured, because the first
  version of this was built on the opposite belief. It took the body out of flow
  and offset it by the scroll position instead, on the grounds that hiding the
  overflow forces a scroller back to the top; that is not true, and the
  measurement it came from was a bad one (a test harness had scrolled the page to
  0 before the lock ever ran, in a different app). Taking the body out of flow
  cost two things that were then visible on screen: an html element with no
  in-flow content stops the body's background reaching the canvas, so the band
  above the overlay went **white** — *for the navbar all of a sudden white where
  white is nowhere else used* — and the body, being a centred `max-width` column,
  no longer centred once it was positioned. Hiding the overflow moves nothing and
  paints nothing differently.

  **Two ways to hold it, because the two surfaces want different things.**
  `lock-at-top!` is for a surface whose chrome is up in the top bar: the bar has
  to be on screen or the exit is not, so the page is put at the top first and its
  place given back on release. `lock-in-place!` is for a dialog that covers the
  viewport anyway, where moving the page underneath it would be a jolt with
  nothing to show for it.

  A counter, not a flag: the Inbox's dismiss confirmation opens *over* the
  version viewer (`.modal-backdrop` at 30 over `.diff-overlay` at 25 — the
  stylesheet argues that), so two locks can be up at once. The first takes the
  page and the last hands it back; a flag would have let the confirmation's close
  give the page back while the surface behind it was still up."
  (:require [reagent.core :as r]))

;; How many surfaces are holding the page, and where it was when the first of
;; them took it.
(defonce ^:private holders (atom 0))
(defonce ^:private held-at (atom 0))

(defn- lock! [to-top?]
  (when (= 1 (swap! holders inc))
    (let [style (.. js/document -documentElement -style)
          ;; The scrollbar is about to go; paying its width back as padding is
          ;; what keeps the page from shifting sideways under the surface.
          gap (- (.-innerWidth js/window)
                 (.. js/document -documentElement -clientWidth))]
      (reset! held-at (.-scrollY js/window))
      ;; Before the overflow, not after: with the document no longer scrollable
      ;; there is nothing left to scroll to the top.
      (when to-top? (.scrollTo js/window 0 0))
      (set! (.-paddingRight style) (str gap "px"))
      (set! (.-overflow style) "hidden"))))

(defn lock-at-top!
  "Hold the page behind and show it **from the top**, so that the top bar — and
   with it the way off the surface — is where it can be seen and clicked."
  []
  (lock! true))

(defn lock-in-place!
  "Hold the page behind exactly where the reader left it. For a dialog that
   covers the viewport, where scrolling the page underneath would be a jolt
   nobody asked for."
  []
  (lock! false))

(defn unlock!
  "Hand the page back — to the last holder only — and put it back where it was.

   The scroll is restored unconditionally rather than only for a `lock-at-top!`:
   `lock-in-place!` did not move it, so setting it to where it already is costs
   nothing, and one exit path is one fewer thing to keep in step with two
   entrances."
  []
  (when (zero? (swap! holders dec))
    (let [style (.. js/document -documentElement -style)]
      (set! (.-overflow style) "")
      (set! (.-paddingRight style) "")
      (.scrollTo js/window 0 @held-at))))

(def while-mounted
  "Holds the page for as long as it is in the tree, and draws nothing.

   For a surface that is plain hiccup with no lifecycle of its own — the four
   `.modal-backdrop`s. A surface that already has a `:ref` doing mount and
   unmount work calls `lock-at-top!` / `unlock!` from there instead, beside the
   rest of what it takes over (`views.diff/surface-ref`).

   A `def` and not a `defn`: `r/create-class` must be evaluated once, or each
   render would hand reagent a new class and the remount would release a page it
   had just taken."
  (r/create-class
   {:display-name "page-lock"
    :component-did-mount (fn [_] (lock-in-place!))
    :component-will-unmount (fn [_] (unlock!))
    :reagent-render (fn [] nil)}))
