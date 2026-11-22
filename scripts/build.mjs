import { cpSync, mkdirSync, rmSync } from 'node:fs';

const out = new URL('../www/', import.meta.url);
rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });
for (const file of ['index.html', 'styles.css', 'app.js']) {
  cpSync(new URL(`../${file}`, import.meta.url), new URL(`../www/${file}`, import.meta.url));
}
cpSync(new URL('../public/', import.meta.url), new URL('../www/', import.meta.url), { recursive: true });
console.log('Built static web app into www/');
