/* Sipan's document-local reader. Android pulls bounded snapshots only after a user gesture.
   No JavaScriptInterface: frames and page scripts never get a callable native object. */
(function (key, options) {
  'use strict';
  if (window[key]) return true;
  const root = document.documentElement;
  if (!root || !document.body) return false;
  const SKIP = 'script,style,noscript,pre,code,kbd,samp,input,textarea,select,option,svg,math,[contenteditable],[translate="no"],.notranslate,[aria-hidden="true"]';
  const BLOCK = 'p,h1,h2,h3,h4,h5,h6,li,td,th,blockquote,figcaption,button,label,dt,dd,a,summary,legend,div,article,section,header,footer,main,nav';
  const textState = new Map(), nodes = new WeakMap(), dirs = new Map();
  const media = new Map(), mediaNodes = new WeakMap();
  let next = 1, original = false, foreground = true, held = null, holdTimer = 0, dirty = true, walker = null;
  let imageCursor = null, lastSweep = 0, pointer = null;
  const scanBreak = () => window.performance.now();
  const signal = () => { if (!dirty) { dirty = true; walker = null; imageCursor = null; } };
  const observer = new MutationObserver(signal);
  observer.observe(root, { subtree: true, childList: true, characterData: true, attributes: true,
    attributeFilter: ['src','srcset','data-src','data-original','data-lazy-src','style','class','hidden','dir','lang'] });
  window.addEventListener('scroll', signal, true);
  window.addEventListener('resize', signal);
  window.addEventListener('load', signal, true);
  const view = () => ({ width: window.innerWidth, height: window.innerHeight });
  const near = (r, v, margin) => r.width > 0 && r.height > 0 && r.bottom >= -margin && r.right >= -100 && r.top <= v.height + margin && r.left <= v.width + 100;
  const visible = (el) => { const s = window.getComputedStyle(el); return s.visibility === 'visible' && s.display !== 'none' && Number(s.opacity) !== 0; };
  function cleanup() {
    if (scanBreak() - lastSweep < 1600) return;
    lastSweep = scanBreak();
    for (const [id, r] of textState) if (!r.node.isConnected) textState.delete(id);
    for (const [el] of dirs) if (!el.isConnected) dirs.delete(el);
    for (const [id, r] of media) if (!r.el.isConnected) media.delete(id);
  }
  function textBatch(v, deadline) {
    if (!options.text || textState.size >= 9500) return [];
    walker ||= document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    const blocks = new Map();
    let count = 0, length = 0, visits = 0;
    while (count < 24 && length < 3200 && visits++ < 1100 && (visits < 20 || scanBreak() < deadline)) {
      const node = walker.nextNode();
      if (!node) { walker = null; textExhausted = true; break; }
      const raw = node.nodeValue || '', cleaned = raw.trim();
      if (!cleaned || !/\p{L}/u.test(cleaned) || cleaned.length > 10000) continue;
      const el = node.parentElement;
      if (!el || el.closest(SKIP) || !near(el.getBoundingClientRect(), v, 180) || !visible(el)) continue;
      const range = document.createRange(); range.selectNodeContents(node);
      if (!near(range.getBoundingClientRect(), v, 180)) continue;
      const previous = nodes.get(node);
      if (previous && (previous.raw === raw || previous.paint === raw)) continue;
      if (previous) textState.delete(previous.id);
      const block = el.closest(BLOCK) || el;
      const id = next++, record = { id, node, block, raw, cleaned, paint: null };
      nodes.set(node, record); textState.set(id, record);
      if (!blocks.has(block)) blocks.set(block, []);
      blocks.get(block).push({ id, text: cleaned });
      count++; length += cleaned.length;
    }
    return Array.from(blocks.values()).flat();
  }
  let textExhausted = false, imageExhausted = false;
  function paintDirections() {
    for (const [block, dir] of dirs) {
      if (!block.isConnected) continue;
      if (original) {
        if (dir === null) block.removeAttribute('dir'); else block.setAttribute('dir', dir);
      } else if (block.getAttribute('dir') !== 'auto') block.setAttribute('dir', 'auto');
    }
  }
  function textResult(pairs) {
    if (!Array.isArray(pairs)) return;
    for (const pair of pairs) {
      const r = textState.get(pair.id);
      if (!r || !r.node.isConnected || r.node.nodeValue !== r.raw || typeof pair.text !== 'string' || !pair.text.trim()) continue;
      const lead = (r.raw.match(/^\s*/) || [''])[0], tail = (r.raw.match(/\s*$/) || [''])[0];
      r.paint = lead + pair.text + tail;
      if (!original) r.node.nodeValue = r.paint;
      // A whole translated block can adopt RTL without moving individual inline elements.
      if (!dirs.has(r.block)) dirs.set(r.block, r.block.getAttribute('dir'));
      if (!original) r.block.setAttribute('dir', 'auto');
    }
  }
  function urlOf(value) {
    if (!value) return '';
    try { const u = new URL(value.trim(), document.baseURI); return /^(https?:|data:|blob:)$/.test(u.protocol) ? u.href : ''; } catch (_) { return ''; }
  }
  function imgSource(el) {
    if (el.currentSrc && el.complete && el.naturalWidth >= 80) return urlOf(el.currentSrc);
    for (const key of ['data-src','data-lazy-src','data-original','data-url','src']) {
      const u = urlOf(el.getAttribute(key)); if (u) return u;
    }
    return urlOf((el.getAttribute('data-srcset') || el.getAttribute('srcset') || '').split(',')[0].trim().split(/\s+/)[0]);
  }
  function bgSource(el) {
    const bg = getComputedStyle(el).backgroundImage;
    // Replace only single background layers, never collapse gradients or several layers.
    const m = /^url\((?:"([^"]+)"|'([^']+)'|([^)]*))\)$/.exec(bg);
    return m ? urlOf(m[1] || m[2] || m[3]) : '';
  }
  function imageBatch(v, deadline) {
    if (!options.images || media.size >= 850) return [];
    imageCursor ||= document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT);
    const found = [];
    let visits = 0;
    while (found.length < 16 && visits++ < 500 && (visits < 30 || scanBreak() < deadline)) {
      const el = imageCursor.nextNode();
      if (!el) { imageCursor = null; imageExhausted = true; break; }
      const previous = mediaNodes.get(el);
      if (previous && previous.applied) {
        // A SPA may recycle an image node for the next panel.
        const stillOwn = previous.kind !== 'img' || (el.getAttribute('src') === previous.out && !el.hasAttribute('srcset'));
        if (stillOwn || original || previous === held) continue;
        // Drop a size hint we added if this element has been reused for another panel.
        const hint = previous.saved?.intrinsicWidth;
        if (hint && el.getAttribute('width') === String(hint)) el.removeAttribute('width');
        media.delete(previous.id); mediaNodes.delete(el);
      }
      if (el.closest('[translate="no"],.notranslate,[aria-hidden="true"]')) continue;
      const rect = el.getBoundingClientRect();
      if (!near(rect, v, Math.min(900, v.height)) || rect.width < 70 || rect.height < 70 || !visible(el)) continue;
      const kind = el instanceof HTMLImageElement ? 'img' : el instanceof HTMLCanvasElement ? 'canvas' : 'bg';
      if (kind === 'img' && el.complete && el.naturalWidth > 0 && Math.min(el.naturalWidth, el.naturalHeight) < 80) continue;
      if (kind === 'canvas' && Math.min(el.width, el.height) < 80) continue;
      const url = kind === 'img' ? imgSource(el) : kind === 'bg' ? bgSource(el) : '';
      if ((!url && kind !== 'canvas') || url.length > 15_000_000) continue;
      if (previous && previous.url === url && media.has(previous.id)) continue;
      if (previous) media.delete(previous.id);
      const id = next++;
      const priority = Math.max(0, -rect.bottom, rect.top - v.height);
      const record = {id, kind, el, url, applied: false, out: '', overlay: null, saved: null};
      media.set(id, record); mediaNodes.set(el, record);
      found.push({id, kind, url: /^(data:|blob:)/.test(url) ? '' : url, top: Math.round(rect.top),
        width: Math.round(rect.width), height: Math.round(rect.height), priority: Math.round(priority),
        lang: (el.closest('[lang]')?.getAttribute('lang') || root.lang || '').slice(0, 30)});
    }
    return found.sort((a, b) => a.priority - b.priority || a.top - b.top);
  }
  const tall = (w, h) => w >= 80 && h > 1700 && h * 5 >= w * 8;
  // Read embedded strip bytes without recompressing them; keep each WebView reply below the snapshot budget.
  function embedded(id, offset) {
    const r = media.get(id);
    if (original || !foreground || r?.kind !== 'img' || r.applied || !r.el.isConnected) return '';
    const el = r.el;
    if (!el.complete || el.currentSrc !== r.url || !tall(el.naturalWidth, el.naturalHeight)) return '';
    const prefix = /^data:image\/(?:png|jpeg|webp);base64,/i.exec(r.url)?.[0];
    if (!prefix) return '';
    if (offset === -1) return r.url.length - prefix.length;
    if (!Number.isInteger(offset) || offset < 0 || offset >= r.url.length - prefix.length) return '';
    return r.url.slice(prefix.length + offset, prefix.length + offset + 400000);
  }
  function snap(id) {
    const r = media.get(id);
    if (!r || !r.el.isConnected) return '';
    try {
      const el = r.el, canvas = document.createElement('canvas');
      const width = el.naturalWidth || el.width, height = el.naturalHeight || el.height;
      if (!width || !height || !['img','canvas'].includes(r.kind)) return '';
      let ratio = Math.min(1, 1700 / Math.max(width, height));
      for (let attempt = 0; attempt < 3; attempt++, ratio *= .67) {
        canvas.width = Math.max(1, Math.round(width * ratio)); canvas.height = Math.max(1, Math.round(height * ratio));
        canvas.getContext('2d').drawImage(el, 0, 0, canvas.width, canvas.height);
        const encoded = canvas.toDataURL('image/jpeg', .78);
        if (encoded.length <= 470000) return encoded;
      }
    } catch (_) { /* cross-origin or tainted canvas */ }
    return '';
  }
  function keepAttr(el, names) { return names.map(n => [n, el.getAttribute(n)]); }
  function attrs(el, list) { for (const [name, val] of list) if (val === null) el.removeAttribute(name); else el.setAttribute(name, val); }
  function render(r, show) {
    if (!r.applied || !r.el.isConnected) return;
    const el = r.el;
    if (r.kind === 'img') {
      if (show) {
        for (const [source] of r.saved.sources) source.removeAttribute('srcset');
        el.removeAttribute('srcset'); el.removeAttribute('sizes');
        // Preserve the preferred width of an auto-sized strip. CSS sizing/max-width still wins.
        if (r.saved.intrinsicWidth) el.setAttribute('width', r.saved.intrinsicWidth);
        el.setAttribute('data-original-src', r.url);
        if (el.getAttribute('src') !== r.out) el.setAttribute('src', r.out);
      } else {
        attrs(el, r.saved.attrs);
        for (const [source, saved] of r.saved.sources) attrs(source, saved);
      }
    } else if (r.kind === 'bg') {
      if (show) el.style.setProperty('background-image', `url("${r.out}")`, 'important');
      else if (r.saved.bg) el.style.setProperty('background-image', r.saved.bg, r.saved.priority);
      else el.style.removeProperty('background-image');
    } else {
      if (show && r.overlay && !r.overlay.isConnected) {
        const rect = el.getBoundingClientRect();
        r.overlay.style.left = `${rect.left + window.scrollX}px`; r.overlay.style.top = `${rect.top + window.scrollY}px`;
        r.overlay.style.width = `${rect.width}px`; r.overlay.style.height = `${rect.height}px`;
        document.body.appendChild(r.overlay); el.style.visibility = 'hidden';
      }
      if (!show) { r.overlay?.remove(); el.style.visibility = r.saved.visibility; }
    }
  }
  function imageResult(id, out) {
    const r = media.get(id);
    if (!r || r.applied || !r.el.isConnected || typeof out !== 'string') return false;
    const el = r.el;
    if (r.kind === 'img' && imgSource(el) !== r.url) return false;
    if (r.kind === 'bg' && bgSource(el) !== r.url) return false;
    if (r.kind === 'img') r.saved = {
      attrs: keepAttr(el, ['src','srcset','sizes','width','data-original-src']),
      intrinsicWidth: !el.hasAttribute('width') && !el.hasAttribute('height') && tall(el.naturalWidth, el.naturalHeight) ? el.naturalWidth : 0,
      sources: Array.from(el.parentElement?.matches('picture') ? el.parentElement.querySelectorAll('source') : [], source => [source, keepAttr(source, ['srcset'])])
    };
    if (r.kind === 'bg') r.saved = { bg: el.style.getPropertyValue('background-image'), priority: el.style.getPropertyPriority('background-image') };
    if (r.kind === 'canvas') {
      r.saved = { visibility: el.style.visibility };
      const overlay = document.createElement('img');
      overlay.alt = ''; overlay.src = out; overlay.className = 'rojink-canvas-translation';
      overlay.style.cssText = 'position:absolute;z-index:1;margin:0;max-width:none;object-fit:fill;';
      r.overlay = overlay; mediaNodes.set(overlay, r);
    }
    r.out = out; r.applied = true;
    if (!original) render(r, true);
    if (r.kind === 'img') {
      el.addEventListener('error', () => {
        if (r.applied && el.getAttribute('src') === r.out) { render(r, false); r.applied = false; failures++; }
      }, { once:true });
    }
    return true;
  }
  let failures = 0;
  function show(value) {
    original = !!value;
    for (const r of textState.values()) if (r.paint && r.node.isConnected && [r.raw, r.paint].includes(r.node.nodeValue)) r.node.nodeValue = original ? r.raw : r.paint;
    paintDirections();
    for (const r of media.values()) render(r, !original && r !== held);
    if (!original) signal();
  }
  function release() {
    clearTimeout(holdTimer); pointer = null;
    if (held) { const r = held; held = null; if (!original) render(r, true); }
  }
  function hit(target) {
    for (let el = target; el; el = el.parentElement) {
      const entry = mediaNodes.get(el); if (entry?.applied) return entry;
    }
    return null;
  }
  function down(e) {
    release(); if (original || !foreground) return;
    const r = hit(e.target); if (!r) return;
    pointer = { id: e.pointerId, x:e.clientX, y:e.clientY };
    holdTimer = setTimeout(() => { if (pointer) { held = r; render(r, false); } }, 420);
  }
  function move(e) { if (pointer?.id === e.pointerId && Math.hypot(e.clientX-pointer.x, e.clientY-pointer.y) > 16) release(); }
  function context(e) { if (!original && hit(e.target)) e.preventDefault(); }
  const events = [['pointerdown', down], ['pointermove', move], ['pointerup', release], ['pointercancel', release], ['blur', release], ['contextmenu', context]];
  for (const [evt, fn] of events) window.addEventListener(evt, fn, true);
  window[key] = {
    take(wantImages, wantText) {
      if (original || !foreground) return null;
      cleanup();
      if (dirty) { walker = null; imageCursor = null; textExhausted = imageExhausted = false; dirty = false; }
      const v = view(), deadline = scanBreak() + 11;
      // Start from a short time budget, then continue the iterators next tick.
      const images = wantImages && !imageExhausted ? imageBatch(v, deadline) : [];
      const texts = wantText && !textExhausted ? textBatch(v, deadline + 7) : [];
      const errors = failures; failures = 0;
      return { images, texts, errors, lang: (root.lang || '').slice(0,30) };
    },
    text: textResult, picture: imageResult, snap, embedded, show,
    active(value) { foreground = !!value; if (foreground) signal(); else release(); },
    stop() {
      release(); show(true); observer.disconnect();
      window.removeEventListener('scroll', signal, true); window.removeEventListener('resize', signal); window.removeEventListener('load', signal, true);
      for (const [evt, fn] of events) window.removeEventListener(evt, fn, true);
      delete window[key];
    }
  };
  return true;
})
