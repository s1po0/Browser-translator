'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { webcrypto, createHash } = require('node:crypto');
const { mkdtempSync, writeFileSync, readFileSync, rmSync } = require('node:fs');
const { spawnSync } = require('node:child_process');
const { join } = require('node:path');
const { tmpdir } = require('node:os');
const forge = require('node-forge');
const { makeRojInkKey: makeKey, shortenRojInkPassword } = require('./key.cjs');

function checkCommand(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8' });
  assert.equal(result.status, 0, `${command} failed its test`);
  return result.stdout + result.stderr;
}

test('produces a protected Android release keystore', async () => {
  const key = await makeKey(forge, webcrypto);
  assert.match(key.password, /^[A-Za-z0-9_-]{12}$/);
  assert.equal(key.alias, 'rojink');
  const decode = password => forge.pkcs12.pkcs12FromAsn1(forge.asn1.fromDer(forge.util.decode64(key.base64)), password);
  assert.throws(() => decode('wrong-password'));
  const imported = decode(key.password);
  const bag = imported.getBags({ friendlyName: key.alias, bagType: forge.pki.oids.pkcs8ShroudedKeyBag }).friendlyName[0];
  assert.equal(bag.key.n.bitLength(), 3072);
  const cert = imported.getBags({ friendlyName: key.alias, bagType: forge.pki.oids.certBag }).friendlyName[0].cert;
  assert.equal(cert.publicKey.n.compareTo(bag.key.n), 0);
  assert.equal(cert.subject.getField('CN').value, 'RojInk Release');
  assert.equal(cert.validity.notAfter.getUTCFullYear() - new Date().getUTCFullYear(), 30);
  const legacyPassword = Buffer.from(webcrypto.getRandomValues(new Uint8Array(24))).toString('base64url');
  const legacy = forge.asn1.toDer(forge.pkcs12.toPkcs12Asn1(bag.key, [cert], legacyPassword,
    { friendlyName: key.alias, algorithm: 'aes256', count: 120_000, saltSize: 16 })).getBytes();
  const rotated = shortenRojInkPassword(forge, webcrypto, legacy, legacyPassword);
  assert.match(rotated.password, /^[A-Za-z0-9_-]{12}$/);
  assert.equal(rotated.alias, key.alias);
  assert.equal(rotated.fingerprint, key.fingerprint);
  const rewrap = forge.pkcs12.pkcs12FromAsn1(forge.asn1.fromDer(forge.util.decode64(rotated.base64)), rotated.password);
  const rewrapped = rewrap.getBags({ friendlyName: key.alias, bagType: forge.pki.oids.pkcs8ShroudedKeyBag }).friendlyName[0].key;
  assert.equal(rewrapped.n.compareTo(bag.key.n), 0);
  assert.equal(rewrapped.d.compareTo(bag.key.d), 0);
  const dir = mkdtempSync(join(tmpdir(), 'rojink-key-test-'));
  try {
    const store = join(dir, 'test.p12');
    const password = join(dir, 'test-pass');
    writeFileSync(store, Buffer.from(rotated.base64, 'base64'), { mode: 0o600 });
    writeFileSync(password, `${rotated.password}\n`, { mode: 0o600 });
    const info = checkCommand('openssl', ['pkcs12', '-in', store, '-passin', `file:${password}`, '-info', '-noout']);
    assert.match(info, /AES-256-CBC, Iteration 120000/);
    if (process.env.KEYTOOL) {
      const list = checkCommand(process.env.KEYTOOL, ['-list', '-storetype', 'PKCS12', '-keystore', store, '-storepass:file', password, '-alias', key.alias]);
      assert.match(list, /PrivateKeyEntry/);
      assert.ok(list.includes(key.fingerprint));
    }
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('standalone page blocks communication and uses only its own embedded sheets and scripts', () => {
  const dir = mkdtempSync(join(tmpdir(), 'rojink-key-page-test-'));
  try {
    const file = join(dir, 'index.html');
    checkCommand(process.execPath, [join(__dirname, 'build.cjs'), file]);
    const html = readFileSync(file, 'utf8');
    const csp = html.match(/http-equiv="Content-Security-Policy" content="([^"]+)"/)[1];
    assert.match(csp, /default-src 'none'; connect-src 'none'; form-action 'none'/);
    const assets = Array.from(html.matchAll(/<(script|style)>([\s\S]+?)<\/\1>/g), match => match[2]);
    assert.equal(assets.length, 3);
    for (const asset of assets) assert.ok(csp.includes(`'sha256-${createHash('sha256').update(asset).digest('base64')}'`));
    assert.doesNotMatch(html, /<script[^>]+src=|<link[^>]+href=|<form/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
