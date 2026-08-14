(ns et.cb.ui.views.new-recipe
  "The page a Recipe is made on — the shelf's compose form, moved to a surface of its
  own.

  *on the overview page, there is a whole section for creating a new cookbook recipe. i
  dont want that, i want that page to be about filtering. what we gonna do. at the top
  of the page the will be an \"Add\" button which takes you to a page which looks like
  when we go from the recipe Page page to edit.*

  **The first sentence is the rule and this page is its consequence.** The shelf is
  where Recipes are *found* — narrowed by a search, by the human filter, by two Scope
  filters — and a form for making one sat above all of that taking up the screen. So
  composing goes where every other thing that is about one Recipe already went: onto a
  surface of its own, reached by one button. That is the same rule the top bar's two
  slots were rebuilt on — the surface keeps what is about it — one level along.

  **It looks like `/recipe/<id>?edit=true` because it is made of that page's parts.**
  `recipe-fields/edit-fields` is the four inputs, shared rather than copied, and
  `core/left-slot` draws Save and Cancel in the bar exactly as it does for the editor.
  What this page adds is a heading and a Scope picker; what it takes away is everything
  that would be a claim about a Recipe that does not exist yet.

  **Three things render as nothing at all, and each because there is no such fact:**

  - **the badge row.** No version, no reads, no dates, nothing published. `header`
    draws six facts about a stored Recipe and every one of them would be a guess here.
  - **Show provenance.** There is no history to attribute lines to. The editor keys
    that button off `caution` being in the response, which is a key this page has no
    row to carry.
  - **Delete.** There is nothing to delete. `views.recipe/delete-action` explains why
    Delete is on the *editor* — a Recipe you have unsaved edits to is still a Recipe —
    and a Recipe that has never been saved is the case that argument does not cover.

  Rendered as nothing rather than as empty boxes, which is this run of work's most
  repeated lesson: `views.recipe/mutating-actions` made exactly that argument about a
  `div` with panel spacing and nothing in it, and was then deleted outright rather than
  emptied.

  **There is a heading, and it is the page's name rather than the Recipe's.** The
  editor puts the stored title in an `h1` above the title field, and argues the pair as
  *what is saved* over *what you are about to save*. Here nothing is saved yet, so the
  upper half would be blank — and a page of four empty fields with no statement of what
  Save will do is the one thing worse than a redundant heading. It says what the page
  is, once, and the title field says what the Recipe will be called.

  **The address is `/`, not `/recipe/new`** — `state/open-new-recipe` argues that: a
  Recipe that does not exist has no identity to put in a bar, and the address becomes
  `/recipe/<id>` on Save, which is the first moment it has one."
  (:require [et.cb.ui.recipe-fields :as recipe-fields]
            [et.cb.ui.state :as state]))

(defn new-recipe-page
  "The panel: a heading, the four fields, and the Scope picker under them.

  **The picker is here and it is the sentence that made the shelf's docstring false.**
  That file said the compose form *picks Scopes for a Recipe that does not exist yet,
  which is the one case that cannot be done on a page* — there is a page now, so the
  exception is gone and the general rule holds without one: filing happens on the
  surface that is about the Recipe. What is still true is the *other* half of that
  argument, which is why the picker sits here rather than saving as it is toggled:
  `views.recipe/scope-filing` files a Recipe per chip because there is a Recipe to
  file, and here there is nothing to PUT to until Save. So this one collects, like the
  compose form's did, and `add-recipe` sends the set with the fields in one create.

  **Under the fields rather than under the header**, which is where it differs from
  both other surfaces that draw it. On a Recipe's page the picker is under the header
  because it is part of what says which Recipe this is, and it saves immediately; here
  it is part of what is about to be *created*, so it belongs with the rest of the form
  — the last thing before Save, like the compose form had it.

  Owner-only, and that gate is `core/page-body`'s rather than a `when` in here: this is
  in `owner-only-pages`, so a signed-out client is sent to the shelf instead of being
  shown a form the API would refuse. The picker draws nothing for a visitor anyway,
  which is the coincidence that is not the gate."
  []
  [:div.recipe-page
   [:h1.recipe-page-title "New Recipe"]
   [recipe-fields/edit-fields
    {:fields (state/recipe-edit-fields)
     :on-change state/set-recipe-draft-field
     ;; **Enter saves, from any of the three short fields.** The compose form did and
     ;; the editor does not; on this page the compose form's habit is the one to keep,
     ;; because adding a Recipe quickly is what it was for. `edit-fields` never puts it
     ;; on the textarea, where Enter is a newline in the body being written.
     :on-enter state/save-new-recipe}]
   [recipe-fields/scope-picker
    {:selected (state/new-recipe-scopes)
     :on-toggle state/toggle-new-recipe-scope
     :class "new-recipe-filing"
     ;; The label is this surface's, as the shelf's filter row's is: the picker's
     ;; default says *Categories this Recipe is filed under*, and there is no Recipe
     ;; yet to be filed — what a chip means here is where this one will go.
     :label "Scopes"
     :label-title "Categories to file this Recipe under when you save it"}]])
