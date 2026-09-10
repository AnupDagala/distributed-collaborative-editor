import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../frontend/package.json', import.meta.url));
const WebSocket = require('ws');
const baseA = process.env.SMOKE_BASE_A ?? 'http://localhost:8080';
const baseB = process.env.SMOKE_BASE_B ?? baseA;
const origin = process.env.SMOKE_ORIGIN ?? 'http://localhost:5173';
const sockets = [];
async function api(base, path, token, body, method = body === undefined ? 'GET' : 'POST') {
  const response = await fetch(`${base}/api/v1${path}`, { method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw Error(`HTTP ${response.status} for ${method} ${path}: ${(await response.text()).slice(0, 160)}`);
  return response.status === 204 ? undefined : response.json();
}
async function connect(base, account, doc, existingActor = null, after = 0) {
  const ticket = await api(base, `/documents/${doc}/connections`, account.accessToken, { actorId: existingActor });
  const ws = new WebSocket(base.replace(/^http/, 'ws') + ticket.socketPath, ['collab.v1', `ticket.${ticket.ticket}`], { origin, handshakeTimeout: 5000 }); sockets.push(ws);
  const messages = [], pending = [];
  const client = { ws, actor: ticket.actorId, watermark: after, snapshotRecoveries: 0,
    send(type, data = {}) { ws.send(JSON.stringify({ protocolVersion: 1, type, documentId: doc, actorId: ticket.actorId, ...data })); },
    wait(predicate, label) { const found = messages.findIndex(predicate); if (found >= 0) return Promise.resolve(messages.splice(found, 1)[0]); return new Promise((resolve, reject) => { const timer = setTimeout(() => { const index = pending.findIndex(p => p.resolve === resolve); if (index >= 0) pending.splice(index, 1); reject(Error(`Timed out: ${label}`)); }, 10000); pending.push({ predicate, resolve, reject, timer }); }); },
  };
  ws.on('message', async raw => {
    try {
      const message = JSON.parse(raw.toString());
      if (message.type === 'SYNC_RESPONSE') client.watermark = message.data.throughSequence;
      if (message.type === 'SYNC_REQUIRED') {
        const snapshot = await api(base, `/documents/${doc}/snapshot`, account.accessToken); client.watermark = snapshot.watermark; client.snapshotRecoveries++; client.send('SYNC_REQUEST', { afterSequence: snapshot.watermark });
      }
      const waiter = pending.find(p => p.predicate(message)); if (waiter) { clearTimeout(waiter.timer); pending.splice(pending.indexOf(waiter), 1); waiter.resolve(message); } else { messages.push(message); if (messages.length > 300) messages.shift(); }
    } catch (error) { for (const waiter of pending.splice(0)) { clearTimeout(waiter.timer); waiter.reject(error); } }
  });
  await new Promise((resolve, reject) => { ws.once('open', resolve); ws.once('error', reject); });
  client.send('JOIN_DOCUMENT', { afterSequence: after }); await client.wait(m => m.type === 'SYNC_RESPONSE', 'join'); return client;
}
function insert(actor, seq, clock, predecessor, value) { return { type: 'INSERT', operationId: { actor, counter: seq }, lamport: clock, elementId: { actor, counter: clock }, predecessorId: predecessor, value, targetId: null }; }
async function append(client, operations) { client.send('EDIT', { operations }); const message = await client.wait(m => m.type === 'ACK' || m.type === 'ERROR', 'edit acknowledgment'); assert.equal(message.type, 'ACK', message.data.code); return message.data.acknowledgments; }
async function caughtUp(client, watermark) { if (client.watermark < watermark) await client.wait(m => m.type === 'SYNC_RESPONSE' && m.data.throughSequence >= watermark, 'peer catch-up'); }
function text(state) {
  const children = new Map(), key = id => id ? `${id.actor}:${id.counter}` : '-';
  for (const node of state.nodes) { const list = children.get(key(node.predecessor)) ?? []; list.push(node); children.set(key(node.predecessor), list); }
  for (const list of children.values()) list.sort((a, b) => b.id.counter - a.id.counter || (a.id.actor < b.id.actor ? 1 : -1));
  const stack = [...(children.get('-') ?? [])].reverse(), out = [];
  while (stack.length) { const node = stack.pop(); if (!node.deleted) out.push(node.value); stack.push(...[...(children.get(key(node.id)) ?? [])].reverse()); } return out.join('');
}
try {
  const accountA = await api(baseA, '/auth/register', null, { email: `smoke-a-${randomUUID()}@example.test`, password: `Test-only-${randomUUID()}` });
  const accountB = await api(baseB, '/auth/register', null, { email: `smoke-b-${randomUUID()}@example.test`, password: `Test-only-${randomUUID()}` });
  const doc = await api(baseA, '/documents', accountA.accessToken, { title: 'Disposable collaboration smoke test' });
  await api(baseA, `/documents/${doc.id}/members`, accountA.accessToken, { email: accountB.email, role: 'EDITOR' });
  const a = await connect(baseA, accountA, doc.id), b = await connect(baseB, accountB, doc.id);
  const root = insert(a.actor, 1, 1, null, 'H'); await append(a, [root]); await caughtUp(b, 1);
  const x = insert(a.actor, 2, 2, root.elementId, 'X'), y = insert(b.actor, 1, 2, root.elementId, '🌍');
  await Promise.all([append(a, [x]), append(b, [y])]); await caughtUp(a, 3); await caughtUp(b, 3);
  const baseline = await api(baseA, `/documents/${doc.id}/snapshot`, accountA.accessToken); assert.match(text(baseline.state), /^H(?:X🌍|🌍X)$/);
  // A duplicate delivery must preserve both content and the watermark.
  assert.equal((await append(a, [x]))[0].duplicate, true);
  a.ws.terminate(); const reconnected = await connect(baseA, accountA, doc.id, a.actor, 0); await caughtUp(reconnected, 3);
  assert.equal((await append(reconnected, [x]))[0].duplicate, true);
  // A bounded wall-clock throughput sample, including database persistence and snapshots.
  const count = 128, start = performance.now(); let predecessor = x.elementId;
  for (let offset = 0; offset < count; offset += 16) {
    const operations = [];
    for (let i = offset; i < offset + 16; i++) { const op = insert(a.actor, i + 3, i + 3, predecessor, 'x'); operations.push(op); predecessor = op.elementId; }
    await append(reconnected, operations);
  }
  const elapsedMs = performance.now() - start; await caughtUp(b, 131);
  const left = await api(baseA, `/documents/${doc.id}/snapshot`, accountA.accessToken), right = await api(baseB, `/documents/${doc.id}/snapshot`, accountB.accessToken);
  assert.deepEqual(left, right); assert.equal(left.watermark, 131); assert.equal(Array.from(text(left.state)).length, 131);
  b.ws.terminate(); const recovered = await connect(baseB, accountB, doc.id, b.actor, 1); await caughtUp(recovered, 131); assert.ok(recovered.snapshotRecoveries >= 1);
  await api(baseA, `/documents/${doc.id}/members`, accountA.accessToken, { email: accountB.email, role: 'VIEWER' });
  recovered.send('EDIT', { operations: [insert(b.actor, 2, 132, predecessor, 'v')] });
  const denial = await recovered.wait(m => m.type === 'ERROR', 'viewer rejection'); assert.equal(denial.data.code, 'ACCESS_DENIED');
  await api(baseA, `/documents/${doc.id}`, accountA.accessToken, undefined, 'DELETE');
  console.log(JSON.stringify({ result: 'PASS', instances: baseA === baseB ? 1 : 2, checks: ['concurrent Unicode insert', 'cross-instance delivery', 'duplicate acknowledgment', 'reconnect replay', 'snapshot fallback', 'equal persisted states', 'viewer denied'], sample: { operations: count, elapsedMs: +elapsedMs.toFixed(2), operationsPerSecond: +(count * 1000 / elapsedMs).toFixed(2), concurrency: 1, batchSize: 16, includes: 'persistence and snapshot creation; excludes login and setup' } }, null, 2));
} finally { for (const ws of sockets) ws.terminate(); }
