import http from 'node:http';
import { DatabaseSync } from 'node:sqlite';
import { randomBytes, randomInt, createHash } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const hash = s => createHash('sha256').update(s).digest('hex');
const token = () => randomBytes(32).toString('base64url');
const fail = (status, message) => { throw Object.assign(new Error(message), { status }); };
const idOK = s => typeof s === 'string' && /^[a-zA-Z0-9-]{1,64}$/.test(s);
const keyOK = s => typeof s === 'string' && /^B[A-Za-z0-9+/]{86}=$/.test(s) && Buffer.from(s, 'base64').length === 65;
const kinds = new Set(['notification', 'remove', 'reply', 'reply_result', 'health', 'health_result', 'ping']);

export function createRelay({ path = ':memory:', now = Date.now } = {}) {
  if (path !== ':memory:') mkdirSync(dirname(resolve(path)), { recursive: true });
  const db = new DatabaseSync(path);
  db.exec(`PRAGMA journal_mode=WAL;
    CREATE TABLE IF NOT EXISTS pairs(id TEXT PRIMARY KEY, code TEXT UNIQUE, expires INTEGER, revoked INTEGER DEFAULT 0);
    CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, pair_id TEXT, role TEXT, public_key TEXT, token_hash TEXT UNIQUE, approved INTEGER DEFAULT 0, UNIQUE(pair_id,role));
    CREATE TABLE IF NOT EXISTS events(id TEXT, recipient TEXT, sender TEXT, pair_id TEXT, created INTEGER, expires INTEGER, body TEXT, PRIMARY KEY(id,recipient));
    CREATE INDEX IF NOT EXISTS delivery ON events(recipient,created);`);
  const rates = new Map();
  function rate(ip, action, max) {
    const key = `${ip}:${action}`, stamp = Math.floor(now() / 60000);
    const r = rates.get(key);
    const next = r?.stamp === stamp ? { stamp, count: r.count + 1 } : { stamp, count: 1 };
    rates.set(key, next);
    if (rates.size > 10000) for (const [k, v] of rates) if (v.stamp < stamp) rates.delete(k);
    if (next.count > max) fail(429, 'Too many requests; retry in one minute');
  }
  function auth(req) {
    const bearer = req.headers.authorization?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1];
    const d = bearer && db.prepare('SELECT * FROM devices WHERE token_hash=?').get(hash(bearer));
    if (!d) fail(401, 'Invalid device credentials');
    const p = db.prepare('SELECT * FROM pairs WHERE id=?').get(d.pair_id);
    if (!p || p.revoked) fail(401, 'Device link revoked');
    const peer = db.prepare('SELECT * FROM devices WHERE pair_id=? AND id<>?').get(d.pair_id, d.id);
    if (!(d.approved && peer?.approved) && p.expires < now()) fail(410, 'Pairing expired');
    return { d, p, peer, active: Boolean(d.approved && peer?.approved) };
  }
  async function body(req) {
    let bytes = 0; const chunks = [];
    for await (const c of req) { bytes += c.length; if (bytes > 512000) fail(413, 'Payload too large'); chunks.push(c); }
    try { return JSON.parse(Buffer.concat(chunks).toString()); } catch { fail(400, 'Expected JSON'); }
  }
  const server = http.createServer(async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    try {
      const url = new URL(req.url, 'http://relay.local');
      const ip = req.socket.remoteAddress; // Never trust client-supplied forwarded IP headers.
      rate(ip, 'all', 300);
      db.prepare('DELETE FROM events WHERE expires<?').run(now());
      let result;
      if (req.method === 'GET' && url.pathname === '/healthz') result = { ok: true, version: 1 };
      else if (req.method === 'POST' && ['/v1/pair/create', '/v1/pair/join'].includes(url.pathname)) {
        rate(ip, 'pair', 10);
        const b = await body(req);
        if (!idOK(b.deviceId) || !keyOK(b.publicKey)) fail(400, 'Invalid identity or P-256 public key');
        if (db.prepare('SELECT 1 FROM devices WHERE id=?').get(b.deviceId)) fail(409, 'Generate a new device identity before pairing again');
        const secret = token(); let pair;
        if (url.pathname.endsWith('/create')) {
          const pairId = randomBytes(16).toString('hex');
          const code = String(randomInt(0, 1000000)).padStart(6, '0');
          db.prepare('DELETE FROM pairs WHERE expires<? AND id NOT IN (SELECT pair_id FROM devices WHERE approved=1)').run(now());
          if (db.prepare('SELECT 1 FROM pairs WHERE code=?').get(code)) fail(503, 'Please retry creating a code');
          db.prepare('INSERT INTO pairs(id,code,expires) VALUES(?,?,?)').run(pairId, code, now() + 600000);
          pair = db.prepare('SELECT * FROM pairs WHERE id=?').get(pairId);
          db.prepare('INSERT INTO devices(id,pair_id,role,public_key,token_hash) VALUES(?,?,?,?,?)').run(b.deviceId, pairId, 'android', b.publicKey, hash(secret));
        } else {
          if (typeof b.code !== 'string' || !/^\d{6}$/.test(b.code)) fail(400, 'Expected six digit pairing code');
          pair = db.prepare('SELECT * FROM pairs WHERE code=? AND expires>? AND revoked=0').get(b.code, now());
          if (!pair) fail(404, 'Pairing code not found or expired');
          if (db.prepare("SELECT 1 FROM devices WHERE pair_id=? AND role='watch'").get(pair.id)) fail(409, 'Code already used');
          db.prepare('INSERT INTO devices(id,pair_id,role,public_key,token_hash) VALUES(?,?,?,?,?)').run(b.deviceId, pair.id, 'watch', b.publicKey, hash(secret));
        }
        result = { token: secret, code: pair.code, expires: pair.expires };
      } else {
        const a = auth(req);
        if (req.method === 'GET' && url.pathname === '/v1/pair') result = {
          active: a.active, approved: Boolean(a.d.approved), peerApproved: Boolean(a.peer?.approved),
          peerId: a.peer?.id ?? null, peerPublicKey: a.peer?.public_key ?? null, expires: a.p.expires
        };
        else if (req.method === 'POST' && url.pathname === '/v1/pair/approve') {
          if (!a.peer) fail(409, 'Other device has not joined');
          db.prepare('UPDATE devices SET approved=1 WHERE id=?').run(a.d.id);
          result = { ok: true };
        } else if (req.method === 'POST' && url.pathname === '/v1/revoke') {
          db.prepare('UPDATE pairs SET revoked=1 WHERE id=?').run(a.p.id);
          db.prepare('DELETE FROM events WHERE pair_id=?').run(a.p.id);
          result = { ok: true };
        } else if (!a.active) fail(403, 'Confirm matching security codes on both devices');
        else if (req.method === 'POST' && url.pathname === '/v1/events') {
          const e = await body(req);
          if (e.v !== 1 || !idOK(e.id) || e.sender !== a.d.id || e.recipient !== a.peer.id || !kinds.has(e.type) ||
              !Number.isSafeInteger(e.created) || e.created > now() + 300000 || now() - e.created > (e.type === 'health' ? 7 : 1) * 86400000 ||
              !Number.isSafeInteger(e.expires) || e.expires <= now() || e.expires > now() + 7 * 86400000 ||
              typeof e.nonce !== 'string' || Buffer.from(e.nonce, 'base64').length !== 12 ||
              typeof e.ciphertext !== 'string' || e.ciphertext.length > 460000 || Buffer.from(e.ciphertext, 'base64').length < 16)
            fail(400, 'Invalid encrypted envelope');
          const old = db.prepare('SELECT body FROM events WHERE id=? AND recipient=?').get(e.id, e.recipient);
          if (old && old.body !== JSON.stringify(e)) fail(409, 'Message ID reused with different bytes');
          const count = db.prepare('SELECT COUNT(*) AS n FROM events WHERE pair_id=?').get(a.p.id).n;
          if (!old && count >= 1000) fail(429, 'Queue full');
          db.prepare('INSERT OR IGNORE INTO events VALUES(?,?,?,?,?,?,?)').run(e.id,e.recipient,e.sender,a.p.id,e.created,e.expires,JSON.stringify(e));
          result = { accepted: e.id };
        } else if (req.method === 'GET' && url.pathname === '/v1/events') {
          result = { events: db.prepare('SELECT body FROM events WHERE recipient=? ORDER BY created,id LIMIT 50').all(a.d.id).map(r => JSON.parse(r.body)) };
        } else if (req.method === 'POST' && url.pathname === '/v1/events/ack') {
          const b = await body(req);
          if (!Array.isArray(b.ids) || b.ids.length > 50 || !b.ids.every(idOK)) fail(400, 'Invalid acknowledgements');
          const del = db.prepare('DELETE FROM events WHERE id=? AND recipient=?');
          for (const id of b.ids) del.run(id, a.d.id);
          result = { ok: true };
        } else fail(404, 'Not found');
      }
      res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(result));
    } catch (e) {
      const status = e.status ?? 500;
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: status === 500 ? 'Internal relay error' : e.message }));
    }
  });
  server.requestTimeout = 15000;
  server.headersTimeout = 10000;
  server.on('close', () => db.close());
  return server;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const server = createRelay({ path: process.env.DB_PATH ?? 'data/watchbridge.sqlite' });
  const port = Number(process.env.PORT ?? 8787), host = process.env.HOST ?? '127.0.0.1';
  server.listen(port, host, () => console.log(`WatchBridge relay listening on ${host}:${port}. Use HTTPS reverse proxy for devices.`));
}
