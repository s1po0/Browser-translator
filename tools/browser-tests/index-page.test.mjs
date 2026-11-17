import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { JSDOM } from 'jsdom';

const script = readFileSync(new URL('../../app/src/main/assets/index-page.js', import.meta.url), 'utf8');

test('local index snapshot excludes forms, hidden text and navigation and bounds what it stores', () => {
  const dom = new JSDOM(`<title>Chapter 1</title><meta name="description" content="A comic page">
    <main><h1>Beyond the hill</h1><nav>nav-secret</nav><p hidden>hidden-secret</p>
    <form><p>form-secret</p><input value="password-secret"></form><script>script-secret</script>
    <p>Character with an unusual name</p><p>${'x'.repeat(8500)}</p></main>`, {
    runScripts: 'outside-only', url: 'https://example.org/chapter',
  });
  dom.window.HTMLElement.prototype.getClientRects = () => [{}];
  try {
    const result = dom.window.eval(script);
    assert.equal(result.url, 'https://example.org/chapter');
    assert.equal(result.title, 'Chapter 1');
    assert.equal(result.summary, 'A comic page');
    assert.match(result.body, /Beyond the hill Character with an unusual name/);
    assert.doesNotMatch(result.body, /secret/);
    assert.ok(result.body.length <= 6000);
  } finally { dom.window.close(); }
});
