#!/usr/bin/env node
// Catalogue import for Command'o: a swiss-cli JSON Lines export of one store -> stores/{store}/products/{number}.
// AI assistance: Claude Code. See README.md. Dry-run unless --write is passed.
import { execSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

/** The chains with a full online catalogue; other chains' items are entered by hand in the app. */
export const STORES = ['migros', 'coop'];
const PROJECT = 'command-o';
const DATABASE = `projects/${PROJECT}/databases/(default)`;
const FIELDS = ['id', 'name', 'price', 'image_url', 'removed_at', 'search_prefixes'];
const BATCH_SIZE = 400; // Firestore commits hold at most 500 writes.
const USAGE = `Usage: node import.mjs <export.jsonl> --store ${STORES.join('|')} [--write [--project ${PROJECT}]] [--max-writes N]`;

/** Must stay identical to normalizeProductSearchText in Command'o's ProductRepositoryFirestore. */
export function normalizeSearchText(text) {
  const ligatures = text.toLowerCase().replaceAll('œ', 'oe').replaceAll('æ', 'ae').replaceAll('ß', 'ss');
  return ligatures.normalize('NFD').replace(/\p{M}+/gu, '');
}

// 2- to 15-character prefixes of the words of the name and of the last category segment, which
// the app's productSearchTerms looks up. Parent categories ("Produits laitiers") are too broad.
export function searchPrefixes(name, categoryPath = '') {
  const prefixes = new Set();
  for (const word of normalizeSearchText(`${name} ${categoryPath.split('>').at(-1)}`).split(/[^a-z0-9]+/)) {
    for (let length = 2; length <= Math.min(word.length, 15); length += 1) prefixes.add(word.slice(0, length));
  }
  return Object.fromEntries([...prefixes].sort().map((prefix) => [prefix, true]));
}

// A Firestore document ID the app accepts: no whitespace or `/`, not `.`, `..` or `__…__`.
const isDocumentId = (id) => /^[^\s/]+$/.test(id) && !/^(\.\.?|__.*__)$/.test(id) && Buffer.byteLength(id) <= 1500;
// The app reads whole cents into an Int (ProductRepositoryFirestore.toPriceCents), so the rounded
// amount must be 1 to Int.MAX_VALUE cents.
const MAX_PRICE_CENTS = 2_147_483_647;
const isPrice = (value) => {
  const cents = typeof value === 'number' ? Math.round(value * 100) : NaN;
  return cents >= 1 && cents <= MAX_PRICE_CENTS && Math.abs(value * 100 - cents) < 1e-6;
};
// swiss-cli writes removed_at with toISOString(). Date.parse alone also takes 0, "2026" or a 30 February.
const isTimestamp = (value) => {
  const date = typeof value === 'string' && /^(\d{4}-\d{2}-\d{2})T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$/.exec(value)?.[1];
  return Boolean(date) && !Number.isNaN(Date.parse(value)) && new Date(`${date}T00:00:00Z`).toISOString().startsWith(date);
};

/**
 * Maps one export row to `{ path, data, removedAt }`, or `{ error }` naming why it is skipped. A
 * delisted row only needs its id and `removed_at` and has `data: null`: it can only mark an existing
 * product removed, and swiss-cli may export it without a price (its prices are a LEFT JOIN).
 */
export function toProduct(row, store) {
  if (row?.store !== store) return { error: `not a ${store} product` };
  const number = typeof row.id === 'string' && row.id.startsWith(`${store}:`) ? row.id.slice(store.length + 1) : '';
  if (!isDocumentId(number)) return { error: 'invalid id' };
  if (row.removed_at != null && !isTimestamp(row.removed_at)) return { error: 'invalid removed_at' };
  const path = `stores/${store}/products/${number}`;
  if (row.removed_at != null) return { path, data: null, removedAt: row.removed_at };
  if (typeof row.name !== 'string' || !row.name.trim()) return { error: 'blank name' };
  if (!isPrice(row.price)) return { error: 'invalid price' };
  const imageUrl = typeof row.image_url === 'string' ? row.image_url.trim() : row.image_url ?? null;
  if (imageUrl && !/^https?:\/\/\S+$/.test(imageUrl)) return { error: 'invalid image_url' };
  if (row.category != null && typeof row.category !== 'string') return { error: 'invalid category' };
  const name = row.name.trim();
  const data = {
    id: row.id,
    name,
    price: Math.round(row.price * 100) / 100,
    image_url: imageUrl || null,
    removed_at: null,
    search_prefixes: searchPrefixes(name, row.category ?? ''),
  };
  return { path, data, removedAt: null };
}

/** Valid products of one store's JSON Lines export, and one diagnostic per skipped line. */
export function parseExport(text, store) {
  if (!STORES.includes(store)) throw new Error(`Unsupported store ${store}: use ${STORES.join(' or ')}`);
  const products = new Map();
  const skipped = [];
  text.split(/\r?\n/).forEach((line, index) => {
    if (!line.trim()) return;
    let row;
    try {
      row = JSON.parse(line);
    } catch {
      row = null;
    }
    const product = row ? toProduct(row, store) : { error: 'not JSON' };
    if (!product.error && products.has(product.path)) product.error = `duplicate of ${row.id}`;
    if (product.error) skipped.push(`line ${index + 1}: ${product.error}`);
    else products.set(product.path, product);
  });
  return { products: [...products.values()], skipped };
}

const sorted = (value) =>
  value && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, sorted(value[key])])) : value;
const owned = (data) => JSON.stringify(sorted(Object.fromEntries(FIELDS.map((field) => [field, data[field] ?? null]))));
const sameFields = (existing, data) => owned(existing) === owned(data);

// Listed products are created or updated; a delisted one is only marked removed if it exists and is
// active. Every write is guarded by the document's state when it was read.
export function planWrites(products, existingByPath) {
  const counts = { create: 0, update: 0, remove: 0, unchanged: 0, removedNotInFirestore: 0 };
  const writes = [];
  for (const { path, data, removedAt } of products) {
    const existing = existingByPath.get(path);
    const currentDocument = existing ? { updateTime: existing.updateTime } : { exists: false };
    let action;
    if (removedAt) action = !existing ? 'removedNotInFirestore' : existing.data.removed_at != null ? 'unchanged' : 'remove';
    else action = !existing ? 'create' : sameFields(existing.data, data) ? 'unchanged' : 'update';
    counts[action] += 1;
    if (action === 'remove') {
      const fields = { removed_at: { timestampValue: new Date(removedAt).toISOString() } };
      writes.push({ update: { name: documentName(path), fields }, updateMask: { fieldPaths: ['removed_at'] }, currentDocument });
    } else if (action === 'create' || action === 'update') {
      // The mask replaces only the importer's fields and keeps any other field of the product.
      writes.push({ update: { name: documentName(path), fields: encodeFields(data) }, updateMask: { fieldPaths: FIELDS }, currentDocument });
    }
  }
  return { counts, writes };
}

/** Reads the export's documents, plans and, with `write`, commits at most `maxWrites` in bounded batches. */
export async function importProducts(products, { client, write = false, maxWrites = Infinity, log = console.log }) {
  const existing = new Map();
  for (let i = 0; i < products.length; i += BATCH_SIZE) {
    for (const [path, document] of await client.get(products.slice(i, i + BATCH_SIZE).map((product) => product.path))) {
      existing.set(path, document);
    }
  }
  const { counts, writes } = planWrites(products, existing);
  log(`Plan: ${JSON.stringify(counts)}`);
  if (!write) return { counts, written: 0 };
  const allowed = writes.slice(0, maxWrites);
  for (let i = 0; i < allowed.length; i += BATCH_SIZE) {
    await client.commit(allowed.slice(i, i + BATCH_SIZE));
    log(`Committed ${Math.min(i + BATCH_SIZE, allowed.length)}/${allowed.length} writes`);
  }
  if (allowed.length < writes.length) log(`--max-writes reached: ${writes.length - allowed.length} writes left, run again to continue`);
  return { counts, written: allowed.length };
}

const documentName = (path) => `${DATABASE}/documents/${path}`;
const encode = (value) => {
  if (value === null) return { nullValue: null };
  if (typeof value === 'string') return { stringValue: value };
  if (typeof value === 'number') return { doubleValue: value };
  if (typeof value === 'boolean') return { booleanValue: value };
  return { mapValue: { fields: encodeFields(value) } };
};
const encodeFields = (data) => Object.fromEntries(Object.entries(data).map(([key, value]) => [key, encode(value)]));
const decode = (value) => {
  if ('mapValue' in value) return Object.fromEntries(Object.entries(value.mapValue.fields ?? {}).map(([key, item]) => [key, decode(item)]));
  if ('nullValue' in value) return null;
  if ('doubleValue' in value || 'integerValue' in value) return Number(value.doubleValue ?? value.integerValue);
  return value.stringValue ?? value.booleanValue ?? value.timestampValue ?? value;
};

/** FIRESTORE_EMULATOR_HOST, which must be local: the emulator gets an owner token and plain HTTP. */
export function emulatorHost(env = process.env) {
  const host = env.FIRESTORE_EMULATOR_HOST?.trim();
  if (host && !/^(localhost|127\.0\.0\.1|\[::1\]):\d+$/.test(host)) throw new Error(`FIRESTORE_EMULATOR_HOST must be a local host:port, not ${host}`);
  return host || null;
}

/** The Firebase CLI's credentials (`firebase login`); no key is stored by this tool. */
async function firebaseCliToken() {
  const npmRoot = process.env.FIREBASE_TOOLS_ROOT || execSync('npm root -g', { encoding: 'utf8' }).trim();
  const cli = createRequire(join(npmRoot, 'firebase-tools', 'package.json'));
  const { configstore } = cli('./lib/configstore.js');
  const [user, tokens] = [configstore.get('user'), configstore.get('tokens')];
  if (!user || !tokens) throw new Error('The Firebase CLI is not signed in: run firebase login');
  await cli('./lib/requireAuth.js').requireAuth({ user, tokens, nonInteractive: true, project: PROJECT });
  return cli('./lib/apiv2.js').getAccessToken();
}

/** A REST client for `(default)` of command-o, or of the local emulator. */
export async function firestoreClient(host = emulatorHost()) {
  const token = host ? 'owner' : await firebaseCliToken();
  const call = async (suffix, body) => {
    const response = await fetch(`${host ? `http://${host}` : 'https://firestore.googleapis.com'}/v1/${DATABASE}${suffix}`, {
      method: body ? 'POST' : 'GET',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: body && JSON.stringify(body),
      signal: AbortSignal.timeout(60_000),
    });
    const text = await response.text();
    if (!response.ok) throw new Error(`Firestore ${suffix || 'database'} failed (HTTP ${response.status}): ${text.slice(0, 300)}`);
    return JSON.parse(text);
  };
  // Fails unless this exact project and database exist and the signed-in account may use them.
  const database = host ? { name: DATABASE } : await call('');
  if (database.name !== DATABASE) throw new Error(`Unexpected database ${database.name}`);
  return {
    target: host ? `emulator ${host}` : DATABASE,
    get: async (paths) => {
      const found = (await call('/documents:batchGet', { documents: paths.map(documentName) })).filter((item) => item.found);
      return found.map(({ found: doc }) => [doc.name.split('/documents/')[1], { data: decode({ mapValue: doc }), updateTime: doc.updateTime }]);
    },
    commit: (writes) => call('/documents:commit', { writes }),
  };
}

export function parseArgs(args) {
  const options = { file: null, store: null, write: false, project: null, maxWrites: Infinity };
  for (let i = 0; i < args.length; i += 1) {
    if (args[i] === '--write') options.write = true;
    else if (args[i] === '--store') options.store = args[++i];
    else if (args[i] === '--project') options.project = args[++i];
    else if (args[i] === '--max-writes') options.maxWrites = Number(args[++i]);
    else if (!args[i].startsWith('--') && !options.file) options.file = args[i];
    else throw new Error(`Unexpected argument ${args[i]}. ${USAGE}`);
  }
  if (!options.file) throw new Error(USAGE);
  if (!STORES.includes(options.store)) throw new Error(`--store must be ${STORES.join(' or ')}. ${USAGE}`);
  if (options.project !== null && options.project !== PROJECT) throw new Error(`This tool only targets ${PROJECT}`);
  if (!(Number.isInteger(options.maxWrites) && options.maxWrites > 0) && options.maxWrites !== Infinity) throw new Error('--max-writes must be a positive whole number');
  if (options.write && !emulatorHost() && options.project !== PROJECT) throw new Error(`Production write blocked: review the dry-run, then add --project ${PROJECT}`);
  return options;
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const { products, skipped } = parseExport(readFileSync(options.file, 'utf8'), options.store);
  skipped.slice(0, 20).forEach((line) => console.warn(`Skipped ${line}`));
  if (skipped.length > 20) console.warn(`... and ${skipped.length - 20} more skipped lines`);
  console.log(`${products.length} valid products, ${skipped.length} skipped lines`);
  if (products.length === 0) throw new Error(`The export has no valid ${options.store} product`);
  const client = await firestoreClient();
  console.log(`${options.write ? 'WRITING to' : 'Dry run against'} ${client.target}, collection stores/${options.store}/products`);
  await importProducts(products, { client, write: options.write, maxWrites: options.maxWrites });
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main().catch((error) => {
    console.error(`Error: ${error.message}`);
    process.exitCode = 1;
  });
}
