// End-to-end test of the guest web page: starts the installed relay on a free port, plays the owner over plain HTTP
// and the guest in Chromium (Playwright).
//
//   ./gradlew :server:installDist && node server/src/test/e2e/guest.e2e.mjs [--screenshot <png>]
//
// Exits non-zero when any test fails.
import { spawn, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..');
const START = join(ROOT, 'server/build/install/server/bin/server');
const MAX_FILE_MB = 40;
const MIB = 1024 * 1024;
const screenshotArg = process.argv.indexOf('--screenshot');
const SCREENSHOT = screenshotArg > 0 ? process.argv[screenshotArg + 1] : null;

const tmp = mkdtempSync(join(tmpdir(), 'mjolnir-guest-e2e-'));
let relay;
let base;
let owner; // { deviceId, token }

// ---------- helpers ----------

function freePort() {
  return new Promise((ok, fail) => {
    const srv = createServer();
    srv.listen(0, '127.0.0.1', () => { const { port } = srv.address(); srv.close(() => ok(port)); });
    srv.on('error', fail);
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sha = (buf) => createHash('sha256').update(buf).digest('hex');

function assert(cond, msg) { if (!cond) throw new Error(`assertion failed: ${msg}`); }

async function waitFor(fn, what, timeoutMs = 15000) {
  const end = Date.now() + timeoutMs;
  let last;
  while (Date.now() < end) {
    try { last = await fn(); if (last) return last; } catch (e) { last = e; }
    await sleep(100);
  }
  throw new Error(`timed out waiting for ${what} (last: ${last})`);
}

async function http(method, path, { token, json, body, headers = {} } = {}) {
  const h = { ...headers };
  if (token) h.Authorization = `Bearer ${token}`;
  if (json !== undefined) { h['Content-Type'] = 'application/json'; body = JSON.stringify(json); }
  const r = await fetch(base + path, { method, headers: h, body });
  return r;
}

async function ok(r, status) {
  if (r.status !== status) throw new Error(`${r.url}: expected ${status}, got ${r.status} ${await r.text()}`);
  const t = await r.text();
  return t ? JSON.parse(t) : null;
}

/** Owner side: uploads [buf] as [relativePath] in one chunk and returns its file id. */
async function ownerUpload(relativePath, buf) {
  const st = await ok(await http('POST', '/v1/uploads', { token: owner.token, json: { relativePath, mimeType: 'application/octet-stream', sizeBytes: buf.length } }), 201);
  const done = await ok(await http('PUT', `/v1/uploads/${st.uploadId}`, {
    token: owner.token, body: buf, headers: { 'Content-Range': `bytes 0-${buf.length - 1}/${buf.length}` },
  }), 200);
  assert(done.file, 'owner upload complete');
  return done.file.id;
}

async function openRoom() {
  return ok(await http('POST', '/v1/rooms', { token: owner.token }), 201);
}

async function endRoom(roomId) {
  const r = await http('DELETE', `/v1/rooms/${roomId}`, { token: owner.token });
  assert(r.status === 204, `end room ${r.status}`);
}

async function guestOfRoom() {
  const r = await http('GET', '/v1/rooms/current', { token: owner.token });
  if (r.status !== 200) return null;
  return (await r.json()).guest;
}

async function ownerInbox() {
  return ok(await http('GET', '/v1/inbox', { token: owner.token }), 200);
}

async function ownerDownload(fileId) {
  const r = await http('GET', `/v1/files/${fileId}`, { token: owner.token });
  assert(r.status === 200, `owner download ${r.status}`);
  return Buffer.from(await r.arrayBuffer());
}

function diskFile(name, size, seed) {
  const buf = Buffer.alloc(size);
  for (let i = 0; i < size; i++) buf[i] = (i * 31 + seed) & 0xff;
  const dir = mkdtempSync(join(tmp, 'f-'));
  const path = join(dir, name);
  writeFileSync(path, buf);
  return { path, buf };
}

async function newGuestPage(browser, init) {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 1, acceptDownloads: true });
  if (init) await context.addInitScript(init);
  const page = await context.newPage();
  page.on('pageerror', (e) => console.error('  [pageerror]', e.message));
  return { context, page };
}

const connectedText = "You're connected to DESKTOP-4F2K.";

// ---------- tests ----------

async function e2eJoinUploadDownload(browser) {
  const room = await openRoom();
  const { context, page } = await newGuestPage(browser);
  await page.goto(`${base}/g/${room.secret}`);
  await page.getByText(connectedText).waitFor();
  await page.getByText(/^\d\d:\d\d left$/).waitFor();
  await page.getByText(/This room closes in \d+ minutes\. Nothing to install; nothing is kept after it closes\./).waitFor();
  assert(await page.getByRole('button', { name: 'Choose folder' }).isVisible(), 'folder button visible with support');
  const guest = await waitFor(guestOfRoom, 'guest in room');

  // Guest → owner: a 3 MB file.
  const three = diskFile('ceremony.bin', 3 * 1000 * 1000, 7);
  await page.locator('#file-input').setInputFiles(three.path);
  await page.locator('.sent', { hasText: 'ceremony.bin' }).getByText(/delivered/).waitFor();
  const fromGuest = await waitFor(async () => (await ownerInbox()).find((d) => d.files.some((f) => f.name === 'ceremony.bin')), 'owner inbox has the file');
  const ref = fromGuest.files[0];
  assert(fromGuest.from.deviceId === guest.deviceId, 'delivery is from the guest');
  assert(ref.sizeBytes === three.buf.length, 'size matches');
  assert(sha(await ownerDownload(ref.id)) === sha(three.buf), 'owner received the same bytes');

  // A pasted link goes to the owner as a tab.
  await page.locator('#drop').focus();
  await page.evaluate(() => {
    const dt = new DataTransfer();
    dt.setData('text/plain', 'https://maps.example.com/venue');
    document.getElementById('drop').dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true }));
  });
  await waitFor(async () => (await ownerInbox()).some((d) => d.tabs.some((t) => t.url === 'https://maps.example.com/venue')), 'owner inbox has the link');

  // Too big is refused with the exact copy.
  const big = diskFile('huge.bin', (MAX_FILE_MB + 1) * MIB, 1);
  await page.locator('#file-input').setInputFiles(big.path);
  await page.getByText(`Too big. Files can be up to ${MAX_FILE_MB} MB.`).waitFor();

  // Owner → guest: one file, one folder, one link.
  const pdf = Buffer.from('%PDF-1.4 seating plan');
  const pdfId = await ownerUpload('Seating plan.pdf', pdf);
  await ok(await http('POST', '/v1/send', { token: owner.token, json: { to: guest.deviceId, fileIds: [pdfId] } }), 202);
  const a = Buffer.from('photo a'); const c = Buffer.from('photo c in sub folder');
  const folderIds = [await ownerUpload('Rehearsal photos/a.jpg', a), await ownerUpload('Rehearsal photos/b/c.jpg', c)];
  await ok(await http('POST', '/v1/send', { token: owner.token, json: { to: guest.deviceId, fileIds: folderIds } }), 202);
  await ok(await http('POST', '/v1/send', { token: owner.token, json: { to: guest.deviceId, tabs: [{ url: 'https://maps.example.com/directions', title: 'Venue directions' }] } }), 202);

  const pdfRow = page.locator('.item', { hasText: 'Seating plan.pdf' });
  const folderRow = page.locator('.item', { hasText: 'Rehearsal photos' });
  const linkRow = page.locator('.item', { hasText: 'Venue directions' });
  await folderRow.getByText('Folder · 2 files').waitFor({ timeout: 10000 });
  await linkRow.getByRole('link', { name: 'Open' }).waitFor();
  assert(await linkRow.getByRole('link', { name: 'Open' }).getAttribute('href') === 'https://maps.example.com/directions', 'link href');

  const [dl1] = await Promise.all([page.waitForEvent('download'), pdfRow.getByRole('button', { name: 'Download' }).click()]);
  assert(dl1.suggestedFilename() === 'Seating plan.pdf', `file name ${dl1.suggestedFilename()}`);
  assert(sha(readFileSync(await dl1.path())) === sha(pdf), 'downloaded file bytes');
  await pdfRow.getByText('Downloaded').waitFor();

  const [dl2] = await Promise.all([page.waitForEvent('download'), folderRow.getByRole('button', { name: 'Download' }).click()]);
  assert(dl2.suggestedFilename() === 'Rehearsal photos.zip', `zip name ${dl2.suggestedFilename()}`);
  const zipPath = join(tmp, 'folder.zip');
  await dl2.saveAs(zipPath);
  execFileSync('unzip', ['-tq', zipPath]); // throws when the zip does not open
  const listing = execFileSync('unzip', ['-Z1', zipPath]).toString().trim().split('\n').sort();
  assert(JSON.stringify(listing) === JSON.stringify(['Rehearsal photos/a.jpg', 'Rehearsal photos/b/c.jpg']), `zip entries ${listing}`);
  assert(execFileSync('unzip', ['-p', zipPath, 'Rehearsal photos/b/c.jpg']).equals(c), 'zip entry bytes');
  await folderRow.getByText('Downloaded').waitFor();

  if (SCREENSHOT) await page.screenshot({ path: SCREENSHOT });
  await context.close();
  await endRoom(room.roomId);
}

async function e2eRejoinResumes(browser) {
  const room = await openRoom();
  const { context, page } = await newGuestPage(browser);
  const joins = [];
  page.on('request', (r) => { if (r.url().endsWith('/v1/guest/join')) joins.push(r.url()); });
  await page.goto(`${base}/g/${room.secret}`);
  await page.getByText(connectedText).waitFor();
  const guest = await waitFor(guestOfRoom, 'guest in room');

  // A 20 MB file (3 chunks): let the first chunk through, then the connection "drops" and the page is reloaded.
  const file = diskFile('video.bin', 20 * MIB, 3);
  let puts = 0;
  await page.route('**/v1/uploads/*', (route) => {
    if (route.request().method() === 'PUT' && ++puts > 1) return route.abort();
    return route.continue();
  });
  await page.locator('#file-input').setInputFiles(file.path);
  await waitFor(() => puts > 1, 'second chunk attempted');
  await page.unroute('**/v1/uploads/*');

  const ranges = [];
  page.on('request', (r) => { if (r.method() === 'PUT') ranges.push(r.headers()['content-range']); });
  await page.reload();
  await page.getByText(connectedText).waitFor();
  assert(joins.length === 1, `rejoined without a second join (${joins.length} joins)`);
  assert(!(await page.getByText('This room is already in use.').isVisible()), 'not in use for the same browser');

  await page.locator('#file-input').setInputFiles(file.path);
  await page.locator('.sent', { hasText: 'video.bin' }).getByText(/delivered/).waitFor({ timeout: 20000 });
  assert(ranges[0] === `bytes ${8 * MIB}-${16 * MIB - 1}/${20 * MIB}`, `resumed at the relay's offset (first range ${ranges[0]})`);
  const d = await waitFor(async () => (await ownerInbox()).find((x) => x.files.some((f) => f.name === 'video.bin')), 'owner has the resumed file');
  assert(d.from.deviceId === guest.deviceId, 'same guest after the reload');
  assert(sha(await ownerDownload(d.files[0].id)) === sha(file.buf), 'resumed file bytes');
  await context.close();
  await endRoom(room.roomId);
}

async function e2eSecondBrowserInUse(browser) {
  const room = await openRoom();
  const first = await newGuestPage(browser);
  await first.page.goto(`${base}/g/${room.secret}`);
  await first.page.getByText(connectedText).waitFor();
  const second = await newGuestPage(browser);
  await second.page.goto(`${base}/g/${room.secret}`);
  await second.page.getByText('This room is already in use.').waitFor();
  assert(!(await second.page.getByRole('button', { name: 'Choose files' }).isVisible()), 'no drop area when in use');
  await first.context.close();
  await second.context.close();
  await endRoom(room.roomId);
}

async function e2eClosedAfterEnd(browser) {
  const room = await openRoom();
  const { context, page } = await newGuestPage(browser);
  await page.goto(`${base}/g/${room.secret}`);
  await page.getByText(connectedText).waitFor();
  await endRoom(room.roomId);
  await page.getByText('This room has closed.').waitFor({ timeout: 8000 });
  assert(!(await page.getByRole('button', { name: 'Choose files' }).isVisible()), 'no drop area once closed');
  const stored = await page.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith('mjolnir.guest.')));
  assert(stored.length === 0, `room storage cleared (${stored})`);
  await context.close();

  const late = await newGuestPage(browser);
  await late.page.goto(`${base}/g/${room.secret}`);
  await late.page.getByText('This room has closed.').waitFor();
  await late.context.close();
}

async function folderButtonHiddenWithoutSupport(browser) {
  const room = await openRoom();
  const { context, page } = await newGuestPage(browser, () => { delete HTMLInputElement.prototype.webkitdirectory; });
  await page.goto(`${base}/g/${room.secret}`);
  await page.getByText(connectedText).waitFor();
  assert(await page.getByRole('button', { name: 'Choose files' }).isVisible(), 'files button visible');
  assert(!(await page.getByRole('button', { name: 'Choose folder' }).isVisible()), 'folder button hidden');
  await context.close();
  await endRoom(room.roomId);
}

// ---------- runner ----------

async function startRelay() {
  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  relay = spawn(START, [], {
    env: {
      ...process.env,
      PORT: String(port),
      MJOLNIR_DB: join(tmp, 'relay.db'),
      MJOLNIR_FILES: join(tmp, 'files'),
      MJOLNIR_MAX_FILE_MB: String(MAX_FILE_MB),
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let log = '';
  relay.stdout.on('data', (d) => { log += d; });
  relay.stderr.on('data', (d) => { log += d; });
  relay.on('exit', (code) => { if (code) console.error(`relay exited ${code}\n${log}`); });
  await waitFor(async () => (await fetch(`${base}/health`)).ok, 'relay health', 60000);
  owner = await ok(await http('POST', '/v1/devices', { json: { name: 'DESKTOP-4F2K', kind: 'PC' } }), 201);
}

const tests = [e2eJoinUploadDownload, e2eRejoinResumes, e2eSecondBrowserInUse, e2eClosedAfterEnd, folderButtonHiddenWithoutSupport];
let failed = 0;
let browser;
try {
  await startRelay();
  browser = await chromium.launch();
  for (const t of tests) {
    const started = Date.now();
    try {
      await t(browser);
      console.log(`PASS ${t.name} (${Date.now() - started} ms)`);
    } catch (e) {
      failed++;
      console.log(`FAIL ${t.name}: ${e.message}`);
      // A failed test may leave its room open; the owner can only have one.
      const cur = await http('GET', '/v1/rooms/current', { token: owner.token });
      if (cur.status === 200) await endRoom((await cur.json()).roomId);
    }
  }
} catch (e) {
  failed++;
  console.log(`FAIL setup: ${e.message}`);
} finally {
  await browser?.close();
  relay?.kill();
  rmSync(tmp, { recursive: true, force: true });
}
console.log(failed ? `${failed} failed` : `all ${tests.length} passed`);
process.exit(failed ? 1 : 0);
