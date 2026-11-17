import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { JSDOM } from 'jsdom';

const reader = fs.readFileSync(new URL('../../app/src/main/assets/reader.js', import.meta.url), 'utf8');
const rect = (top = 5) => ({ top, left: 0, right: 480, bottom: top + 400, width: 480, height: 400 });
function page(body) {
  const dom = new JSDOM(`<html lang="en"><body>${body}</body></html>`, { url: 'https://fixture.example/', runScripts: 'outside-only' });
  const w = dom.window;
  w.Element.prototype.getBoundingClientRect = function () { return rect(Number(this.dataset?.top || '5')); };
  w.Range.prototype.getBoundingClientRect = () => rect();
  for (const el of w.document.querySelectorAll('*')) el.style.opacity ||= '1';
  const install = (options = { text:true, images:true }) => w.eval(`${reader}('bridge',${JSON.stringify(options)})`);
  return { w, d: w.document, get: () => w.bridge, install, end: () => w.close() };
}
const tick = () => new Promise(resolve => setTimeout(resolve, 0));

test('text is consent gated, keeps markup and restores exact original node and direction', () => {
  const p = page('<p dir="ltr"> Hello <strong>friend</strong>! </p><code>DO NOT SEND</code><span hidden>not visible</span><input value="private">');
  try {
    assert.equal(p.get(), undefined);
    const h = p.d.querySelector('p');
    const before = h.outerHTML;
    assert.equal(p.install(), true);
    const batch = p.get().take(false, true).texts;
    assert.deepEqual(Array.from(batch, i => i.text), ['Hello','friend']);
    p.get().text([{id:batch[0].id,text:'سڵاو'},{id:batch[1].id,text:'هاوڕێ'}]);
    assert.equal(h.dir, 'auto');
    assert.equal(h.querySelector('strong').textContent, 'هاوڕێ');
    assert.equal(h.textContent, ' سڵاو هاوڕێ! ');
    p.get().show(true);
    assert.equal(h.outerHTML, before);
    assert.equal(p.get().take(true,true), null);
    p.get().show(false);
    assert.equal(h.dir, 'auto');
    p.get().stop();
    assert.equal(h.outerHTML, before);
    assert.equal(p.get(), undefined);
  } finally { p.end(); }
});

test('observer scans new nodes if active and not while original or hidden', async () => {
  const p = page('<h1>Hello</h1>');
  try {
    p.install();
    p.get().take(false, true);
    p.get().active(false);
    const el = p.d.createElement('h2'); el.style.opacity = '1'; el.textContent = 'New chapter';
    p.d.body.append(el); await tick();
    assert.equal(p.get().take(false, true), null);
    p.get().active(true);
    assert.deepEqual(Array.from(p.get().take(false, true).texts, i => i.text), ['New chapter']);
    p.get().stop();
  } finally { p.end(); }
});

test('prioritizes visible pictures, restores picture srcsets and supports hold-and-release', async () => {
  const p = page('<img src="lower.png" data-top="900"><picture><source srcset="wide.png 2x"><img data-src="panel.png" src="placeholder.png" srcset="other.png 2x"></picture>');
  try {
    p.install({text:false,images:true});
    const records = p.get().take(true, false).images;
    assert.deepEqual(Array.from(records, x => x.url), ['https://fixture.example/panel.png', 'https://fixture.example/lower.png']);
    const image = p.d.querySelector('picture img'), source = p.d.querySelector('picture source');
    const attrs = () => image.getAttributeNames().sort().map(n => [n, image.getAttribute(n)]);
    const before = attrs();
    assert.equal(p.get().picture(records[0].id, 'https://fixture.example/.__rojink/test.png'), true);
    assert.equal(image.getAttribute('data-original-src'), 'https://fixture.example/panel.png');
    assert.equal(source.hasAttribute('srcset'), false);
    image.dispatchEvent(Object.assign(new p.w.Event('pointerdown', { bubbles: true }), {pointerId:1,clientX:20,clientY:30}));
    await new Promise(resolve => setTimeout(resolve, 460));
    assert.deepEqual(attrs(), before);
    image.dispatchEvent(new p.w.Event('pointerup', { bubbles:true }));
    assert.equal(image.getAttribute('src'), 'https://fixture.example/.__rojink/test.png');
    p.get().stop();
    assert.deepEqual(attrs(), before);
    assert.equal(source.getAttribute('srcset'), 'wide.png 2x');
  } finally { p.end(); }
});
