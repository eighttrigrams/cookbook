// The check suite for a Recipe's own page and the address it lives at.
//
// **Three phases, because two of these properties cannot be observed from inside
// one `evaluate`.** A cold load of `/recipe/<id>` replaces the JS context, so the
// check that the *server* route works has to run in the context that load created;
// and being signed out is a state the page has to be put into first. Back and
// Forward are the opposite case and belong together in one phase: nothing here ever
// leaves the document — every move is a `pushState` — so `history.back()` fires a
// `popstate` in the same context and can be waited on like any other consequence.
//
// The file evaluates to an object of phases. Run one at a time:
//
//     (<contents of this file>).shelf()       — signed in, on /
//     (<contents of this file>).coldLoad()    — after loading /recipe/<id> fresh
//     (<contents of this file>).signedOut()   — signed in, on /; it signs itself out
//
// Each returns `{passed, of, failed, results, notes}`. See README.md in this
// directory for the run and for the mutation each check was watched to fail
// against, and for what this suite reads out of the dev database.
//
// It writes nothing. `SUBJECT` is read, twice, and the only trace a run leaves is
// the `view_count` those reads move — which is the number the shelf is ranked by,
// and moving it is what reading a Recipe *is*. Nothing is created and nothing is
// deleted, so there is no cleanup script beside this one.
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

  const wait = ms => new Promise(r => setTimeout(r, ms));
  const until = async (fn, ms = 5000) => {
    const t0 = Date.now();
    while (Date.now() - t0 < ms) { let v; try { v = fn(); } catch (e) { v = null; }
                                  if (v) return v; await wait(50); }
    return null;
  };
  const st = window.et.cb.ui.state, c = window.cljs.core;
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

      // 1. the fifth button navigates, and what it lands on is the Recipe
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
      await check('6 the page wears the same header facts as the card', () => {
        const onPage = badgesIn(document.querySelector('.recipe-page-badges'));
        const missing = cardBadges.filter(b => !onPage.includes(b));
        return {pass: cardBadges.length >= 5 && missing.length === 0
                      && onPage.includes('source-badge'),
                evidence: {onTheCard: cardBadges, onThePage: onPage, missing}};
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

      // and leave the browser where the next phase needs it
      await step('go back to the Recipe page', () =>
        clickIn(cardFor(SUBJECT), '.card-actions button', 'Page'));
      await until(() => page());
      notes.push('reload ' + location.origin + url + ' and run coldLoad()');
      return done({subject: {id: subject.id, title: subject.title, url}});
    },

    // ---- phase two: after a cold load of /recipe/<id> -----------------------
    // **The half a pushState-only implementation fakes.** Everything phase one
    // asserted would still pass with no server route at all: the address changes
    // and the page renders because the client never left the document. This is the
    // load that goes to the server first, and it is why `GET /recipe/*` exists.
    coldLoad: async () => {
      const {check, done} = runner();
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
                      // the visitor's rendering and not the owner's page relabelled
                      && !document.querySelector('.recipe-page-tags')
                      && !document.querySelector('.recipe-page-scopes'),
                evidence: {loggedIn: stateGet('logged-in?'), path: path(),
                           recipePageRendered: !!page(), shelfRendered: !!shelf(),
                           title: text('.recipe-page-title'),
                           body: text('.recipe-page-body'),
                           ownerOnlyBitsOnThePage:
                             [...document.querySelectorAll(
                               '.recipe-page-tags, .recipe-page-scopes, .pending-badge, .published-badge')]
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

      // Put the client back the way it was found. The shelf **first**, and then the
      // sign-in: `fetch_auth_required` ends in `sync-from-url!`, which re-derives
      // the page from the bar — and the bar still names 999999 at this point, so
      // the other order signs back in and lands straight back on the not-found.
      await step('back to the shelf', () => st.go_to_page(kw('shelf')));
      await until(() => shelf());
      // dev signs itself in, so this is the call the page makes on boot, not a fake
      await step('sign back in', () => st.fetch_auth_required());
      await until(() => stateGet('logged-in?') === true && shelf());
      return done({});
    },
  };
})()
