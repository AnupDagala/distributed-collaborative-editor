// Run Maven dependency:tree -DoutputType=json -DoutputFile=target/dependency-tree.json first.
import { readFileSync, writeFileSync } from 'node:fs';
const tree = JSON.parse(readFileSync(new URL('../target/dependency-tree.json', import.meta.url), 'utf8'));
const artifacts = new Map();
function visit(node) { if (['test', 'provided', 'system'].includes(node.scope)) return; if (node !== tree) artifacts.set(`${node.groupId}:${node.artifactId}:${node.version}`, { package: { ecosystem: 'Maven', name: `${node.groupId}:${node.artifactId}` }, version: node.version }); (node.children ?? []).forEach(visit); }
visit(tree);
const queries = [...artifacts.values()], findings = [];
for (let offset = 0; offset < queries.length; offset += 100) {
  const batch = queries.slice(offset, offset + 100);
  const response = await fetch('https://api.osv.dev/v1/querybatch', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ queries: batch }), signal: AbortSignal.timeout(30000) });
  if (!response.ok) throw Error(`OSV scan unavailable: HTTP ${response.status}`);
  const data = await response.json();
  if (!Array.isArray(data.results) || data.results.length !== batch.length) throw Error('Invalid OSV result');
  data.results.forEach((result, index) => { if (result.vulns?.length) findings.push({ artifact: batch[index], vulnerabilities: result.vulns.map(v => v.id) }); });
}
const report = { checkedAt: new Date().toISOString(), source: 'OSV querybatch', artifacts: queries.length, findings };
writeFileSync(new URL('../target/runtime-audit.json', import.meta.url), JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
if (findings.length) process.exitCode = 1;
