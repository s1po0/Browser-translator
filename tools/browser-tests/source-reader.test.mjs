import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { JSDOM } from 'jsdom';

const script = fs.readFileSync(new URL('../../app/src/main/assets/source-reader.js', import.meta.url), 'utf8');

test('Source reader hides marked ads, including later insertions, without altering chapter content', () => {
    const dom = new JSDOM(`<article><div class="ad-banner">Ad</div><img src="/page.jpg" class="adventure">
        <a href="/chapter-2">Next chapter</a><iframe title="ordinary embed"></iframe></article>`, {
        url: 'https://source.example/chapter-1', runScripts: 'outside-only',
    });
    try {
        const { window:w } = dom;
        const { document:d } = w;
        const chapter = d.querySelector('article');
        const original = chapter.innerHTML;
        w.eval(script);
        w.eval(script); // Fallback after document start is idempotent.
        assert.equal(d.querySelectorAll('#sipan-source-reader-style').length, 1);
        assert.equal(chapter.innerHTML, original);
        assert.equal(w.getComputedStyle(d.querySelector('.ad-banner')).display, 'none');
        assert.notEqual(w.getComputedStyle(d.querySelector('img')).display, 'none');
        assert.notEqual(w.getComputedStyle(d.querySelector('iframe')).display, 'none');
        chapter.insertAdjacentHTML('beforeend', '<aside data-ad-slot="later">Another ad</aside>');
        assert.equal(w.getComputedStyle(d.querySelector('aside')).display, 'none');
    } finally { dom.window.close(); }
});
