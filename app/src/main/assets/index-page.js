(function () {
  'use strict';
  // Explicit local indexing only. No fetch, no forms, and no scriptable native bridge.
  const main = document.querySelector('main, article, [role=main]') || document.body;
  if (!main) return null;
  const skip = 'script, style, noscript, template, nav, footer, aside, form, input, textarea, select, button, pre, code, iframe, svg, [contenteditable], [hidden], [aria-hidden="true"], [inert]' + (main === document.body ? ', header' : '');
  const clean = (s, count) => String(s || '').slice(0, count * 2).replace(/\s+/g, ' ').trim().slice(0, count);
  const walk = document.createTreeWalker(main, NodeFilter.SHOW_TEXT);
  const limit = 6000;
  let body = '', scanned = 0;
  for (let node; body.length < limit && scanned++ < 1800 && (node = walk.nextNode());) {
    const el = node.parentElement;
    if (!el || !node.nodeValue || !node.nodeValue.trim() || el.closest(skip)) continue;
    const style = getComputedStyle(el);
    if (style.visibility !== 'visible' || style.display === 'none' || Number(style.opacity || 1) === 0 || !el.getClientRects().length) continue;
    const text = clean(node.nodeValue, limit - body.length);
    if (text) body += (body ? ' ' : '') + text;
  }
  const meta = document.querySelector('meta[name="description"]');
  return { url: location.href, title: clean(document.title, 160), summary: clean(meta?.content || body, 300), body: body.slice(0, limit) };
})()
