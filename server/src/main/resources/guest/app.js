// Mjolnir guest page: plain JS, no framework, no build step. Talks only to the relay that served it.
// The guest redeems the invite once (joinRoom), keeps its token in localStorage under mjolnir.guest.{roomId},
// uploads in resumable 8 MiB chunks (uploadFile), sends to the room's owner (sendFiles, sendLinks), polls its inbox
// every 3 s (listen; a browser can't put the bearer token on a WebSocket) and downloads with single-use tickets
// (download).
'use strict';

const MIB = 1048576;
const CHUNK_BYTES = 8 * MIB; // Limits.UPLOAD_CHUNK_BYTES
const MAX_FILES = 1000; // Limits.MAX_FILES_PER_SEND
const MAX_TABS = 100; // Limits.MAX_TABS_PER_SEND
const MAX_CONFLICTS = 5; // as the Kotlin client: give up after this many 409s in a row
const POLL_MS = 3000;
const PREFIX = 'mjolnir.guest.';
const TRANSIENT = new Set([429, 500, 502, 503, 504]);

const COPY = {
  inUse: 'This room is already in use.',
  closed: 'This room has closed.',
  tooBig: (mb) => `Too big. Files can be up to ${mb} MB.`,
  tooMany: 'Too many files. Send up to 1,000 at a time.',
  relayFull: 'The relay is full right now. Try again later.',
  keepOpen: 'Keep this page open. If it drops, it picks up where it left off.',
  footer: (n) => `This room closes in ${n} minutes. Nothing to install; nothing is kept after it closes.`,
  left: (mmss) => `${mmss} left`,
  connecting: 'Connecting…',
  sendFailed: "Couldn't send. Try again.",
  downloadFailed: "Couldn't download. Try again.",
};

/** The open room: { roomId, deviceId, token, owner, maxFileMb, deadline (local ms), link, downloaded: [keys] }. */
let session = null;
let closed = false;
let pollTimer = 0;
let tickTimer = 0;
let uploadQueue = Promise.resolve();
const forYouRows = new Map(); // item key → row element

class RoomClosed extends Error {}
class HttpError extends Error {
  constructor(status, code) { super(`HTTP ${status} ${code}`); this.status = status; this.code = code; }
}

const $ = (id) => document.getElementById(id);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------- storage (every access guarded: private modes can throw) ----------

function load(key) {
  try { const v = localStorage.getItem(key); return v ? JSON.parse(v) : null; } catch (e) { return null; }
}
function store(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* the page still works, it just can't resume */ }
}
function forget(key) {
  try { localStorage.removeItem(key); } catch (e) { /* ignore */ }
}
function storedKeys() {
  try { return Object.keys(localStorage).filter((k) => k.startsWith(PREFIX)); } catch (e) { return []; }
}
function saveSession() { if (session) store(PREFIX + session.roomId, session); }

/** Drops everything kept for [roomId]: its token and its upload sessions. */
function forgetRoom(roomId) {
  storedKeys().filter((k) => k === PREFIX + roomId || k.startsWith(`${PREFIX}${roomId}.`)).forEach(forget);
}

/** A non-reversible key for the invite secret, so the secret itself is not stored. */
async function linkKey(secret) {
  if (window.crypto && crypto.subtle) {
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(secret));
    return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
  }
  return secret; // no SubtleCrypto outside a secure context; the token stored beside it is the stronger credential anyway
}

/** The stored session for [link], after dropping sessions whose hour is over. */
function findSession(link) {
  let found = null;
  for (const key of storedKeys()) {
    const s = load(key);
    if (!s || !s.token || key !== PREFIX + s.roomId) continue;
    if (s.deadline <= Date.now()) forgetRoom(s.roomId);
    else if (s.link === link) found = s;
  }
  return found;
}

// ---------- relay calls ----------

/** fetch with the guest's bearer token; a 401 means the room is gone. */
async function request(method, path, { json, body, headers = {} } = {}) {
  if (closed) throw new RoomClosed();
  const h = { ...headers, Authorization: `Bearer ${session.token}` };
  if (json !== undefined) { h['Content-Type'] = 'application/json'; body = JSON.stringify(json); }
  const r = await fetch(path, { method, headers: h, body, cache: 'no-store' });
  if (r.status === 401) { closeRoom(); throw new RoomClosed(); }
  return r;
}

async function httpError(r) {
  let code = '';
  try { code = (await r.json()).error || ''; } catch (e) { /* not JSON */ }
  return new HttpError(r.status, code);
}

/** A JSON call: the parsed body, or HttpError. */
async function api(method, path, json) {
  const r = await request(method, path, { json });
  if (!r.ok) throw await httpError(r);
  return r.status === 204 ? null : r.json();
}

/** Runs [fn] until it neither fails on the network nor answers a transient status, backing off up to 15 s. */
async function retrying(fn) {
  for (let wait = 1000; ; wait = Math.min(wait * 2, 15000)) {
    try {
      const r = await fn();
      if (!(r instanceof Response) || !TRANSIENT.has(r.status)) return r;
    } catch (e) {
      const transient = e instanceof TypeError || (e instanceof HttpError && TRANSIENT.has(e.status));
      if (!transient) throw e;
    }
    if (closed) throw new RoomClosed();
    await sleep(wait);
  }
}

// ---------- joining ----------

async function joinRoom() {
  const secret = decodeURIComponent(location.pathname.split('/').filter(Boolean).pop() || '');
  const link = await linkKey(secret);
  const stored = findSession(link);
  if (stored) return enter(stored); // same browser: rejoin with the kept token

  for (let wait = 2000; ; wait = Math.min(wait * 2, 30000)) {
    let r = null;
    try {
      r = await fetch('/v1/guest/join', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ secret }), cache: 'no-store',
      });
    } catch (e) { /* offline: try again */ }
    if (r && r.status === 201) {
      const j = await r.json();
      // The countdown runs on this device's clock: the time left at join, measured against the relay's Date header.
      const serverNow = Date.parse(r.headers.get('Date') || '') || Date.now();
      const s = {
        roomId: j.roomId, deviceId: j.deviceId, token: j.token, owner: j.owner, maxFileMb: j.maxFileMb,
        deadline: Date.now() + (j.expiresAtEpochMs - serverNow), link, downloaded: [],
      };
      store(PREFIX + s.roomId, s);
      return enter(s);
    }
    if (r && r.status === 409) return showStatus(COPY.inUse);
    if (r && r.status === 410) return showStatus(COPY.closed);
    await sleep(wait);
  }
}

function enter(s) {
  session = s;
  $('owner').textContent = s.owner.name;
  $('status').hidden = true;
  $('intro').hidden = false;
  $('room').hidden = false;
  $('footer').hidden = false;
  tick();
  listen();
}

function showStatus(text) {
  $('status').textContent = text;
  $('status').hidden = false;
  $('intro').hidden = true;
  $('room').hidden = true;
  $('footer').hidden = true;
  $('time').textContent = '';
}

/** The room is over (ended, expired or revoked): forget it and say so. */
function closeRoom() {
  if (closed) return;
  closed = true;
  clearTimeout(pollTimer);
  clearTimeout(tickTimer);
  if (session) forgetRoom(session.roomId);
  showStatus(COPY.closed);
}

function tick() {
  if (closed) return;
  const left = session.deadline - Date.now();
  if (left <= 0) return closeRoom();
  const secs = Math.ceil(left / 1000);
  const mmss = `${String(Math.floor(secs / 60)).padStart(2, '0')}:${String(secs % 60).padStart(2, '0')}`;
  $('time').textContent = COPY.left(mmss);
  $('footer').textContent = COPY.footer(Math.ceil(left / 60000));
  tickTimer = setTimeout(tick, (left % 1000) || 1000);
}

// ---------- receiving ----------

/** Polls the inbox every 3 s; a 401 (the room is gone) stops it via closeRoom. */
async function listen() {
  if (closed) return;
  try {
    renderInbox(await api('GET', '/v1/inbox'));
  } catch (e) {
    if (closed) return; // otherwise a dropped connection: the next poll tries again
  }
  pollTimer = setTimeout(listen, POLL_MS);
}

const topFolder = (path) => (path.includes('/') ? path.split('/')[0] : null);
const isWebUrl = (url) => /^https?:\/\/\S+$/i.test(url);
function hostOf(url) { try { return new URL(url).host; } catch (e) { return url; } }

/** The deliveries as rows, newest first: one per link, per loose file, and per top folder (a zip). */
function inboxItems(deliveries) {
  const items = [];
  for (const d of deliveries.slice().reverse()) {
    d.tabs.forEach((t, i) => {
      if (!isWebUrl(t.url)) return;
      items.push({ kind: 'link', key: `${d.id}#${i}`, name: t.title || hostOf(t.url), meta: `Link · ${hostOf(t.url)}`, url: t.url });
    });
    const folders = new Map();
    for (const f of d.files) {
      const top = topFolder(f.relativePath || f.name);
      if (top === null) {
        items.push({ kind: 'file', key: f.id, name: f.name, meta: `${typeLabel(f.name)} · ${formatBytes(f.sizeBytes)}`, fileId: f.id });
      } else {
        if (!folders.has(top)) folders.set(top, []);
        folders.get(top).push(f);
      }
    }
    for (const [folder, files] of folders) {
      const size = files.reduce((n, f) => n + f.sizeBytes, 0);
      items.push({
        kind: 'folder', key: `${d.id}/${folder}`, name: folder, deliveryId: d.id, folder,
        meta: `Folder · ${count(files.length, 'file')} · ${formatBytes(size)} · downloads as .zip`,
      });
    }
  }
  return items;
}

function renderInbox(deliveries) {
  const items = inboxItems(deliveries);
  const list = $('for-you');
  const keep = new Set(items.map((i) => i.key));
  for (const [key, row] of forYouRows) if (!keep.has(key)) { row.remove(); forYouRows.delete(key); }
  items.forEach((item, index) => {
    let row = forYouRows.get(item.key);
    if (!row) { row = itemRow(item); forYouRows.set(item.key, row); }
    // Move rows only when out of place, so a focused button keeps its focus between polls.
    if (list.children[index] !== row) list.insertBefore(row, list.children[index] || null);
  });
  $('for-you-section').hidden = items.length === 0;
}

function itemRow(item) {
  const row = el('div', 'item');
  const label = el('span', 'label');
  const meta = el('span', 'meta', item.meta);
  label.append(el('span', 'name', item.name), meta);
  row.append(label);
  if (item.kind === 'link') {
    const a = el('a', 'btn btn-outline', 'Open');
    a.href = item.url;
    a.target = '_blank';
    a.rel = 'noopener noreferrer';
    row.append(a);
    return row;
  }
  const button = el('button', 'btn btn-outline', 'Download');
  button.type = 'button';
  button.addEventListener('click', () => download(item, row));
  row.append(button);
  if (session.downloaded.includes(item.key)) markDownloaded(row);
  return row;
}

function markDownloaded(row) {
  const meta = row.querySelector('.meta');
  meta.classList.remove('error');
  if (!meta.querySelector('.tag')) meta.append(el('span', 'tag', 'Downloaded'));
}

/** Fetches a single-use ticket for [item], then lets the browser download the ticketed URL. */
async function download(item, row) {
  const button = row.querySelector('button');
  button.disabled = true;
  try {
    const req = item.kind === 'file' ? { fileId: item.fileId } : { deliveryId: item.deliveryId, folder: item.folder };
    const { ticket } = await retrying(() => api('POST', '/v1/guest/ticket', req));
    const t = encodeURIComponent(ticket);
    const url = item.kind === 'file'
      ? `/v1/files/${encodeURIComponent(item.fileId)}?t=${t}`
      : `/v1/files/zip?delivery=${encodeURIComponent(item.deliveryId)}&folder=${encodeURIComponent(item.folder)}&t=${t}`;
    const a = document.createElement('a');
    a.href = url;
    a.download = ''; // the relay's Content-Disposition names the file
    document.body.append(a);
    a.click();
    a.remove();
    if (!session.downloaded.includes(item.key)) { session.downloaded.push(item.key); saveSession(); }
    markDownloaded(row);
  } catch (e) {
    if (!(e instanceof RoomClosed)) {
      const meta = row.querySelector('.meta');
      meta.textContent = COPY.downloadFailed;
      meta.classList.add('error');
      setTimeout(() => { meta.textContent = item.meta; meta.classList.remove('error'); if (session.downloaded.includes(item.key)) markDownloaded(row); }, 4000);
    }
  } finally {
    button.disabled = false;
  }
}

// ---------- sending ----------

/** Where an upload session for this exact file (same path, size and last-modified) is kept between page loads. */
function uploadKey(file, relativePath) {
  return `${PREFIX}${session.roomId}.up.${encodeURIComponent(relativePath)}|${file.size}|${file.lastModified}`;
}

/**
 * Uploads [file] as [relativePath] in 8 MiB chunks with Content-Range, resuming a kept upload session; returns the
 * relay's file id. Mirrors relay-client's uploadResumable: a 409 carries the relay's offset and the upload carries on
 * from there (up to 5 conflicts in a row), a lost session starts over, and network drops are retried while the page
 * stays open.
 */
async function uploadFile(file, relativePath, onProgress = () => {}) {
  const key = uploadKey(file, relativePath);
  const size = file.size;
  const newSession = async () => {
    const s = await retrying(() => api('POST', '/v1/uploads', {
      relativePath, mimeType: file.type || 'application/octet-stream', sizeBytes: size,
    }));
    store(key, { uploadId: s.uploadId });
    return s;
  };

  let status = null;
  const kept = load(key);
  if (kept && kept.uploadId) {
    try {
      status = await retrying(() => api('GET', `/v1/uploads/${encodeURIComponent(kept.uploadId)}`));
    } catch (e) {
      if (!(e instanceof HttpError && e.status === 404)) throw e; // expired or purged: start over
    }
  }
  if (!status) status = await newSession();

  let conflicts = 0;
  while (!status.file) {
    const pos = status.offset;
    if (!(pos >= 0 && pos < size)) throw new Error(`relay reports offset ${pos} of ${size}`);
    onProgress(pos);
    const end = Math.min(pos + CHUNK_BYTES, size);
    const id = status.uploadId;
    const r = await retrying(() => request('PUT', `/v1/uploads/${encodeURIComponent(id)}`, {
      body: file.slice(pos, end), headers: { 'Content-Range': `bytes ${pos}-${end - 1}/${size}` },
    }));
    if (r.status === 409) {
      // The relay holds another offset (or already has the whole file): carry on from what it says.
      if (++conflicts > MAX_CONFLICTS) throw new Error(`upload ${id} keeps conflicting`);
      status = await r.json();
      continue;
    }
    if (r.status === 404) { status = await newSession(); continue; }
    if (!r.ok) throw await httpError(r);
    status = await r.json();
    conflicts = 0;
  }
  onProgress(size);
  return status.file.id;
}

/** Sends uploaded files to the owner. A retry after a lost response may find them already sent: that is success. */
async function sendFiles(fileIds) {
  try {
    await retrying(() => api('POST', '/v1/send', { to: session.owner.deviceId, fileIds }));
  } catch (e) {
    if (!(e instanceof HttpError && e.code === 'already_sent')) throw e;
  }
}

/** Sends web links to the owner (pasted or dropped into the drop area). */
async function sendLinks(urls) {
  const tabs = urls.filter(isWebUrl).slice(0, MAX_TABS).map((url) => ({ url }));
  if (tabs.length === 0) return;
  const card = sentCard(tabs.length === 1 ? hostOf(tabs[0].url) : count(tabs.length, 'link'));
  try {
    await retrying(() => api('POST', '/v1/send', { to: session.owner.deviceId, tabs }));
    card.delivered(tabs.length === 1 ? `Link · ${hostOf(tabs[0].url)} · delivered` : 'Links · delivered');
  } catch (e) {
    if (!(e instanceof RoomClosed)) card.failed(errorCopy(e));
  }
}

/** Queues one pick or drop of files: uploaded one after another, then sent as a single delivery. */
function sendBatch(entries) {
  if (entries.length === 0) return;
  const total = entries.reduce((n, e) => n + e.file.size, 0);
  const { name, meta } = describeBatch(entries, total);
  const card = sentCard(name);
  if (entries.length > MAX_FILES) return card.failed(COPY.tooMany);
  if (entries.some((e) => e.file.size > session.maxFileMb * MIB)) return card.failed(COPY.tooBig(session.maxFileMb));
  card.progress(0, total);
  uploadQueue = uploadQueue.then(async () => {
    try {
      const ids = [];
      let done = 0;
      for (const e of entries) {
        ids.push(await uploadFile(e.file, e.path, (bytes) => card.progress(done + bytes, total)));
        done += e.file.size;
      }
      await sendFiles(ids);
      entries.forEach((e) => forget(uploadKey(e.file, e.path))); // sent: choosing the file again sends it anew
      card.delivered(`${meta} · delivered`);
    } catch (e) {
      if (!(e instanceof RoomClosed)) card.failed(errorCopy(e, entries.length));
    }
  });
}

function errorCopy(e, files = 0) {
  if (e instanceof HttpError) {
    if (e.status === 413) return COPY.tooBig(session.maxFileMb);
    if (e.status === 507 || e.code === 'relay_full') return COPY.relayFull;
    if (e.code === 'invalid_files' && files > MAX_FILES) return COPY.tooMany;
  }
  return COPY.sendFailed;
}

function describeBatch(entries, total) {
  const tops = new Set(entries.map((e) => topFolder(e.path)));
  if (tops.size === 1 && !tops.has(null)) {
    return { name: [...tops][0], meta: `Folder · ${count(entries.length, 'file')}` };
  }
  if (entries.length === 1) return { name: entries[0].file.name, meta: formatBytes(total) };
  return { name: count(entries.length, 'file'), meta: formatBytes(total) };
}

/** A "You sent" card: progress while uploading, then delivered with a tick, or the error. */
function sentCard(name) {
  const card = el('div', 'sent');
  $('sent').prepend(card);
  $('sent-section').hidden = false;
  const nameEl = el('span', 'name', name);
  const head = el('div', 'sent-head');
  const pct = el('span', 'progress-text');
  head.append(nameEl, pct);
  const bar = el('div', 'bar');
  const fill = el('div', 'bar-fill');
  bar.append(fill);
  card.append(head, bar, el('span', 'note', COPY.keepOpen));

  const finish = (meta, isError) => {
    card.textContent = '';
    card.className = isError ? 'sent failed' : 'sent done';
    const label = el('span', 'label');
    const metaEl = el('span', isError ? 'meta error' : 'meta', meta);
    if (isError) metaEl.setAttribute('role', 'alert');
    label.append(nameEl, metaEl);
    card.append(label);
    if (!isError) card.append(checkIcon());
  };
  return {
    progress(done, total) {
      const p = total > 0 ? Math.floor((done / total) * 100) : 100;
      const unit = unitFor(total);
      pct.textContent = `${p}% · ${inUnit(done, unit)} of ${inUnit(total, unit)} ${unit.name}`;
      fill.style.width = `${p}%`;
    },
    delivered: (meta) => finish(meta, false),
    failed: (message) => finish(message, true),
  };
}

function checkIcon() {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  for (const [k, v] of Object.entries({
    width: 20, height: 20, viewBox: '0 0 24 24', fill: 'none', stroke: '#4ADE80', 'stroke-width': 2.5, role: 'img', 'aria-label': 'Delivered', class: 'check',
  })) svg.setAttribute(k, v);
  const path = document.createElementNS(ns, 'path');
  path.setAttribute('d', 'M5 12l5 5L20 7');
  svg.append(path);
  return svg;
}

// ---------- picking, dropping, pasting ----------

const asEntries = (files) => Array.from(files, (f) => ({ file: f, path: f.webkitRelativePath || f.name }));

/** Files of a drop, walking dropped folders where the browser exposes them (webkitGetAsEntry). */
async function droppedEntries(dt) {
  // Entries must be taken synchronously, while the drop event is still being dispatched.
  const roots = Array.from(dt.items || [])
    .filter((i) => i.kind === 'file' && i.webkitGetAsEntry)
    .map((i) => i.webkitGetAsEntry())
    .filter(Boolean);
  if (roots.length === 0) return asEntries(dt.files || []);
  const files = Array.from(dt.files || []);
  const out = [];
  const walk = async (entry, prefix) => {
    if (entry.isFile) {
      const file = await new Promise((ok, fail) => entry.file(ok, fail));
      out.push({ file, path: prefix + file.name });
    } else if (entry.isDirectory) {
      const reader = entry.createReader();
      for (;;) {
        const batch = await new Promise((ok, fail) => reader.readEntries(ok, fail));
        if (batch.length === 0) break;
        for (const child of batch) await walk(child, `${prefix}${entry.name}/`);
      }
    }
  };
  try {
    for (const root of roots) await walk(root, '');
  } catch (e) {
    return asEntries(files); // fall back to the plain file list
  }
  return out;
}

function linksIn(text) {
  return (text || '').split(/\s+/).filter(isWebUrl);
}

function setUpInputs() {
  const fileInput = $('file-input');
  const folderInput = $('folder-input');
  $('pick-files').addEventListener('click', () => fileInput.click());
  $('pick-folder').addEventListener('click', () => folderInput.click());
  // Folder picking needs webkitdirectory; without it the button would only pick files.
  if (!('webkitdirectory' in HTMLInputElement.prototype)) $('pick-folder').hidden = true;
  for (const input of [fileInput, folderInput]) {
    input.addEventListener('change', () => {
      sendBatch(asEntries(input.files));
      input.value = '';
    });
  }

  const drop = $('drop');
  const isFileDrag = (e) => e.dataTransfer && Array.from(e.dataTransfer.types || []).some((t) => t === 'Files' || t === 'text/uri-list' || t === 'text/plain');
  document.addEventListener('dragover', (e) => {
    if (!isFileDrag(e)) return;
    e.preventDefault(); // the whole page accepts drops, so a near miss never opens the file in the tab
    drop.classList.add('over');
  });
  document.addEventListener('dragleave', (e) => { if (!e.relatedTarget) drop.classList.remove('over'); });
  document.addEventListener('drop', async (e) => {
    if (!e.dataTransfer) return;
    e.preventDefault();
    drop.classList.remove('over');
    if (closed || !session) return;
    const dt = e.dataTransfer;
    if (dt.files && dt.files.length > 0) return sendBatch(await droppedEntries(dt));
    sendLinks(linksIn(dt.getData('text/uri-list') || dt.getData('text/plain')));
  });

  document.addEventListener('paste', (e) => {
    if (closed || !session || !e.clipboardData) return;
    const target = e.target;
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) return;
    const files = e.clipboardData.files;
    if (files && files.length > 0) { e.preventDefault(); return sendBatch(asEntries(files)); }
    const urls = linksIn(e.clipboardData.getData('text/uri-list') || e.clipboardData.getData('text/plain'));
    if (urls.length > 0) { e.preventDefault(); sendLinks(urls); }
  });
}

// ---------- small helpers ----------

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

const count = (n, noun) => `${n.toLocaleString('en-US')} ${noun}${n === 1 ? '' : 's'}`;

function typeLabel(name) {
  const dot = name.lastIndexOf('.');
  const ext = dot > 0 ? name.slice(dot + 1) : '';
  return ext && ext.length <= 5 ? ext.toUpperCase() : 'File';
}

const UNITS = [{ name: 'B', size: 1 }, { name: 'KB', size: 1024 }, { name: 'MB', size: MIB }, { name: 'GB', size: 1024 * MIB }];
function unitFor(bytes) {
  let unit = UNITS[0];
  for (const u of UNITS) if (bytes >= u.size) unit = u;
  return unit;
}
function inUnit(bytes, unit) {
  const v = bytes / unit.size;
  return unit.size === 1 || v >= 10 ? String(Math.round(v)) : v.toFixed(1);
}
function formatBytes(bytes) {
  const unit = unitFor(bytes);
  return `${inUnit(bytes, unit)} ${unit.name}`;
}

// ---------- start ----------

$('status').textContent = COPY.connecting;
setUpInputs();
joinRoom();
