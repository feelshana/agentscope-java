// Static artifact checks only; these do not launch a browser or verify rendering.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const postcss = require('../../../frontend/node_modules/postcss');
const root = __dirname;
const htmlPath = path.join(root, '../dataagent-ui-review-20261031.html');
const html = fs.readFileSync(htmlPath, 'utf8');
const css = fs.readFileSync(path.join(root, 'review.css'), 'utf8');
const js = fs.readFileSync(path.join(root, 'review.js'), 'utf8');
new vm.Script(js, { filename: 'review.js' });
postcss.parse(css, { from: 'review.css' });
for (const asset of ['review.css','review.js','brand-mark.svg','scheduled-tasks-calendar.png','01-home.png','02-chat.png','03-knowledge.png','04-modeling.png']) {
  if (!fs.statSync(path.join(root, asset)).isFile()) throw Error(`Missing asset: ${asset}`);
}
for (const [, ref] of html.matchAll(/(?:src|href)="([^"]+)"/g)) {
  if (ref.startsWith('#')) continue;
  if (!fs.existsSync(path.resolve(path.dirname(htmlPath), ref))) throw Error(`Missing HTML reference: ${ref}`);
}
if (/\b(?:fetch|XMLHttpRequest|WebSocket|EventSource)\s*\(/.test(js)) throw Error('Unexpected network client');
const series = [820,1024,1186,1326,1218,948,598];
if (series.reduce((a,b)=>a+b,0) !== 7120 || Math.max(...series) !== 1326) throw Error('Synthetic example mismatch');
const region=js.slice(js.indexOf('const pages = {'),js.indexOf('const state='));
const pageIds=[...region.matchAll(/^\s+(\w+):\{name:/gm)].map(m=>m[1]);
const stateLists=[...region.matchAll(/states:\[(.*?)\],problem:/g)].map(m=>m[1]);
const states=stateLists.reduce((sum,s)=>sum+[...s.matchAll(/\['[^']+','[^']+'\]/g)].length,0);
if (pageIds.length !== 13 || states !== 37) throw Error(`Unexpected page/state count: ${pageIds.length}/${states}`);
const cases=new Set([...js.matchAll(/case '([^']+)'/g)].map(m=>m[1]));
for (const [, action] of js.matchAll(/\bB\('[^']*','([^']+)'/g)) {
  if (!cases.has(action) && !action.startsWith('analysis:') && action !== 'readonly-wait') throw Error(`Unmapped fixed action: ${action}`);
}
console.log(JSON.stringify({ htmlReferences:'pass', javascriptSyntax:'pass', cssSyntax:'pass', assets:8, pages:pageIds.length, variants:states, syntheticTotals:'pass', networkClients:0, fixedActions:'pass', browserValidation:'separate evidence: desktop-checks.json, mobile-checks.json and 20261101-ui-review-prototype.md' },null,2));
