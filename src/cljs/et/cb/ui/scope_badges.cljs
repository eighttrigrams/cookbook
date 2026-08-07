(ns et.cb.ui.scope-badges
  "The Scope badge: one pill, for the two surfaces that wear it.

  A shelf card has worn them in its header since Scopes existed. A queue row wears
  them now, because triaging what the agents did means knowing what area a Recipe is
  about, and `this page doesnt show the scope badges yet … so i dont know for what
  the recipes are` is what he said about the page where that mattered most.

  Two surfaces, one look — which is why this is a namespace rather than a second
  `for` beside the first. `et.cb.ui.provenance` makes the same argument about the two
  places that name where a version came from: the fact is one fact, so a reader must
  not have to work out that two spellings of it mean the same thing.

  **What is deliberately not shared is the gesture.** Shift+clicking a badge on the
  shelf hides the Recipes filed under that Scope — see `views/recipes`, which says why
  that filter is negative-only and where it is undone. On a queue row the same gesture
  would set a filter whose entire effect is on a page he is not looking at, so the
  Inbox passes no handler and its tooltip promises nothing: a badge that did something
  invisible would read as a badge that does nothing. Hence `hint` and `on-click` — the
  caller says what its own badges do, and a surface with no gesture explains none."
  (:require [clojure.string :as str]))

(defn badges
  "The pills for `scopes`, wrapped in a span carrying the caller's `:class`.

  **Wrapped rather than returned as a seq.** A component whose return value *is* a
  seq is handed to React as a fragment whose children are the raw hiccup vectors —
  and a cljs vector is iterable, so React walks into one and tries to render
  `:span.scope-badge`, the keyword, as a child. A seq of children inside a hiccup
  vector is the shape reagent converts.

  The class stays the caller's because the *layout* is the surface's while the pill is
  not: `.card-scopes` is at most half of a card header, `.inbox-scopes` rides inside a
  queue row's title cell so a Recipe filed under three Scopes cannot push a button off
  the line. Only `.scope-badge` itself is the shared look, and it is shared in the
  stylesheet as well as here.

  The description is the tooltip — the one place a reader meets it outside the Scopes
  page — with `hint` appended when the surface has something to explain. `on-click`,
  when there is one, is called with the Scope's id and the event."
  [scopes {:keys [class hint on-click]}]
  [:span {:class class}
   (for [{:keys [id title description]} scopes]
     ^{:key id}
     [:span.scope-badge
      (cond-> {:title (cond-> (if (str/blank? description)
                                "A Scope this Recipe is filed under"
                                description)
                        hint (str " — " hint))}
        on-click (assoc :on-click #(on-click id %)))
      title])])
