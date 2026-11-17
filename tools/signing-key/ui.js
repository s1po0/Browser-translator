(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const generate = $('generate');
  const shorten = $('shorten');
  const oldFile = $('existing-file');
  const oldPassword = $('existing-password');
  const instructions = [
    ['ANDROID_KEYSTORE_BASE64', key => key.base64],
    ['ANDROID_STORE_PASSWORD', key => key.password],
    ['ANDROID_KEY_ALIAS', key => key.alias],
    ['ANDROID_KEY_PASSWORD', key => key.password],
  ];
  let key = null;
  let attempt = 0;
  let downloadUrl = '';
  const clearDownload = () => {
    if (downloadUrl) URL.revokeObjectURL(downloadUrl);
    downloadUrl = '';
  };
  function clear() {
    attempt++;
    clearDownload();
    for (const field of $('secrets').querySelectorAll('textarea')) field.value = '';
    $('secrets').replaceChildren();
    $('fingerprint').textContent = '';
    $('results').hidden = true;
    $('status').hidden = true;
    key = null;
    generate.disabled = shorten.disabled = false;
    oldFile.value = oldPassword.value = '';
  }
  function say(text) {
    $('status').textContent = text;
    $('status').hidden = false;
  }
  function button(className, text) {
    const el = document.createElement('button'); el.type = 'button'; el.className = className; el.textContent = text;
    return el;
  }
  function createRows() {
    for (const [name, read] of instructions) {
      const li = document.createElement('li');
      const code = document.createElement('code'); code.dir = 'ltr'; code.textContent = name;
      const actions = document.createElement('div'); actions.className = 'controls';
      const copy = button('secondary', 'کۆپی');
      const reveal = button('small', 'پیشاندان'); reveal.setAttribute('aria-expanded', 'false');
      copy.setAttribute('aria-label', `کۆپیکردنی ${name}`);
      const field = document.createElement('textarea'); field.hidden = true; field.rows = name.endsWith('BASE64') ? 4 : 1;
      field.readOnly = true; field.dir = 'ltr'; field.spellcheck = false; field.autocomplete = 'off'; field.setAttribute('aria-label', name);
      const status = document.createElement('span'); status.className = 'result-status'; status.setAttribute('role', 'status');
      function show(value) {
        field.hidden = !value; field.value = value && key ? read(key) : '';
        reveal.textContent = value ? 'شاردنەوە' : 'پیشاندان';
        reveal.setAttribute('aria-expanded', String(value));
      }
      reveal.addEventListener('click', () => { if (key) show(field.hidden); });
      copy.addEventListener('click', async () => {
        if (!key) return;
        const current = key;
        try {
          if (!navigator.clipboard?.writeText) throw new Error('Use manual copy');
          await navigator.clipboard.writeText(read(current));
          if (key === current) status.textContent = 'کۆپی کرا.';
        } catch (_) {
          if (key !== current) return;
          show(true); field.focus(); field.select();
          status.textContent = 'بەهاکە لە خانەکە کۆپی بکە.';
        }
      });
      actions.append(copy, reveal, status); li.append(code, actions, field); $('secrets').append(li);
    }
  }
  async function run(operation, failure, done) {
    if (key || generate.disabled) return;
    generate.disabled = shorten.disabled = true;
    const active = ++attempt;
    say('لە ئامادەکردنی بەهاکاندایە؛ تکایە ئەم پەڕەیە دامەخە...');
    try {
      await new Promise(resolve => setTimeout(resolve, 40));
      const result = await operation();
      if (active !== attempt) return;
      key = result;
      oldFile.value = oldPassword.value = '';
      $('existing-key').open = false;
      createRows();
      $('fingerprint').textContent = key.fingerprint;
      $('results').hidden = false;
      say(done);
    } catch (_) {
      if (active !== attempt) return;
      clear();
      say(failure);
    }
  }
  generate.addEventListener('click', () => run(() => makeRojInkKey(window.forge, window.crypto),
    'کلیل دروست نەبوو. فایلەکە بە Chrome یان Firefox بکەرەوە و دووبارە هەوڵ بدە.',
    'کلیل ئامادەیە. فایلەکە و وشەی نهێنیی ١٢ پیتی هەڵبگرە.'));
  shorten.addEventListener('click', () => {
    const file = oldFile.files[0];
    const password = oldPassword.value;
    if (!file || !password) { say('فایل و وشەی نهێنیی پێشوو پێویستن.'); return; }
    oldPassword.value = '';
    run(async () => {
      if (file.size > 96 * 1024) throw new Error('Keystore too large');
      const encoded = /\.txt$/i.test(file.name) ? atob((await file.text()).replace(/\s/g, '')) : keyBytes(await file.arrayBuffer());
      return shortenRojInkPassword(window.forge, window.crypto, encoded, password);
    }, 'وشەی نهێنی یان فایلی پێشوو دروست نییە. فایلەکەت نەگۆڕدرا.',
    'هەمان کلیل پارێزرا. TXT ـی نوێ و وشەی نهێنیی ١٢ پیتی لە GitHub دابنێ.');
  });
  function download(blob, name) {
    clearDownload();
    downloadUrl = URL.createObjectURL(blob);
    const file = document.createElement('a');
    file.href = downloadUrl; file.download = name; file.rel = 'noopener';
    document.body.append(file); file.click(); file.remove();
  }
  $('download').addEventListener('click', () => {
    if (!key) return;
    const pfx = Uint8Array.from(atob(key.base64), item => item.charCodeAt(0));
    download(new Blob([pfx], { type: 'application/x-pkcs12' }), 'rojink-release.p12');
    pfx.fill(0);
  });
  $('download-base64').addEventListener('click', () => {
    if (!key) return;
    download(new Blob([key.base64], { type: 'text/plain;charset=utf-8' }), 'rojink-keystore-base64.txt');
  });
  $('clear').addEventListener('click', () => {
    if (window.confirm('فایل و وشەی نهێنیت هەڵگرتووە؟ دوای سڕینەوە، ئەم پەڕەیە ناتوانێت هەمان کلیل بگەڕێنێتەوە.')) clear();
  });
  window.addEventListener('pagehide', clear);
})();
