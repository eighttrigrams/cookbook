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

  **Why the body is taken out of flow rather than given `overflow: hidden`.**
  Hiding the overflow does stop the wheel, and a scroller whose overflow turns
  hidden is forced back to the top — so the page behind would jump, and the
  scroll position it jumped from is not recoverable afterwards. Out of flow there
  is no in-flow content for the document to scroll at all, and `top` decides what
  the reader sees behind the surface. The position is remembered here and handed
  back as scroll on release.

  **Two ways to hold it, because the two surfaces want different things.**
  `lock-at-top!` is for a surface whose chrome is up in the top bar: the bar has
  to be on screen or the exit is not. `lock-in-place!` is for a dialog that
  covers the viewport anyway, where moving the page underneath it would be a jolt
  with nothing to show for it.

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

(defn- lock! [show-from]
  (when (= 1 (swap! holders inc))
    (let [y (.-scrollY js/window)
          style (.. js/document -body -style)
          ;; The scrollbar is about to go; paying its width back as padding is
          ;; what keeps the page from shifting sideways under the surface.
          gap (- (.-innerWidth js/window)
                 (.. js/document -documentElement -clientWidth))]
      (reset! held-at y)
      (set! (.-paddingRight style) (str gap "px"))
      (set! (.-position style) "fixed")
      (set! (.-top style) (str "-" (case show-from :top 0 y) "px"))
      ;; `left`/`right` because a fixed box with both auto shrink-wraps to its
      ;; content — the body is a centred 1100px column (`base.css`), and without
      ;; these it would collapse to the width of its widest line.
      (set! (.-left style) "0")
      (set! (.-right style) "0"))))

(defn lock-at-top!
  "Hold the page behind and show it **from the top**, so that the top bar — and
   with it the way off the surface — is where it can be seen and clicked."
  []
  (lock! :top))

(defn lock-in-place!
  "Hold the page behind exactly where the reader left it. For a dialog that
   covers the viewport, where scrolling the page underneath would be a jolt
   nobody asked for."
  []
  (lock! :here))

(defn unlock!
  "Hand the page back — to the last holder only — and put it back where it was."
  []
  (when (zero? (swap! holders dec))
    (let [style (.. js/document -body -style)]
      (set! (.-position style) "")
      (set! (.-top style) "")
      (set! (.-left style) "")
      (set! (.-right style) "")
      (set! (.-paddingRight style) "")
      ;; **After a layout read, and the read is used.** Clearing the styles above
      ;; does not reflow on its own, so a `scrollTo` here is clamped against the
      ;; collapsed scroll range and lands at 0 — the page at the top, which is
      ;; the thing being avoided. Reading the range forces the reflow, and
      ;; clamping to it is a truer scroll than asking for an offset the document
      ;; may have grown shorter than while the surface was up.
      (let [range (- (.. js/document -documentElement -scrollHeight)
                     (.-innerHeight js/window))]
        (.scrollTo js/window 0 (min @held-at (max range 0)))))))

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
