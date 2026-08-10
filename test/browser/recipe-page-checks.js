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
//     (<contents of this file>).provenance()  — the provenance view; needs its seed
//     (<contents of this file>).filing()      — the Scope picker; needs the same seed
//
// Each returns `{passed, of, failed, results, notes}`. See README.md in this
// directory for the run and for the mutation each check was watched to fail
// against, and for what this suite reads out of the dev database.
//
// **Four of the six phases write nothing.** `SUBJECT` is read and the only trace
// those reads leave is the `view_count` they move — which is the number the shelf is
// ranked by, and moving it is what reading a Recipe *is*. The editor is opened,
// prefilled and left by Cancel, never saved. `filing()` is the exception and it says
// so at length: filing *is* a write, so it works on the seeded `CHECK-PROV` rather
// than on anything of his, and puts it back.
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
      const cardBody = cardFor(SUBJECT)?.querySelector('.card-body')?.textContent?.trim();
      // and the header facts it wears, for check 6
      const BADGES = '.published-badge, .pending-badge, .scope-badge, .version-badge,' +
                     ' .source-badge, .views-badge, .card-date';
      const badgesIn = root => [...(root?.querySelectorAll(BADGES) || [])]
        .map(e => e.className.split(' ')[0]).sort();
      const cardBadges = badgesIn(cardFor(SUBJECT)?.querySelector('.card-header'));
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

      // 14. **and the four are here instead**, which is the other half of 13: the
      //     removal alone would have made publishing, editing, version-viewing and
      //     deleting unreachable in the whole UI, since the card footer was the only
      //     caller of all four `state/start-*` fns. Asserted as the *set* of labels,
      //     so a fifth control appearing in the row reddens this too.
      //
      //     SUBJECT is published, so the expected set is the three: Publish is
      //     conditional on the latch, exactly as it was on the card. The row's own
      //     `published` flag is in the evidence, so a run against an unpublished
      //     SUBJECT says why it wants four rather than looking arbitrary.
      await check('14 the Recipe page carries the four actions, Publish by the latch', () => {
        const labels = [...document.querySelectorAll('.recipe-page-actions button')]
          .map(b => b.textContent.trim());
        const published = subject.published === 1;
        const expected = published ? ['Edit', 'Versions', 'Delete']
                                   : ['Publish', 'Edit', 'Versions', 'Delete'];
        const danger = [...document.querySelectorAll('.recipe-page-actions button.danger')]
          .map(b => b.textContent.trim());
        return {pass: labels.join(',') === expected.join(',') && danger.join(',') === 'Delete',
                evidence: {onThePage: labels, expected, published,
                           wearingDanger: danger,
                           ownerOnly: stateGet('logged-in?')}};
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
      await check('5 a top-bar button leaves the page and puts / back in the bar', async () => {
        clickIn(document, '.inbox-toggle');
        await until(() => document.querySelector('.inbox'));
        const onInbox = {path: path(), inbox: !!document.querySelector('.inbox'),
                         recipePage: !!page()};
        clickIn(document, '.inbox-toggle');          // the toggle goes back to the shelf
        await until(() => shelf());
        return {pass: onInbox.path === '/' && onInbox.inbox && !onInbox.recipePage
                      && path() === '/' && !!shelf(),
                evidence: {afterTheInboxButton: onInbox, afterTogglingBack:
                           {path: path(), shelf: !!shelf()}}};
      });

      // and back onto the Recipe page, which is where 16 and 17 start and where the
      // next phase wants the browser left
      await step('go back to the Recipe page', () =>
        clickIn(cardFor(SUBJECT), '.card-actions button', 'Page'));
      await until(() => page());

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
        clickIn(document, '.recipe-page-actions button', 'Edit');
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
        clickIn(form.querySelector('.recipe-page-edit-actions'), 'button.secondary', 'Cancel');
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
        clickIn(document, '.recipe-page-actions button', 'Edit');
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
        clickIn(document.querySelector('.recipe-page-edit-actions'), 'button.secondary', 'Cancel');
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
          const act = label => [...document.querySelectorAll('.recipe-page-actions button')]
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
          em && clickIn(em.querySelector('.recipe-page-edit-actions'),
                        'button.secondary', 'Cancel');
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
      await check('18 a cold load of ?edit=true opens the editor, not the reading',
        async () => {
          const form = await until(() => document.querySelector('.recipe-page-edit'), 8000);
          const id = Number(path().split('/')[2]);
          return {pass: !!form && !!page() && !shelf()
                        && location.search === '?edit=true'
                        && stateGet('recipe-page-edit?') === true
                        && stateGet('recipe-page-id') === id
                        && stateGet('recipe-page-status') === 'found'
                        && !document.querySelector('.recipe-page-body')
                        && !!form.querySelector('.recipe-page-edit-title')?.value,
                  evidence: {bar: path() + location.search,
                             editorRendered: !!form,
                             readingRendered: !!document.querySelector('.recipe-page-body'),
                             flag: stateGet('recipe-page-edit?'),
                             status: stateGet('recipe-page-status'),
                             recipePageId: stateGet('recipe-page-id'), askedFor: id,
                             prefilledTitle:
                               form?.querySelector('.recipe-page-edit-title')?.value}};
        });
      // leave the reading up rather than a form nobody asked to fill in
      await step('leave the editor', async () => {
        const actions = document.querySelector('.recipe-page-edit-actions');
        actions && clickIn(actions, 'button.secondary', 'Cancel');
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

    // ---- phase four: the provenance view -----------------------------------
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
      const expectedPerLine = () => {
        const out = [];
        for (const r of ((row().caution || {}).ranges || []))
          for (let n = r.from; n <= r.to; n++) out[n - 1] = r.caution;
        return out;
      };

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
      await check('12 no caution in the response, no button — even signed in', async () => {
        const path = c.vector(kw('details'), id);
        const cached = c.get_in(c.deref(st._STAR_app_state), path);
        c.swap_BANG_(st._STAR_app_state,
                     m => c.assoc_in(m, path, c.dissoc(cached, kw('caution'))));
        await until(() => !toggle());
        const gone = {toggle: !!toggle(), source: !!document.querySelector('.provenance-source'),
                      legend: !!document.querySelector('.provenance-legend'),
                      rendered: !!document.querySelector('.recipe-page-body'),
                      loggedIn: stateGet('logged-in?')};
        c.swap_BANG_(st._STAR_app_state, m => c.assoc_in(m, path, cached));
        await until(() => toggle());
        return {pass: !gone.toggle && !gone.source && !gone.legend
                      && gone.rendered && gone.loggedIn === true && !!toggle(),
                evidence: {withoutTheKey: gone, buttonBackAfterRestoring: !!toggle()}};
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

    // ---- phase five: the filing, which is the one that writes ----------------
    // **The only phase in this file that changes a Recipe**, which is why it works on
    // the seeded `CHECK-PROV` and not on `SUBJECT`: filing is a write, and a suite
    // that promised to write nothing must not start writing to his shelf. It puts the
    // fixture back where it found it — filed under nothing — and `cleanup.py` removes
    // the fixture anyway.
    //
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

        return {pass: filed.ids.length === 1 && filed.lit.includes(firstName)
                      && filed.version === versionBefore
                      && filed.rowVersion === rowVersionBefore
                      && cleared.ids.length === 0 && cleared.lit.length === 0
                      && cleared.version === versionBefore
                      && cleared.rowVersion === rowVersionBefore
                      // the write really happened, which is the other half
                      && filed.stamp !== stampBefore
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
        await until(() => !stateGet('filing'), 8000);
        await until(() => filedIds().length === 2);
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

      // Put the fixture back the way it was found — one gesture per Scope, through
      // the same control, so the tidy-up is itself the thing under test.
      await step('unfile what these checks filed', async () => {
        // chip → id through the owner's own Scope list, because a chip carries its
        // title and not its id, and adding one to the markup for a check's benefit
        // would be the suite reaching into the app
        const idOf = name => ((stateGet('scopes') || []).find(s => s.title === name) || {}).id;
        for (const chip of chips())
          if (chip.classList.contains('on')
              && !startedFiledUnder.includes(idOf(chip.textContent.trim()))) chip.click();
        await until(() => !stateGet('filing'), 8000);
        await until(() => filedIds().length === startedFiledUnder.length);
      });
      notes.push('left on ' + path() + ', ' + MIXED + ' filed under '
                 + filedIds().length + ' Scope(s)');
      return done({subject: {id, title: subject.title, url: '/recipe/' + id}});
    },
  };
})()
