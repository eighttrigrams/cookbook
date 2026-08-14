// The check suite for a Recipe's own page and the address it lives at.
//
// **Phases, because several of these properties cannot be observed from inside one
// `evaluate`.** A cold load replaces the JS context, so a check that the *server*
// route works has to run in the context that load created — and there are two such
// loads now, one per mode of the page. Being signed out is a state the page has to be
// put into first. Back and Forward are the opposite case and belong inside one phase:
// nothing there ever leaves the document — every move is a `pushState` — so
// `history.back()` fires a `popstate` in the same context and can be waited on like
// any other consequence.
//
// The file evaluates to an object of phases. Run one at a time:
//
//     (<contents of this file>).shelf()       — signed in, on /
//     (<contents of this file>).coldLoad()    — after loading /recipe/<id> fresh
//     (<contents of this file>).coldEdit()    — after loading /recipe/<id>?edit=true
//     (<contents of this file>).signedOut()   — signed in, on /; it signs itself out
//     (<contents of this file>).save()        — a version-making save; builds its own
//     (<contents of this file>).provenance()  — the provenance view; needs its seed
//     (<contents of this file>).filing()      — the Scope picker; needs the same seed
//     (<contents of this file>).draftProvenance([token])
//                                             — the draft preview; builds its own two
//     (<contents of this file>).clampedBody() — the shelf's abbreviation; builds its own
//
// Each returns `{passed, of, failed, results, notes}`. See README.md in this
// directory for the run and for the mutation each check was watched to fail
// against, and for what this suite reads out of the dev database.
//
// **Four phases write nothing.** `SUBJECT` is read and the only trace those reads
// leave is the `view_count` they move — which is the number the shelf is ranked by,
// and moving it is what reading a Recipe *is*. The editor is opened, prefilled and
// left by Cancel, never saved. The ones that write say so at length and none of them
// writes to anything of his: `filing()` files and unfiles the seeded `CHECK-PROV`,
// `save()` — the only phase that makes a *version* — builds a `CHECK-SAVE` of its own
// to make it on, because a save moves the ladder of cautions the provenance phase
// reads, and `draftProvenance()` and `clampedBody()` each build the fixtures their
// property needs and cannot borrow. Every one of those is a CHECK- Recipe, which is
// what `cleanup.py` looks for.
//
// Every check is isolated and every evidence object is lazy, for the reason
// `checks.js` gives at length: a mutation that makes one selector return null must
// cost that check a column and not end the run.
(() => {
  // The Recipe these checks are about: published, and it has a body. Both matter —
  // the body is what check 1 compares across the two surfaces, and the publish
  // latch is what makes check 4a the visitor's case it claims to be rather than a
  // Recipe nobody but the owner could ever have reached.
  const SUBJECT = 'Sourdough starter';

  // The provenance phase's Recipe, and the one thing in this file that has to be
  // made rather than found: a body with a line the API calls 1.00, a line it calls
  // 0.00, a line strictly between them and a trailing empty one. `provenance-seed.py`
  // builds it and says why each of those four is needed; `cleanup.py` removes it.
  const MIXED = 'CHECK-PROV';

  const wait = ms => new Promise(r => setTimeout(r, ms));
  const until = async (fn, ms = 5000) => {
    const t0 = Date.now();
    while (Date.now() - t0 < ms) { let v; try { v = fn(); } catch (e) { v = null; }
                                  if (v) return v; await wait(50); }
    return null;
  };
  const st = window.et.cb.ui.state, c = window.cljs.core;
  // `urls` and not `url`: `shelf()` binds a local `url` to the Recipe's address, and
  // a module alias it shadowed would be a very quiet failure.
  const urls = window.et.cb.ui.url;
  const kw = k => c.keyword(k);
  const stateGet = k => c.clj__GT_js(c.get(c.deref(st._STAR_app_state), kw(k)));
  const path = () => location.pathname;
  const page = () => document.querySelector('.recipe-page');
  const shelf = () => document.querySelector('.shelf');
  const cards = () => [...document.querySelectorAll('.card')];
  const cardFor = t => cards().find(x => x.textContent.includes(t));
  const rowFor = t => (stateGet('recipes') || []).find(r => (r.title || '').includes(t));
  const text = sel => document.querySelector(sel)?.textContent?.trim();
  // A missing root throws rather than falling back to `document`, for the reason
  // `checks.js` learnt the hard way: a click that lands on some *other* card's
  // button is a check that goes green about the wrong Recipe.
  const clickIn = (root, sel, label) => {
    if (!root) throw new Error('nothing to click in, for: ' + sel);
    const el = [...root.querySelectorAll(sel)]
      .find(e => !label || e.textContent.trim() === label);
    if (!el) throw new Error('nothing to click: ' + sel + (label ? ' [' + label + ']' : ''));
    el.click();
    return el;
  };
  // What the top bar is holding, by class and by label. A **set** and not a
  // presence test per control, because the claim these checks make is about what is
  // and is not up there — a fourth widget appearing on a focused surface has to
  // redden something.
  const barSlots = sel => [...document.querySelectorAll(sel + ' > *')]
    .map(e => (typeof e.className === 'string' && e.className ? e.className.split(' ')[0]
                                                              : e.tagName.toLowerCase())
              + (e.textContent ? ':' + e.textContent.trim() : ''));
  // Typing into a controlled input. React tracks the value on the node, so setting
  // `.value` directly is not seen — the prototype's setter plus an `input` event is
  // what a keystroke looks like from in here.
  const type = (el, v) => {
    if (!el) throw new Error('nothing to type into');
    Object.getOwnPropertyDescriptor(el.constructor.prototype, 'value').set.call(el, v);
    el.dispatchEvent(new Event('input', {bubbles: true}));
  };
  // A `caution` answer spread out per line. The ranges are 1-based and inclusive on
  // both ends, so this is the one place in the file that arithmetic lives — read by
  // the provenance phase, which compares it against what the view drew, and by
  // `save()`, which compares one of them against another.
  const perLine = caution => {
    const out = [];
    for (const r of ((caution || {}).ranges || []))
      for (let n = r.from; n <= r.to; n++) out[n - 1] = r.caution;
    return out;
  };

  const runner = () => {
    const R = [], notes = [];
    const check = async (name, fn) => {
      try {
        const {pass, evidence} = await fn();
        R.push({name, pass: !!pass, evidence});
      } catch (e) {
        R.push({name, pass: false, evidence: {threw: String((e && e.stack) || e)}});
      }
    };
    const step = async (what, fn) => {
      try { await fn(); } catch (e) { notes.push('step failed: ' + what + ' — ' + e); }
    };
    const done = extra => ({passed: R.filter(r => r.pass).length, of: R.length,
                            failed: R.filter(r => !r.pass).map(r => r.name),
                            results: R, notes, ...extra});
    return {check, step, done, notes};
  };

  return {

    // ---- phase one: signed in, starting on the shelf ------------------------
    // Checks 1, 3 and 5. Leaves the browser on the Recipe's page, which is where
    // `coldLoad` wants it.
    shelf: async () => {
      const {check, step, done, notes} = runner();
      // **Refuse to run from anywhere but the shelf.** Not ceremony: `:recipes` is
      // in the atom on every page, so the `rowFor` guard below passes on a Recipe
      // page — and then `cardFor` finds no card, the clicks land nowhere, and
      // checks 1 and 6 go red for a reason that has nothing to do with the app. A
      // suite run from the wrong place must say so rather than produce two false
      // reds and three greens.
      if (!shelf()) throw new Error('this phase starts on the shelf, and the page is at '
                                    + path() + ' — go to / and run it again');
      const subject = rowFor(SUBJECT);
      if (!subject) throw new Error('no Recipe named ' + SUBJECT + ' on the shelf — see README');
      const url = '/recipe/' + subject.id;

      // The body as the *card* renders it, so check 1 can assert the two surfaces
      // show the same text rather than only that the page shows some. Expanding is
      // what fetches a body at all; the listing never carried one.
      await step('expand the card', () => clickIn(cardFor(SUBJECT), '.card-header'));
      await until(() => cardFor(SUBJECT)?.querySelector('.card-body'));
      // **A card abbreviates a long body, so the whole of it has to be asked for
      // before the two surfaces can be compared.** `SUBJECT` is short and no See
      // more appears on it today, which is exactly why this is here rather than
      // left to be discovered: point the constant at a longer Recipe — which the
      // README invites, and only asks for published-and-has-a-body — and check 1
      // would go red on a card that is behaving correctly. What it compares is the
      // body, not how much of it a shelf shows; the abbreviation is checks 37-40's.
      if (cardFor(SUBJECT)?.querySelector('.see-more')) {
        await step('ask the card for the whole body',
                   () => clickIn(cardFor(SUBJECT), '.see-more'));
        await until(() => !cardFor(SUBJECT)?.querySelector('.see-more'));
        notes.push('the subject is long enough to be abbreviated on the card; '
                   + 'pressed See more before comparing the two surfaces');
      }
      const cardBody = cardFor(SUBJECT)?.querySelector('.card-body')?.textContent?.trim();
      // and the header facts it wears, for check 6
      const BADGES = '.published-badge, .pending-badge, .scope-badge, .version-badge,' +
                     ' .source-badge, .views-badge, .card-date';
      const badgesIn = root => [...(root?.querySelectorAll(BADGES) || [])]
        .map(e => e.className.split(' ')[0]).sort();
      const cardBadges = badgesIn(cardFor(SUBJECT)?.querySelector('.card-header'));
      // and what the top bar holds *here*, for check 22 — captured on the shelf
      // because that is the surface it is being compared against, the way check 6
      // captures the card's badges before it leaves for the page
      const barOnTheShelf = {left: barSlots('.top-bar-left'),
                             right: barSlots('.top-bar-right')};
      await step('collapse it again', () => clickIn(cardFor(SUBJECT), '.card-header'));

      // 13. **the card's footer is one button.** Publish, Edit, Versions and Delete
      //     were beside Page and are on the Recipe's page now — *all the buttons go
      //     to that page then* — so a footer that grew a fifth button back is this
      //     change coming undone. Every card and not only the subject's: the footer
      //     is `card`'s and one Recipe's would pass while the rest regressed.
      //
      //     It runs before check 1 because check 1 navigates. Numbers here are names
      //     and not positions, which the README says at length.
      await check('13 a card carries Page and nothing else', () => {
        const perCard = cards().map(c => ({
          title: c.querySelector('.card-title')?.textContent?.trim().slice(0, 30),
          buttons: [...c.querySelectorAll('.card-actions button')].map(b => b.textContent.trim())}));
        return {pass: perCard.length > 0
                      && perCard.every(c => c.buttons.length === 1 && c.buttons[0] === 'Page'),
                evidence: {loggedIn: stateGet('logged-in?'), perCard}};
      });

      // 1. the one button navigates, and what it lands on is the Recipe
      await check('1 the Page button opens the Recipe at its own address', async () => {
        clickIn(cardFor(SUBJECT), '.card-actions button', 'Page');
        await until(() => page() && document.querySelector('.recipe-page-body'));
        const title = text('.recipe-page-title');
        const body = text('.recipe-page-body');
        return {pass: path() === url && !!page() && !shelf()
                      && (title || '').includes(SUBJECT)
                      && !!cardBody && body === cardBody,
                evidence: {path: path(), expectedPath: url, title,
                           shelfStillRendered: !!shelf(),
                           bodyMatchesTheCard: body === cardBody,
                           bodyOnThePage: body, bodyOnTheCard: cardBody}};
      });

      // 6. **the page says everything the card says about this Recipe.** Written
      //    because the first version of this page did not: the two version counts
      //    are aggregated by the *listing* and `GET /api/recipes/:id` does not
      //    carry them, so `source-split` — which reads a missing count as a fact it
      //    has not been told — rendered nothing at all, and the page quietly showed
      //    five of the card's six header facts. Nothing failed and nothing said so.
      //    Comparing the two surfaces is the only assertion that could have caught
      //    it, because each of them on its own looked complete.
      //
      //    **The Scopes are the one fact the two surfaces say differently, and this
      //    check went red when they started to.** The card wears them as badges
      //    because a card cannot do anything about them; the page draws the picker
      //    that *files* them, and drawing both would be the same fact twice with
      //    only one of them able to be wrong. So `scope-badge` comes out of the
      //    comparison and the picker is asserted in its place — a page that lost
      //    the filing altogether still reddens this, which is what keeps the
      //    exclusion from being a hole.
      await check('6 the page wears the same header facts as the card', () => {
        const onPage = badgesIn(document.querySelector('.recipe-page-badges'));
        const expected = cardBadges.filter(b => b !== 'scope-badge');
        const missing = expected.filter(b => !onPage.includes(b));
        const filing = document.querySelector('.scope-picker.recipe-page-filing');
        return {pass: expected.length >= 5 && missing.length === 0
                      && onPage.includes('source-badge')
                      && !!filing,
                evidence: {onTheCard: cardBadges, comparedWith: expected,
                           onThePage: onPage, missing,
                           scopesAsAControl: !!filing,
                           chips: [...(filing?.querySelectorAll('.scope-chip') || [])]
                             .map(c2 => c2.textContent.trim()
                                        + (c2.classList.contains('on') ? ' (on)' : ''))}};
      });

      // 22. **on a focused surface the app's widgets go and the theme toggle stays**,
      //     and the left slot holds the page's own way out instead of the brand. *a
      //     couple of widgets on the right hand side, of which only dark light mode is
      //     shown in every view.*
      //
      //     Asserted as the **set** on both sides, against what the same bar held on
      //     the shelf a moment ago, so that a fourth widget appearing here reddens
      //     this rather than passing three presence tests. The shelf's own set is
      //     asserted too — a bar that lost the selectors *everywhere* would otherwise
      //     look like a pass.
      //
      //     **`SUBJECT` is published, which is why the right-hand side is still one
      //     thing.** The corner is no longer only ever the toggle — *In the Page view,
      //     put the Publish button in the top right, to the left of the dark mode
      //     switcher* — but a published Recipe has no Publish to offer and the
      //     container it would sit in is not drawn at all. So this check is now about
      //     the *page selectors* being gone, and it says which case it is looking at
      //     rather than reading a count as if it were the rule: `barActions()` is where
      //     the unpublished case is, on a fixture of its own.
      //
      //     Sign in / Sign out is not in either list, and cannot be: dev runs with
      //     `:dangerously-skip-logins?`, so `auth-required?` is false and that button
      //     is never rendered on any surface. The rule that it goes with the
      //     selectors is asserted in check 23's evidence by making the condition, the
      //     way check 12 does for `caution`.
      await check('22 a Recipe page keeps the theme toggle and no page selectors', () => {
        const here = {left: barSlots('.top-bar-left'), right: barSlots('.top-bar-right')};
        const selectorsHere = ['.inbox-toggle', '.scopes-toggle', '.settings-toggle']
          .filter(s => !!document.querySelector(s));
        return {pass: !!page()
                      && subject.published === 1
                      && here.right.length === 1
                      && here.right[0].startsWith('dark-mode-toggle')
                      // published, so no surface action and no empty box for one
                      && !document.querySelector('.recipe-publish')
                      && !document.querySelector('.top-bar-actions')
                      && selectorsHere.length === 0
                      // the slot holds the reading's three and no brand
                      && here.left.length === 3
                      && here.left[0].startsWith('secondary')       // ← Shelf
                      && !document.querySelector('.top-bar-left .brand')
                      // **The shelf's half is a guard against a vacuous comparison
                      // and not a census of the bar**, which is a correction rather
                      // than a loosening: it read `=== 4` — three page selectors and
                      // the theme toggle — and the Deleted page made four selectors,
                      // so this check went red the day 🗑 joined the bar and stayed
                      // red for a decision made on purpose. A count here says
                      // nothing about a Recipe page, which is what the check is
                      // about; what it needs is that the shelf had *more* up there
                      // than the page does, so that "narrowed to the toggle" is a
                      // narrowing. The exactness stays where the claim is, on the
                      // page's own slots above.
                      && barOnTheShelf.right.length > here.right.length
                      && barOnTheShelf.right.some(s => s.startsWith('dark-mode-toggle'))
                      && barOnTheShelf.left.some(s => s.startsWith('brand')),
                evidence: {onTheShelf: barOnTheShelf, onTheRecipePage: here,
                           selectorsStillHere: selectorsHere,
                           subjectPublished: subject.published === 1,
                           publishOffered: !!document.querySelector('.recipe-publish'),
                           actionsContainerDrawn: !!document.querySelector('.top-bar-actions')}};
      });

      // 3. Back and Forward. Nothing reloads — every move so far was a pushState —
      //    so the popstate handler is what has to do the work, and this is the only
      //    phase that can see it happen.
      await check('3a Back returns to the shelf, and the bar says so', async () => {
        history.back();
        await until(() => shelf() && path() === '/');
        return {pass: path() === '/' && !!shelf() && !page(),
                evidence: {path: path(), shelfRendered: !!shelf(),
                           recipePageStillRendered: !!page(),
                           page: stateGet('page')}};
      });
      // **It asserts where it started, and that is not ceremony.** Without it this
      // check passes for the wrong reason the moment 3a fails: with no popstate
      // handler the browser never left the Recipe page, so 'Forward lands on the
      // Recipe' is true of a page that never moved — one dead listener, two checks,
      // and only one of them red.
      await check('3b Forward returns to the Recipe, at the same address', async () => {
        const before = {path: path(), shelf: !!shelf(), page: !!page()};
        history.forward();
        // On `.recipe-page-title` and not on `.recipe-page`, which is the house
        // rule about waiting on the *visible consequence* being applied one level
        // finer. The page's frame — the panel and its Back button — is rendered
        // while the status is still `:loading`, so waiting on it returns before the
        // Recipe has arrived and the title assertion below reads `undefined`. This
        // check went red once for exactly that and the app was fine.
        await until(() => document.querySelector('.recipe-page-title') && path() === url);
        return {pass: before.shelf && !before.page
                      && path() === url && !!page() && !shelf()
                      && (text('.recipe-page-title') || '').includes(SUBJECT),
                evidence: {startedOnTheShelf: before, path: path(), expectedPath: url,
                           title: text('.recipe-page-title'),
                           shelfRendered: !!shelf(),
                           recipePageId: stateGet('recipe-page-id'),
                           status: stateGet('recipe-page-status')}};
      });

      // 5. the top bar leaves the page, and the bar stops naming a Recipe. There is
      //    one addressable thing in this app; everything else is `/`, and a page
      //    that changed under an address that did not would be the whole point of
      //    this change undone.
      //
      //    **Its mechanism changed and its point did not.** It used to press
      //    `.inbox-toggle` here, twice, and that button is not on a Recipe page any
      //    more — the page selectors were taken off it deliberately, so the route
      //    this was asserting no longer exists. The route that replaced it is the
      //    left slot: `← Shelf` stands where the brand stands everywhere else, and
      //    it is now the only way out of the page that the bar offers. Which makes
      //    this check more nearly about its own name than it was.
      await check('5 a top-bar button leaves the page and puts / back in the bar', async () => {
        const before = {path: path(), recipePage: !!page(),
                        inTheSlot: text('.top-bar-left'),
                        // the button this used to press, gone from here on purpose
                        inboxToggle: !!document.querySelector('.inbox-toggle')};
        clickIn(document.querySelector('.top-bar-left'), '.recipe-page-back');
        await until(() => shelf() && path() === '/');
        return {pass: before.recipePage && before.inboxToggle === false
                      && path() === '/' && !!shelf() && !page()
                      // and the slot is the brand again, which is the other half of
                      // the same move
                      && !!document.querySelector('.top-bar-left .brand'),
                evidence: {onThePage: before,
                           afterTheSlotsButton: {path: path(), shelf: !!shelf(),
                                                 recipePage: !!page(),
                                                 inTheSlot: text('.top-bar-left')}}};
      });

      // and back onto the Recipe page, which is where 16 and 17 start and where the
      // next phase wants the browser left.
      //
      // **Waited on the reading and not merely on the panel**, which is the house rule
      // about the visible consequence and it cost check 14 a red: `.recipe-page` exists
      // while the detail is still in flight, so `shot()` read a panel that had its
      // header and not yet its Delete — but only on the *first* run after a load, since
      // a second run finds the row cached and paints it in one frame. An intermittent
      // red about a control that is there.
      await step('go back to the Recipe page', () =>
        clickIn(cardFor(SUBJECT), '.card-actions button', 'Page'));
      await until(() => page() && document.querySelector('.recipe-page-body'), 8000);

      // 14. **the four actions are reachable, across three containers.** Still the
      //     other half of 13 — stripping the card's footer would have made all four
      //     unreachable, since it was the only caller of the four `state/start-*` fns —
      //     and this is its **fourth** revision: once when they moved off the card, once
      //     when Edit and Versions moved into the slot, once when Delete went to the
      //     bottom right, and now that Publish has gone up into the bar's *other* slot.
      //
      //     The shape was built for exactly this: **set equality per container, in both
      //     modes.** A check that counted one container would go green with another
      //     empty, which is the unreachability it exists to catch; a check that only
      //     summed them would miss a control appearing somewhere absurd. This revision
      //     is the one that shows the shape earning its keep — a container was swapped
      //     for a different container and only the table below had to change.
      //
      //       container                 reading                          editing
      //       the bar, left slot        ← Shelf · Edit · Versions        Save · Cancel
      //       the bar, right slot       Publish, absent once published   absent
      //       panel, bottom right       Delete                           Delete
      //       panel, under the header   — nothing, and no container —
      //
      //     **The panel's own actions row is gone rather than empty**, which is the
      //     assertion this check gained: *In the Page view, put the Publish button in
      //     the top right, to the left of the dark mode switcher.* Publish was the last
      //     thing in `.recipe-page-actions`, so the row went with it — and a row left
      //     standing with nothing in it keeps the panel's spacing and reads as a
      //     rendering bug nobody can name, which is the leftover this run of work has
      //     met six times. Asserted as the *absence of the element*, so putting the
      //     container back empty reddens this even though every label would still be
      //     in the right place.
      //
      //     **Publish is absent while editing, and that is not the order's table.** It
      //     was never on the edit page and it is deliberately not put there now that
      //     the bar is where it lives: publishing is a one-way latch, and pressing it
      //     over a draft that has not been saved would make a Recipe public in a state
      //     its own editor disagrees with. The reading is where a Recipe is what it
      //     says it is, and that is where it can be published.
      //
      //     **That line of the table is vacuous here and is asserted in `barActions()`
      //     41 instead**, which is a limit of this phase and not of the rule: `SUBJECT`
      //     is published, so the corner is empty in both modes whatever the mode gate
      //     says, and a run with `(not recipe-page-edit?)` deleted from
      //     `core/surface-actions` leaves all thirteen checks here green. The empty
      //     `barRight` below is still worth asserting — it is what a Publish appearing
      //     on a *published* Recipe would redden — but it is not the mode's evidence.
      //
      //     **It sits after 3a/3b/5 because it navigates.** Entering the editor and
      //     cancelling out pushes two history entries, and those three read a stack of
      //     exactly [/, /recipe/<id>]. Put back above them it reddens 3a and then eight
      //     checks in a row, which is how this was found — the same constraint 16 and
      //     17 already carry.
      await check('14 the four actions are reachable, set per container and per mode', async () => {
        const labelsIn = sel => [...document.querySelectorAll(sel + ' button')]
          .map(b => b.textContent.trim());
        const published = subject.published === 1;
        const shot = () => ({slot: labelsIn('.top-bar-left'),
                             barRight: labelsIn('.top-bar-actions'),
                             underTheHeader: !!document.querySelector('.recipe-page-actions'),
                             bottomRight: labelsIn('.recipe-page-delete'),
                             danger: [...document.querySelectorAll('.recipe-page button.danger')]
                               .map(b => b.textContent.trim()),
                             deleteIsLast: document.querySelector('.recipe-page')
                                             .lastElementChild
                                           === document.querySelector('.recipe-page-delete')});
        const reading = shot();
        clickIn(document.querySelector('.top-bar-left'), 'button', 'Edit');
        await until(() => document.querySelector('.recipe-page-edit'));
        const editing = shot();
        clickIn(document.querySelector('.top-bar-left'), '.recipe-edit-cancel');
        await until(() => document.querySelector('.recipe-page-body'));

        const expectedBarRight = published ? [] : ['Publish'];
        const allFour = ['Publish', 'Edit', 'Versions', 'Delete'].filter(l =>
          published && l === 'Publish'
            ? true
            : reading.slot.concat(reading.barRight, reading.bottomRight).includes(l));
        return {pass: reading.slot.join(',') === '← Shelf,Edit,Versions'
                      && reading.barRight.join(',') === expectedBarRight.join(',')
                      && !reading.underTheHeader
                      && reading.bottomRight.join(',') === 'Delete'
                      && reading.danger.join(',') === 'Delete'
                      && reading.deleteIsLast
                      && editing.slot.join(',') === 'Save,Cancel'
                      && editing.barRight.length === 0
                      && !editing.underTheHeader
                      && editing.bottomRight.join(',') === 'Delete'
                      && editing.danger.join(',') === 'Delete'
                      && editing.deleteIsLast
                      && allFour.length === 4,
                evidence: {reading, editing, expectedBarRight, published,
                           publishAbsentWhileEditing: editing.barRight.length === 0,
                           noActionsRowInThePanel:
                             !reading.underTheHeader && !editing.underTheHeader,
                           allFourReachableInTheReading: allFour}};
      });

      // 16. **Edit is a navigation now, not an overlay.** It was a modal, and the
      //     modal is gone: *instead of an edit modal, lets go to a separate page,
      //     with ?edit=true query param*. So the assertion is about the **address**
      //     as much as the form — a version of this that rendered the fields without
      //     moving the bar would look identical on screen and would not be linkable,
      //     reloadable or leavable by Back, which is the whole reason it is a page.
      //
      //     After 3a/3b/5 and not before, on purpose: those three read a history
      //     stack of exactly [/, /recipe/<id>], and an editor pushed into the middle
      //     of it would make `history.back()` land somewhere else and redden them for
      //     a reason that has nothing to do with what they assert.
      await check('16 Edit goes to ?edit=true, prefilled, and Cancel comes back', async () => {
        const editUrl = url + '?edit=true';
        clickIn(document.querySelector('.top-bar-left'), 'button', 'Edit');
        const form = await until(() => document.querySelector('.recipe-page-edit'));
        const inEditor = {
          bar: path() + location.search,
          flag: stateGet('recipe-page-edit?'),
          title: form?.querySelector('.recipe-page-edit-title')?.value,
          fields: [...(form?.querySelectorAll('input, textarea') || [])].length,
          // the two things that must NOT be here: the filing is the reading's, and
          // the modal's `version N` subtitle is the header's badge now
          picker: !!document.querySelector('.scope-picker'),
          subtitle: !!document.querySelector('.modal-subtitle'),
          modal: !!document.querySelector('.modal-backdrop'),
          versionBadgeInTheHeader: text('.version-badge'),
          readingGone: !document.querySelector('.recipe-page-body')};
        // Cancel is in the top bar's left slot now, not under the form
        clickIn(document.querySelector('.top-bar-left'), '.recipe-edit-cancel');
        await until(() => document.querySelector('.recipe-page-body'));
        const afterCancel = {bar: path() + location.search,
                             flag: stateGet('recipe-page-edit?'),
                             formGone: !document.querySelector('.recipe-page-edit')};
        return {pass: inEditor.bar === editUrl && inEditor.flag === true
                      && (inEditor.title || '').includes(SUBJECT)
                      && inEditor.fields === 4
                      && !inEditor.picker && !inEditor.subtitle && !inEditor.modal
                      && !!inEditor.versionBadgeInTheHeader && inEditor.readingGone
                      && afterCancel.bar === url && afterCancel.flag === false
                      && afterCancel.formGone,
                evidence: {expectedEditUrl: editUrl, inEditor, afterCancel}};
      });

      // 17. **and it is in the history, which is what makes it a page and not a
      //     mode.** Back out of the editor is the gesture a reader will use before
      //     they find Cancel, and it only works because `sync-from-url!` re-derives
      //     the whole view from the address — the same function 3a and 3b are about,
      //     one level finer. A `?edit=true` that were only pushed and never read back
      //     leaves this red while 16 stays green.
      await check('17 Back leaves the editor and Forward returns to it', async () => {
        const editUrl = url + '?edit=true';
        clickIn(document.querySelector('.top-bar-left'), 'button', 'Edit');
        await until(() => document.querySelector('.recipe-page-edit'));
        history.back();
        await until(() => document.querySelector('.recipe-page-body') && path() + location.search === url);
        const afterBack = {bar: path() + location.search, flag: stateGet('recipe-page-edit?'),
                           reading: !!document.querySelector('.recipe-page-body'),
                           form: !!document.querySelector('.recipe-page-edit')};
        history.forward();
        await until(() => document.querySelector('.recipe-page-edit'));
        const afterForward = {bar: path() + location.search, flag: stateGet('recipe-page-edit?'),
                              form: !!document.querySelector('.recipe-page-edit'),
                              title: document.querySelector('.recipe-page-edit-title')?.value};
        clickIn(document.querySelector('.top-bar-left'), '.recipe-edit-cancel');
        await until(() => document.querySelector('.recipe-page-body'));
        return {pass: afterBack.bar === url && afterBack.flag === false
                      && afterBack.reading && !afterBack.form
                      && afterForward.bar === editUrl && afterForward.flag === true
                      && afterForward.form
                      && (afterForward.title || '').includes(SUBJECT)
                      && path() + location.search === url,
                evidence: {afterBack, afterForward, expectedEditUrl: editUrl,
                           leftOn: path() + location.search}};
      });

      // 23. **the slot holds the mode's controls, and only them.** Reading: `← Shelf`,
      //     Edit and Versions — *edit and versions can now move to the top, next to
      //     the back to shelf button*. Editing: Save and Cancel, and *the back button
      //     should not be there*. The absent `← Shelf` is the
      //     half worth asserting: leaving an editor is a question with two answers,
      //     and a third button quietly meaning one of them is the one a hurried
      //     reader presses.
      //
      //     Both directions, in one check, because either half alone passes for a bar
      //     that never changes: a slot stuck on `← Shelf` fails the second reading and
      //     a slot stuck on Save fails the first.
      await check('23 the slot is ← Shelf while reading and Save+Cancel while editing',
        async () => {
          const slotLabels = () => [...document.querySelectorAll('.top-bar-left button')]
            .map(b => b.textContent.trim());
          const reading = {slot: slotLabels(),
                           back: !!document.querySelector('.recipe-page-back'),
                           save: !!document.querySelector('.recipe-edit-save'),
                           cancel: !!document.querySelector('.recipe-edit-cancel')};
          clickIn(document.querySelector('.top-bar-left'), 'button', 'Edit');
          await until(() => document.querySelector('.recipe-page-edit'));
          const editing = {slot: slotLabels(),
                           back: !!document.querySelector('.recipe-page-back'),
                           save: !!document.querySelector('.recipe-edit-save'),
                           cancel: !!document.querySelector('.recipe-edit-cancel'),
                           saveEnabled: !document.querySelector('.recipe-edit-save').disabled,
                           // and no actions row left under the form
                           actionsUnderTheForm:
                             !!document.querySelector('.recipe-page-edit-actions')};
          clickIn(document.querySelector('.top-bar-left'), '.recipe-edit-cancel');
          await until(() => document.querySelector('.recipe-page-body'));
          const backToReading = {slot: slotLabels(),
                                 back: !!document.querySelector('.recipe-page-back')};
          return {pass: reading.back && !reading.save && !reading.cancel
                        && reading.slot.join(',') === '← Shelf,Edit,Versions'
                        && editing.save && editing.cancel && !editing.back
                        && editing.slot.join(',') === 'Save,Cancel'
                        // and the ways of looking at it go with the way out: an
                        // editor is not a place to press Versions from
                        && !editing.slot.includes('Edit')
                        && !editing.slot.includes('Versions')
                        && editing.saveEnabled && !editing.actionsUnderTheForm
                        && backToReading.back
                        && backToReading.slot.join(',') === '← Shelf,Edit,Versions',
                  evidence: {reading, editing, backToReading}};
        });

      // 24. **Cancel does not keep the draft.** The draft is app-state now, so
      //     abandoning an edit is a thing that has to be *undone* rather than a
      //     closure going out of scope with the component — and it is undone in
      //     `show-page!`, on every page move, beside the Scopes page's dialogs.
      //
      //     Typed into and then abandoned, and the assertion is what the *second*
      //     visit shows: a draft that survived would put the abandoned title back in
      //     the field, and a reader would save it without ever having meant to. It
      //     also asserts the heading did not move while typing, which is the
      //     "what is saved over what you are about to save" reading.
      //
      //     Nothing is saved, so `shelf()` still writes nothing.
      await check('24 Cancel abandons the draft, and the next visit shows the Recipe',
        async () => {
          const stored = (stateGet('details') || {})[subject.id] || {};
          clickIn(document.querySelector('.top-bar-left'), 'button', 'Edit');
          await until(() => document.querySelector('.recipe-page-edit'));
          const typed = 'ABANDONED — this must not survive a Cancel';
          type(document.querySelector('.recipe-page-edit-title'), typed);
          await until(() => (stateGet('recipe-draft') || {}).title === typed);
          const whileTyping = {draft: stateGet('recipe-draft'),
                               field: document.querySelector('.recipe-page-edit-title').value,
                               headingStillStored:
                                 text('.recipe-page-title') === (stored.title || '').trim()};
          clickIn(document.querySelector('.top-bar-left'), '.recipe-edit-cancel');
          await until(() => document.querySelector('.recipe-page-body'));
          const afterCancel = {draft: stateGet('recipe-draft'),
                               title: text('.recipe-page-title')};
          clickIn(document.querySelector('.top-bar-left'), 'button', 'Edit');
          await until(() => document.querySelector('.recipe-page-edit'));
          const secondVisit = {field: document.querySelector('.recipe-page-edit-title').value,
                               draft: stateGet('recipe-draft')};
          clickIn(document.querySelector('.top-bar-left'), '.recipe-edit-cancel');
          await until(() => document.querySelector('.recipe-page-body'));
          return {pass: whileTyping.field === typed
                        && whileTyping.headingStillStored
                        && Object.keys(afterCancel.draft || {}).length === 0
                        && secondVisit.field === stored.title
                        && Object.keys(secondVisit.draft || {}).length === 0
                        && text('.recipe-page-title') === (stored.title || '').trim(),
                  evidence: {storedTitle: stored.title, typed, whileTyping, afterCancel,
                             secondVisit}};
      });

      // 25. **the versions viewer, opened from here, says `← Recipe` and comes back
      //     here.** The label is derived from `:page` and not stored, so the two
      //     origins are two assertions about one `case`: `checks.js` 13 has the Inbox
      //     end, and this is the Recipe end, because this surface is this file's
      //     subject.
      //
      //     It also asserts what the slot does *not* hold while the viewer is up. The
      //     viewer is opened from this page, so its button **replaces** `← Shelf`,
      //     Edit and Versions — a slot still offering Versions would be a control for
      //     the surface you are already on.
      await check('25 the versions viewer says ← Recipe and comes back to it', async () => {
        clickIn(document.querySelector('.top-bar-left'), 'button', 'Versions');
        const ov = await until(() => document.querySelector('.diff-overlay'), 8000);
        await until(() => document.querySelector('.diff-header h2'));
        const inTheViewer = {
          slot: [...document.querySelectorAll('.top-bar-left button')].map(b => b.textContent.trim()),
          right: [...document.querySelectorAll('.top-bar-right > *')]
            .map(e => (e.className || e.tagName).split(' ')[0]),
          heading: text('.diff-header h2'),
          noX: !document.querySelector('.diff-close'),
          pageBehindInert: !!page()?.inert,
          barNotInert: document.querySelector('.top-bar').inert !== true,
          clearsTheBar: !!ov && ov.getBoundingClientRect().top
                               >= document.querySelector('.top-bar').getBoundingClientRect().bottom};
        clickIn(document.querySelector('.top-bar-left'), '.diff-back');
        await until(() => !document.querySelector('.diff-overlay'));
        await until(() => document.querySelector('.recipe-page-body'));
        const afterBack = {
          path: path() + location.search,
          slot: [...document.querySelectorAll('.top-bar-left button')].map(b => b.textContent.trim()),
          readingDrawn: !!document.querySelector('.recipe-page-body'),
          diffing: stateGet('diffing'),
          inertReleased: !document.querySelectorAll('[data-inert-behind-viewer]').length};
        return {pass: inTheViewer.slot.join(',') === '← Recipe'
                      && inTheViewer.right.length === 1
                      && inTheViewer.heading === 'Versions'
                      && inTheViewer.noX && inTheViewer.pageBehindInert
                      && inTheViewer.barNotInert && inTheViewer.clearsTheBar
                      && afterBack.path === url
                      && afterBack.slot.join(',') === '← Shelf,Edit,Versions'
                      && afterBack.readingDrawn && !afterBack.diffing
                      && afterBack.inertReleased,
                evidence: {inTheViewer, afterBack, expectedPath: url}};
      });

      notes.push('reload ' + location.origin + url + ' and run coldLoad(), then '
                 + location.origin + url + '?edit=true and run coldEdit()');
      return done({subject: {id: subject.id, title: subject.title, url}});
    },

    // ---- phase two: after a cold load of /recipe/<id> -----------------------
    // **The half a pushState-only implementation fakes.** Everything phase one
    // asserted would still pass with no server route at all: the address changes
    // and the page renders because the client never left the document. This is the
    // load that goes to the server first, and it is why `GET /recipe/*` exists.
    coldLoad: async () => {
      const {check, step, done} = runner();
      await check('2 a cold load of the address lands on the Recipe, not on a 404', async () => {
        await until(() => page() && document.querySelector('.recipe-page-body'), 8000);
        const id = Number(path().split('/')[2]);
        const row = (stateGet('details') || {})[id] || {};
        return {pass: !!page() && !shelf() && !!text('.recipe-page-body')
                      && stateGet('recipe-page-status') === 'found'
                      && stateGet('recipe-page-id') === id,
                evidence: {path: path(), recipePageRendered: !!page(),
                           shelfRendered: !!shelf(),
                           title: text('.recipe-page-title'),
                           body: text('.recipe-page-body'),
                           status: stateGet('recipe-page-status'),
                           recipePageId: stateGet('recipe-page-id'), askedFor: id,
                           cachedTitle: row.title}};
      });

      // 15. **the confirmation and the editor draw on a page the shelf's listing had
      //     nothing to do with**, and this is the one check that could have caught
      //     the bug the actions were moved *into*. `publish-modal` and
      //     `delete-modal` used to find their Recipe with a filter over `:recipes`;
      //     that is a narrowed, ranked answer to a question this page never asked,
      //     so a Recipe missing from it — hidden Scope, active search, or simply a
      //     listing that has not landed yet — got a confirmation that silently did
      //     not render. Both read `:details` now, which is where this page's own
      //     fetch put the row, and so does the editor's prefill.
      //
      //     Here rather than in `shelf()` for the reason this phase exists at all: a
      //     click through from the shelf cannot see it, because the shelf's fetch has
      //     already happened. This phase makes the closest thing a browser can
      //     reach — a context that was never on the shelf — and to make the
      //     independence unmistakable it **empties `:recipes` first**, which is
      //     `checks.js` 12's technique: build the exact condition the failure needs
      //     and leave the rest of the session alone.
      //
      //     Nothing is ever saved. Delete is opened and dismissed, and the editor is
      //     entered and left by Cancel — this file writes nothing and that stays
      //     true. Edit is a **navigation** now, not a modal, so the two halves are
      //     waited on differently: `.modal-backdrop` for the one that is still a
      //     dialog, `.recipe-page-edit` for the one that is a page.
      await check('15 the confirmation and the editor draw from the page\'s own row',
        async () => {
          const before = (stateGet('recipes') || []).length;
          c.swap_BANG_(st._STAR_app_state, m => c.assoc(m, kw('recipes'), c.vector()));
          await until(() => (stateGet('recipes') || []).length === 0);
          // Three containers, and this check does not care which is which — the split
          // is 14's subject. Delete is at the bottom right (`.recipe-page-delete`),
          // Edit in the bar's left slot, Publish in its right one. Both of the bar's
          // slots are named here rather than the two this check presses, so that a
          // control moving between them costs 14 a column and not this one a run.
          const act = label => [...document.querySelectorAll(
              '.top-bar-left button, .top-bar-actions button, .recipe-page-delete button')]
            .find(b => b.textContent.trim() === label);
          const modal = () => document.querySelector('.modal-backdrop');
          const form = () => document.querySelector('.recipe-page-edit');
          const evidence = {rowsInTheListing: (stateGet('recipes') || []).length,
                            rowsBefore: before};

          act('Delete').click();
          const dm = await until(() => modal());
          evidence.deleteConfirmation = {shown: !!dm,
            subtitle: dm?.querySelector('.modal-subtitle')?.textContent?.trim(),
            note: dm?.querySelector('.modal-note')?.textContent?.trim()};
          dm && clickIn(dm.querySelector('.modal-actions'), 'button.secondary', 'Cancel');
          await until(() => !modal());

          act('Edit').click();
          const em = await until(() => form());
          evidence.editor = {shown: !!em,
            prefilledTitle: em?.querySelector('.recipe-page-edit-title')?.value,
            prefilledBody: (em?.querySelector('.recipe-page-edit-body')?.value || '')
              .slice(0, 24)};
          em && clickIn(document.querySelector('.top-bar-left'), '.recipe-edit-cancel');
          await until(() => !form());

          const d = evidence.deleteConfirmation, e = evidence.editor;
          return {pass: evidence.rowsInTheListing === 0
                        && !!d.shown && (d.subtitle || '').includes(SUBJECT)
                        && /version/.test(d.note || '')
                        && !!e.shown && (e.prefilledTitle || '').includes(SUBJECT)
                        && !!e.prefilledBody
                        && !modal() && !form(),
                  evidence};
        });

      // and put the listing back, so a session left open here is the one that was
      // found — the check emptied it, nothing else did
      await step('refetch the listing', async () => {
        st.fetch_recipes();
        await until(() => (stateGet('recipes') || []).length > 0);
      });
      return done({});
    },

    // ---- phase two and a half: after a cold load of /recipe/<id>?edit=true --
    // **A phase of its own for the reason `coldLoad` is one**: the load it is about
    // replaces the JS context. `shelf()` 16 and 17 assert that the editor's address
    // is pushed and read back *inside* one document; this asserts that an address
    // arriving from outside opens the editor at all — the case where nothing has
    // been pushed and the only thing the client has to go on is the bar.
    //
    // It is the half that `sync-from-url!` alone can answer. Everything 16 and 17
    // assert still passes if the boot never looks at `.-search`.
    coldEdit: async () => {
      const {check, step, done} = runner();
      //     **And it comes up prefilled, asserted field by field against the stored
      //     row.** That half was `!!title` and is now an equality on all four,
      //     because this is the case where a draft *seeded* from the Recipe breaks:
      //     seeding needs a moment when the row is present and the page is in edit
      //     mode, and at this address the navigation happens first and the row lands
      //     a round trip later. A seed that fired too early leaves the fields empty,
      //     and an empty editor looks exactly like a Recipe with no content — which
      //     `!!title` would have caught and `title === ''` on a blank Recipe would
      //     not. The draft is a *diff* over the row instead, so there is no seed to
      //     fire at all; `:recipe-draft` being `{}` here is that, observed.
      await check('18 a cold load of ?edit=true opens the editor, prefilled',
        async () => {
          const form = await until(() => document.querySelector('.recipe-page-edit'), 8000);
          const id = Number(path().split('/')[2]);
          const row = (stateGet('details') || {})[id] || {};
          const onScreen = {
            title: form?.querySelector('.recipe-page-edit-title')?.value,
            useful_when: form?.querySelectorAll('input')[1]?.value,
            tags: form?.querySelector('.recipe-page-edit-tags')?.value,
            description: form?.querySelector('.recipe-page-edit-body')?.value};
          const stored = {title: row.title || '', useful_when: row.useful_when || '',
                          tags: row.tags || '', description: row.description || ''};
          const matches = Object.keys(stored).filter(k => onScreen[k] === stored[k]);
          return {pass: !!form && !!page() && !shelf()
                        && location.search === '?edit=true'
                        && stateGet('recipe-page-edit?') === true
                        && stateGet('recipe-page-id') === id
                        && stateGet('recipe-page-status') === 'found'
                        && !document.querySelector('.recipe-page-body')
                        && matches.length === 4
                        && !!stored.title
                        && Object.keys(stateGet('recipe-draft') || {}).length === 0,
                  evidence: {bar: path() + location.search,
                             editorRendered: !!form,
                             readingRendered: !!document.querySelector('.recipe-page-body'),
                             flag: stateGet('recipe-page-edit?'),
                             status: stateGet('recipe-page-status'),
                             recipePageId: stateGet('recipe-page-id'), askedFor: id,
                             onScreen, stored, fieldsThatMatch: matches,
                             draftIsEmptyBecauseNothingIsSeeded:
                               stateGet('recipe-draft')}};
        });
      // leave the reading up rather than a form nobody asked to fill in
      await step('leave the editor', async () => {
        const slot = document.querySelector('.top-bar-left');
        slot?.querySelector('.recipe-edit-cancel') && clickIn(slot, '.recipe-edit-cancel');
        await until(() => document.querySelector('.recipe-page-body'));
      });
      return done({});
    },

    // ---- phase three: not the owner, and an address that names nothing ------
    // Signed out is driven through `state/logout` — the fn the Sign out button
    // calls — rather than by loading a page as a stranger, because the dev server
    // cannot serve one: `:dangerously-skip-logins?` makes every request the
    // owner's, token or no token. So what this phase asserts is the **client** half
    // — that a Recipe page is not owner-only, and that a 404 is a sentence rather
    // than a spinner. Which Recipes a genuine visitor is refused is the server's
    // half and is asserted where it can be: `publish_latch_integration_test.clj`,
    // *a visitor asking for the draft by id gets the same 404*.
    //
    // 4b uses an id nothing was ever written under, which is the same 404 from the
    // same handler that an unpublished Recipe gives a visitor — the server answers
    // both identically on purpose, and `views.recipe/not-found` says why the page
    // must not try to tell them apart.
    signedOut: async () => {
      const {check, step, done} = runner();
      const subject = rowFor(SUBJECT);
      if (!subject) throw new Error('no Recipe named ' + SUBJECT + ' on the shelf — see README');

      await step('sign out', () => st.logout());
      await until(() => shelf() && stateGet('logged-in?') === false);

      await check('4a signed out, a published Recipe still has a page', async () => {
        st.open_recipe_page(subject.id);            // exactly what the Page button calls
        await until(() => page() && !document.querySelector('.card-body-loading'));
        return {pass: !!page() && !shelf()
                      && path() === '/recipe/' + subject.id
                      && !!text('.recipe-page-body')
                      && stateGet('logged-in?') === false
                      // the owner's own facts stay his, which is what says this is
                      // the visitor's rendering and not the owner's page relabelled.
                      // The filing is now the strongest of the three: it is not a
                      // fact the server withheld but a **control**, and a visitor
                      // holding a chip row would be holding one over a PUT the API
                      // refuses. `.recipe-page-scopes` used to be here and is gone
                      // from the app — the picker is what replaced it.
                      && !document.querySelector('.recipe-page-tags')
                      && !document.querySelector('.scope-picker'),
                evidence: {loggedIn: stateGet('logged-in?'), path: path(),
                           recipePageRendered: !!page(), shelfRendered: !!shelf(),
                           title: text('.recipe-page-title'),
                           body: text('.recipe-page-body'),
                           filingControl: !!document.querySelector('.scope-picker'),
                           ownerOnlyBitsOnThePage:
                             [...document.querySelectorAll(
                               '.recipe-page-tags, .scope-picker, .pending-badge, .published-badge')]
                               .map(e => e.className)}};
      });

      await check('4b an address that names no readable Recipe says so, and offers a way back',
        async () => {
          st.open_recipe_page(999999);
          await until(() => document.querySelector('.recipe-page-missing'));
          await wait(300);       // long enough for a spinner to have been left behind
          const missing = document.querySelector('.recipe-page-missing');
          return {pass: !!missing && (missing.textContent || '').trim().length > 40
                        && !document.querySelector('.card-body-loading')
                        && !!document.querySelector('.recipe-page-back')
                        && stateGet('recipe-page-status') === 'missing'
                        && path() === '/recipe/999999',
                  evidence: {path: path(), sentence: missing?.textContent?.trim(),
                             stillLoading: !!document.querySelector('.card-body-loading'),
                             wayBack: text('.recipe-page-back'),
                             status: stateGet('recipe-page-status')}};
        });

      // 19. **a visitor at `?edit=true` gets the reading, and the bar stops saying
      //     otherwise.** `(GET "/recipe/*")` deliberately does not look at what
      //     follows it and a query string never affects the match, so the app is
      //     served to anybody who types this address and the **client** is the only
      //     thing that can refuse it. The API refuses the PUT regardless; a form a
      //     visitor can fill in and never submit is a worse lie than no form.
      //
      //     Driven by putting the address in the bar and calling `sync-from-url!`,
      //     which is exactly what a `popstate` and the boot both do with an address
      //     nobody in this document chose. `replace-state!` and not a push, so the
      //     stack this phase was handed is the stack it hands back.
      await check('19 signed out at ?edit=true gets the reading, with the query gone',
        async () => {
          const editUrl = '/recipe/' + subject.id + '?edit=true';
          urls.replace_state_BANG_(editUrl);
          const barBefore = path() + location.search;
          st.sync_from_url_BANG_();
          await until(() => stateGet('recipe-page-status') === 'found');
          await until(() => document.querySelector('.recipe-page-body'));
          await wait(200);          // long enough for an editor to have appeared
          return {pass: barBefore === editUrl
                        && path() + location.search === '/recipe/' + subject.id
                        && stateGet('recipe-page-edit?') === false
                        && stateGet('logged-in?') === false
                        && !document.querySelector('.recipe-page-edit')
                        && !!document.querySelector('.recipe-page-body'),
                  evidence: {barBefore, barAfter: path() + location.search,
                             flag: stateGet('recipe-page-edit?'),
                             loggedIn: stateGet('logged-in?'),
                             editorRendered: !!document.querySelector('.recipe-page-edit'),
                             readingRendered: !!document.querySelector('.recipe-page-body')}};
        });

      // Put the client back the way it was found. The shelf **first**, and then the
      // sign-in: `fetch_auth_required` ends in `sync-from-url!`, which re-derives
      // the page from the bar — and the bar still names a Recipe at this point, so
      // the other order signs back in and lands straight back on it.
      await step('back to the shelf', () => st.go_to_page(kw('shelf')));
      await until(() => shelf());
      // dev signs itself in, so this is the call the page makes on boot, not a fake
      await step('sign back in', () => st.fetch_auth_required());
      await until(() => stateGet('logged-in?') === true && shelf());
      return done({});
    },

    // ---- phase five: the save, which is the one that makes a version --------
    // **The only phase that makes a version, and the only one that builds its own
    // fixture rather than reading a seeded one.** Both follow from what it is about:
    //
    //   > saving made the show provenance button disappear until i went ofr overview
    //   > and came back
    //
    // The split is derived from the version history, so the only save that can stale it
    // is one that writes a version — and the fix is that the `PUT` now carries the new
    // split (`update-recipe-handler`) while the client forgets only the version list
    // (`forget-versions!`). Asserting that needs a Recipe it may write to twice, and it
    // must not be `CHECK-PROV`: the provenance phase's checks want a body whose lines
    // read 1.00, 0.00 and one in between, and a save would move that ladder under them.
    // A phase that spent another phase's fixture would cost the *next* run its columns,
    // which is the cascade check 21 already taught this file about.
    //
    // So it makes `CHECK-SAVE`, through the API and as a machine — three lines nobody
    // has touched, which the API reads as 0.00 throughout, so a line the owner then
    // rewrites has somewhere visible to move to. It **leaves the Recipe behind** for
    // `cleanup.py`, for the reason that script gives about its own choice of sqlite:
    // `DELETE /api/recipes/:id` files a `deleted` event, and a suite must not put one
    // in his queue.
    //
    // Runs from anywhere and leaves the browser on the shelf, so the two phases after
    // it still start and end where they always did.
    save: async () => {
      const {check, step, done, notes} = runner();
      const api = async (p, {method, body, token} = {}) => {
        const r = await fetch('/api/' + p, {
          method: method || (body ? 'POST' : 'GET'),
          headers: Object.assign({'Content-Type': 'application/json'},
                                 token ? {Authorization: 'Bearer ' + token} : {}),
          body: body === undefined ? undefined : JSON.stringify(body)});
        let parsed = null;
        try { parsed = JSON.parse((await r.text()) || 'null'); } catch (e) { parsed = null; }
        return {status: r.status, body: parsed};
      };
      // Three lines and no trailing newline: this fixture is about a line *changing*,
      // and the trailing-empty-line case is CHECK-PROV's, asserted by check 8.
      const AGENTS = 'The agent wrote this line.\n'
                     + 'And this second one, which the owner is about to rewrite.\n'
                     + 'And this third one, which he leaves alone.';
      const login = await api('auth/login',
                             {body: {username: 'machine-user', password: 'pw'}});
      const token = (login.body || {}).token;
      if (!token) throw new Error('no machine token: ' + JSON.stringify(login));
      const made = await api('recipes', {token, body: {
        title: 'CHECK-SAVE a body the agent wrote and the owner edits',
        useful_when: 'the provenance button must survive a save',
        description: AGENTS}});
      if (made.status !== 201)
        throw new Error('could not build the fixture: ' + JSON.stringify(made));
      const id = made.body.id;
      notes.push('built CHECK-SAVE recipe ' + id + ' and left it for cleanup.py');
      const row = () => (stateGet('details') || {})[id] || {};
      const toggle = () => document.querySelector('.recipe-page-provenance-toggle');

      await step('open the fixture at its own address', () => st.open_recipe_page(id));
      await until(() => page() && document.querySelector('.recipe-page-body') && toggle(),
                  8000);
      if (!toggle())
        throw new Error('no provenance toggle on the fresh fixture — is the API sending'
                        + ' caution on the full read?');
      // The fixture's own property, guaranteed where it is built rather than asserted
      // in a check, exactly as `provenance-seed.py` does with its trailing newline: a
      // check that went red because a *fixture* was not what it thought would be
      // blaming the app for something the setup did.
      const started = perLine(row().caution);
      if (!(started.length === 3 && started.every(v => v === 0)))
        throw new Error('a machine-written v1 should read 0.00 on every line, got '
                        + JSON.stringify(started));

      // 31. **the owner's complaint, and the only proof of it.** Save a content edit
      //     while looking at provenance and the button is still there, describing what
      //     was just written.
      //
      //     Three things could satisfy the first half and only one of them is right, so
      //     the split is asserted as well as the button: a client that kept the *old*
      //     ranges would show a button describing text that no longer exists, and a
      //     client that refetched the page would inflate `view_count`. What is asserted
      //     is the third: the rewritten line reads 1.00 — his — while the two lines he
      //     left alone are still 0.00, and the version has moved by exactly one.
      //
      //     **This is the check the server change alone would not have turned green.**
      //     `update-recipe` ran `cache-detail!` and then dropped the split on the next
      //     line, so a `PUT` carrying a fresh one was thrown away a microsecond after it
      //     arrived, with nothing anywhere looking wrong.
      await check('31 saving keeps the button, with the split of the version it just wrote',
        async () => {
          const before = {version: row().version, perLine: perLine(row().caution)};
          const lines = AGENTS.split('\n');
          const edited = lines.slice();
          edited[1] = 'The owner rewrote this second line himself.';
          st.open_recipe_editor(id);
          await until(() => document.querySelector('.recipe-page-edit-body'));
          type(document.querySelector('.recipe-page-edit-body'), edited.join('\n'));
          await until(() => (stateGet('recipe-draft') || {}).description
                            === edited.join('\n'));
          // **looking at provenance at the moment Save is pressed**, which is the
          // gesture the complaint is about and not an incidental starting state
          toggle().click();
          await until(() => document.querySelector('.provenance-source'));
          const whileSaving = {source: true, label: toggle().textContent.trim()};
          clickIn(document.querySelector('.top-bar-left'), '.recipe-edit-save');
          // the visible consequence and the cached row together: the reading comes back
          // from the row the response cached, so both have to have landed
          await until(() => document.querySelector('.recipe-page-body')
                            && row().version === before.version + 1, 8000);
          await wait(150);
          const after = {version: row().version, perLine: perLine(row().caution),
                         toggle: !!toggle(), label: toggle()?.textContent?.trim(),
                         description: row().description};
          return {pass: after.toggle
                        && after.version === before.version + 1
                        && after.perLine.length === 3
                        && after.perLine[1] === 1
                        && after.perLine[0] === 0 && after.perLine[2] === 0
                        && after.description === edited.join('\n')
                        && !stateGet('error'),
                  evidence: {before, whileSaving, after,
                             theLineHeRewrote: edited[1],
                             // the label reads *Show* again because a save is a page
                             // move and `show-page!` drops the view; the button being
                             // there is the claim, the view resetting is his open
                             // question and deliberately not asserted
                             error: stateGet('error')}};
        });

      // 32. **the other half of the rule: a filing save carries no split and needs
      //     none.** `caution-body` costs a second read and a fold over the whole
      //     history, and a filing `PUT` fires on every Scope chip click — so paying for
      //     it there would be a real cost for an answer that cannot have changed. What
      //     makes that safe is `cache-detail!` merging: a response that does not carry
      //     `caution` leaves the one the client holds alone. This check is what says the
      //     two halves meet — the button survives a save that *was* told the split and a
      //     save that was not.
      await check('32 a filing toggle keeps the split it did not change', async () => {
        const chips = [...document.querySelectorAll('.scope-picker.recipe-page-filing'
                                                    + ' .scope-chip')];
        if (!chips.length)
          return {pass: false, evidence: {why: 'no Scope chips on the page — this needs '
                                               + 'one of his Scopes to exist'}};
        const before = {version: row().version, perLine: perLine(row().caution),
                        toggle: !!toggle()};
        const name = chips[0].textContent.trim();
        chips[0].click();
        await until(() => !stateGet('filing') && (row().scopes || []).length === 1
                          && chips[0].classList.contains('on'), 8000);
        const after = {version: row().version, perLine: perLine(row().caution),
                       toggle: !!toggle(),
                       // `title` and not `name`: a Scope is titled like everything else
                       // in this API, and reading the wrong key gives a green-looking
                       // `[null]` rather than an error
                       scopes: (row().scopes || []).map(s => s.title)};
        return {pass: after.toggle && before.toggle
                      && after.version === before.version
                      && JSON.stringify(after.perLine) === JSON.stringify(before.perLine)
                      && after.scopes.length === 1 && after.scopes[0] === name
                      && !stateGet('error'),
                evidence: {filedUnder: name, before, after, error: stateGet('error')}};
      });

      // Back to the shelf, and refetch it: the listing in hand never had CHECK-SAVE in
      // it and now names a Recipe that has moved twice, so the phases after this one
      // read a listing that agrees with the database.
      await step('back to the shelf', () => st.go_to_page(kw('shelf')));
      await until(() => shelf());
      await step('refetch the listing', () => st.fetch_recipes());
      await until(() => (stateGet('recipes') || []).some(r => r.id === id), 8000);
      notes.push('left on ' + path() + '; CHECK-SAVE ' + id + ' is for cleanup.py');
      return done({fixture: {id, url: '/recipe/' + id}});
    },

    // ---- phase six: the provenance view -------------------------------------
    // **The only phase in this file that needs a fixture**, and the only one that
    // reads a Recipe it did not find already there. `provenance-seed.py` explains
    // what CHECK-PROV has that nothing in the dev database is guaranteed to have;
    // run it before this and `cleanup.py` after.
    //
    // Runs last, and from anywhere: it opens the page it is about through
    // `open_recipe_page`, which is what the card's button calls. It leaves the view
    // **on**, because that is the thing a human is being asked to look at.
    provenance: async () => {
      const {check, step, done, notes} = runner();
      const subject = rowFor(MIXED);
      if (!subject) throw new Error('no ' + MIXED + ' Recipe on the shelf — run '
                                    + 'test/browser/provenance-seed.py first, see README');
      const id = subject.id;
      const row = () => (stateGet('details') || {})[id] || {};
      const toggle = () => document.querySelector('.recipe-page-provenance-toggle');
      const lines = () => [...document.querySelectorAll('.provenance-line')];
      const numberOf = el => Number(el.querySelector('.provenance-line-number').textContent);
      const textOf = el => el.querySelector('.provenance-line-text').textContent;
      const cautionOf = el => parseFloat(el.style.getPropertyValue('--caution'));
      const barOf = el => getComputedStyle(el.querySelector('.provenance-line-bar')).backgroundColor;
      const washOf = el => getComputedStyle(el).backgroundColor;
      // What the API said, per line, worked out here from the ranges rather than from
      // the view — so check 9 is comparing two independent readings of one answer and
      // not the view against itself.
      const expectedPerLine = () => perLine(row().caution);

      // `open_recipe_page` and not a click, for the reason `signedOut` calls it: this
      // phase does not care how a reader got here, and it may be started from a page
      // that has no card to press.
      await step('open the mixed Recipe at its own address', () => st.open_recipe_page(id));
      // **Wait for the settled state and not merely for a toggle**, which is the house
      // rule about waiting on the visible consequence, applied to a phase that may be
      // started from its own previous run. `show-page!` drops `:showing-provenance?`
      // on every move, so the settled start is always the rendered body under a
      // *Show* label — but reagent re-renders a frame later, and a wait that accepted
      // any toggle returned while the last run's source view was still on screen.
      // Check 7 then read that as its starting state and went red about the app.
      await until(() => page() && toggle() && document.querySelector('.recipe-page-body')
                        && toggle().textContent.trim() === 'Show provenance', 8000);
      if (!toggle()) throw new Error('no provenance toggle on ' + MIXED
                                     + ' — is the API sending caution?');

      // 7. **the editor's gesture.** It shows the text, and the rendered body comes
      //    back when it is turned off — the two never both on screen, in either
      //    direction, because a reader who could see both would have no way to know
      //    which one the colours were about.
      await check('7 the toggle swaps the rendered body for the source, and back', async () => {
        const snap = () => ({rendered: !!document.querySelector('.recipe-page-body'),
                             source: !!document.querySelector('.provenance-source'),
                             label: toggle()?.textContent?.trim()});
        const before = snap();
        toggle().click();
        await until(() => document.querySelector('.provenance-source'));
        const on = snap();
        toggle().click();
        await until(() => document.querySelector('.recipe-page-body'));
        const off = snap();
        return {pass: before.rendered && !before.source && before.label === 'Show provenance'
                      && on.source && !on.rendered && on.label === 'Hide provenance'
                      && off.rendered && !off.source && off.label === 'Show provenance',
                evidence: {before, on, off}};
      });

      // 8. **the numbers, against the text as it is stored.** Two claims in one: the
      //    rows run 1..n with nothing skipped, and n is the line count the *API*
      //    numbered its ranges over. `clojure.string/split-lines` drops a trailing
      //    empty line where the ranges keep it, so a view built on it draws one row
      //    too few, at the end, silently — `lastRangeTo` is what catches that.
      //
      //    **Whether the body ends in a newline is evidence here, not an assertion.**
      //    The seed builds one that does, because that is what makes this check able
      //    to catch the `split-lines` bug at all — but the fixture is a Recipe in a
      //    dev database and somebody editing it by hand is a normal thing to do. A
      //    check that failed for that would be reporting a bug in the app when the
      //    only thing that happened is that a human used the app. So it says so in a
      //    note instead: the run was thinner than intended, and nothing is wrong.
      await check('8 the numbers run 1..n over the body as it is stored', async () => {
        toggle().click();
        await until(() => document.querySelector('.provenance-source'));
        const desc = row().description || '';
        const expected = desc.split('\n').length;   // JS keeps trailing empties; so does the API
        const ranges = (row().caution || {}).ranges || [];
        const lastTo = ranges.length ? ranges[ranges.length - 1].to : null;
        const numbers = lines().map(numberOf);
        const roundTrips = lines().map(textOf).join('\n') === desc;
        if (!desc.endsWith('\n'))
          notes.push('8: the body no longer ends in a newline, so this run did not '
                     + 'exercise the trailing-empty-line case — re-run provenance-seed.py '
                     + 'for a fixture that does');
        return {pass: expected > 1 && numbers.length === expected && lastTo === expected
                      && numbers.every((n, i) => n === i + 1) && roundTrips,
                evidence: {linesInTheBody: expected, rowsDrawn: numbers.length,
                           lastRangeTo: lastTo, numbers,
                           bodyEndsWithNewline: desc.endsWith('\n'),
                           textRoundTripsExactly: roundTrips}};
      });

      // 9. **every line wears the number the API gave that line.** The one silent
      //    failure the view can have on its own: ranges are inclusive and one-based
      //    and the rows are a zero-based enumeration, so an off-by-one here tints
      //    every line with its neighbour's provenance and looks entirely plausible
      //    doing it.
      await check('9 each line is tinted with its own caution, not its neighbour\'s', () => {
        const expected = expectedPerLine();
        const drawn = lines().map(cautionOf);
        const mismatches = drawn.map((v, i) => ({line: i + 1, drawn: v, api: expected[i] * 100}))
                                .filter(x => Math.abs(x.drawn - x.api) > 0.001);
        return {pass: expected.length === drawn.length && drawn.length > 0
                      && mismatches.length === 0,
                evidence: {perLineFromTheApi: expected, perLineOnScreen: drawn, mismatches}};
      });

      // 10. **a spectrum and not two buckets.** The API hands out numbers between
      //     the ends, a stretch both have touched is exactly what they are for, and
      //     thresholding at 0.5 would throw that away while still looking like a
      //     working feature. So: his end and the agent's end differ, *and* the middle
      //     is a third colour rather than being rounded onto one of them.
      await check('10 a line between the ends is a third colour, not rounded to one', () => {
        const cautions = lines().map(cautionOf);
        const his = lines()[cautions.indexOf(100)];
        const theirs = lines()[cautions.indexOf(0)];
        const middleIdx = cautions.findIndex(v => v > 0 && v < 100);
        const middle = lines()[middleIdx];
        if (!his || !theirs || !middle)
          return {pass: false, evidence: {cautions,
                    why: 'the fixture must carry 1.00, 0.00 and one in between'}};
        const bars = {his: barOf(his), theirs: barOf(theirs), middle: barOf(middle)};
        const washes = {his: washOf(his), theirs: washOf(theirs)};
        return {pass: bars.his !== bars.theirs
                      && bars.middle !== bars.his && bars.middle !== bars.theirs
                      && washes.his !== washes.theirs,
                evidence: {bars, washes, cautions, middleLine: middleIdx + 1,
                           barWidth: his.querySelector('.provenance-line-bar')
                                        .getBoundingClientRect().width}};
      });

      // 11. **the legend is the API's, not a second wording of it.** It is in the
      //     response so that one sentence explains this scale everywhere, and a copy
      //     typed into the cljs is how the page and `/api/describe` come to say
      //     different things about the same number.
      await check('11 the legend on the page is the string the API sent', async () => {
        const shown = text('.provenance-legend');
        const sent = (row().caution || {}).legend;
        toggle().click();                         // and it goes away with the view
        await until(() => !document.querySelector('.provenance-source'));
        const whenOff = !!document.querySelector('.provenance-legend');
        toggle().click();
        await until(() => document.querySelector('.provenance-source'));
        return {pass: !!sent && sent.length > 20 && shown === sent && !whenOff,
                evidence: {onThePage: shown, fromTheApi: sent,
                           stillThereWithTheViewOff: whenOff}};
      });

      // 12. **the button exists when the answer does, and not when the session
      //     looks right.** A visitor is served no `caution` key at all — that is the
      //     API's decision and `caution_integration_test/a-visitor-is-served-no-split`
      //     is where it is asserted — and this page must not offer a control that
      //     would then draw nothing. Dev cannot produce a genuine visitor
      //     (`:dangerously-skip-logins?` serves every request in the owner's
      //     audience, as this file's header explains for 4a), so what is done here is
      //     the exact condition a visitor's response creates: the key removed from
      //     the cached row, the session left signed in. A button keyed off
      //     `logged-in?` stays on screen and reddens this.
      //
      //     **Extended to both modes rather than duplicated**, because the rule is one
      //     rule: *the button exists when the answer does*. The editor tints the draft
      //     and the reading tints the stored body, but neither has anything to tint
      //     without `caution` — so an edit mode that offered a button the reading did
      //     not would be the same lie on a different surface. One condition, made once,
      //     read on both.
      await check('12 no caution in the response, no button — in either mode', async () => {
        const path = c.vector(kw('details'), id);
        const cached = c.get_in(c.deref(st._STAR_app_state), path);
        c.swap_BANG_(st._STAR_app_state,
                     m => c.assoc_in(m, path, c.dissoc(cached, kw('caution'))));
        await until(() => !toggle());
        const reading = {toggle: !!toggle(), source: !!document.querySelector('.provenance-source'),
                         legend: !!document.querySelector('.provenance-legend'),
                         rendered: !!document.querySelector('.recipe-page-body'),
                         loggedIn: stateGet('logged-in?')};
        // the same row, the same missing key, the other mode
        st.open_recipe_editor(id);
        await until(() => document.querySelector('.recipe-page-edit'));
        await wait(150);
        const editing = {toggle: !!toggle(), source: !!document.querySelector('.provenance-source'),
                         legend: !!document.querySelector('.provenance-legend'),
                         textarea: !!document.querySelector('.recipe-page-edit-body')};
        st.cancel_recipe_edit();
        await until(() => document.querySelector('.recipe-page-body'));
        c.swap_BANG_(st._STAR_app_state, m => c.assoc_in(m, path, cached));
        await until(() => toggle());
        return {pass: !reading.toggle && !reading.source && !reading.legend
                      && reading.rendered && reading.loggedIn === true
                      // and the editor: no button, and the field still there to type in
                      && !editing.toggle && !editing.source && !editing.legend
                      && editing.textarea
                      && !!toggle(),
                evidence: {withoutTheKey: {reading, editing},
                           buttonBackAfterRestoring: !!toggle()}};
      });

      // 30. **the toggle is in the panel's top-right corner, and the row it used to
      //     live in does not render without it.** *also it should be placed in the top
      //     right corner of that REcipe's space* — the panel, level with the title.
      //
      //     Three claims, and the second is the one worth a check rather than an eye:
      //     the toggle sits in the title row and its right edge is the panel's content
      //     edge, which is what "the corner" means in a measurement; the toggle and
      //     the title **do not overlap**, which has to hold in both layouts, so it is
      //     asserted as *side by side or stacked* rather than as one line; and
      //     `.recipe-page-body-tools` — which now only ever holds the legend — is
      //     absent while the view is off. That last one is the fifth leftover-container
      //     of this run of work and the only one a suite can catch cheaply: an empty
      //     row keeps the panel's spacing and reads as a rendering bug nobody can name.
      await check('30 the toggle is in the panel\'s corner, in both modes', async () => {
        const corner = () => {
          const t = toggle().getBoundingClientRect();
          const row = toggle().parentElement;
          const panel = document.querySelector('.recipe-page');
          const title = document.querySelector('.recipe-page-title')
                          .getBoundingClientRect();
          const body = document.querySelector('.recipe-page-body, .provenance-source,'
                                              + ' .recipe-page-edit-body');
          return {inTheTitleRow: row.classList.contains('recipe-page-title-row'),
                  // **against the row and not against the title**: the title carries
                  // `margin-right: auto`, so its box ends where its text does and is
                  // nowhere near the panel's edge. The row is what spans the content
                  // box, so the row's right edge is what "the corner" means in a
                  // measurement.
                  flushRight: Math.abs(t.right - row.getBoundingClientRect().right) <= 1,
                  // the panel's *first line*: the row is the first thing in the header
                  // and the header is the first thing in the panel, which says "top"
                  // without this check having to know the header's class
                  atTheTop: panel.firstElementChild === row.parentElement
                            && row.parentElement.firstElementChild === row,
                  aboveTheBody: !body || t.bottom <= body.getBoundingClientRect().top,
                  clearOfTheTitle: t.left >= title.right - 1 || t.bottom <= title.top + 1,
                  toolsRow: !!document.querySelector('.recipe-page-body-tools')};
        };
        const readingOff = corner();
        toggle().click();                         // and with the view on, the legend
        await until(() => document.querySelector('.provenance-source'));
        const readingOn = Object.assign(corner(), {
          toolsRowHolds: [...document.querySelectorAll('.recipe-page-body-tools > *')]
                           .map(e => e.className)});
        toggle().click();
        await until(() => document.querySelector('.recipe-page-body'));

        st.open_recipe_editor(id);
        await until(() => document.querySelector('.recipe-page-edit-body'));
        await wait(150);                          // the row is laid out a frame later
        const editing = corner();
        st.cancel_recipe_edit();
        await until(() => document.querySelector('.recipe-page-body'));

        const cornered = s => s.inTheTitleRow && s.flushRight && s.atTheTop
                              && s.aboveTheBody && s.clearOfTheTitle;
        return {pass: cornered(readingOff) && cornered(readingOn) && cornered(editing)
                      // the row is the legend's alone, and only when there is a legend
                      && !readingOff.toolsRow && !editing.toolsRow
                      && readingOn.toolsRow
                      && readingOn.toolsRowHolds.join(',') === 'provenance-legend',
                evidence: {readingOff, readingOn, editing}};
      });

      // ---- the draft's provenance, in edit mode -----------------------------
      // *show provenance button should be avilable in both edit and view modes. and in
      // edit modes it should reflect the volatile state.* Four checks, and the fixture
      // is the right one to make them on: `CHECK-PROV` is the only body in the dev
      // database guaranteed to carry a 1.00 line, a 0.00 line and one in between, so a
      // line that keeps its tint is visibly keeping a *particular* tint.
      //
      // The draft is app-state, so none of these save anything — the editor is entered,
      // typed into and left by Cancel, which is what `shelf()` does for check 24.

      const editBody = async (v) => {
        if (!document.querySelector('.recipe-page-edit-body')) {
          toggle().click();
          await until(() => document.querySelector('.recipe-page-edit-body'));
        }
        type(document.querySelector('.recipe-page-edit-body'), v);
        await until(() => (stateGet('recipe-draft') || {}).description === v);
        toggle().click();
        await until(() => document.querySelector('.provenance-source'));
      };
      const untoldFlags = () => lines()
        .map(el => el.classList.contains('provenance-line-untold'));

      // 26. **the alignment rule itself**, called directly, because the rule is worth
      //     testing apart from the view that draws it. Three cases plus the two that
      //     say why it is *index and text* rather than anything cleverer.
      //
      //     `provenance/draft-cautions` and not the DOM: this is the one assertion in
      //     the file about a pure function, and going through the renderer would make a
      //     red ambiguous between the rule and the drawing of it — which is exactly
      //     what 27 to 29 are for.
      await check('26 the alignment rule keeps a line only at the same index and text', () => {
        const prov = window.et.cb.ui.provenance;
        const ranges = c.js__GT_clj(JSON.parse(JSON.stringify(
          [{from: 1, to: 1, caution: 1.0},
           {from: 2, to: 2, caution: 0.5},
           {from: 3, to: 3, caution: 0.0}])), kw('keywordize-keys'), true);
        const stored = 'alpha\nbravo\ncharlie';
        const run = draft => c.clj__GT_js(prov.draft_cautions(stored, ranges, draft));
        const cases = {
          storedAgainstItself: run(stored),
          aNewLineAtTheEnd: run(stored + '\ndelta'),
          aLineEditedInPlace: run('alpha\nBRAVO changed\ncharlie'),
          aLineInsertedOnTop: run('zero\n' + stored),
          aLineRemovedFromTheTop: run('bravo\ncharlie'),
          // the case that says why a text-keyed lookup was refused: the second `alpha`
          // is at index 1, where the stored body has `bravo`, so it is untold. A
          // text-keyed match would have tinted it 1.00 — confidently, and wrongly.
          twoIdenticalLines: run('alpha\nalpha\ncharlie')};
        const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);
        return {pass: eq(cases.storedAgainstItself, [1, 0.5, 0])
                      && eq(cases.aNewLineAtTheEnd, [1, 0.5, 0, null])
                      && eq(cases.aLineEditedInPlace, [1, null, 0])
                      && eq(cases.aLineInsertedOnTop, [null, null, null, null])
                      && eq(cases.aLineRemovedFromTheTop, [null, null])
                      && eq(cases.twoIdenticalLines, [1, null, 0]),
                evidence: cases};
      });

      // 27. **the toggle is in edit mode and it draws the draft.** Two arms of the same
      //     claim: a line typed at the end is untold while everything above keeps its
      //     tint, and a line changed in place goes untold on its own. If the editor
      //     were tinting the *stored* body it would pass neither, and if it were
      //     tinting nothing it would pass the untold half and fail the kept half —
      //     which is why both are in one check.
      await check('27 edit mode tints the draft: a typed line is untold, its neighbours are not',
        async () => {
          const stored = row().description;
          st.open_recipe_editor(id);
          await until(() => document.querySelector('.recipe-page-edit'));
          const before = {toggle: !!toggle(), label: toggle()?.textContent?.trim(),
                          textarea: !!document.querySelector('.recipe-page-edit-body')};
          // an untouched draft has to read exactly as the reading does
          toggle().click();
          await until(() => document.querySelector('.provenance-source'));
          const untouched = {rows: lines().length, untold: untoldFlags().filter(Boolean).length,
                             textareaGone: !document.querySelector('.recipe-page-edit-body')};

          await editBody(stored + '\nA line the owner has just typed.');
          const typed = {rows: lines().length, flags: untoldFlags()};

          const storedLines = stored.split('\n');
          const edited = storedLines.slice();
          edited[1] = storedLines[1] + ' — CHANGED';
          await editBody(edited.join('\n'));
          const changed = {rows: lines().length, flags: untoldFlags()};

          return {pass: before.toggle && before.textarea && untouched.textareaGone
                        && untouched.untold === 0 && untouched.rows > 2
                        // the typed line, and only it
                        && typed.rows === untouched.rows + 1
                        && typed.flags[typed.flags.length - 1] === true
                        && typed.flags.slice(0, -1).every(f => f === false)
                        // the changed line, and only it
                        && changed.rows === untouched.rows
                        && changed.flags[1] === true
                        && changed.flags.filter(Boolean).length === 1,
                  evidence: {before, untouched, typed, changed}};
        });

      // 28. **a line inserted at the top makes every line below it untold, and that is
      //     the rule working rather than a bug.** Asserted deliberately, because it is
      //     the arm somebody will read as a defect and "fix" with a diff.
      //
      //     The rule is index-and-text, so an insertion shifts every following line off
      //     the index its caution belongs to. The alternative — carrying the tints down
      //     — would mean guessing, and a wrong guess here is the view telling the reader
      //     that the owner wrote a line an agent wrote. Under-claiming reads as *we do
      //     not know*, which is true. `provenance/draft-cautions` argues it at length,
      //     including why `views.diff` is not a source of a line-diff.
      await check('28 a line inserted on top makes the rest untold — the conservative arm',
        async () => {
          const stored = row().description;
          await editBody('A line inserted at the very top.\n' + stored);
          const flags = untoldFlags();
          return {pass: flags.length === stored.split('\n').length + 1
                        && flags.every(Boolean),
                  evidence: {rows: flags.length, allUntold: flags.every(Boolean), flags,
                             why: 'index+text alignment: an insertion shifts every line '
                                  + 'below it, so none of them is at the index its '
                                  + 'caution belongs to. Under-claiming is the design.'}};
        });

      // 29. **Cancel out of the editor and the reading is untouched.** The draft never
      //     reached the server, so the stored ranges cannot have moved — but the draft
      //     *is* app-state now, and `show-page!` dropping it on every page move is what
      //     makes that true. This is the assertion that those two facts meet.
      await check('29 Cancel leaves the reading\'s provenance exactly as it was', async () => {
        const stored = row().description;
        st.cancel_recipe_edit();
        await until(() => document.querySelector('.recipe-page-body'));
        await wait(150);
        toggle().click();
        await until(() => document.querySelector('.provenance-source'));
        const reading = {rows: lines().length, untold: untoldFlags().filter(Boolean).length};
        return {pass: reading.rows === stored.split('\n').length
                      && reading.untold === 0
                      && Object.keys(stateGet('recipe-draft') || {}).length === 0
                      && row().description === stored,
                evidence: {reading, draft: stateGet('recipe-draft'),
                           storedUnchanged: row().description === stored}};
      });

      // Leave it on, and on this Recipe: this is the surface a human is being asked
      // to look at, and a phase that tidied it away would have him hunt for it.
      await step('leave the provenance view on', async () => {
        if (!document.querySelector('.provenance-source')) toggle().click();
        await until(() => document.querySelector('.provenance-source'));
      });
      notes.push('left on ' + path() + ' with the provenance view showing');
      return done({subject: {id, title: subject.title, url: '/recipe/' + id}});
    },

    // ---- phase seven: the filing, which writes without making a version ------
    // **The only phase in this file that changes a Recipe**, which is why it works on
    // the seeded `CHECK-PROV` and not on `SUBJECT`: filing is a write, and a suite
    // that promised to write nothing must not start writing to his shelf. It puts the
    // fixture back where it found it — filed under nothing — and `cleanup.py` removes
    // the fixture anyway.
    //
    // ---- the draft preview, against the answer the save produces --------------
    //
    // **The owner reported this one and named the case himself**: *the interesting
    // case is when i insert human edit into agentic surroundings*. Insert one
    // hand-written line into six an agent wrote and the preview used to show three
    // red lines and then four blank ones — his own new line among them — because the
    // old rule kept a stored caution only where a draft line sat at the *same index*
    // and read the same, so one insertion untold everything below it. Then Save came
    // back red, blue, red, and *i believe when i save that is different afterwards*.
    //
    // So the phase asserts the two halves of that sentence: what the preview draws
    // now, and that it is what the API says once the draft has landed. Check 35 is
    // the complaint itself, and it is the reason this phase builds a fixture and
    // saves on it rather than borrowing CHECK-PROV — a save moves the ladder of
    // cautions the `provenance` phase reads, which is the argument `save()` already
    // makes one phase up.
    //
    // Check 36 is the other direction and matters more than its size suggests: the
    // rule that a line matched to nothing is *his* must not survive an alignment that
    // was never computed. Its fixture is 250 lines because that is what it takes to
    // go past `alignment-budget` once the common head and tail are off, and a body
    // replaced wholesale is the shape that does it.
    // **Takes an optional machine token**, unlike `save()`, which logs in as
    // `machine-user`/`pw`. That pair is what a fresh dev database is seeded with and
    // it is still the default here — but a dev database is a place where passwords
    // get rotated by hand, and this one's had been, so `save()` and this phase both
    // died at the login with nothing wrong in the app. Mint one from the backend
    // nREPL (`(et.cb.auth/create-machine-token nil "machine-user")`) and pass it in
    // when that happens; the fixtures this builds need a machine's authorship and
    // there is no other way to write one.
    draftProvenance: async (givenToken) => {
      const {check, step, done, notes} = runner();
      const api = async (p, {method, body, token} = {}) => {
        const r = await fetch('/api/' + p, {
          method: method || (body ? 'POST' : 'GET'),
          headers: Object.assign({'Content-Type': 'application/json'},
                                 token ? {Authorization: 'Bearer ' + token} : {}),
          body: body === undefined ? undefined : JSON.stringify(body)});
        let parsed = null;
        try { parsed = JSON.parse((await r.text()) || 'null'); } catch (e) { parsed = null; }
        return {status: r.status, body: parsed};
      };
      // The reported body, near enough: six lines an agent wrote, two of them empty,
      // and the empties are not decoration. Under the old rule a blank line could
      // keep its caution by *coincidence* — same index, same text — while its
      // neighbours went untold, which is how the screenshot came to have one lone red
      // band in a field of white. If an index rule is ever restored, checks 33 and 34
      // catch it; these two lines are what make the failure look like the report.
      const AGENT_BODY = 'Use ragtime for migrations.\n'
                         + '\n'
                         + 'Each migration is an edn map.\n'
                         + 'Keep :transactions false for SQLite.\n'
                         + '\n'
                         + 'See also the rollback notes.';
      const HIS_LINE = 'IMPORTANT: I always run these against a copy first.';
      const INSERT_AFTER = 3;                       // 1-based; his line becomes line 4

      const login = givenToken ? null
                    : await api('auth/login',
                                {body: {username: 'machine-user', password: 'pw'}});
      const token = givenToken || (login && login.body || {}).token;
      if (!token) throw new Error('no machine token: ' + JSON.stringify(login)
                                  + ' — the dev password has drifted from the seeded '
                                  + 'one; mint a token on the nREPL and pass it to '
                                  + 'draftProvenance(token), see the comment above');
      if (givenToken) notes.push('ran with a token supplied by the runner, not a login');
      const build = async (title, description) => {
        const made = await api('recipes', {token, body: {
          title, useful_when: 'the draft preview must agree with the save', description}});
        if (made.status !== 201)
          throw new Error('could not build ' + title + ': ' + JSON.stringify(made));
        notes.push('built ' + title + ' as recipe ' + made.body.id
                   + ' and left it for cleanup.py');
        return made.body.id;
      };
      const id = await build('CHECK-DRAFT one hand-written line in agent surroundings',
                             AGENT_BODY);
      const row = () => (stateGet('details') || {})[id] || {};
      const toggle = () => document.querySelector('.recipe-page-provenance-toggle');
      const lines = () => [...document.querySelectorAll('.provenance-line')];
      const textOf = el => el.querySelector('.provenance-line-text').textContent;
      // `null` for an untold row and a number for a told one, which is the distinction
      // the whole phase turns on — `parseFloat` of an absent custom property is NaN,
      // and NaN compares false against everything including itself, so a check written
      // on it would pass by accident in both directions.
      const drawn = () => lines().map(el =>
        el.classList.contains('provenance-line-untold')
          ? null : parseFloat(el.style.getPropertyValue('--caution')) / 100);

      await step('open the fixture at its own address', () => st.open_recipe_page(id));
      await until(() => page() && document.querySelector('.recipe-page-body') && toggle(),
                  8000);
      if (!toggle())
        throw new Error('no provenance toggle on the fresh fixture — is the API sending'
                        + ' caution on the full read?');
      // Guaranteed where the fixture is built rather than asserted in a check, as
      // `save()` does: a check going red because the *setup* was not what it thought
      // would be blaming the app for the harness.
      const stored = perLine(row().caution);
      if (!(stored.length === 6 && stored.every(v => v === 0)))
        throw new Error('a machine-written v1 should read 0.00 on every line, got '
                        + JSON.stringify(stored));

      const openDraftPreview = async description => {
        st.open_recipe_editor(id);
        await until(() => document.querySelector('.recipe-page-edit-body'));
        type(document.querySelector('.recipe-page-edit-body'), description);
        await until(() => (stateGet('recipe-draft') || {}).description === description);
        if (toggle().textContent.trim() === 'Show provenance') toggle().click();
        await until(() => document.querySelector('.provenance-source'));
        await wait(50);
      };

      const edited = (() => {
        const l = AGENT_BODY.split('\n');
        l.splice(INSERT_AFTER, 0, HIS_LINE);
        return l.join('\n');
      })();

      // 33. **the lines he did not touch keep what the API said about them**, at
      //     their new numbers. This is the whole of the regression: they are the same
      //     lines, one row further down, and an insertion is not an opinion about
      //     them. Under the old rule every one of these was untold.
      //
      //     Asserted against `stored` — the API's own answer for the body before the
      //     edit, spread per line — so this compares the view against the server and
      //     not against itself.
      await check('33 an insertion leaves every other line with the caution the API gave it',
        async () => {
          await openDraftPreview(edited);
          const got = drawn();
          const rows = lines().map(textOf);
          // draft row j -> stored row index, for every row except the inserted one
          const expected = stored.slice(0, INSERT_AFTER)
                                 .concat([undefined])          // his line: check 34
                                 .concat(stored.slice(INSERT_AFTER));
          const others = got.filter((_, j) => j !== INSERT_AFTER);
          const othersExpected = expected.filter((_, j) => j !== INSERT_AFTER);
          // **The inserted row is left out of both halves of this**, including the
          // untold count, and that is what keeps this check and 34 separate: the
          // typed line is 34's whole subject, and a 33 that also asserted something
          // about it would go red for two unrelated reasons and say which only in
          // its evidence. M47 is the mutation that proved the point — it left every
          // other line right and 33 reddened anyway.
          return {pass: rows.join('\n') === edited
                        && got.length === 7
                        && others.every((v, k) => v === othersExpected[k])
                        && !others.some(v => v === null),
                  evidence: {drawn: got, expectedForTheOthers: othersExpected,
                             untoldAmongTheOthers: others.filter(v => v === null).length,
                             theRowsAreTheDraft: rows.join('\n') === edited}};
        });

      // 34. **the line he is typing reads as his.** The claim the second half of
      //     `draft-cautions` makes, and the one thing the old preview could never say:
      //     this field's Save writes a `ui` version, so a line that is in the draft and
      //     in no stored line got there by his hand.
      await check('34 the inserted line previews at 1.00 — his', async () => {
        const got = drawn();
        return {pass: got[INSERT_AFTER] === 1
                      && lines()[INSERT_AFTER] && textOf(lines()[INSERT_AFTER]) === HIS_LINE,
                evidence: {atTheInsertion: got[INSERT_AFTER],
                           text: lines()[INSERT_AFTER] && textOf(lines()[INSERT_AFTER]),
                           whole: got}};
      });

      // 35. **the complaint, as an assertion**: *i believe when i save that is
      //     different afterwards*. The preview is recorded, Save is pressed while it
      //     is on screen, and what the API then says about the version that landed is
      //     compared against it, line for line.
      //
      //     It can only be checked by saving, which is why this phase owns its
      //     fixture. Equality is the assertion and not merely "no longer blank": a
      //     preview that had gone confidently *wrong* would satisfy anything weaker,
      //     and wrong is the direction that matters.
      await check('35 the preview is what the save produces, line for line', async () => {
        const before = {version: row().version, preview: drawn()};
        clickIn(document.querySelector('.top-bar-left'), '.recipe-edit-save');
        await until(() => document.querySelector('.recipe-page-body')
                          && row().version === before.version + 1, 8000);
        await wait(150);
        const after = perLine(row().caution);
        return {pass: before.preview.length === after.length
                      && before.preview.every((v, i) => v === after[i])
                      && row().version === before.version + 1,
                evidence: {preview: before.preview, afterSave: after,
                           version: [before.version, row().version]}};
      });

      // 36. **the safety valve, which must fail towards untold and never towards his.**
      //     Past `alignment-budget` no alignment is computed, and *we did not work it
      //     out* has to stay distinguishable from *you typed this* — collapse the two
      //     and a pasted-in body would be claimed as his work, wholesale, which is the
      //     one confident lie this preview must not tell.
      //
      //     Two hundred and fifty lines against two hundred and fifty different ones,
      //     because the budget is only reached once the common head and tail are off
      //     and a wholesale replacement is what leaves nothing to trim.
      await check('36 past the alignment budget the preview says untold, not "yours"',
        async () => {
          const many = n => Array.from({length: 250}, (_, i) => n + ' line ' + i).join('\n');
          const bigId = await build('CHECK-DRAFT-BIG a body replaced wholesale', many('agent'));
          st.open_recipe_page(bigId);
          await until(() => page() && document.querySelector('.recipe-page-body')
                            && document.querySelector('.recipe-page-provenance-toggle'), 8000);
          st.open_recipe_editor(bigId);
          await until(() => document.querySelector('.recipe-page-edit-body'));
          const replacement = many('mine');
          type(document.querySelector('.recipe-page-edit-body'), replacement);
          await until(() => (stateGet('recipe-draft') || {}).description === replacement);
          if (toggle().textContent.trim() === 'Show provenance') toggle().click();
          await until(() => document.querySelector('.provenance-source'), 8000);
          await wait(100);
          const got = drawn();
          return {pass: got.length === 250 && got.every(v => v === null),
                  evidence: {rows: got.length, untold: got.filter(v => v === null).length,
                             claimedAsHis: got.filter(v => v === 1).length,
                             first: got.slice(0, 3)}};
        });

      notes.push('left on ' + path());
      return done({subject: {id, url: '/recipe/' + id}});
    },

    // Needs **two** of the owner's Scopes to exist for check 21. Dev has three.
    //
    // What these two are about is the split the whole change turns on: *yeah, we dont
    // need no version bump on this and can go to the read page*. The API agrees —
    // `update-recipe-handler`: *Changing it makes no version either — a Scope is a
    // way back to a Recipe, not part of it* — and nothing else in either suite
    // asserts it.
    filing: async () => {
      const {check, step, done, notes} = runner();
      const subject = rowFor(MIXED);
      if (!subject) throw new Error('no ' + MIXED + ' Recipe on the shelf — run '
                                    + 'test/browser/provenance-seed.py first, see README');
      const id = subject.id;
      const row = () => (stateGet('details') || {})[id] || {};
      const picker = () => document.querySelector('.scope-picker.recipe-page-filing');
      const chips = () => [...(picker()?.querySelectorAll('.scope-chip') || [])];
      const lit = () => chips().filter(b => b.classList.contains('on'))
                               .map(b => b.textContent.trim());
      const filedIds = () => (row().scopes || []).map(s => s.id).sort();

      await step('open the fixture at its own address', () => st.open_recipe_page(id));
      await until(() => page() && stateGet('recipe-page-status') === 'found');
      // On the picker and not on the page: `fetch-scopes` is a second request and the
      // chips are what these checks press.
      await until(() => chips().length > 0, 8000);
      if (chips().length < 2)
        throw new Error('this phase needs two of the owner\'s Scopes and found '
                        + chips().length + ' — make one on the Scopes page');
      const startedFiledUnder = filedIds();
      if (startedFiledUnder.length)
        notes.push('the fixture arrived filed under ' + startedFiledUnder.length
                   + ' Scope(s); it is put back that way at the end');

      // **Bring the fixture to a known state before asserting anything, and put it
      // back after.** Both checks below toggle chips and read what happened, so both
      // need to know what they started from — and a run that goes red can leave the
      // fixture filed, which then makes the *next* run assert the opposite of what it
      // means. That happened: check 21 failed on a paint race, its tidy-up read the
      // un-repainted chips, found none lit and unfiled nothing, and the following run
      // saw a fixture filed under two Scopes and reddened check 20 as well. One red
      // check should cost one column, not the next run.
      //
      // Driven through `state` and not through the chips, deliberately: this is
      // *cleanup* and not an assertion, so it should be the most robust thing
      // available rather than the most faithful to a click. `toggle-recipe-scope` is
      // the only writer of the filing, so the set is reached by toggling the symmetric
      // difference — and waited on in the atom, where paint timing cannot reach.
      const fileExactly = async (target) => {
        const now = filedIds();
        // `scopeId` and not `id`, which is the Recipe's and would be shadowed here
        for (const scopeId of [...new Set([...now, ...target])])
          if (now.includes(scopeId) !== target.includes(scopeId))
            st.toggle_recipe_scope(id, scopeId);
        await until(() => !stateGet('filing')
                          && JSON.stringify(filedIds()) === JSON.stringify([...target].sort()),
                    8000);
        return filedIds();
      };
      await step('start from filed-under-nothing', () => fileExactly([]));

      // 20. **filing writes, and the version does not move.** Two claims that only
      //     make sense together: a toggle really does reach the server — the receipt
      //     comes back and says so — *and* the version badge is the same number
      //     afterwards. A filing save routed through `update-recipe` would pass the
      //     first half and fail the second, which is exactly the mistake the state
      //     fn exists to not make.
      //
      //     It toggles a chip **on and then off again**, so the second half is the
      //     empty-array case: the API keeps the filing for an omitted `scope_ids` and
      //     clears it for `[]`, and a picker that sent what was selected rather than
      //     always sending an array would silently do the first at exactly the moment
      //     the owner unfiled his last Scope.
      await check('20 a toggle files the Recipe, and the version does not move', async () => {
        const versionBefore = text('.version-badge');
        const rowVersionBefore = row().version;
        const stampBefore = row().modified_at;
        const first = chips()[0], firstName = first.textContent.trim();

        // **Both conditions, and the DOM one is not redundant.** `:filing` going nil
        // says the receipt landed; the chips repaint a frame later, so a wait on the
        // state alone reads the *previous* paint and reddens this about the app. That
        // is this directory's first house rule — wait on the visible consequence —
        // and it cost a red to remember here.
        first.click();
        await until(() => !stateGet('filing') && lit().includes(firstName));
        const filed = {lit: lit(), ids: filedIds(), version: text('.version-badge'),
                       rowVersion: row().version, stamp: row().modified_at};

        // and off again, which is the `[]` request
        chips().find(b => b.textContent.trim() === firstName).click();
        await until(() => !stateGet('filing') && lit().length === 0);
        const cleared = {lit: lit(), ids: filedIds(), version: text('.version-badge'),
                         rowVersion: row().version};

        // **The receipt is the proof that the write happened, and the stamp is not.**
        // `modified_at` has one-second resolution, so two saves inside the same second
        // carry the same value — which made `filed.stamp !== stampBefore` pass when
        // this phase was run once and fail when it was run twice in a row, about an
        // app that was doing the right thing both times. The row coming back with the
        // Scope on it is what says the server wrote; that is asserted, and both stamps
        // stay in the evidence because they are worth reading. (It is the same
        // one-second fact that makes a double-clicked chip 409 only sometimes — see
        // `state/toggle-recipe-scope`.)
        return {pass: filed.ids.length === 1 && filed.lit.includes(firstName)
                      && filed.version === versionBefore
                      && filed.rowVersion === rowVersionBefore
                      && cleared.ids.length === 0 && cleared.lit.length === 0
                      && cleared.version === versionBefore
                      && cleared.rowVersion === rowVersionBefore
                      && !stateGet('error'),
                evidence: {versionBefore, rowVersionBefore, filed, cleared,
                           stampMoved: filed.stamp !== stampBefore,
                           stampBefore, stampAfter: filed.stamp,
                           error: stateGet('error')}};
      });

      // 21. **two chips pressed in one frame both land**, which is two failures at
      //     once and neither is visible from the outside:
      //
      //     - the save moves `modified_at` and the PUT carries it as the 409 guard,
      //       so a second request sent while the first is out is refused and a chip
      //       springs back. `state/toggle-recipe-scope` queues instead — the evidence
      //       below reads `:filing` straight after the two clicks, where `:wanted`
      //       differing from `:sent` *is* the queue.
      //     - and the next set has to be computed from the live one rather than from
      //       what was rendered, or the second click overwrites the first's intent
      //       and one chip he pressed is simply not filed. That is the shape this
      //       check was written against, having happened.
      await check('21 two chips in one frame both land', async () => {
        const a = chips()[0], b = chips()[1];
        const names = [a.textContent.trim(), b.textContent.trim()].sort();
        a.click();
        b.click();                                   // same frame: no await between
        const queued = stateGet('filing');
        // **Both conditions, and the DOM one is not redundant** — the same trap check
        // 20 records, and this check went red on it once: `:filing` clearing says the
        // receipt landed, the chips repaint a frame later, and `until` returns on its
        // first poll without awaiting when the state is already right. So the wait has
        // to include the *visible* consequence or the assertion reads the previous
        // paint and blames the app.
        await until(() => !stateGet('filing') && lit().length === 2, 8000);
        const after = {lit: lit().sort(), ids: filedIds(),
                       version: text('.version-badge')};
        return {pass: after.lit.join(',') === names.join(',')
                      && after.ids.length === 2
                      && !!queued && JSON.stringify(queued.wanted) !== JSON.stringify(queued.sent)
                      && !stateGet('error'),
                evidence: {pressed: names, rightAfterBothClicks: queued, after,
                           theQueueIsWhatWantedDifferingFromSentMeans: true,
                           error: stateGet('error')}};
      });

      // Put the fixture back exactly as it was found, by the same state-driven route
      // and for the same reason.
      await step('restore the filing this phase found', () => fileExactly(startedFiledUnder));
      notes.push('left on ' + path() + ', ' + MIXED + ' filed under '
                 + filedIds().length + ' Scope(s)');
      return done({subject: {id, title: subject.title, url: '/recipe/' + id}});
    },

    // ---- phase eight: the shelf abbreviates a long body ---------------------
    // Checks 37 to 40. Runs from the shelf and leaves the browser there.
    //
    // **It builds its own fixture**, and needs no machine token to do it: nothing
    // here is about authorship, so the plain POST dev reads as the owner's is
    // enough. It builds rather than borrows for `save()`'s reason turned around —
    // what these checks need is a body *longer than the threshold*, and a length
    // that is the whole point of the fixture is not something a run may depend on
    // a Recipe of his still having. `SUBJECT` is two blocks and would make 37, 38
    // and 39 vacuously green.
    //
    // It writes one Recipe, files the `created` event any POST files, and leaves
    // both for `cleanup.py` — which removes CHECK- Recipes *and* their events, for
    // the reason it uses sqlite at all.
    clampedBody: async () => {
      const {check, step, done, notes} = runner();
      if (!shelf()) throw new Error('this phase is about the shelf, and the page is at '
                                    + path() + ' — go to / and run it again');
      const api = async (p, {method, body} = {}) => {
        const r = await fetch('/api/' + p, {
          method: method || (body ? 'POST' : 'GET'),
          headers: {'Content-Type': 'application/json'},
          body: body === undefined ? undefined : JSON.stringify(body)});
        let parsed = null;
        try { parsed = JSON.parse((await r.text()) || 'null'); } catch (e) { parsed = null; }
        return {status: r.status, body: parsed};
      };

      // **The threshold is read out of the app and not written down here.** It is
      // `views.recipes/visible-blocks`, and a copy of the number in this file would
      // let the two drift with nothing going red: turn the app's 10 into a 5 and a
      // suite holding its own 10 reddens 37 for a change that was made on purpose,
      // while a suite that had also been "kept in sync" would prove nothing at all.
      const VISIBLE = window.et?.cb?.ui?.views?.recipes?.visible_blocks;
      if (typeof VISIBLE !== 'number')
        throw new Error('cannot read views.recipes/visible-blocks — has the clamp moved '
                        + 'namespace, or is this a release build?');
      const TOTAL = VISIBLE + 4;                       // four blocks past the cut
      const MARK = n => 'MARK-' + String(n).padStart(2, '0');
      // Two digits, so that a `includes(MARK(1))` cannot be satisfied by MARK-14.
      const para = n => MARK(n) + ' a paragraph of the body, one of the ones a reader '
                        + 'either meets on the card or has to ask for.';
      // A fenced block **with blank lines in it, placed where a blank-line split
      // would cut it in half.** Fence-aware it is one block and it is the last one
      // before the cut; split naively its three pieces are blocks VISIBLE-1,
      // VISIBLE and VISIBLE+1, so a rule that counts them separately shows two of
      // them, leaves the fence open, and hides a line of code the reader can see
      // the beginning of. Check 39 asserts that arrangement as well as the outcome,
      // because a fixture that stopped being cut by the naive rule would leave 39
      // green while proving nothing.
      const CODE = ['```clojure',
                    ';; ' + MARK(VISIBLE) + '-CODE-A',
                    '(defn a [] :a)',
                    '',
                    ';; ' + MARK(VISIBLE) + '-CODE-B',
                    '(defn b [] :b)',
                    '',
                    ';; ' + MARK(VISIBLE) + '-CODE-C',
                    '(defn c [] :c)',
                    '```'].join('\n');
      const blocks = [];
      for (let n = 1; n < VISIBLE; n++) blocks.push(para(n));
      blocks.push(CODE);                               // block VISIBLE, the last shown
      for (let n = VISIBLE + 1; n <= TOTAL; n++) blocks.push(para(n));
      const BODY = blocks.join('\n\n');
      const TITLE = 'CHECK-CLAMP a body longer than a card shows';

      const made = await api('recipes', {body: {
        title: TITLE, useful_when: 'the shelf must abbreviate a long body',
        description: BODY}});
      if (made.status !== 201)
        throw new Error('could not build the fixture: ' + JSON.stringify(made));
      notes.push('built ' + TITLE + ' as recipe ' + made.body.id
                 + ' and left it for cleanup.py');

      // Onto the shelf it goes by a refetch, because the listing this client holds
      // was fetched before the fixture existed.
      await step('refetch the listing', () => st.fetch_recipes());
      await until(() => cardFor(TITLE), 8000);
      if (!cardFor(TITLE)) throw new Error('the fixture is not on the shelf');
      await step('expand the fixture', () => clickIn(cardFor(TITLE), '.card-header'));
      await until(() => cardFor(TITLE)?.querySelector('.card-body'), 8000);

      const body = () => cardFor(TITLE)?.querySelector('.card-body');
      const shown = () => body()?.textContent || '';
      const seeMore = () => cardFor(TITLE)?.querySelector('.see-more');
      const marksIn = t => {
        const out = [];
        for (let n = 1; n <= TOTAL; n++) if (t.includes(MARK(n))) out.push(n);
        return out;
      };
      const upTo = n => { const out = []; for (let i = 1; i <= n; i++) out.push(i); return out; };

      // 37. **an expanded card shows the first blocks and holds the rest back.**
      //     The complaint this came from: *when uncollapsing a card, it should not
      //     show the full text immediately.* Asserted as the exact set of blocks —
      //     `1..VISIBLE` present and nothing after them — rather than as "shorter
      //     than the whole", which a body cut anywhere at all would satisfy.
      await check('37 an expanded card abbreviates a long body', () => {
        const marks = marksIn(shown());
        return {pass: !!body() && !!seeMore()
                      && marks.join(',') === upTo(VISIBLE).join(','),
                evidence: {visibleBlocksInTheApp: VISIBLE, blocksInTheFixture: TOTAL,
                           marksShown: marks, expected: upTo(VISIBLE),
                           seeMoreOffered: !!seeMore(),
                           charactersShown: shown().length, charactersStored: BODY.length}};
      });

      // 39. **the cut falls between blocks and never inside a fenced code block.**
      //     Cookbook's own case rather than tracker's: these bodies carry code with
      //     blank lines in it, and a blank-line split both counts one listing as
      //     three blocks and can cut between two of them — leaving the shown half
      //     with an unclosed fence, which marked reads as code running on to the end
      //     of the text. So this asserts a *complete* listing: one `pre`, all three
      //     of its lines, and the naive rule shown to have cut it. That last one is
      //     what keeps the check from going quietly vacuous.
      //
      //     Before 38, because 38 is what asks for the rest — and what this check is
      //     about is the abbreviated reading.
      await check('39 the abbreviation keeps a fenced code block whole', () => {
        const naiveShown = BODY.split(/\r?\n\r?\n+/).slice(0, VISIBLE).join('\n\n');
        const naiveCutsTheFence = ((naiveShown.match(/```/g) || []).length % 2) === 1;
        const pres = [...(body()?.querySelectorAll('pre') || [])];
        const lines = ['A', 'B', 'C'].filter(x => shown().includes('CODE-' + x));
        return {pass: naiveCutsTheFence && pres.length === 1 && lines.length === 3,
                evidence: {theFixtureIsCutByABlankLineSplit: naiveCutsTheFence,
                           preBlocks: pres.length, codeLinesShown: lines,
                           codeAsRendered: pres[0]?.textContent}};
      });

      // 38. **See more shows the whole body and stops offering.** Both halves: an
      //     affordance that stays after it has been used reads as a control that did
      //     nothing, and it is the second press that would have nothing left to do.
      await check('38 See more shows the rest of the body, and goes', async () => {
        clickIn(cardFor(TITLE), '.see-more');
        await until(() => !seeMore());
        const marks = marksIn(shown());
        return {pass: marks.join(',') === upTo(TOTAL).join(',') && !seeMore(),
                evidence: {marksShown: marks, expected: upTo(TOTAL),
                           seeMoreStillThere: !!seeMore(),
                           charactersShown: shown().length, charactersStored: BODY.length}};
      });

      // 40. **a body short enough to show gets no affordance at all**, and the
      //     control matters here as much as the clamp: *every* card wearing a See
      //     more is what a threshold of zero looks like, and it would pass 37 and 38
      //     without either of them noticing. `SUBJECT` is the short Recipe the rest
      //     of this file already depends on being there; the check reads its block
      //     count out of `body-blocks` rather than assuming it, so a run against a
      //     database where it has grown says which it was.
      await check('40 a short body is shown whole, with nothing to press', async () => {
        const row = rowFor(SUBJECT);
        if (!row) throw new Error('no Recipe named ' + SUBJECT + ' on the shelf — see README');
        if (!cardFor(SUBJECT)?.querySelector('.card-body')) {
          clickIn(cardFor(SUBJECT), '.card-header');
          await until(() => cardFor(SUBJECT)?.querySelector('.card-body'), 8000);
        }
        const stored = ((stateGet('details') || {})[row.id] || {}).description || '';
        // `c.count` and not `.length`: `body-blocks` answers with a cljs vector, whose
        // `.length` is `undefined` — and `undefined <= 10` is false, so the first
        // version of this check went red with no `blocksInIt` in its evidence at all,
        // because `JSON.stringify` drops the key. Which is the shape of a check that
        // fails for a reason inside itself.
        const count = c.count(window.et.cb.ui.views.recipes.body_blocks(stored));
        return {pass: count > 0 && count <= VISIBLE
                      && !!cardFor(SUBJECT)?.querySelector('.card-body')
                      && !cardFor(SUBJECT)?.querySelector('.see-more'),
                evidence: {subject: SUBJECT, blocksInIt: count, threshold: VISIBLE,
                           seeMoreOffered: !!cardFor(SUBJECT)?.querySelector('.see-more')}};
      });

      await step('collapse the two cards this phase opened', () => {
        clickIn(cardFor(TITLE), '.card-header');
        clickIn(cardFor(SUBJECT), '.card-header');
      });
      notes.push('left on ' + path());
      return done({fixture: {id: made.body.id, title: TITLE, blocks: TOTAL}});
    },

    // ---- the shelf's positive Scope filter, and the gate between the two ------
    // *and on the main page, below the searchbar, list all scopes and have them be
    // an OR filter for scopes*, and then *ah ok yeah. but when no negative filter
    // is selecgted, allow to select positively.*
    //
    // **The matrix itself is tested in Clojure** — `et.cb.filters-test`, over every
    // state the two filters can be in, without a DOM. What is here is the half that
    // file cannot see: that the shelf *wires* it, that a refused chip is refused
    // visibly, and that a refused badge click does not fall through and open the
    // card. A green matrix over a UI that ignored it is exactly the shape this
    // suite exists to catch.
    //
    // **It builds its own Scopes and its own Recipes** and takes the Scopes back
    // out at the end. `cleanup.py` removes CHECK- *Recipes* and knows nothing about
    // Scopes, so a phase that left two behind would leave his picker two chips
    // longer every run — the one kind of litter this suite must not make, since the
    // control under test is a list of them.
    scopeFilter: async () => {
      const {check, step, done, notes} = runner();
      if (!shelf()) throw new Error('this phase is about the shelf, and the page is at '
                                    + path() + ' — go to / and run it again');
      const api = async (p, {method, body} = {}) => {
        const r = await fetch('/api/' + p, {
          method: method || (body ? 'POST' : 'GET'),
          headers: {'Content-Type': 'application/json'},
          body: body === undefined ? undefined : JSON.stringify(body)});
        let parsed = null;
        try { parsed = JSON.parse((await r.text()) || 'null'); } catch (e) { parsed = null; }
        return {status: r.status, body: parsed};
      };
      const mk = async (title, description) => {
        const r = await api('scopes', {body: {title, description}});
        if (r.status !== 201) throw new Error('could not make a Scope: ' + JSON.stringify(r));
        return r.body.id;
      };
      // Two Scopes, and three Recipes arranged so the **union** has something to
      // prove: one under each Scope and one under neither. A fixture where every
      // Recipe carried both would pass an AND just as happily.
      const left = await mk('CHECK-SCOPE-L', 'the left half of the union');
      const right = await mk('CHECK-SCOPE-R', 'the right half of the union');
      const made = [];
      for (const [title, ids] of [['CHECK-FILTER left only', [left]],
                                  ['CHECK-FILTER right only', [right]],
                                  ['CHECK-FILTER unfiled', []]]) {
        const r = await api('recipes', {body: {title, useful_when: 'when testing the filter',
                                               description: 'body', scope_ids: ids}});
        if (r.status !== 201) throw new Error('could not build a fixture: ' + JSON.stringify(r));
        made.push(r.body.id);
      }
      notes.push('built CHECK-SCOPE-L / -R and three CHECK-FILTER Recipes; the Scopes '
                 + 'are deleted at the end of this phase, the Recipes are cleanup.py\'s');
      // **Start from an unnarrowed shelf, and say so rather than assuming it.** This
      // phase is the one that leaves filters set if it fails part-way, so it is also
      // the one likeliest to be entered with a narrowing already up — and then its
      // own fresh fixtures are filtered off the shelf before a single check runs,
      // which surfaces as every card-shaped assertion failing for a reason that has
      // nothing to do with what they assert. Found exactly that way, twice, during
      // the mutation run: a phase that had thrown mid-way left `:included-scopes` set
      // and the next run could not see its own Recipes.
      await step('clear any narrowing a previous run left, and refetch', async () => {
        st.clear_included_scopes();
        st.clear_excluded_scopes();
        st.set_search('');
        st.fetch_scopes();
        st.fetch_recipes();
      });
      await until(() => (stateGet('included-scopes') || []).length === 0
                        && (stateGet('excluded-scopes') || []).length === 0, 8000);
      // And wait for this run's fixtures to be **on the shelf and drawn**, not merely
      // for the filters to be clear. The listing is a round trip and the badges are a
      // render after it; a phase that started measuring before either had landed was
      // the other half of the flakiness the reset step above fixes.
      await until(() => {
        const rows = stateGet('recipes') || [];
        return made.every(id => rows.some(r => r.id === id))
               && cards().some(c => c.textContent.includes('CHECK-FILTER left only')
                                    && [...c.querySelectorAll('.scope-badge')]
                                         .some(b => b.textContent.trim() === 'CHECK-SCOPE-L'));
      }, 10000);
      await until(() => cardFor('CHECK-FILTER unfiled')
                        && [...document.querySelectorAll('.shelf-scope-filter .scope-chip')]
                             .some(c => c.textContent.trim() === 'CHECK-SCOPE-L'), 8000);

      const chips = () => [...document.querySelectorAll('.shelf-scope-filter .scope-chip')];
      const chipFor = t => chips().find(c => c.textContent.trim() === t);
      const included = () => stateGet('included-scopes') || [];
      const excluded = () => stateGet('excluded-scopes') || [];
      // **This run's Recipes by id, and its cards by id-and-badge.** Found by
      // mutation rather than by design: the phase builds three Recipes with fixed
      // titles, so a second run before `cleanup.py` puts a second set of the same
      // three on the shelf — and every title-keyed assertion then reads the *older*
      // run's rows, which are filed under Scopes this phase has since deleted and so
      // carry no badges at all. Three checks went red under a mutation that had
      // nothing to do with any of them, which is the tell.
      const madeSet = new Set(made);
      const mine = () => (stateGet('recipes') || [])
        .filter(r => madeSet.has(r.id)).map(r => r.title);
      // A card of *this* run's, identified by carrying the badge this run made: an
      // earlier run's namesake has none, since its Scopes are gone.
      const cardWithBadge = (cardTitle, scopeTitle) =>
        cards().find(c => c.textContent.includes(cardTitle)
                          && [...c.querySelectorAll('.scope-badge')]
                               .some(b => b.textContent.trim() === scopeTitle));
      // **Awaited, not demanded.** A card carrying a badge is the consequence of a
      // listing having landed *and* reagent having drawn it, and this phase disturbs
      // the listing more than once — check 46 signs out and back in, which throws the
      // rows away and refetches them. Written as a plain lookup this threw
      // `no card for … carrying …` on the first run after that and passed on the
      // second, which is the house rule's first hazard wearing a different hat.
      const badgeIn = async (cardTitle, scopeTitle) => {
        const c = await until(() => cardWithBadge(cardTitle, scopeTitle), 8000);
        if (!c) throw new Error('no card for ' + cardTitle + ' carrying ' + scopeTitle
                                + ' — the listing never came back with its badges');
        return [...c.querySelectorAll('.scope-badge')]
          .find(x => x.textContent.trim() === scopeTitle);
      };
      // A real modifier click. `el.click()` cannot carry shiftKey, and the whole
      // gate turns on it — so the shift gestures go through a constructed
      // MouseEvent, which does set it. (Focus and text selection are the driving
      // session's business; `checks.js` says why some things need real keys.)
      const clickBadge = (b, shift) => b.dispatchEvent(
        new MouseEvent('click', {bubbles: true, cancelable: true, shiftKey: !!shift}));

      // 46. **the row is under the search and lists every Scope.**
      //
      //     Measured into numbers on the spot rather than kept as nodes: this phase
      //     re-renders the row more than once, and a detached element measures 0×0 —
      //     which `0 >= 0` satisfies, so the placement assertion passed vacuously on
      //     every run until the boxes were printed. The same trap `barActions()` 41
      //     met, and the reason both now keep booleans.
      await check('46 the Scope filter row sits under the search, one chip per Scope',
        async () => {
          const rowBox = document.querySelector('.scope-filter').getBoundingClientRect();
          const controlsBox = document.querySelector('.shelf-controls').getBoundingClientRect();
          const placement = {rowTop: Math.round(rowBox.top),
                             rowHeight: Math.round(rowBox.height),
                             controlsBottom: Math.round(controlsBox.bottom),
                             below: rowBox.top >= controlsBox.bottom,
                             laidOut: rowBox.height > 0 && controlsBox.height > 0};
          const all = (stateGet('scopes') || []).map(s2 => s2.title).sort();
          const drawn = chips().map(c => c.textContent.trim()).sort();
          const order = [...document.querySelectorAll('.shelf > *')]
            .map(e => (e.className || '').split(' ')[0]);
          return {pass: placement.laidOut && placement.below
                        && JSON.stringify(drawn) === JSON.stringify(all)
                        && drawn.length > 0
                        && order.indexOf('scope-filter') > order.indexOf('shelf-controls'),
                  evidence: {chipsDrawn: drawn, scopesInState: all, placement,
                             shelfOrder: order.filter(c => c !== 'card')}};
        });

      // 47. **one Scope narrows to it, two are the union, and the unfiled Recipe
      //     goes.** The union is the assertion with teeth: neither fixture carries
      //     both Scopes, so an AND would answer with nothing at all.
      await check('47 one Scope narrows, two are the union, unfiled falls out',
        async () => {
          chipFor('CHECK-SCOPE-L').click();
          await until(() => included().length === 1 && mine().length === 1, 8000);
          const one = mine();
          chipFor('CHECK-SCOPE-R').click();
          await until(() => included().length === 2 && mine().length === 2, 8000);
          const both = mine();
          const url = performance.getEntriesByType('resource').map(r => r.name)
            .filter(n => n.includes('include-scopes')).slice(-1)[0];
          document.querySelector('.clear-scope-filter').click();
          await until(() => included().length === 0 && mine().length === 3, 8000);
          return {pass: one.join(',') === 'CHECK-FILTER left only'
                        && both.length === 2
                        && both.includes('CHECK-FILTER left only')
                        && both.includes('CHECK-FILTER right only')
                        && !both.includes('CHECK-FILTER unfiled')
                        && /include-scopes=\d+,\d+/.test(url || '')
                        && mine().length === 3,
                  evidence: {withOneScope: one, withBoth: both, lastUrl: url,
                             afterClear: mine()}};
        });

      // 48. **the gate, in the UI.** Three states, and the middle one is the reason
      //     this check exists: with an exclusion up a plain badge click is refused
      //     **and consumed**, so the card must not expand — a fall-through would
      //     answer a filter gesture by opening something.
      await check('48 the two filters refuse each other, visibly', async () => {
        // plain badge click selects
        clickBadge(await badgeIn('CHECK-FILTER left only', 'CHECK-SCOPE-L'));
        await until(() => included().length === 1, 8000);
        const afterPlain = {included: included(), excluded: excluded()};
        // shift is refused while a selection is up
        clickBadge(await badgeIn('CHECK-FILTER left only', 'CHECK-SCOPE-L'), true);
        await wait(400);
        const shiftRefused = {included: included(), excluded: excluded()};
        document.querySelector('.clear-scope-filter').click();
        await until(() => included().length === 0, 8000);
        // shift from a clean slate excludes
        clickBadge(await badgeIn('CHECK-FILTER left only', 'CHECK-SCOPE-L'), true);
        await until(() => excluded().length === 1, 8000);
        await until(() => !mine().includes('CHECK-FILTER left only'), 8000);
        const excluding = {included: included(), excluded: excluded(),
                           chipsDisabled: chips().map(c => c.disabled),
                           note: text('.scope-filter-refused'),
                           hint: document.querySelector('.card-scopes .scope-badge')?.title};
        // and a plain badge click is now refused, and does not open the card
        const stillThere = cardWithBadge('CHECK-FILTER right only', 'CHECK-SCOPE-R');
        const openBefore = (stateGet('open') || []).length;
        clickBadge(await badgeIn('CHECK-FILTER right only', 'CHECK-SCOPE-R'));
        await wait(500);
        const plainRefused = {included: included(),
                              open: (stateGet('open') || []).length,
                              expanded: !!stillThere.querySelector(
                                '.card-body, .card-body-loading, .card-body-empty')};
        st.clear_excluded_scopes();
        await until(() => excluded().length === 0 && mine().length === 3, 8000);
        return {pass: afterPlain.included.length === 1 && afterPlain.excluded.length === 0
                      // shift changed nothing while a selection was up
                      && JSON.stringify(shiftRefused) === JSON.stringify(afterPlain)
                      && excluding.excluded.length === 1
                      && excluding.chipsDisabled.every(Boolean)
                      && (excluding.note || '').length > 10
                      && /hidden/i.test(excluding.hint || '')
                      // the refused plain click did nothing and opened nothing
                      && plainRefused.included.length === 0
                      && plainRefused.open === openBefore
                      && plainRefused.expanded === false,
                evidence: {afterPlain, shiftRefused, excluding, plainRefused, openBefore}};
      });

      // 49. **the empty shelf tells the truth about which narrowing emptied it.**
      //     The wording is the point: 'Nothing matches.' with a selection up would
      //     be the same lie `empty-message`'s docstring records being corrected for
      //     the exclusion, so the selection's sentence has to outrank the search's.
      await check('49 an empty result names the Scope selection, not the search',
        async () => {
          chipFor('CHECK-SCOPE-L').click();
          await until(() => included().length === 1, 8000);
          type(document.querySelector('.search'), 'zzzznothingmatchesthis');
          await until(() => document.querySelector('.empty'), 8000);
          const withBoth = text('.empty');
          type(document.querySelector('.search'), '');
          await until(() => !document.querySelector('.empty'), 8000);
          document.querySelector('.clear-scope-filter').click();
          await until(() => included().length === 0, 8000);
          type(document.querySelector('.search'), 'zzzznothingmatchesthis');
          await until(() => document.querySelector('.empty'), 8000);
          const searchOnly = text('.empty');
          type(document.querySelector('.search'), '');
          await until(() => !document.querySelector('.empty'), 8000);
          return {pass: /Scopes you picked/i.test(withBoth || '')
                        && !/Nothing matches/i.test(withBoth || '')
                        && !/No recipes yet/i.test(withBoth || '')
                        && /Nothing matches/i.test(searchOnly || ''),
                  evidence: {selectionAndSearch: withBoth, searchAlone: searchOnly}};
        });

      // 50. **owner-only, and the gate is real rather than a coincidence.**
      //
      //     Last in the phase on purpose: it signs the session out and back in, and
      //     every check above needs a settled signed-in shelf. Sitting in the middle
      //     it made three of them flaky — the listing is thrown away and refetched by
      //     a sign-in, so the next check raced a shelf that was still arriving. That
      //     is `signedOut()`'s reason for being a phase of its own, met inside one.
      //
      //     Dev cannot produce a genuine visitor, so `state/logout` is driven and the
      //     **client** rule is what is asserted — check 12's technique. It matters
      //     that this is checked rather than assumed to fall out: a visitor is sent no
      //     Scopes, so the picker would draw nothing for one **anyway**, and that
      //     coincidence is not the gate. The gate is real — `list-recipes` refuses a
      //     visitor `?include-scopes` outright — and a build with the `logged-in?`
      //     conjunct deleted would still look right here without this check's last
      //     two conjuncts, which is why the other two controls are asserted present.
      await check('50 the filter row is the owner\'s alone', async () => {
        st.logout();
        await until(() => stateGet('logged-in?') === false, 8000);
        await wait(300);
        const asVisitor = {row: !!document.querySelector('.scope-filter'),
                           chips: chips().length,
                           search: !!document.querySelector('.search'),
                           humanFilter: !!document.querySelector('.human-filter'),
                           cards: document.querySelectorAll('.card').length};
        st.fetch_auth_required();
        await until(() => stateGet('logged-in?') === true, 8000);
        const back = await until(() => document.querySelector('.scope-filter'), 8000);
        return {pass: !asVisitor.row && asVisitor.chips === 0
                      // the other two narrowings stay: this is a narrowing of the
                      // controls, not a signed-out shelf with no controls at all
                      && asVisitor.search && asVisitor.humanFilter
                      && asVisitor.cards > 0
                      // and it comes back with the session, so the absence was the
                      // gate and not a shelf that had broken
                      && !!back,
                evidence: {asVisitor, backAfterSignIn: !!back,
                           chipsBack: chips().length}};
      });

      await step('take the two Scopes back out', async () => {
        for (const id of [left, right]) await api('scopes/' + id, {method: 'DELETE'});
        st.fetch_scopes();
        st.fetch_recipes();
        await until(() => !chipFor('CHECK-SCOPE-L'), 8000);
      });
      notes.push('left on ' + path());
      return done({fixtures: {scopes: [left, right], recipes: made}});
    },

    // ---- the bar's right-hand slot, on a Recipe that can still be published ----
    // *In the Page view, put the Publish button in the top right, to the left of the
    // dark mode switcher.*
    //
    // **A phase of its own because it needs a Recipe that is not published**, and
    // `SUBJECT` is — which is exactly what makes `shelf()` 14 and 22 the *published*
    // case: both of them assert that the corner is the theme toggle alone, and both
    // would go on passing if Publish had never arrived. So this builds `CHECK-BAR`,
    // an ordinary owner's Recipe with a body, and it is the only phase in this file
    // that presses a **latch**: check 43 publishes the fixture and there is no
    // unpublish. Nothing of his is ever what it publishes, and `cleanup.py` takes the
    // fixture out with everything else called `CHECK-`.
    //
    // The three checks are three claims about one move and each is green while the
    // other two are broken: where the button *is*, what it does to a surface opened
    // over it, and that pressing it still publishes.
    barActions: async () => {
      const {check, step, done, notes} = runner();
      const api = async (p, {method, body} = {}) => {
        const r = await fetch('/api/' + p, {
          method: method || (body ? 'POST' : 'GET'),
          headers: {'Content-Type': 'application/json'},
          body: body === undefined ? undefined : JSON.stringify(body)});
        let parsed = null;
        try { parsed = JSON.parse((await r.text()) || 'null'); } catch (e) { parsed = null; }
        return {status: r.status, body: parsed};
      };
      const TITLE = 'CHECK-BAR a Recipe with a Publish button';
      const made = await api('recipes', {body: {
        title: TITLE,
        useful_when: 'the bar has to offer Publish, and then stop offering it',
        description: 'A fixture for the top bar\'s right-hand slot.\n\n'
                     + 'It is unpublished, which is the state the button exists in, and it '
                     + 'has a body so that the page under the bar is the ordinary reading '
                     + 'rather than the blank case.'}});
      if (made.status !== 201)
        throw new Error('could not build the fixture: ' + JSON.stringify(made));
      const id = made.body.id;
      notes.push('built ' + TITLE + ' as recipe ' + id + ' and left it for cleanup.py');

      // **Waited on this Recipe's own title, not on `.recipe-page-body`.** The first
      // version of this waited for a body and went straight through: the phase can be
      // entered from another Recipe's page, which already has one, so the wait was
      // satisfied by the render that was on screen before the click. 41 then read a bar
      // that was still the previous page's — no Publish, no container — and 42, one
      // check later, saw the button perfectly well. That is the house rule's first
      // hazard exactly (`networkidle` and its cousins answer *the data arrived*, not
      // *reagent has re-rendered*), met with the wrong consequence rather than with no
      // wait at all, and it is the shape to be careful of: a selector that matches on
      // both sides of the move cannot tell you the move happened.
      await step('open the fixture at its own address', () => st.open_recipe_page(id));
      await until(() => page() && text('.recipe-page-title') === TITLE
                        && !!((stateGet('details') || {})[id]), 8000);
      if (text('.recipe-page-title') !== TITLE)
        throw new Error('the fixture never came up: the page says ' + text('.recipe-page-title'));

      const labelsIn = sel => [...document.querySelectorAll(sel + ' button')]
        .map(b => b.textContent.trim());
      const corner = () => ({right: barSlots('.top-bar-right'),
                             actions: labelsIn('.top-bar-actions'),
                             publish: !!document.querySelector('.recipe-publish')});

      // 41. **Publish is in the bar's right-hand slot, immediately left of the toggle,
      //     and nowhere else.** Three things, because *in the top right, to the left of
      //     the dark mode switcher* is a position and not just a container: the button
      //     is inside `.top-bar-actions`, that box is the toggle's immediately
      //     preceding sibling, and the two sit on one line at one height. A check that
      //     only looked for the class would pass with the button at the far end of the
      //     bar or wrapped under it.
      //
      //     And the negative half in the same breath: no Publish anywhere in the panel,
      //     and no `.recipe-page-actions` to hold one. That is 14's assertion made
      //     where it can actually fail — 14 runs on a published Recipe, where an
      //     actions row put back would be empty and a Publish put back would be
      //     `when-not`-ed away.
      //
      //     **The editor is the third thing it asserts, and it is here for the same
      //     reason.** 14's table says Publish is absent while editing, and on a
      //     published Recipe that line is vacuous: the button is absent in both modes
      //     whatever the mode gate says. Found by mutation — dropping
      //     `(not recipe-page-edit?)` from `core/surface-actions` left 14 green with
      //     all thirteen of `shelf()` passing. So the mode is exercised where the
      //     button exists, and both directions are asserted: gone on Edit, back on
      //     Cancel. One direction alone passes for a corner that never changes.
      await check('41 Publish is in the bar, immediately left of the theme toggle',
        async () => {
          // **Measured now and kept as booleans, never as nodes.** The mode round
          // trip below unmounts `.top-bar-actions` and mounts a new one, so a
          // `box.nextElementSibling` evaluated at the end would be asking a detached
          // element who its neighbour is — which answers `null` however right the bar
          // is. Found by mutation: M56 reddened this check on a conjunct that had
          // nothing to do with M56, which is the shape of a check failing for a reason
          // inside itself.
          const position = (() => {
            const pub = document.querySelector('.recipe-publish');
            const box = document.querySelector('.top-bar-actions');
            const tog = document.querySelector('.dark-mode-toggle');
            if (!pub || !box || !tog) return {publishThere: !!pub, boxThere: !!box};
            const p = pub.getBoundingClientRect(), t = tog.getBoundingClientRect();
            return {publishThere: true, boxThere: true,
                    insideTheBox: box.contains(pub),
                    boxIsTheTogglesSibling: box.nextElementSibling === tog,
                    sameLine: Math.abs(p.top - t.top) < 4,
                    leftOfIt: p.right <= t.left,
                    sameHeight: Math.abs(p.height - t.height) < 2,
                    heights: [Math.round(p.height), Math.round(t.height)]};
          })();
          const reading = corner();
          clickIn(document.querySelector('.top-bar-left'), 'button', 'Edit');
          await until(() => document.querySelector('.recipe-page-edit'), 8000);
          const editing = {...corner(),
                           slot: labelsIn('.top-bar-left'),
                           formUp: !!document.querySelector('.recipe-page-edit')};
          clickIn(document.querySelector('.top-bar-left'), '.recipe-edit-cancel');
          await until(() => document.querySelector('.recipe-page-body'), 8000);
          const backToReading = corner();
          return {pass: position.publishThere && position.insideTheBox
                        && position.boxIsTheTogglesSibling
                        && position.sameLine && position.leftOfIt && position.sameHeight
                        && reading.actions.join(',') === 'Publish'
                        // and nowhere in the panel, container included
                        && !document.querySelector('.recipe-page .recipe-publish')
                        && !document.querySelector('.recipe-page-actions')
                        && ![...document.querySelectorAll('.recipe-page button')]
                             .some(b => b.textContent.trim() === 'Publish')
                        // the editor is not a place to publish from — and it comes
                        // back on Cancel, or this passes for a corner that never fills
                        && editing.formUp && editing.slot.join(',') === 'Save,Cancel'
                        && !editing.publish && editing.actions.length === 0
                        && editing.right.length === 1
                        && backToReading.publish
                        && backToReading.actions.join(',') === 'Publish',
                  evidence: {corner: reading, position, editing, backToReading,
                             panelActionsRow: !!document.querySelector('.recipe-page-actions'),
                             panelButtons: labelsIn('.recipe-page')}};
        });

      // 42. **the version viewer replaces Publish rather than joining it.** The viewer
      //     is opened *over* this page and both surfaces are live at once, so the bar
      //     has to answer for one of them — `core/surface-actions` is an ordered `cond`
      //     and the viewer outranks the page underneath, exactly as the left slot's is.
      //
      //     Two claims in one, and the second is the one this suite is the only place
      //     for: while the viewer is up the corner is the theme toggle **alone** —
      //     Publish is gone *and* the viewer put nothing there, because a version
      //     viewer opened from a Recipe's own page has nothing to approve, dismiss or
      //     acknowledge. `checks.js` has the other origin, where the viewer does have
      //     answers to offer.
      //
      //     It matters more than a tidy corner: `views.diff/inert-behind!` exempts the
      //     whole top bar from the dialog's `inert`, so anything up there is reachable
      //     by keyboard from inside the dialog. A Publish button left standing would be
      //     one Tab and one Enter from a latch, over a surface that is not about
      //     publishing.
      await check('42 the versions viewer takes Publish out of the bar', async () => {
        clickIn(document.querySelector('.top-bar-left'), 'button', 'Versions');
        await until(() => document.querySelector('.diff-overlay'), 8000);
        await until(() => document.querySelector('.diff-header h2'));
        const inTheViewer = {...corner(),
                             slot: labelsIn('.top-bar-left'),
                             heading: text('.diff-header h2'),
                             barNotInert: document.querySelector('.top-bar').inert !== true,
                             pageBehindInert: !!page()?.inert};
        clickIn(document.querySelector('.top-bar-left'), '.diff-back');
        await until(() => !document.querySelector('.diff-overlay'));
        await until(() => document.querySelector('.recipe-page-body'));
        const afterBack = corner();
        return {pass: inTheViewer.right.length === 1
                      && inTheViewer.right[0].startsWith('dark-mode-toggle')
                      && !inTheViewer.publish
                      && inTheViewer.actions.length === 0
                      && inTheViewer.slot.join(',') === '← Recipe'
                      && inTheViewer.heading === 'Versions'
                      && inTheViewer.barNotInert && inTheViewer.pageBehindInert
                      // and the page's own action comes back with the page
                      && afterBack.publish
                      && afterBack.actions.join(',') === 'Publish',
                evidence: {inTheViewer, afterBack}};
      });

      // 44. **a page with no Recipe on it offers nothing.** The bar reads the row out
      //     of `:details` under `:recipe-page-id` — the same lookup the panel draws
      //     the reading from — so the two cannot come to be about different Recipes,
      //     and the page's other two states have no row at all. That used to be free:
      //     the button was drawn *inside* `found`, so loading and not-found could not
      //     have one. Up in the bar it has to be said, and a gate written as
      //     `(not= 1 published)` alone reads a missing row as *not published yet* and
      //     puts a Publish button over **No such Recipe here**.
      //
      //     Asserted on the 404 and not on the spinner, because the spinner is a
      //     moment and this is a state: `4b` is the same id and the same sentence.
      await check('44 an address that names no Recipe offers no Publish', async () => {
        st.open_recipe_page(999999);
        await until(() => document.querySelector('.recipe-page-missing'), 8000);
        await wait(200);
        const onTheMissingPage = {...corner(),
                                  sentence: text('.recipe-page-missing p')?.slice(0, 40),
                                  status: stateGet('recipe-page-status')};
        st.open_recipe_page(id);
        await until(() => text('.recipe-page-title') === TITLE, 8000);
        return {pass: onTheMissingPage.status === 'missing'
                      && !onTheMissingPage.publish
                      && onTheMissingPage.actions.length === 0
                      && onTheMissingPage.right.length === 1
                      && text('.recipe-page-title') === TITLE
                      && !!document.querySelector('.recipe-publish'),
                evidence: {onTheMissingPage, backOnTheFixture: corner()}};
      });

      // 45. **a visitor gets no Publish, on a Recipe he would otherwise be offered
      //     one for.** The gate here is `logged-in?` and it is *not* cosmetic the way
      //     the header badges' are — it is a write, and the API refuses it to anybody
      //     else — so the check has to be made against the case that can actually
      //     fail: an **unpublished** Recipe, where the other four conditions all hold
      //     and the session is the only thing saying no.
      //
      //     Dev cannot produce a genuine visitor — `:dangerously-skip-logins?` serves
      //     every request in the owner's audience — so this does what check 12 does
      //     for `caution`: it makes the exact condition the failure needs and leaves
      //     the rest of the session alone. `state/logout` is the fn the Sign out
      //     button calls, the page is opened again as that client, and the server
      //     still hands over the row because dev is dev. What is being asserted is the
      //     **client** rule, which is the only one that can be observed from in here.
      //
      //     And then it signs back in and asserts the button **comes back**, which is
      //     the half that keeps this from passing for a page that simply broke: an
      //     absence is not evidence of a gate until the presence returns with the
      //     condition.
      await check('45 signed out, an unpublished Recipe offers no Publish', async () => {
        st.logout();
        await until(() => stateGet('logged-in?') === false);
        st.open_recipe_page(id);
        await until(() => text('.recipe-page-title') === TITLE, 8000);
        await wait(200);
        const asVisitor = {...corner(),
                           loggedIn: stateGet('logged-in?'),
                           pageRendered: !!page(),
                           bodyRendered: !!text('.recipe-page-body'),
                           published: ((stateGet('details') || {})[id] || {}).published};
        st.fetch_auth_required();
        await until(() => stateGet('logged-in?') === true, 8000);
        st.open_recipe_page(id);
        await until(() => text('.recipe-page-title') === TITLE
                          && !!document.querySelector('.recipe-publish'), 8000);
        const signedInAgain = corner();
        return {pass: asVisitor.loggedIn === false
                      // the page is there — the absence below is the gate, not a blank
                      && asVisitor.pageRendered && asVisitor.bodyRendered
                      && asVisitor.published === 0
                      && !asVisitor.publish && asVisitor.actions.length === 0
                      && asVisitor.right.length === 1
                      && signedInAgain.publish,
                evidence: {asVisitor, signedInAgain}};
      });

      // 43. **pressing it still publishes, and then the button is gone.** The end of
      //     the move: the confirmation is opened from the bar now, and the button that
      //     opened it disappears on the answer — not because anything hides it, but
      //     because `core/surface-actions` reads `published` off the same row the panel
      //     draws from, so the latch closing is what takes the control away.
      //
      //     Asserted on the **state** as well as the screen. A button that vanished
      //     while the row still said `published: 0` would be the bar and the panel
      //     disagreeing about the Recipe, which is the failure that reading one row in
      //     one place exists to prevent — and it is the assertion a check that only
      //     looked at the DOM would have got right for the wrong reason.
      //
      //     This is the latch, and it is why the fixture is the suite's own.
      await check('43 Publish from the bar publishes, and the button goes', async () => {
        const before = ((stateGet('details') || {})[id] || {}).published;
        clickIn(document.querySelector('.top-bar-actions'), '.recipe-publish');
        const m = await until(() => document.querySelector('.modal-backdrop'));
        const confirmation = {shown: !!m,
                              subtitle: m?.querySelector('.modal-subtitle')?.textContent?.trim(),
                              note: m?.querySelector('.modal-note')?.textContent?.trim()};
        clickIn(m, '.publish-confirm');
        await until(() => !document.querySelector('.modal-backdrop'), 8000);
        await until(() => ((stateGet('details') || {})[id] || {}).published === 1);
        await wait(200);
        const after = ((stateGet('details') || {})[id] || {}).published;
        return {pass: before === 0 && confirmation.shown
                      && (confirmation.subtitle || '').includes('CHECK-BAR')
                      && /no unpublish/.test(confirmation.note || '')
                      && after === 1
                      && !document.querySelector('.recipe-publish')
                      && !document.querySelector('.top-bar-actions')
                      && !document.querySelector('.error')
                      // the page is still the page, and now says it is published
                      && !!document.querySelector('.recipe-page-body')
                      && !!document.querySelector('.published-badge'),
                evidence: {publishedBefore: before, publishedAfter: after, confirmation,
                           corner: corner(),
                           publishedBadge: !!document.querySelector('.published-badge'),
                           errorBanner: text('.error') || null}};
      });

      await step('go back to the shelf', () => st.go_to_page(kw('shelf')));
      await until(() => shelf(), 8000);
      notes.push('left on ' + path());
      return done({fixture: {id, title: TITLE}});
    },
  };
})()
