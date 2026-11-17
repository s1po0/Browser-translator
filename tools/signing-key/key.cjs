'use strict';

// This file is shared by the offline page and the CLI compatibility tests.
const keyBytes = raw => Array.from(new Uint8Array(raw), value => String.fromCharCode(value)).join('');
function secureBytes(entropy, count) {
  if (typeof entropy?.getRandomValues !== 'function') throw new Error('Secure randomness unavailable');
  return entropy.getRandomValues(new Uint8Array(count));
}

async function makeRojInkKey(forge, entropy) {
  const random = count => secureBytes(entropy, count);
  let privateKey;
  if (entropy.subtle) {
    const pair = await entropy.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 3072,
      publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify']);
    const encoded = await entropy.subtle.exportKey('pkcs8', pair.privateKey);
    try { privateKey = forge.pki.privateKeyFromAsn1(forge.asn1.fromDer(keyBytes(encoded))); }
    finally { new Uint8Array(encoded).fill(0); }
  } else {
    // Some Android file viewers expose secure random bytes but not SubtleCrypto.
    // Forge's incremental RSA generator runs with OS randomness and no worker, network, or extra file.
    const state = forge.pki.rsa.createKeyPairGenerationState(3072, 0x10001, {
      prng: { getBytesSync: count => keyBytes(random(count)) },
    });
    while (!forge.pki.rsa.stepKeyPairGenerationState(state, 18)) {
      await new Promise(resolve => setTimeout(resolve, 0));
    }
    privateKey = state.keys.privateKey;
  }
  const cert = forge.pki.createCertificate();
  cert.publicKey = forge.pki.setRsaPublicKey(privateKey.n, privateKey.e);
  cert.serialNumber = '01' + forge.util.bytesToHex(keyBytes(random(15)));
  cert.validity.notBefore = new Date(Date.now() - 86_400_000);
  cert.validity.notAfter = new Date();
  cert.validity.notAfter.setUTCFullYear(cert.validity.notAfter.getUTCFullYear() + 30);
  const identity = [{ name: 'commonName', value: 'RojInk Release' }, { name: 'organizationName', value: 'RojInk' }];
  cert.setSubject(identity);
  cert.setIssuer(identity);
  cert.setExtensions([{ name: 'basicConstraints', cA: false }, { name: 'keyUsage', digitalSignature: true }, { name: 'subjectKeyIdentifier' }]);
  cert.sign(privateKey, forge.md.sha256.create());
  return protectRojInkKey(forge, entropy, privateKey, [cert]);
}

function protectRojInkKey(forge, entropy, privateKey, certs) {
  const alias = 'rojink';
  // 12 URL-safe characters retain all 72 bits from 9 bytes of secure randomness.
  const password = forge.util.encode64(keyBytes(secureBytes(entropy, 9))).replace(/\+/g, '-').replace(/\//g, '_');
  const pfx = forge.pkcs12.toPkcs12Asn1(privateKey, certs, password, {
    friendlyName: alias, algorithm: 'aes256', count: 120_000, saltSize: 16, useMac: true,
  });
  const base64 = forge.util.encode64(forge.asn1.toDer(pfx).getBytes());
  const fingerprint = forge.md.sha256.create().update(forge.asn1.toDer(forge.pki.certificateToAsn1(certs[0])).getBytes())
    .digest().toHex().toUpperCase().match(/../g).join(':');
  return { alias, password, base64, fingerprint };
}

// Re-encrypt a previous RojInk export without replacing the signing key or certificate.
function shortenRojInkPassword(forge, entropy, encoded, oldPassword) {
  if (encoded.length === 0 || encoded.length > 64 * 1024) throw new Error('Unexpected keystore size');
  const store = forge.pkcs12.pkcs12FromAsn1(forge.asn1.fromDer(encoded), oldPassword);
  const keys = [forge.pki.oids.pkcs8ShroudedKeyBag, forge.pki.oids.keyBag].flatMap(oid =>
    store.getBags({ bagType: oid })[oid] || []);
  if (keys.length !== 1 || !keys[0].key) throw new Error('Expected one signing key');
  const privateKey = keys[0].key;
  const ck = forge.pki.oids.certBag;
  const certs = (store.getBags({ bagType: ck })[ck] || []).map(bag => bag.cert).filter(Boolean);
  const index = certs.findIndex(cert => cert.publicKey?.n?.compareTo(privateKey.n) === 0 && cert.publicKey.e?.compareTo(privateKey.e) === 0);
  if (index < 0) throw new Error('Missing signing certificate');
  const [leaf] = certs.splice(index, 1);
  return protectRojInkKey(forge, entropy, privateKey, [leaf, ...certs]);
}

if (typeof module !== 'undefined' && module.exports) module.exports = { makeRojInkKey, shortenRojInkPassword };
