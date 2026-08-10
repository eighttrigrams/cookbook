// The check suite for the Inbox's `proposed` row and the viewer it opens on.
// One async arrow function: paste this file's contents into a Playwright
// `evaluate`. See README.md in this directory for the run, and for the mutations
// each of these was watched to fail against.
//
// **Every check is isolated and every evidence object is lazy.** That is not
// tidiness: the version of this suite that shipped before built its evidence
// eagerly, so a mutation that made one selector return null threw out of the whole
// run and three of the five mutation columns in that report could not have come
// from it. A run that throws is a failed run and the conclusion survived; the
// per-check breakdown did not. So `check` takes a thunk, catches, and records the
// throw as that check's evidence — the rest of the suite still runs, and a mutation
// gets a column instead of a stack trace.
//
// The state atom is driven directly in exactly one place, check 8, and the reason is
// measured rather than asserted — see the note there.
async () => {
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
  const stateInbox = () => stateGet('inbox') || [];
  const diffingProposal = () => stateGet('diffing-proposal');
  const rows = () => [...document.querySelectorAll('.inbox-row')];
  const entries = () => [...(document.querySelector('.inbox-list')?.children || [])];
  const rowFor = t => rows().find(r => r.textContent.includes(t));
  const entryFor = t => entries().find(e => e.textContent.includes(t));
  const overlay = () => document.querySelector('.diff-overlay');
  const modal = () => document.querySelector('.modal-backdrop');
  const shellClasses = () => [...new Set([...document.querySelectorAll('.diff-overlay *')]
    .map(e => e.className).filter(s => typeof s === 'string' && s.startsWith('diff')))].sort();
  const idOf = t => { const e = stateInbox().find(x => (x.recipe_title || '').includes(t));
                      return e && e.id; };
  // A missing root throws rather than falling back to `document`. It fell back once
  // and M4 showed what that costs: with CHECK-1 already resolved, `rowFor('CHECK-1')`
  // was undefined, the click landed on some *other* row's Dismiss, and check 6 went
  // green on a confirmation about a different proposal.
  const clickIn = (root, sel) => {
    if (!root) throw new Error('nothing to click in, for: ' + sel);
    const el = root.querySelector(sel);
    if (!el) throw new Error('nothing to click: ' + sel);
    el.click();
    return el;
  };
  const R = [], notes = [];
  // A check is a thunk returning {pass, evidence}. Both are inside the try, so a
  // null selector in *either* costs this check and nothing else.
  const check = async (name, fn) => {
    try {
      const {pass, evidence} = await fn();
      R.push({name, pass: !!pass, evidence});
    } catch (e) {
      R.push({name, pass: false, evidence: {threw: String((e && e.stack) || e)}});
    }
  };
  // Getting from one check to the next — closing an overlay, cancelling a modal.
  // Also fallible, and also must not end the run.
  const step = async (what, fn) => {
    try { await fn(); } catch (e) { notes.push('step failed: ' + what + ' — ' + e); }
  };

  // ---- get onto the page --------------------------------------------------
  if (!document.querySelector('.inbox')) {
    await step('open the Inbox page', () => clickIn(document, '.inbox-toggle'));
    await until(() => document.querySelector('.inbox'));
  }
  await until(() => rowFor('CHECK-1'));

  // 1. the inbox page renders no proposal panes inline
  await check('1 no proposal panes inline', () => {
    const proposed = rows().filter(r => r.querySelector('.inbox-kind')?.textContent === 'proposed');
    const inside = document.querySelectorAll('.inbox .diff-editor, .inbox .diff-meta').length;
    const oldClasses = document.querySelectorAll('.inbox-review, .inbox-item').length;
    return {pass: inside === 0 && oldClasses === 0 && proposed.length > 0,
            evidence: {panesInsideInbox: inside, oldWrapperClasses: oldClasses,
                       proposedRowsOnPage: proposed.length}};
  });

  // 2. every entry is one line — measured on the ENTRY and not on `.inbox-row`.
  //    `.inbox-row` is one line in the layout he complained about too: that shape
  //    put the comparison in a *sibling* of the row inside a wrapper, so a check
  //    measuring the row would have passed against the exact thing being fixed.
  //    `.inbox-list`'s own children are the entries, whatever they are made of.
  await check('2 every entry is one row', () => {
    const heights = entries().map(e => Math.round(e.getBoundingClientRect().height));
    const rowHeights = rows().map(r => Math.round(r.getBoundingClientRect().height));
    return {pass: heights.length >= 4 && Math.max(...heights) < 80,
            evidence: {entryHeights: heights, tallestEntry: Math.max(...heights),
                       rowHeights, tallestRow: Math.max(...rowHeights)}};
  });

  // 3. a proposed row's title opens the viewer on that proposal
  await check('3a a proposed row has a title link', () => {
    const link = rowFor('CHECK-1')?.querySelector('.inbox-title-link');
    return {pass: !!link, evidence: {hasLink: !!link,
                                     rowText: rowFor('CHECK-1')?.textContent}};
  });
  await step('open CHECK-1 in the viewer',
             () => clickIn(rowFor('CHECK-1'), '.inbox-title-link'));
  await until(() => overlay() && document.querySelector('.diff-editor .cm-editor'));
  await wait(300);
  await check('3b the viewer opens on the proposal, current against proposed', () => {
    const sides = [...document.querySelectorAll('.diff-meta-version')].map(e => e.textContent);
    const paneText = [...document.querySelectorAll('.diff-editor .cm-content')].map(e => e.textContent);
    const heading = document.querySelector('.diff-header h2')?.textContent;
    const left = paneText.some(t => t.includes('The owner wrote this paragraph'));
    const right = paneText.some(t => t.includes('An agent rewrote the second paragraph of check 1'));
    return {pass: !!overlay() && heading === 'Proposal'
                  && sides.join('|') === 'This Recipe now|Proposed by an agent'
                  && left && right,
            evidence: {heading, sides, leftHasOwnerText: left, rightHasAgentText: right,
                       subject: document.querySelector('.diff-recipe-title')?.textContent,
                       diffingProposal: diffingProposal()}};
  });
  const proposalShell = shellClasses();
  const proposalButtons = [...document.querySelectorAll('.diff-header button')].map(b => b.textContent.trim());

  // 11 here rather than at the end, because it is about a viewer that is *open* and
  // this is where one is. The keyboard cannot leave this surface *for the page* —
  // asserted as the census the finding used, because nothing inside the page can send
  // a real Tab: a synthetic KeyboardEvent does not move focus. focus-probe.js is the
  // keystroke half, driven from the session.
  //
  // **This check changed sides, and that is worth more than deleting it.** It used to
  // assert `topBarInert` and `outside.length === 0` — nothing at all focusable outside
  // the overlay. Both are now **false by design**: the way off this surface is the top
  // bar's left slot, so a bar taken out of the tab order would be a dialog whose one
  // exit the keyboard cannot reach. `inert-behind!` exempts `.top-bar` and nothing
  // else, so what this asserts now is the *exact* set outside the overlay — the back
  // button and the theme toggle — rather than an empty one. An exemption that widened
  // to a third control, or a page that stopped being inert, reddens this.
  await check('11 the overlay is a dialog, and outside it only the bar is reachable', () => {
    const ov = overlay();
    const focusable = [...document.querySelectorAll(
      'button, a[href], input, select, textarea, [tabindex]')]
      .filter(e => !e.disabled && !e.closest('[inert]'));
    const outside = focusable.filter(e => !ov.contains(e))
      // <link> in <head> matches a[href]-ish selectors in some engines and is not
      // focusable; anything not rendered cannot be tabbed to either.
      .filter(e => e.tagName !== 'LINK' && (e.offsetParent !== null || e === document.activeElement));
    const bar = document.querySelector('.top-bar');
    const outsideNames = outside.map(e => (e.className || e.tagName).split(' ')[0]
                                          + ':' + (e.textContent || '').trim().slice(0, 12));
    return {pass: ov.getAttribute('role') === 'dialog'
                  && ov.getAttribute('aria-modal') === 'true'
                  && document.querySelector('.inbox').inert === true
                  // the bar is reachable, and it is the *only* thing that is
                  && bar.inert !== true
                  && outside.length === 2
                  && outside.every(e => bar.contains(e))
                  && outside.some(e => e.classList.contains('diff-back'))
                  && outside.some(e => e.classList.contains('dark-mode-toggle'))
                  && ov.contains(document.activeElement),
            evidence: {role: ov.getAttribute('role'), ariaModal: ov.getAttribute('aria-modal'),
                       ariaLabel: ov.getAttribute('aria-label'),
                       inboxInert: document.querySelector('.inbox').inert,
                       topBarInert: bar.inert,
                       barIsExemptNotMarked: !bar.hasAttribute('data-inert-behind-viewer'),
                       focusableOutsideOverlay: outsideNames,
                       allOfThemInTheBar: outside.every(e => bar.contains(e)),
                       focusStartsInside: ov.contains(document.activeElement),
                       activeElement: document.activeElement.tagName + '.' + document.activeElement.className}};
  });

  // 13. **the viewer's chrome is the bar's now.** *for the versions view that instead
  //     of an x there will be a back button (going back to either the inbox or to tha
  //     Page page, depending where we came from).* Three facts about one move, so one
  //     check: the slot says where back *is*, the right-hand side is down to the one
  //     widget in every view, and the ✕ is nowhere.
  //
  //     `← Inbox` and not `← Recipe` because this suite opens the viewer from the
  //     queue. The label is derived from `:page` rather than stored, so this is also
  //     the assertion that the derivation reads the right end of it; the Recipe origin
  //     is `recipe-page-checks.js` 25, since that surface is that file's subject.
  await check('13 the viewer wears a back button in the bar, and no ✕', () => {
    const slot = [...document.querySelectorAll('.top-bar-left button')]
      .map(b => b.textContent.trim());
    const right = [...document.querySelectorAll('.top-bar-right > *')]
      .map(e => (e.className || e.tagName).split(' ')[0]);
    const xs = [...document.querySelectorAll('.diff-overlay button')]
      .filter(b => b.textContent.trim() === '✕');
    return {pass: slot.length === 1 && slot[0] === '← Inbox'
                  && !!document.querySelector('.top-bar-left .diff-back')
                  && right.length === 1 && right[0] === 'dark-mode-toggle'
                  && xs.length === 0 && !document.querySelector('.diff-close')
                  // and the surface starts below the bar rather than over it
                  && document.querySelector('.diff-overlay').getBoundingClientRect().top
                     >= document.querySelector('.top-bar').getBoundingClientRect().bottom,
            evidence: {slot, right, closeButtonsFound: xs.length,
                       page: stateGet('page'),
                       overlayTop: Math.round(document.querySelector('.diff-overlay')
                                              .getBoundingClientRect().top),
                       barBottom: Math.round(document.querySelector('.top-bar')
                                             .getBoundingClientRect().bottom)}};
  });

  // 4. dismiss from the viewer asks first, and the question lands on top
  await step('dismiss from the viewer', () => clickIn(document, '.diff-header .proposal-dismiss'));
  await until(() => modal());
  await check('4 dismiss from the viewer confirms first, above the viewer', () => {
    const m = modal(), ov = overlay();
    const zModal = m && parseInt(getComputedStyle(m).zIndex, 10);
    const zOverlay = ov && parseInt(getComputedStyle(ov).zIndex, 10);
    return {pass: !!m && m.textContent.includes('Dismiss this proposal?')
                  && !!ov && zModal > zOverlay
                  // and it keeps its buttons: the surface below is inert, this one
                  // is above it and must not be.
                  && !m.closest('[inert]') && !m.inert,
            evidence: {modalShown: !!m, zModal, zOverlay, viewerStillOpen: !!ov,
                       modalInert: !!m && (m.inert || !!m.closest('[inert]')),
                       buttons: [...document.querySelectorAll('.modal-actions button')]
                         .map(b => ({text: b.textContent, inert: !!b.closest('[inert]')}))}};
  });
  await step('cancel the confirmation', () =>
    clickIn(document.querySelector('.modal-actions'), 'button.secondary'));
  await until(() => !modal());

  // 14. **back lands where you came from, with the queue as it was.** The overlay was
  //     kept an overlay precisely so this needs no recorded origin: `:diffing` is
  //     independent of `:page`, so the Inbox is still underneath and `stop-diff` alone
  //     puts you back on it. What that buys is asserted here rather than assumed —
  //     the Inbox, not the shelf, and the same number of rows, since a round trip
  //     through a viewer must not refetch the queue out from under him.
  const rowsBeforeTheViewer = rows().length;
  await check('14 the slot\'s button lands back on the Inbox, queue unchanged', async () => {
    clickIn(document.querySelector('.top-bar-left'), '.diff-back');
    await until(() => !overlay());
    await wait(200);
    return {pass: !overlay() && stateGet('page') === 'inbox'
                  && !!document.querySelector('.inbox')
                  && rows().length === rowsBeforeTheViewer
                  && !document.querySelector('.shelf')
                  && !stateGet('diffing')
                  // and the bar is the Inbox's again
                  && !!document.querySelector('.top-bar-left .brand')
                  && !document.querySelectorAll('[data-inert-behind-viewer]').length,
            evidence: {page: stateGet('page'), inboxDrawn: !!document.querySelector('.inbox'),
                       shelfDrawn: !!document.querySelector('.shelf'),
                       rows: rows().length, rowsBeforeTheViewer,
                       diffing: stateGet('diffing'),
                       slot: [...document.querySelectorAll('.top-bar-left > *')]
                         .map(e => (e.className || e.tagName).split(' ')[0]),
                       inertReleased: !document.querySelectorAll('[data-inert-behind-viewer]').length}};
  });

  // 5. one presentation — the proposal overlay and the version overlay are the same
  //    markup. Open a `modified` row and compare the shells.
  await step('open a modified entry', () => {
    const modRow = rows().find(r => r.querySelector('.inbox-kind')?.textContent === 'modified');
    if (!modRow) throw new Error('no modified entry in the dev queue — see README');
    clickIn(modRow, '.inbox-title-link');
  });
  await until(() => overlay() && document.querySelector('.diff-editor .cm-editor'));
  await wait(300);
  await check('5 one presentation: both readings are the same markup', () => {
    const versionShell = shellClasses();
    // `diff-close` was in this list and the ✕ is gone — the way off the surface is
    // the top bar's left slot, which is not part of either reading's markup.
    const chrome = ['diff-overlay', 'diff-page', 'diff-header',
                    'diff-recipe-title', 'diff-version-label', 'diff-mode-toggle',
                    'diff-meta', 'diff-meta-side', 'diff-meta-version', 'diff-meta-when',
                    'diff-meta-row', 'diff-meta-key', 'diff-meta-value', 'diff-editor'];
    const chromeOf = xs => xs.filter(s => chrome.includes(s));
    return {pass: JSON.stringify(chromeOf(proposalShell)) === JSON.stringify(chromeOf(versionShell))
                  && chromeOf(proposalShell).length >= 12
                  && proposalShell.every(s => versionShell.includes(s) || s === 'diff-note'),
            evidence: {sharedChrome: chromeOf(proposalShell),
                       onlyInVersionReading: versionShell.filter(s => !proposalShell.includes(s)),
                       onlyInProposalReading: proposalShell.filter(s => !versionShell.includes(s))}};
  });
  await step('close the viewer', () => clickIn(document.querySelector('.top-bar-left'), '.diff-back'));
  await until(() => !overlay());

  // 6. dismiss from the row asks first too
  await step('dismiss CHECK-1 from the row', () => clickIn(rowFor('CHECK-1'), '.proposal-dismiss'));
  await until(() => modal());
  await check('6 dismiss from the row confirms first', () => ({
    pass: !!modal() && modal().textContent.includes('Dismiss this proposal?') && !overlay(),
    evidence: {modalShown: !!modal(), viewerOpen: !!overlay()}}));
  await step('cancel the confirmation', () =>
    clickIn(document.querySelector('.modal-actions'), 'button.secondary'));
  await until(() => !modal());

  // 7. approve from the viewer resolves the entry AND closes the viewer
  await step('open CHECK-1 and approve it from the viewer', async () => {
    clickIn(rowFor('CHECK-1'), '.inbox-title-link');
    await until(() => overlay() && document.querySelector('.diff-editor .cm-editor'));
    clickIn(document, '.diff-header .proposal-approve');
  });
  await until(() => !rowFor('CHECK-1'));
  await wait(400);
  await check('7 approve from the viewer resolves and closes it', () => ({
    pass: !overlay() && !rowFor('CHECK-1') && diffingProposal() === null,
    evidence: {viewerOpen: !!overlay(), entryStillInQueue: !!rowFor('CHECK-1'),
               diffingProposal: diffingProposal()}}));

  // 8. approve from the ROW while the viewer is open on that proposal closes it.
  //
  //    **Driven through the state fn the row's button calls, and the reason is now
  //    measured rather than argued.** The previous pass claimed the gesture was
  //    unreachable because the overlay is `position: fixed; inset: 0` — true of the
  //    mouse and false of the keyboard, which a reviewer showed with Tab+Enter. It is
  //    unreachable *now*, because the surface is a dialog and everything behind it is
  //    inert, and this check asserts that in the same breath as the invariant instead
  //    of asserting it in a sentence: `rowApproveReachable` is part of the pass.
  const id2 = idOf('CHECK-2');
  await step('open CHECK-2 in the viewer', async () => {
    clickIn(rowFor('CHECK-2'), '.inbox-title-link');
    await until(() => overlay() && document.querySelector('.diff-editor .cm-editor'));
  });
  await check('8 approve from the row closes a viewer open on that proposal', async () => {
    const openedOn = diffingProposal();
    const rowApprove = rowFor('CHECK-2')?.querySelector('.proposal-approve');
    const reachable = !!rowApprove && !rowApprove.closest('[inert]') && !rowApprove.disabled;
    st.approve_proposal(id2, null);        // exactly what the row's Approve calls
    await until(() => !rowFor('CHECK-2'));
    await wait(400);
    return {pass: openedOn === id2 && !overlay() && !rowFor('CHECK-2')
                  && diffingProposal() === null && reachable === false,
            evidence: {viewerWasOpenOn: openedOn, eventId: id2, viewerOpen: !!overlay(),
                       entryStillInQueue: !!rowFor('CHECK-2'),
                       diffingProposal: diffingProposal(),
                       rowApproveReachableByHand: reachable}};
  });

  // 9. dismiss from the viewer, confirmed: resolves and closes.
  //    **It asserts that it was asked**, and not only that the entry went. M4 — the
  //    viewer's Dismiss calling `dismiss-proposal` straight — left this green: the
  //    entry did leave and the viewer did close, which is all it used to look at, so
  //    a check whose name says "confirmed" was passing against no confirmation.
  let askedFirst = null;
  await step('open CHECK-3 and dismiss it for real', async () => {
    clickIn(rowFor('CHECK-3'), '.inbox-title-link');
    await until(() => overlay() && document.querySelector('.diff-editor .cm-editor'));
    clickIn(document, '.diff-header .proposal-dismiss');
    askedFirst = !!(await until(() => modal()));
    clickIn(document.querySelector('.modal-actions'), 'button.danger');
  });
  await until(() => !rowFor('CHECK-3'));
  await wait(400);
  await check('9 dismiss from the viewer, confirmed, resolves and closes it', () => ({
    pass: askedFirst === true && !overlay() && !modal() && !rowFor('CHECK-3')
          && diffingProposal() === null,
    evidence: {confirmationAppeared: askedFirst, viewerOpen: !!overlay(),
               modalOpen: !!modal(), entryStillInQueue: !!rowFor('CHECK-3'),
               diffingProposal: diffingProposal()}}));

  // 10. **the ordering, and not the end state.** The check the last pass needed and
  //     did not ship. With the close removed from `resolve-proposal` the *end* state
  //     is identical either way — viewer closed, `:diffing-proposal` nil, entry gone —
  //     because something else gets there a round trip later. What differs is
  //     *when*: with the close, the viewer shuts before the new list lands; without
  //     it, there is a state in which the viewer is open over an entry the queue has
  //     already dropped, and that state is the bug.
  //
  //     `add-watch` sees every `swap!`, so nothing is sampled and nothing is missed —
  //     but the assertion is on the **order of two transitions** and not on how many
  //     swaps there were. A count would go red the day an unrelated `assoc` is added.
  await check('10 the viewer closes before the new list lands', async () => {
    const id4 = idOf('CHECK-4');
    if (!id4) throw new Error('CHECK-4 is not in the queue — reseed');
    clickIn(rowFor('CHECK-4'), '.inbox-title-link');
    await until(() => overlay() && document.querySelector('.diff-editor .cm-editor'));
    const trace = [];
    const listedIn = state => {
      const inbox = c.clj__GT_js(c.get(state, kw('inbox'))) || [];
      return inbox.some(e => e.id === id4);
    };
    const probe = kw('checks-ordering-probe');
    c.add_watch(st._STAR_app_state, probe, (k, ref, oldv, newv) => {
      const point = {dp: c.clj__GT_js(c.get(newv, kw('diffing-proposal'))), listed: listedIn(newv)};
      const last = trace[trace.length - 1];
      if (!last || last.dp !== point.dp || last.listed !== point.listed) trace.push(point);
    });
    const before = {dp: diffingProposal(), listed: stateInbox().some(e => e.id === id4)};
    st.approve_proposal(id4, null);
    await until(() => !rowFor('CHECK-4'));
    await wait(600);
    c.remove_watch(st._STAR_app_state, probe);
    // The state before the first swap, then one point per *change* — a `swap!` that
    // moves neither of the two facts is not a step of this sequence.
    const seq = [before, ...trace].filter((p, i, a) =>
      i === 0 || p.dp !== a[i - 1].dp || p.listed !== a[i - 1].listed);
    const iClosed = seq.findIndex(p => p.dp === null);
    const iGone = seq.findIndex(p => p.listed === false);
    const closedBeforeTheListLanded = iClosed !== -1 && iGone !== -1 && iClosed < iGone;
    const viewerLeftOpenOverAnEntryAlreadyGone = seq.some(p => p.dp !== null && p.listed === false);
    return {pass: closedBeforeTheListLanded && !viewerLeftOpenOverAnEntryAlreadyGone,
            evidence: {trace: seq, iClosed, iGone, closedBeforeTheListLanded,
                       viewerLeftOpenOverAnEntryAlreadyGone,
                       endState: {viewerOpen: !!overlay(), diffingProposal: diffingProposal(),
                                  entryStillInQueue: !!rowFor('CHECK-4')}}};
  });

  // 12. the two proposals that have something to say first cannot be approved from
  //     the row, and the row says why. Both notes are in the viewer, and the row is
  //     where the button was — so what the row carries is a flag and a version badge
  //     showing the relationship, and a dead Approve.
  await check('12 a warned proposal is not approvable from the row, and the row says why', () => {
    const read = t => {
      const r = rowFor(t);
      if (!r) throw new Error('no row for ' + t + ' — reseed');
      const v = r.querySelector('.inbox-version');
      return {version: v?.textContent, stale: !!r.querySelector('.inbox-version.stale'),
              flags: [...r.querySelectorAll('.proposal-flag')].map(f => f.textContent),
              approveDisabled: r.querySelector('.proposal-approve')?.disabled,
              dismissLive: r.querySelector('.proposal-dismiss')?.disabled === false};
    };
    // CHECK-5 and not one of 1 to 4: this runs after the checks that resolve those,
    // and the unwarned case has to be a row that is still on the page.
    const plain = read('CHECK-5'), pub = read('CHECK-WP'),
          stale = read('CHECK-WS'), both = read('CHECK-WB');
    return {pass: plain.approveDisabled === false && plain.flags.length === 0 && !plain.stale
                  && pub.approveDisabled === true && pub.flags.join() === 'published'
                  && stale.approveDisabled === true && stale.stale === true
                  && /^v\d+ → v\d+$/.test(stale.version || '')
                  && both.approveDisabled === true && both.flags.join() === 'published'
                  && both.stale === true
                  // Dismiss is untouched on all of them: nothing about a warning
                  // makes throwing the agent's text away the dangerous answer.
                  && [plain, pub, stale, both].every(x => x.dismissLive),
            evidence: {plain, published: pub, stale, both}};
  });
  return {passed: R.filter(r => r.pass).length, of: R.length,
          failed: R.filter(r => !r.pass).map(r => r.name), results: R,
          proposalHeaderButtons: proposalButtons, notes};
}
