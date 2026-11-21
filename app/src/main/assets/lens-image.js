(() => {
    'use strict';
    // Read-only: report the largest image nearest the middle of what the reader is looking at.
    // Returning a plain object keeps the native side parsing the same JSON as index-page.js.
    if (!document.images || !document.images.length) return { src: '' };
    const middle = window.scrollY + window.innerHeight / 2;
    let best = '';
    let bestGap = Infinity;
    for (const image of document.images) {
        const rect = image.getBoundingClientRect();
        if (rect.width < 160 || rect.height < 160) continue;
        const source = image.currentSrc || image.src || '';
        if (!/^https?:/.test(source)) continue;
        const gap = Math.abs(rect.top + window.scrollY + rect.height / 2 - middle);
        if (gap < bestGap) { bestGap = gap; best = source; }
    }
    return { src: best };
})()
