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
  // The answers are in the top bar now, not in the header — *also, on the inbox/tray.
  // place the Seen Approve/Dismiss buttons in that position.* Both are recorded: the
  // bar's, which is where they are, and the header's, which has to be **empty of them**
  // and is asserted so in 13.
  const proposalButtons = [...document.querySelectorAll('.top-bar-actions button')]
    .map(b => b.textContent.trim());
  const headerButtons = [...document.querySelectorAll('.diff-header button')]
    .map(b => b.textContent.trim());

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
  // else, so what this asserts is the *exact* set outside the overlay rather than an
  // empty one. A page that stopped being inert, or a control appearing up there that
  // is nobody's, reddens this.
  //
  // **And that set has grown by two, which is this check's whole job.** The answers
  // are in the bar now — *also, on the inbox/tray. place the Seen Approve/Dismiss
  // buttons in that position* — so `← Inbox`, Approve, Dismiss and the theme toggle
  // are what a keyboard can reach outside this surface. `inert-behind!`'s docstring
  // predicted that a widget added to the bar would become reachable from a dialog
  // *here*, and said that would be the moment to narrow the exemption. What arrived
  // is not a widget: it is this dialog's own answers, for which being reachable from
  // the dialog is the point. So the exemption keeps its width and **this check is the
  // narrowing** — it names the four, by class, and reddens on a fifth whatever the
  // fifth is. A Recipe page's Publish surviving into the bar under the viewer is the
  // failure it is really guarding against, and `core/surface-actions`' ordered `cond`
  // is what prevents it; `recipe-page-checks.js` 42 is that from the other side.
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
                  && outside.length === 4
                  && outside.every(e => bar.contains(e))
                  && outside.some(e => e.classList.contains('diff-back'))
                  && outside.some(e => e.classList.contains('proposal-approve'))
                  && outside.some(e => e.classList.contains('proposal-dismiss'))
                  && outside.some(e => e.classList.contains('dark-mode-toggle'))
                  // and nothing belonging to the page underneath — named, because a
                  // Recipe page's Publish surviving into the bar under a dialog is the
                  // one thing this exemption could actually be abused by
                  && !outside.some(e => e.classList.contains('recipe-publish'))
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

  // 13. **the viewer's chrome is the bar's now — both ends of it.** *for the versions
  //     view that instead of an x there will be a back button (going back to either
  //     the inbox or to tha Page page, depending where we came from)*, and then *also,
  //     on the inbox/tray. place the Seen Approve/Dismiss buttons in that position.*
  //     So one check for one arrangement: the left slot is the way out, the right slot
  //     is what this reading can be **answered** with, and the surface itself carries
  //     neither — no ✕, and no Approve or Dismiss in `.diff-header`.
  //
  //     **The header's emptiness is the half that can rot quietly.** A version of this
  //     that only looked in the bar would go green with the answers drawn in *both*
  //     places, which is the state a half-finished move leaves behind and is worse than
  //     either end of it: two live Approve buttons, one of them under the reader's
  //     thumb where the old muscle memory is.
  //
  //     `← Inbox` and not `← Recipe` because this suite opens the viewer from the
  //     queue. The label is derived from `:page` rather than stored, so this is also
  //     the assertion that the derivation reads the right end of it; the Recipe origin
  //     is `recipe-page-checks.js` 25, since that surface is that file's subject — and
  //     42 over there is the *other* half of this one, where the viewer has no answers
  //     to offer and the right slot has to be empty rather than hold the page's own.
  await check('13 the viewer wears a back button in the bar, and its answers beside it', () => {
    const slot = [...document.querySelectorAll('.top-bar-left button')]
      .map(b => b.textContent.trim());
    const right = [...document.querySelectorAll('.top-bar-right > *')]
      .map(e => (e.className || e.tagName).split(' ')[0]);
    const answers = [...document.querySelectorAll('.top-bar-actions button')]
      .map(b => b.textContent.trim());
    const inTheHeader = [...document.querySelectorAll('.diff-header button')]
      .map(b => b.textContent.trim());
    const xs = [...document.querySelectorAll('.diff-overlay button')]
      .filter(b => b.textContent.trim() === '✕');
    const box = document.querySelector('.top-bar-actions');
    const tog = document.querySelector('.dark-mode-toggle');
    return {pass: slot.length === 1 && slot[0] === '← Inbox'
                  && !!document.querySelector('.top-bar-left .diff-back')
                  // the answers, in the slot, immediately left of the toggle
                  && answers.join(',') === 'Approve,Dismiss'
                  && !!box && box.nextElementSibling === tog
                  && right.join(',') === 'top-bar-actions,dark-mode-toggle'
                  // and none of them left behind on the surface
                  && inTheHeader.length === 0
                  && !document.querySelector('.diff-overlay .proposal-approve')
                  && !document.querySelector('.diff-overlay .proposal-dismiss')
                  && xs.length === 0 && !document.querySelector('.diff-close')
                  // and the surface starts below the bar rather than over it
                  && document.querySelector('.diff-overlay').getBoundingClientRect().top
                     >= document.querySelector('.top-bar').getBoundingClientRect().bottom,
            evidence: {slot, right, answers, inTheHeader, closeButtonsFound: xs.length,
                       answersBoxIsTheTogglesSibling: !!box && box.nextElementSibling === tog,
                       page: stateGet('page'),
                       overlayTop: Math.round(document.querySelector('.diff-overlay')
                                              .getBoundingClientRect().top),
                       barBottom: Math.round(document.querySelector('.top-bar')
                                             .getBoundingClientRect().bottom)}};
  });

  // 4. **dismiss asks first, and the question lands on top — now that the button that
  //    opens it is in the top bar.** That is the part of this check that had to be
  //    re-established rather than re-run: `.modal-backdrop` at 30 over `.diff-overlay`
  //    at 25 is a claim about two siblings at the app root, and a button in the bar is
  //    a different stacking context from a button in `.diff-header`. It still holds —
  //    the bar creates no stacking context of its own and the modal is not its child —
  //    but the check asserts the z-indexes and the *hit test* rather than trusting
  //    either of those sentences.
  await step('dismiss from the viewer',
             () => clickIn(document, '.top-bar-actions .proposal-dismiss'));
  await until(() => modal());
  await check('4 dismiss from the viewer confirms first, above the viewer', () => {
    const m = modal(), ov = overlay();
    const zModal = m && parseInt(getComputedStyle(m).zIndex, 10);
    const zOverlay = ov && parseInt(getComputedStyle(ov).zIndex, 10);
    const confirm = document.querySelector('.modal-actions button.danger');
    const r = confirm && confirm.getBoundingClientRect();
    const atTheButton = r && document.elementFromPoint(r.left + r.width / 2,
                                                       r.top + r.height / 2);
    return {pass: !!m && m.textContent.includes('Dismiss this proposal?')
                  && !!ov && zModal > zOverlay
                  // and it keeps its buttons: the surface below is inert, this one
                  // is above it and must not be.
                  && !m.closest('[inert]') && !m.inert
                  // measured, not deduced: the confirm button is what is painted at
                  // its own centre, so nothing of the viewer is over it
                  && !!atTheButton && confirm.contains(atTheButton),
            evidence: {modalShown: !!m, zModal, zOverlay, viewerStillOpen: !!ov,
                       modalInert: !!m && (m.inert || !!m.closest('[inert]')),
                       openedFrom: 'the top bar',
                       confirmButtonHitTest: atTheButton
                         && (typeof atTheButton.className === 'string'
                             ? atTheButton.className : atTheButton.tagName),
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
  //
  //    **`CHECK-MOD`'s row and not whichever `modified` row the dev database happens
  //    to hold.** This step used to take the first one it found, which was fine while
  //    5 only read it; 16 now presses its Seen, and answering an entry takes it out of
  //    the queue for good. Borrowing one of his and consuming it would leave the next
  //    run with nothing to open — so `seed.py` builds one, as the machine, twice.
  await step('open a modified entry', () => {
    const modRow = rows().find(r =>
      r.querySelector('.inbox-kind')?.textContent === 'modified'
      && r.textContent.includes('CHECK-MOD'));
    if (!modRow) throw new Error('no CHECK-MOD modified entry in the queue — reseed');
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
  // 15. **the third kind's one answer is Seen, and it is in the same slot.** *when we
  //     go from the tray/inbox, to the versions, we can approve/dismiss but not set
  //     "Seen". add that*, and then *also, on the inbox/tray. place the Seen
  //     Approve/Dismiss buttons in that position.* So the two asks meet in the bar:
  //     whatever a queue entry can be answered with is offered where it is read, and
  //     for a `modified` entry that is one button.
  //
  //     **Which button it is, is the assertion.** A slot that simply drew *the
  //     answers* without asking which reading is up would put Approve on a change that
  //     has already happened — there is nothing to approve, the save is in the history
  //     — and the API refuses `seen` on a proposal for the mirror-image reason. So this
  //     asserts the set exactly: Seen, and not Approve or Dismiss beside it.
  //
  //     The viewer is the one this suite opened for check 5, which is a `modified`
  //     entry from the dev queue.
  await check('15 a modified entry offers Seen in the bar, and only Seen', () => {
    const answers = [...document.querySelectorAll('.top-bar-actions button')]
      .map(b => b.textContent.trim());
    const box = document.querySelector('.top-bar-actions');
    const tog = document.querySelector('.dark-mode-toggle');
    // What the header may still hold is the ← → **nav**: stepping through a history is
    // a way of *looking* at it and belongs with the reading, which is the same line
    // the bar's two slots are drawn along. What it may not hold is an answer.
    const headerButtons = [...document.querySelectorAll('.diff-header button')]
      .map(b => b.textContent.trim());
    return {pass: answers.join(',') === 'Seen'
                  && !!document.querySelector('.top-bar-actions .diff-seen')
                  && !!box && box.nextElementSibling === tog
                  && !document.querySelector('.top-bar-actions .proposal-approve')
                  && !document.querySelector('.diff-overlay .diff-seen')
                  && headerButtons.join(',') === '←,→'
                  && !!overlay(),
            evidence: {answers, heading: document.querySelector('.diff-header h2')?.textContent,
                       headerButtons,
                       diffingEvent: stateGet('diffing-event'),
                       diffingProposal: diffingProposal()}};
  });

  // 16. **and pressing it there answers the entry: the queue loses one and the surface
  //     goes.** The button is in the bar and the surface it answers is under it, so
  //     this is also the assertion that the two are still wired to each other —
  //     `state/mark-seen` closes the viewer when the entry it acknowledged is the one
  //     the surface was opened from, which is `resolve-proposal`'s rule for the other
  //     kind. A Seen that left the surface standing over an answered entry is the
  //     failure this shares with check 7.
  const rowsBeforeSeen = rows().length;
  const seenEvent = stateGet('diffing-event');
  await check('16 Seen from the bar takes the entry out of the queue and closes it',
    async () => {
      clickIn(document, '.top-bar-actions .diff-seen');
      await until(() => !overlay(), 8000);
      await wait(500);
      const stillListed = stateInbox().some(e => e.id === seenEvent);
      return {pass: !overlay() && !stillListed
                    && rows().length === rowsBeforeSeen - 1
                    && !stateGet('diffing') && !stateGet('diffing-event')
                    && !document.querySelector('.top-bar-actions')
                    && !document.querySelectorAll('[data-inert-behind-viewer]').length,
              evidence: {eventAnswered: seenEvent, rowsBefore: rowsBeforeSeen,
                         rowsNow: rows().length, stillListed,
                         viewerOpen: !!overlay(), diffing: stateGet('diffing'),
                         barActionsLeftBehind: !!document.querySelector('.top-bar-actions'),
                         inertReleased:
                           !document.querySelectorAll('[data-inert-behind-viewer]').length}};
    });

  // 6. **the row has no way to dismiss, so the confirmation has exactly one entry
  //    point.** This check has changed sides, like 11: it asserted that dismissing
  //    *from the row* asked first, and there is no such gesture any more — *we should
  //    not allow to to approve/dismiss or seen ON the tray/overview page*, and then
  //    the answers left the surface's header for the top bar as well. It had been red
  //    since the first of those two moves, on `nothing to click: .proposal-dismiss`,
  //    which is a check outliving its subject rather than a bug in the app.
  //
  //    Kept and turned round rather than deleted, because the number is the name and
  //    because the negative is a real claim: a row is a kind, a title that opens, what
  //    it is filed under, which version, and when — triage is choosing what to open.
  //    A Dismiss reappearing on a row reddens this, and so does one appearing anywhere
  //    but the bar.
  await check('6 no row can dismiss, and the only Dismiss is in the bar', () => {
    // `:not(.inbox-title-link)`, because the title **is** a button — it is the way
    // through to the surface — and counting it as an answer is how a check about
    // "no buttons on a row" goes red about the one control a row is supposed to have.
    const onRows = [...document.querySelectorAll('.inbox-row button:not(.inbox-title-link)')]
      .map(b => b.textContent.trim());
    const anywhere = [...document.querySelectorAll('.proposal-dismiss')]
      .map(b => (b.closest('.top-bar') && 'the top bar')
                || (b.closest('.inbox-row') && 'a queue row')
                || (b.closest('.diff-overlay') && 'the viewer')
                || 'somewhere else');
    return {pass: !document.querySelector('.inbox-row .proposal-dismiss')
                  && !document.querySelector('.inbox-row .proposal-approve')
                  // the rows that keep Seen are the purged ones, and only those
                  && onRows.every(t => t === 'Seen' || t === '…')
                  && !modal() && !overlay(),
            evidence: {buttonsOnRows: onRows, dismissButtonsInTheDocument: anywhere,
                       rows: rows().length}};
  });
  // No confirmation to cancel here any more: 6 used to open one from a row and this
  // step closed it again. The step outlived the gesture and went on failing into the
  // notes — `nothing to click in, for: button.secondary` — which is the quiet kind of
  // rot a suite carries: nothing red, a line in a field nobody reads, and the next
  // check starting from a state the run only assumed.

  // 7. **approve from the viewer's own answers — in the bar — resolves the entry, closes
  //    the viewer, and the Recipe takes the agent's text.** The last of those is new
  //    and is the end of the road the button moved along: pressing it in a different
  //    place must still write the same version. Read back through the API rather than
  //    off the screen, because what the client is holding a moment after a write is
  //    exactly what a cached response would be right about for the wrong reason.
  const beforeApprove = (() => {
    const e = stateInbox().find(x => (x.recipe_title || '').includes('CHECK-1'));
    return e && {recipeId: e.recipe_id, version: e.proposal.recipe_version,
                 proposed: e.proposal.description};
  })();
  await step('open CHECK-1 and approve it from the bar', async () => {
    clickIn(rowFor('CHECK-1'), '.inbox-title-link');
    await until(() => overlay() && document.querySelector('.diff-editor .cm-editor'));
    await until(() => document.querySelector('.top-bar-actions .proposal-approve'));
    clickIn(document, '.top-bar-actions .proposal-approve');
  });
  await until(() => !rowFor('CHECK-1'));
  await wait(400);
  await check('7 approve from the bar resolves, closes, and writes the version',
    async () => {
      const stored = beforeApprove
        ? await (await fetch('/api/recipes/' + beforeApprove.recipeId + '?detail=full'))
            .json()
        : null;
      return {pass: !overlay() && !rowFor('CHECK-1') && diffingProposal() === null
                    && !!beforeApprove && !!stored
                    && stored.description === beforeApprove.proposed
                    && stored.version === beforeApprove.version + 1
                    && stored.source === 'machine',
              evidence: {viewerOpen: !!overlay(), entryStillInQueue: !!rowFor('CHECK-1'),
                         diffingProposal: diffingProposal(),
                         versionBefore: beforeApprove?.version, versionNow: stored?.version,
                         sourceNow: stored?.source,
                         bodyIsTheAgentsWording:
                           stored?.description === beforeApprove?.proposed,
                         storedTail: (stored?.description || '').trim().slice(-40)}};
    });

  // 8. approve from the ROW while the viewer is open on that proposal closes it.
  //
  //    **Driven through the state fn the row's button called, and the reason is now
  //    measured rather than argued.** The previous pass claimed the gesture was
  //    unreachable because the overlay is `position: fixed; inset: 0` — true of the
  //    mouse and false of the keyboard, which a reviewer showed with Tab+Enter. It
  //    became unreachable when the surface became a dialog with everything behind it
  //    inert, and the button has since gone from the row altogether. `reachable` is
  //    still part of the pass and now answers false for the stronger of the two
  //    reasons: `rowApprove` is null, because there is no such button to be inert.
  //
  //    The *invariant* is what this check is about and it is untouched by any of that:
  //    a resolution arriving from anywhere must close a viewer that is open on it.
  //    `state/resolve-proposal` is the one place both entry points meet, and with the
  //    answers now in the bar the second entry point is an agent's POST landing while
  //    he reads — which is exactly what this drives.
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
    await until(() => document.querySelector('.top-bar-actions .proposal-dismiss'));
    clickIn(document, '.top-bar-actions .proposal-dismiss');
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

  // 12. **a warned proposal's row still says why, and now that is *all* a row does.**
  //     This check has changed sides too, and for the same reason 6 did: it asserted a
  //     dead Approve on the two warned rows and a live one on the plain row, and there
  //     is no Approve on any row — so it had been red on `approveDisabled: undefined`
  //     since the answers left the queue. A gate that has become an absence is stronger
  //     than the gate was, and saying so is what keeps the two warnings from being
  //     quietly forgotten along with the button they used to disable.
  //
  //     So what it asserts is the **words**, which is what a row was left with: the
  //     `published` flag, and the version badge printing the *relationship* `v1 → v3`
  //     rather than a stale number on its own. Those are triage — they say *read this
  //     one carefully* — and the paragraphs they point at are in the viewer
  //     (`published-note`, `staleness-note`), which is where the answering happens now.
  //     The plain row is the control: no flag, no stale badge, and no buttons either,
  //     so a run cannot pass by every row being equally bare for the wrong reason.
  await check('12 a warned proposal\'s row says why, and no row answers', () => {
    const read = t => {
      const r = rowFor(t);
      if (!r) throw new Error('no row for ' + t + ' — reseed');
      const v = r.querySelector('.inbox-version');
      return {version: v?.textContent, stale: !!r.querySelector('.inbox-version.stale'),
              flags: [...r.querySelectorAll('.proposal-flag')].map(f => f.textContent),
              // the title link is excluded and the title link is the point: it is a
              // button, it is the way through to where the entry *can* be answered,
              // and a row is otherwise buttonless. See 6.
              buttons: [...r.querySelectorAll('button:not(.inbox-title-link)')]
                .map(b => b.textContent.trim()),
              opens: !!r.querySelector('.inbox-title-link')};
    };
    // CHECK-5 and not one of 1 to 4: this runs after the checks that resolve those,
    // and the unwarned case has to be a row that is still on the page.
    const plain = read('CHECK-5'), pub = read('CHECK-WP'),
          stale = read('CHECK-WS'), both = read('CHECK-WB');
    const all = [plain, pub, stale, both];
    return {pass: plain.flags.length === 0 && !plain.stale
                  && pub.flags.join() === 'published'
                  && stale.stale === true && /^v\d+ → v\d+$/.test(stale.version || '')
                  && both.flags.join() === 'published' && both.stale === true
                  // no answer on any of them, warned or not, and each still opens
                  && all.every(x => x.buttons.length === 0 && x.opens)
                  // and the answers exist — in the bar, once a proposal is open
                  && !document.querySelector('.top-bar-actions'),
            evidence: {plain, published: pub, stale, both,
                       buttonsOnAnyProposalRow: all.flatMap(x => x.buttons),
                       barActionsWithNothingOpen:
                         !!document.querySelector('.top-bar-actions')}};
  });
  return {passed: R.filter(r => r.pass).length, of: R.length,
          failed: R.filter(r => !r.pass).map(r => r.name), results: R,
          proposalHeaderButtons: proposalButtons, notes};
}
