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
  function imageURL(node) {
    if (!node || node.localName !== 'img' || !node.complete || !node.naturalWidth || !node.currentSrc) return null;
    const url = new URL(node.currentSrc);
    return url.origin === 'https://imgs.search.brave.com' && !url.username && !url.password ? url : null;
  }
  function open(event) {
    if (location.href !== page) { clear(); return; }
    if (event.button !== 0 || event.detail === 0 || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return;
    let url = imageURL(event.target);
    if (!url) {
      for (const node of document.elementsFromPoint(event.clientX, event.clientY)) {
        url = imageURL(node);
        if (url) break;
      }
    }
    if (!url && event.target instanceof Element) {
      // A result button can receive the tap instead of its image. Never guess between pictures.
      const card = event.target.closest('button, a, figure, [role="button"]');
      if (card) {
        const images = Array.from(card.querySelectorAll('img')).filter(image => image.naturalWidth >= 64);
        if (images.length === 1) url = imageURL(images[0]);
      }
    }
    if (!url) return;
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
