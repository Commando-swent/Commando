#!/usr/bin/env node
// Catalogue import for Command'o: a swiss-cli JSON Lines export of one store -> stores/{store}/products/{number}.
// AI assistance: Claude Code. See README.md. Dry-run unless --write is passed.
import { execSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const cleanText = (text) => text.replace(/\s+/g, ' ').trim();

/**
 * The name shown in the app, the one mapping step that differs between the chains' exports. Migros
 * splits its title into brand, name and variant (see displayName). Coop's title is already the name
 * Coop shows, brand and size included ("1664 Blonde Bière 6x50cl", brand Kronenbourg), so adding the
 * brand would repeat or misplace it ("Acqua Panna Acqua Eau minérale Panna"): only spaces are cleaned.
 */
const STORE_NAMES = {
  migros: (row) => displayName(row.name, row.brand, row.variant),
  coop: (row) => cleanText(row.name),
};
/** The chains with a full online catalogue; other chains' items are entered by hand in the app. */
export const STORES = Object.keys(STORE_NAMES);
const PROJECT = 'command-o';
const DATABASE = `projects/${PROJECT}/databases/(default)`;
const BATCH_SIZE = 400; // Firestore commits hold at most 500 writes.
const USAGE = `Usage: node import.mjs <export.jsonl> --store ${STORES.join('|')} [--sizes manifest.jsonl] [--project ${PROJECT}] [--write] [--max-writes N]
  Target: --project ${PROJECT}, or a local emulator in FIRESTORE_EMULATOR_HOST. Dry run unless --write.`;

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

/** Clean source taxonomy plus its broad first segment, or nulls when the export has none. */
function categories(category) {
  const path = category?.split('>').map(cleanText).filter(Boolean).join(' > ') || null;
  return { category: path?.split(' > ')[0] ?? null, category_path: path };
}

/**
 * The display name, after GS1's product description: brand, the store's name, then the variant.
 * A part already in the name is not repeated, and the words a brand ends with and the name starts
 * with are written once ("Jura Sel" and "Sel de table" give "Jura Sel de table"). Nothing is invented.
 */
export function displayName(name, brand, variant) {
  const words = (text) => normalizeSearchText(text ?? '').split(/[^a-z0-9]+/).filter(Boolean);
  const contains = (text, part) => part.length > 0 && text.some((_, i) => part.every((word, j) => text[i + j] === word));
  const brandWords = words(brand);
  // Migros titles can keep a double space or a dangling "·" separator ("Ice Tea ·").
  let full = cleanText(name.replace(/^[\s·]+|[\s·]+$/g, ''));
  if (brandWords.length > 0 && !contains(words(full), brandWords)) {
    const parts = full.split(/\s+/);
    const shared = parts.findLastIndex((_, i) => {
      const head = words(parts.slice(0, i + 1).join(' '));
      return head.length <= brandWords.length && head.every((word, j) => brandWords[brandWords.length - head.length + j] === word);
    });
    full = [brand.trim(), ...parts.slice(shared + 1)].join(' ');
  }
  return contains(words(full), words(variant)) || !variant?.trim() ? full : `${full} ${variant.trim()}`;
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
 * The size of an export row: swiss-cli's `export` calls it `size`, the --sizes file and older exports
 * `size_text`. Without a Migros quantity, swiss-cli puts the variant line there ("doux" as size and
 * variant): that is not a size, so it counts as missing. Blank candidates are missing too; when both
 * fields contain a size, the older `size_text` field keeps precedence.
 */
const exportSize = (row) => {
  const clean = (text) => (typeof text === 'string' ? cleanText(text).toLowerCase() : text);
  const variant = clean(row.variant);
  return [row.size_text, row.size].find((size) => {
    const value = clean(size);
    return value != null && value !== '' && value !== variant;
  }) ?? null;
};

/**
 * Maps one export row to `{ path, data, removedAt }`, or `{ error }` naming why it is skipped. A
 * delisted row only needs its id and `removed_at` and has `data: null`: it can only mark an existing
 * product removed, and swiss-cli may export it without a price (its prices are a LEFT JOIN).
 */
export function toProduct(row, store) {
  if (!STORES.includes(store) || row?.store !== store) return { error: `not a ${store} product` };
  const number = typeof row.id === 'string' && row.id.startsWith(`${store}:`) ? row.id.slice(store.length + 1) : '';
  if (!isDocumentId(number)) return { error: 'invalid id' };
  if (row.removed_at != null && !isTimestamp(row.removed_at)) return { error: 'invalid removed_at' };
  const path = `stores/${store}/products/${number}`;
  if (row.removed_at != null) return { path, data: null, removedAt: row.removed_at };
  if (typeof row.name !== 'string' || !row.name.trim()) return { error: 'blank name' };
  if (!isPrice(row.price)) return { error: 'invalid price' };
  const imageUrl = typeof row.image_url === 'string' ? row.image_url.trim() : row.image_url ?? null;
  if (imageUrl && !/^https?:\/\/\S+$/.test(imageUrl)) return { error: 'invalid image_url' };
  for (const field of ['category', 'brand', 'variant', 'version_text', 'size_text', 'size']) {
    if (row[field] != null && typeof row[field] !== 'string') return { error: `invalid ${field}` };
  }
  const name = STORE_NAMES[store](row);
  const data = {
    id: row.id,
    name,
    price: Math.round(row.price * 100) / 100,
    image_url: imageUrl || null,
    removed_at: null,
    search_prefixes: searchPrefixes(name, row.category ?? ''),
    size_text: cleanText(exportSize(row) ?? '') || null,
    // The parts of the name, as the store gives them.
    source_name: row.name.trim(),
    brand: row.brand?.trim() || null,
    variant: row.variant?.trim() || null,
    ...categories(row.category),
  };
  return { path, data, removedAt: null };
}

/** The sizes of a `--sizes` file (`{ data: { id, size_text } }` lines; see README) by product ID. */
export function parseSizes(text) {
  const sizes = new Map();
  for (const line of text.split(/\r?\n/).filter((item) => item.trim())) {
    const { id, size_text: size } = JSON.parse(line).data ?? {};
    if (typeof id === 'string' && typeof size === 'string' && size.trim()) sizes.set(id, size.trim());
  }
  return sizes;
}

/**
 * Listed Migros products that would show the same name and size and have no variant take swiss-cli's
 * unclassified `version_text` in their name ("Essie Vernis à ongle 954 Off Beat Chic"), so shades and
 * models can be told apart. `variant` stays null, and a unique name and size keeps its name.
 */
function distinguishCollisions(products, rows) {
  const clean = (text) => cleanText(normalizeSearchText(text ?? ''));
  const key = ({ data }) => `${clean(data.name)}|${clean(data.size_text)}`;
  const counts = new Map();
  for (const product of products) counts.set(key(product), (counts.get(key(product)) ?? 0) + 1);
  for (const product of products) {
    const row = rows.get(product.path);
    const versionText = cleanText(row.version_text ?? '');
    if (counts.get(key(product)) < 2 || product.data.variant || !versionText || clean(versionText) === clean(product.data.size_text)) continue;
    const name = displayName(row.name, row.brand, versionText);
    if (name !== product.data.name) product.data = { ...product.data, name, search_prefixes: searchPrefixes(name, row.category ?? '') };
  }
}

/**
 * Valid products of one store's JSON Lines export, and one diagnostic per skipped line. A row
 * without a usable export size takes it from `sizes`, so sizes already fetched need no new crawl.
 * Migros names that would collide are told apart with `version_text` (see distinguishCollisions).
 */
export function parseExport(text, store, sizes = new Map()) {
  if (!STORES.includes(store)) throw new Error(`Unsupported store ${store}: use ${STORES.join(' or ')}`);
  const products = new Map();
  const rows = new Map();
  const skipped = [];
  text.split(/\r?\n/).forEach((line, index) => {
    if (!line.trim()) return;
    let row;
    try {
      row = JSON.parse(line);
    } catch {
      row = null;
    }
    const sized = row && exportSize(row) == null ? { ...row, size_text: sizes.get(row.id) } : row;
    const product = row ? toProduct(sized, store) : { error: 'not JSON' };
    if (!product.error && products.has(product.path)) product.error = `duplicate of ${row.id}`;
    if (!product.error) rows.set(product.path, row);
    if (product.error) skipped.push(`line ${index + 1}: ${product.error}`);
    else products.set(product.path, product);
  });
  if (store === 'migros') distinguishCollisions([...products.values()].filter((product) => product.data), rows);
  return { products: [...products.values()], skipped };
}

const sorted = (value) =>
  value && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, sorted(value[key])])) : value;
// The importer's fields are the keys toProduct gives `data`: the comparison and the update mask both
// come from them, so a field cannot be built but left unwritten or unchecked.
const owned = (data, fields) => JSON.stringify(sorted(Object.fromEntries(fields.map((field) => [field, data[field] ?? null]))));
const sameFields = (existing, data) => owned(existing, Object.keys(data)) === owned(data, Object.keys(data));

// Listed products are created or updated; a delisted one is only marked removed if it exists and is
// active. Every write is guarded by the document's state when it was read.
export function planWrites(products, existingByPath) {
  const counts = { create: 0, update: 0, remove: 0, unchanged: 0, removedNotInFirestore: 0 };
  const writes = [];
  for (const { path, data: row, removedAt } of products) {
    const existing = existingByPath.get(path);
    // A row without a size keeps the stored one, so a rerun without --sizes never erases sizes.
    const data = row && {
      ...row,
      size_text: row.size_text ?? existing?.data.size_text ?? null,
      category: row.category ?? existing?.data.category ?? null,
      category_path: row.category_path ?? existing?.data.category_path ?? null,
      search_prefixes:
        row.category_path == null && existing?.data.category_path
          ? searchPrefixes(row.name, existing.data.category_path)
          : row.search_prefixes,
    };
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
      writes.push({ update: { name: documentName(path), fields: encodeFields(data) }, updateMask: { fieldPaths: Object.keys(data) }, currentDocument });
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

/**
 * Where a run reads and writes, never implied: the local emulator of `env`, or command-o when
 * `--project command-o` is passed. Neither or both is an error, so nothing falls back to production.
 */
export function firestoreTarget(project, env) {
  const host = emulatorHost(env);
  if (host && project) throw new Error(`FIRESTORE_EMULATOR_HOST is set and --project ${project} was passed: choose one target`);
  if (!host && !project) throw new Error(`No Firestore target: run against a local emulator (FIRESTORE_EMULATOR_HOST) or pass --project ${PROJECT}`);
  return host ? { emulator: host } : { project };
}

/**
 * The firebase-tools release whose login modules firebaseCliToken reads. They are internal, so
 * another release may move them: it is refused instead. Keep it equal to FIREBASE_TOOLS_VERSION in ci.yml.
 */
export const FIREBASE_TOOLS_VERSION = '15.32.1';

/** `require` inside the firebase-tools of the `node_modules` folder `root`, if it is the pinned release. */
export function firebaseTools(root) {
  const manifest = resolve(root, 'firebase-tools', 'package.json');
  if (!existsSync(manifest)) {
    throw new Error(`firebase-tools ${FIREBASE_TOOLS_VERSION} is not installed in ${root}: run "npm install --prefix tools/catalog-import/tmp/firebase-tools firebase-tools@${FIREBASE_TOOLS_VERSION}" and set FIREBASE_TOOLS_ROOT=tools/catalog-import/tmp/firebase-tools/node_modules, or see "Prerequisites" in README.md`);
  }
  const { version } = JSON.parse(readFileSync(manifest, 'utf8'));
  if (version !== FIREBASE_TOOLS_VERSION) {
    throw new Error(`firebase-tools ${version} in ${root}, but this tool needs ${FIREBASE_TOOLS_VERSION}: see "Prerequisites" in README.md`);
  }
  return createRequire(manifest);
}

/** The Firebase CLI's credentials (`firebase login`); no key is stored by this tool. */
async function firebaseCliToken() {
  const cli = firebaseTools(process.env.FIREBASE_TOOLS_ROOT || execSync('npm root -g', { encoding: 'utf8' }).trim());
  const { configstore } = cli('./lib/configstore.js');
  const [user, tokens] = [configstore.get('user'), configstore.get('tokens')];
  if (!user || !tokens) throw new Error('The Firebase CLI is not signed in: run firebase login');
  await cli('./lib/requireAuth.js').requireAuth({ user, tokens, nonInteractive: true, project: PROJECT });
  return cli('./lib/apiv2.js').getAccessToken();
}

/** A REST client for `(default)` of a `firestoreTarget`: the local emulator or command-o. */
export async function firestoreClient(target) {
  if (!target?.emulator && target?.project !== PROJECT) throw new Error(`firestoreClient needs { emulator: host } or { project: '${PROJECT}' }`);
  const host = target.emulator ? emulatorHost({ FIRESTORE_EMULATOR_HOST: target.emulator }) : null;
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
  const options = { file: null, store: null, sizes: null, write: false, project: null, maxWrites: Infinity };
  const valueOptions = {
    '--store': (value) => { options.store = value; },
    '--project': (value) => { options.project = value; },
    '--sizes': (value) => { options.sizes = value; },
    '--max-writes': (value) => { options.maxWrites = Number(value); },
  };
  for (let i = 0; i < args.length; i += 1) {
    if (args[i] === '--write') options.write = true;
    else if (valueOptions[args[i]]) {
      const option = args[i];
      const value = args[i + 1];
      if (!value || value.startsWith('--')) throw new Error(`${option} requires a value. ${USAGE}`);
      valueOptions[option](value);
      i += 1;
    }
    else if (!args[i].startsWith('--') && !options.file) options.file = args[i];
    else throw new Error(`Unexpected argument ${args[i]}. ${USAGE}`);
  }
  if (!options.file) throw new Error(USAGE);
  if (!STORES.includes(options.store)) throw new Error(`--store must be ${STORES.join(' or ')}. ${USAGE}`);
  if (options.project !== null && options.project !== PROJECT) throw new Error(`This tool only targets ${PROJECT}`);
  if (!(Number.isInteger(options.maxWrites) && options.maxWrites > 0) && options.maxWrites !== Infinity) throw new Error('--max-writes must be a positive whole number');
  return options;
}

const SKIPPED_SHOWN = 20;
/** The warnings for skipped lines: the first `shown` of them, then how many more there are. */
export function skippedReport(skipped, shown = SKIPPED_SHOWN) {
  const more = skipped.length - shown;
  return [...skipped.slice(0, shown).map((line) => `Skipped ${line}`), ...(more > 0 ? [`... and ${more} more skipped lines`] : [])];
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const target = firestoreTarget(options.project, process.env);
  const sizes = options.sizes ? parseSizes(readFileSync(options.sizes, 'utf8')) : new Map();
  if (options.sizes) console.log(`${sizes.size} sizes read from ${options.sizes}`);
  const { products, skipped } = parseExport(readFileSync(options.file, 'utf8'), options.store, sizes);
  skippedReport(skipped).forEach((line) => console.warn(line));
  console.log(`${products.length} valid products, ${skipped.length} skipped lines`);
  if (products.length === 0) throw new Error(`The export has no valid ${options.store} product`);
  const client = await firestoreClient(target);
  console.log(`${options.write ? 'WRITING to' : 'Dry run against'} ${client.target}, collection stores/${options.store}/products`);
  await importProducts(products, { client, write: options.write, maxWrites: options.maxWrites });
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main().catch((error) => {
    console.error(`Error: ${error.message}`);
    process.exitCode = 1;
  });
}
