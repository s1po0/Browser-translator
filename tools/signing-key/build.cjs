'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '../..');
const output = process.argv[2] || path.join(root, 'RojInk-Signing-Key.html');
const read = name => fs.readFileSync(path.join(__dirname, name), 'utf8').replace(/\r\n?/g, '\n');
const hash = content => `'sha256-${crypto.createHash('sha256').update(content).digest('base64')}'`;
const htmlScript = content => content.replace(/<\/script/gi, '<\\/script');
const forge = htmlScript(read('node_modules/node-forge/dist/forge.min.js').replace(/\n?\/\/# sourceMappingURL=.*\s*$/, ''));
const app = htmlScript(read('key.cjs') + '\n' + read('ui.js'));
const styles = read('style.css');
const license = read('node_modules/node-forge/LICENSE').split('GNU GENERAL PUBLIC LICENSE')[0].trim().replace(/--/g, '—');
const slots = {
  __SCRIPT_HASHES__: `${hash(forge)} ${hash(app)}`, __STYLE_HASH__: hash(styles), __STYLES__: styles,
  __LICENSE__: `node-forge 1.4.3, used under the BSD 3-Clause license.\n${license}`, __FORGE__: forge, __APP__: app,
};
const page = read('page.html').replace(/__[A-Z_]+__/g, token => slots[token]);
fs.writeFileSync(output, page);
console.log(`Created ${output} (no key material)`);
