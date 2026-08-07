// Where focus is, and whether the thing holding it is being drawn. Evaluate this
// between *real* `Tab` presses from the driving session — `page.keyboard.press('Tab')`
// — because a synthetic KeyboardEvent does not move focus and nothing inside the page
// can send a real one. Check 11 in checks.js makes the same claim by census, in one
// evaluate; this is the keystroke.
//
// `insideOverlay: false` with `painted: 'diff-overlay'` is the finding this exists for:
// focus on a control nothing is drawing, one Enter from a write.
() => {
  const el = document.activeElement, ov = document.querySelector('.diff-overlay');
  const r = el.getBoundingClientRect();
  const at = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
  return {active: el.tagName + '.' + (typeof el.className === 'string' ? el.className : ''),
          text: (el.textContent || '').trim().slice(0, 30),
          insideOverlay: ov ? ov.contains(el) : null,
          insideInert: !!el.closest('[inert]'),
          painted: at && (typeof at.className === 'string' ? at.className : at.tagName)};
}
