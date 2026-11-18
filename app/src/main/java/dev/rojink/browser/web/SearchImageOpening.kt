package dev.rojink.browser.web

/** Enable direct opening for one click on an already displayed Brave thumbnail, without a native bridge. */
internal fun braveImageOpeningScript(enable: Boolean): String = """
(() => {
  if (location.origin !== 'https://search.brave.com' || location.pathname !== '/images') return false;
  if (typeof window.__sipanClearDirectImage === 'function') window.__sipanClearDirectImage();
  if (!$enable) return true;
  const page = location.href;
  function clear() {
    window.removeEventListener('click', open, true);
    window.removeEventListener('pagehide', clear);
    delete window.__sipanClearDirectImage;
  }
  function open(event) {
    if (location.href !== page) { clear(); return; }
    if (event.button !== 0 || event.detail === 0 || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return;
    const image = document.elementsFromPoint(event.clientX, event.clientY).find(node =>
      node.localName === 'img' && node.complete && node.naturalWidth > 0);
    if (!image || !image.currentSrc) return;
    const url = new URL(image.currentSrc);
    if (url.origin !== 'https://imgs.search.brave.com' || url.username || url.password) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    clear();
    location.assign(url.href);
  }
  window.__sipanClearDirectImage = clear;
  window.addEventListener('click', open, true);
  window.addEventListener('pagehide', clear);
  return true;
})()
""".trimIndent()
