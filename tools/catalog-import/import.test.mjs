// AI assistance: Claude Code. Run with: node --test tools/catalog-import/import.test.mjs
import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  displayName, emulatorHost, FIREBASE_TOOLS_VERSION, firebaseTools, firestoreClient, firestoreTarget, importProducts, parseArgs,
  parseExport, parseSizes, planWrites, searchPrefixes, skippedReport, toProduct,
} from './import.mjs';

const row = (number, overrides = {}) =>
  ({ id: `migros:${number}`, store: 'migros', name: 'Lait entier', price: 1.95, image_url: null, category: 'Lait > Lait UHT', ...overrides });
const exportOf = (...rows) => parseExport(rows.map((item) => JSON.stringify(item)).join('\n'), rows[0]?.store ?? 'migros').products;
const path = (number, store = 'migros') => `stores/${store}/products/${number}`;
// The delisting date of the removal tests, as swiss-cli exports it and as Firestore returns it.
const REMOVED_AT = '2026-10-20T08:00:00Z';
const STORED_REMOVED_AT = new Date(REMOVED_AT).toISOString();
// A product from swiss-cli's Coop listing fixture (test/fixtures/coop-listing.json), as `export` writes it.
const coopRow = (overrides = {}) => ({
  id: 'coop:3465933', store: 'coop', name: 'Naturaplan Bio Avocado 1 Stück', price: 1.95, category: 'Avocados', removed_at: null,
  image_url: 'https://www.coop.ch/img/produkte/310_310/RGB/3465933_001.jpg?_=1710535453816', ...overrides,
});

/** A Firestore stand-in that applies update masks and preconditions like the REST commit does. */
function fakeClient(documents = new Map()) {
  const commits = [];
  let clock = 0;
  return {
    documents,
    commits,
    get: async (paths) => paths.filter((p) => documents.has(p)).map((p) => [p, structuredClone(documents.get(p))]),
    commit: async (writes) => {
      commits.push(writes.length);
      for (const { update, updateMask, currentDocument } of writes) {
        const key = update.name.split('/documents/')[1];
        const existing = documents.get(key);
        if (currentDocument.exists === false ? existing : existing?.updateTime !== currentDocument.updateTime) throw new Error('FAILED_PRECONDITION');
        const data = { ...existing?.data };
        for (const field of updateMask.fieldPaths) data[field] = decodeValue(update.fields[field]);
        documents.set(key, { data, updateTime: `t${(clock += 1)}` });
      }
    },
  };
}
const decodeValue = (value) =>
  'mapValue' in value ? Object.fromEntries(Object.entries(value.mapValue.fields).map(([k, v]) => [k, decodeValue(v)])) : Object.values(value)[0];

test('searchPrefixes folds accents and the æ, œ and ß ligatures and indexes only the last category', () => {
  const prefixes = Object.keys(searchPrefixes('Bœuf haché', 'Viande > Æbleskiver Straße'));
  assert.deepEqual(prefixes, [
    'ae', 'aeb', 'aebl', 'aeble', 'aebles', 'aeblesk', 'aebleski', 'aebleskiv', 'aebleskive', 'aebleskiver',
    'bo', 'boe', 'boeu', 'boeuf', 'ha', 'hac', 'hach', 'hache', 'st', 'str', 'stra', 'stras', 'strass', 'strasse',
  ]);
  assert.deepEqual(Object.keys(searchPrefixes('ŒUFS Crème brûlée')), [
    'br', 'bru', 'brul', 'brule', 'brulee', 'cr', 'cre', 'crem', 'creme', 'oe', 'oeu', 'oeuf', 'oeufs',
  ]);
  const long = Object.keys(searchPrefixes('Straciatellaamaretto'));
  assert.deepEqual([long.length, long.at(-1)], [14, 'straciatellaama']);
});

test('search_prefixes hold every term the app derives from the shared query vectors', () => {
  // Terms copied from ProductRepositoryFirestoreTest.productSearchTerms_normalizeFrenchAccentsLigaturesAndPunctuation.
  const vectors = [
    ['  Crème BRÛLÉE ', ['creme', 'brulee']],
    ['Œufs plein-air', ['oeufs', 'plein', 'air']],
    ["l'eau minérale, 1 x 5l", ['eau', 'minerale', '5l']],
    ['Huile d’hiver', ['huile', 'hiver']],
    ['Straciatellaamaretto', ['straciatellaama']],
  ];
  for (const [name, terms] of vectors) {
    const prefixes = searchPrefixes(name);
    assert.deepEqual(terms.filter((term) => prefixes[term] !== true), [], name);
  }
  assert.equal(searchPrefixes("l'eau").l, undefined);
});

test('toProduct maps an export row to the app schema with a CHF price and a stable id', () => {
  const product = toProduct(row('100100700000', { name: ' Crème ', price: 2.5, image_url: ' https://image.migros.ch/c.jpg ', category: null }), 'migros');
  assert.deepEqual(product, {
    path: 'stores/migros/products/100100700000',
    removedAt: null,
    data: {
      id: 'migros:100100700000',
      name: 'Crème',
      price: 2.5,
      image_url: 'https://image.migros.ch/c.jpg',
      removed_at: null,
      search_prefixes: { cr: true, cre: true, crem: true, creme: true },
      size_text: null,
      source_name: 'Crème',
      brand: null,
      variant: null,
      category: null,
      category_path: null,
    },
  });
  assert.equal(toProduct(row('1', { price: 3, image_url: '' }), 'migros').data.price, 3);
  assert.equal(toProduct(row('1', { image_url: '  ' }), 'migros').data.image_url, null);
  assert.equal(toProduct(row('1', { price: 0.1 + 0.2 }), 'migros').data.price, 0.3);
  // Boundaries of the app's Int cents: CHF 0.01 and Int.MAX_VALUE cents.
  assert.equal(toProduct(row('1', { price: 0.01 }), 'migros').data.price, 0.01);
  assert.equal(toProduct(row('1', { price: 21_474_836.47 }), 'migros').data.price, 21_474_836.47);
});

test('categories keep a broad label and the cleaned full source path for both stores', () => {
  const migros = toProduct(row('1', { category: ' Food  >  Dairy > Milk ' }), 'migros').data;
  const coop = toProduct(coopRow({ category: ' Avocados ' }), 'coop').data;
  const missing = toProduct(row('2', { category: ' > ' }), 'migros').data;
  assert.deepEqual([migros.category, migros.category_path], ['Food', 'Food > Dairy > Milk']);
  assert.deepEqual([coop.category, coop.category_path], ['Avocados', 'Avocados']);
  assert.deepEqual([missing.category, missing.category_path], [null, null]);
});

test('a later export without category keeps the stored taxonomy and its search prefixes', async () => {
  const client = fakeClient();
  const categorized = exportOf(row('1', { name: 'Nature', category: 'Food > Yogourts nature' }));
  await importProducts(categorized, { client, write: true, log: () => {} });
  const withoutCategory = exportOf(row('1', { name: 'Nature', category: null }));
  const rerun = await importProducts(withoutCategory, { client, write: true, log: () => {} });
  const data = client.documents.get(path('1')).data;
  assert.deepEqual([rerun.counts.unchanged, rerun.written], [1, 0]);
  assert.deepEqual([data.category, data.category_path, data.search_prefixes.yogourts], [
    'Food', 'Food > Yogourts nature', true,
  ]);
});

test('displayName writes brand, name and variant once each and never drops a different word', () => {
  const cases = [
    ['Noxana', 'Frey', null, 'Frey Noxana'], ['Lait', ' M-Budget ', null, 'M-Budget Lait'], ['Frey Noir', 'Frey', null, 'Frey Noir'],
    ['Lait M-Budget', 'm budget', null, 'Lait M-Budget'], ['Freyja Tea', 'Frey', null, 'Frey Freyja Tea'],
    ['Sel de table', 'Jura Sel', null, 'Jura Sel de table'], ['Féta', 'Xenia Feta', null, 'Xenia Feta'],
    ['Läckerlis de Bâle Piccolo', 'Läckerli Huus', null, 'Läckerli Huus Läckerlis de Bâle Piccolo'],
    ['Tourist', 'Frey', ' Lait ', 'Frey Tourist Lait'], ['Crémant 55%', 'Frey', 'crémant', 'Frey Crémant 55%'],
    ['Emmental', null, 'doux', 'Emmental doux'], ['Tresse', null, null, 'Tresse'], ['Tresse', ' ', ' ', 'Tresse'],
    ['Lait  M-Drink', null, null, 'Lait M-Drink'], ['Ice Tea ·', 'Vivi', null, 'Vivi Ice Tea'],
  ];
  for (const [name, brand, variant, expected] of cases) assert.equal(displayName(name, brand, variant), expected);
});

test('toProduct keeps the source name, brand, variant and size apart and indexes the display name', () => {
  const product = toProduct(row('100125000000', { name: ' Tourist ', brand: 'Frey', variant: 'Lait', size_text: ' 100g ', category: 'Chocolat > Blanc' }), 'migros');
  const { name, source_name, brand, variant, size_text, search_prefixes } = product.data;
  assert.deepEqual([name, source_name, brand, variant, size_text], ['Frey Tourist Lait', 'Tourist', 'Frey', 'Lait', '100g']);
  assert.deepEqual(Object.keys(search_prefixes), ['bl', 'bla', 'blan', 'blanc', 'fr', 'fre', 'frey', 'la', 'lai', 'lait', 'to', 'tou', 'tour', 'touri', 'touris', 'tourist']);
  assert.deepEqual([toProduct(row('1', { size_text: '  ', variant: ' ' }), 'migros').data.size_text, toProduct(row('1'), 'migros').data.variant], [null, null]);
  assert.equal(toProduct(row('1', { size_text: 'Packungsgrösse\n60ml' }), 'migros').data.size_text, 'Packungsgrösse 60ml');
  for (const field of ['brand', 'variant', 'size_text']) assert.deepEqual(toProduct(row('1', { [field]: 7 }), 'migros'), { error: `invalid ${field}` });
});

test('parseExport takes missing sizes from a manifest by product ID, never over the export', () => {
  const manifest = [{ version: 4 }, ...[' 1 l ', '500 g', null].map((size, i) => ({ data: { id: `migros:${i + 1}`, size_text: size } }))];
  const sizes = parseSizes(`${manifest.map((line) => JSON.stringify(line)).join('\n')}\n`);
  assert.deepEqual([...sizes], [['migros:1', '1 l'], ['migros:2', '500 g']]);
  const lines = [row('1'), row('2', { size_text: '6 x 500 g' }), row('3')].map((item) => JSON.stringify(item)).join('\n');
  const { products } = parseExport(lines, 'migros', sizes);
  assert.deepEqual(products.map((product) => product.data.size_text), ['1 l', '6 x 500 g', null]);
});

test('blank size_text falls back to the export size and then the optional manifest', () => {
  const lines = [
    row('1', { size_text: '  ', size: '100 g' }),
    row('2', { size_text: '\t' }),
  ].map((item) => JSON.stringify(item)).join('\n');
  const sizes = new Map([['migros:1', '200 g'], ['migros:2', '500 g']]);
  const { products } = parseExport(lines, 'migros', sizes);
  assert.deepEqual(products.map(({ data }) => data.size_text), ['100 g', '500 g']);
});

test('a Migros size that only repeats the variant counts as missing, as swiss-cli exports it without a quantity', () => {
  // Cases of swiss-cli's test/unit/migros-metadata.test.ts ("preserves the legacy size fallback").
  const lines = [
    row('1', { name: 'Emmental', variant: 'doux', size: 'doux' }),
    row('2', { name: 'Emmental', variant: ' Doux ', size: 'doux' }),
    row('3', { name: 'Tourist', brand: 'Frey', variant: 'Lait', size: '100g' }),
    row('4', { name: 'Pastilles', variant: null, size: '175' }),
  ].map((item) => JSON.stringify(item)).join('\n');
  const parsed = (sizes) => parseExport(lines, 'migros', sizes).products.map(({ data }) => [data.name, data.size_text]);
  assert.deepEqual(parsed(new Map()), [['Emmental doux', null], ['Emmental Doux', null], ['Frey Tourist Lait', '100g'], ['Pastilles', '175']]);
  const sizes = new Map([['migros:1', '1 kg'], ['migros:3', '200 g']]);
  assert.deepEqual(parsed(sizes).map(([, size]) => size), ['1 kg', null, '100g', '175']);
});

test('a brand, size or variant change is an update and a rerun of the corrected export writes nothing', async () => {
  const client = fakeClient();
  await importProducts(exportOf(row('1', { name: 'Lait' })), { client, write: true, log: () => {} });
  for (const extra of [{ brand: 'M-Budget', size_text: '100g' }, { brand: 'M-Budget', size_text: '100g', variant: 'noisettes entières' }]) {
    const corrected = exportOf(row('1', { name: 'Lait', ...extra }));
    const fix = await importProducts(corrected, { client, write: true, log: () => {} });
    assert.deepEqual([fix.counts.update, fix.written], [1, 1]);
    const rerun = await importProducts(corrected, { client, write: true, log: () => {} });
    assert.deepEqual([rerun.counts.unchanged, rerun.written], [1, 0]);
  }
  const { name, size_text, variant, search_prefixes } = client.documents.get(path('1')).data;
  assert.deepEqual([name, size_text, variant, search_prefixes.budget, search_prefixes.entieres], ['M-Budget Lait noisettes entières', '100g', 'noisettes entières', true, true]);
});

test('a renamed product gets the prefixes of its new name only, and a rerun without sizes keeps them', async () => {
  const client = fakeClient();
  const sizes = parseSizes(`${JSON.stringify({ data: { id: 'migros:1', size_text: '300 g' } })}\n`);
  await importProducts(parseExport(JSON.stringify(row('1', { name: 'Tourist', brand: 'Frey' })), 'migros', sizes).products, { client, write: true, log: () => {} });
  const renamed = parseExport(JSON.stringify(row('1', { name: 'Noxana', brand: 'Frey' })), 'migros', sizes).products;
  assert.equal((await importProducts(renamed, { client, write: true, log: () => {} })).counts.update, 1);
  const { name, size_text, search_prefixes } = client.documents.get(path('1')).data;
  assert.deepEqual([name, size_text], ['Frey Noxana', '300 g']);
  assert.deepEqual(Object.keys(search_prefixes).filter((prefix) => prefix.startsWith('to')), []);
  assert.equal(search_prefixes.noxana, true);
  const withoutSizes = parseExport(JSON.stringify(row('1', { name: 'Noxana', brand: 'Frey' })), 'migros').products;
  const rerun = await importProducts(withoutSizes, { client, write: true, log: () => {} });
  assert.deepEqual([rerun.counts.unchanged, rerun.written], [1, 0]);
  assert.equal(client.documents.get(path('1')).data.size_text, '300 g');
});

test('parseExport skips invalid and duplicate lines with their line numbers', () => {
  const lines = [
    row('1'),
    row('2', { store: 'coop', id: 'coop:2' }),
    row('a/b'),
    row('__x__'),
    row('3', { name: '  ' }),
    row('4', { price: 0 }),
    row('5', { price: 1.955 }),
    row('6', { price: '2.50' }),
    row('7', { image_url: 'ftp://x' }),
    row('8', { removed_at: 'yesterday' }),
    row('1', { name: 'Again' }),
  ].map((item) => JSON.stringify(item));
  const { products, skipped } = parseExport([...lines, '{oops', ''].join('\n'), 'migros');
  assert.deepEqual(products.map((product) => product.data.id), ['migros:1']);
  assert.deepEqual(skipped, [
    'line 2: not a migros product', 'line 3: invalid id', 'line 4: invalid id', 'line 5: blank name', 'line 6: invalid price',
    'line 7: invalid price', 'line 8: invalid price', 'line 9: invalid image_url', 'line 10: invalid removed_at',
    'line 11: duplicate of migros:1', 'line 12: not JSON',
  ]);
});

test('reruns update by stable id, skip unchanged products and keep other documents and fields', async () => {
  const client = fakeClient(new Map([
    [path('9'), { data: { id: 'migros:9', name: 'Not in this export', removed_at: null }, updateTime: 'old' }],
    [path('2'), { data: { id: 'migros:2', name: 'Pain', price: 2, price_history: [1.9] }, updateTime: 'old' }],
  ]));
  const products = exportOf(row('1'), row('2', { name: 'Pain', price: 2.2 }));
  assert.deepEqual((await importProducts(products, { client, log: () => {} })).counts.create, 1);
  assert.equal(client.commits.length, 0, 'a dry run writes nothing');

  const first = await importProducts(products, { client, write: true, log: () => {} });
  assert.deepEqual([first.counts.create, first.counts.update, first.written], [1, 1, 2]);
  assert.equal(client.documents.get(path('2')).data.price, 2.2);
  assert.deepEqual(client.documents.get(path('2')).data.price_history, [1.9]);
  assert.equal(client.documents.get(path('9')).updateTime, 'old');

  const rerun = await importProducts(products, { client, write: true, log: () => {} });
  assert.deepEqual([rerun.counts.unchanged, rerun.written, client.documents.size], [2, 0, 3]);
});

test('delisted products are only marked removed when active in Firestore, and relisted ones come back', async () => {
  const client = fakeClient();
  await importProducts(exportOf(row('1')), { client, write: true, log: () => {} });
  const removal = await importProducts(exportOf(row('1', { removed_at: REMOVED_AT }), row('2', { removed_at: REMOVED_AT })), { client, write: true, log: () => {} });
  assert.deepEqual([removal.counts.remove, removal.counts.removedNotInFirestore, removal.written], [1, 1, 1]);
  assert.equal(client.documents.get(path('1')).data.removed_at, STORED_REMOVED_AT);
  assert.equal(client.documents.get(path('1')).data.name, 'Lait entier');
  assert.equal(client.documents.has(path('2')), false);
  assert.equal((await importProducts(exportOf(row('1', { removed_at: REMOVED_AT })), { client, write: true, log: () => {} })).written, 0);
  await importProducts(exportOf(row('1')), { client, write: true, log: () => {} });
  assert.equal(client.documents.get(path('1')).data.removed_at, null);
});

test('delisted rows need only an id and removed_at, while listed rows still need a valid price', () => {
  // Below one cent, CHF 0.00, a fraction of a cent, or above Int.MAX_VALUE cents.
  for (const price of [undefined, null, 0, -1, '2.50', 1.955, 0.000000001, 0.004, 0.001, 21_474_836.48, 1e300, Infinity, NaN]) {
    const delisted = row('1', { price, name: null, image_url: 'ftp://x', category: 3, removed_at: REMOVED_AT });
    assert.deepEqual(toProduct(delisted, 'migros'), { path: path('1'), data: null, removedAt: REMOVED_AT }, `delisted, price ${price}`);
    assert.deepEqual(toProduct(row('1', { price }), 'migros'), { error: 'invalid price' }, `listed, price ${price}`);
  }
  // removed_at must be a timestamp string as toISOString() writes it, not anything Date.parse takes.
  const malformed = [0, 1, true, false, '', '0', '2026', '2026-10-20', 'yesterday', 'Tue Oct 20 2026', '2026-10-20 08:00:00Z',
    '2026-02-30T08:00:00Z', '2026-13-01T08:00:00Z', [], {}];
  for (const removed of malformed) {
    assert.deepEqual(toProduct(row('1', { price: null, removed_at: removed }), 'migros'), { error: 'invalid removed_at' }, `removed_at ${JSON.stringify(removed)}`);
  }
  for (const removed of ['2026-10-20T08:00:00.000Z', '2026-10-20T10:00:00+02:00']) {
    assert.deepEqual(toProduct(row('1', { price: null, removed_at: removed }), 'migros'), { path: path('1'), data: null, removedAt: removed });
  }
  const lines = [
    { id: 'migros:2', store: 'migros', removed_at: REMOVED_AT },
    { id: 'migros:3', store: 'migros', name: 'Pain' },
    { id: 'migros:4', store: 'migros', name: 'Pain', price: 2, removed_at: 0 },
  ];
  const { products, skipped } = parseExport(lines.map((item) => JSON.stringify(item)).join('\n'), 'migros');
  assert.deepEqual([products.map((product) => product.path), skipped], [[path('2')], ['line 2: invalid price', 'line 3: invalid removed_at']]);
});

test('a delisted product without a price is marked removed, keeps its other fields and reruns without writes', async () => {
  const listed = { ...toProduct(row('1'), 'migros').data, price_history: [1.9] };
  const client = fakeClient(new Map([[path('1'), { data: structuredClone(listed), updateTime: 'old' }]]));
  const products = exportOf(
    row('1', { price: undefined, removed_at: REMOVED_AT }),
    row('2', { price: null, removed_at: REMOVED_AT }),
    row('3', { price: 'n/a', removed_at: REMOVED_AT }),
  );
  const removal = await importProducts(products, { client, write: true, log: () => {} });
  assert.deepEqual(removal.counts, { create: 0, update: 0, remove: 1, unchanged: 0, removedNotInFirestore: 2 });
  assert.equal(removal.written, 1);
  assert.deepEqual(client.documents.get(path('1')).data, { ...listed, removed_at: STORED_REMOVED_AT });
  assert.deepEqual([...client.documents.keys()], [path('1')], 'a delisted product is never created');

  const updateTime = client.documents.get(path('1')).updateTime;
  const rerun = await importProducts(products, { client, write: true, log: () => {} });
  assert.deepEqual([rerun.counts.unchanged, rerun.written, client.commits.length], [1, 0, 1]);
  assert.equal(client.documents.get(path('1')).updateTime, updateTime);
});

test('writes go in batches of at most 400 and stop at --max-writes', async () => {
  const products = exportOf(...Array.from({ length: 900 }, (_, index) => row(String(index))));
  const client = fakeClient();
  assert.equal((await importProducts(products, { client, write: true, maxWrites: 500, log: () => {} })).written, 500);
  assert.deepEqual(client.commits, [400, 100]);
  assert.equal((await importProducts(products, { client, write: true, log: () => {} })).written, 400);
  assert.deepEqual(client.commits, [400, 100, 400]);
});

test('Coop rows map to stores/coop with coop: IDs and the same search prefixes as Migros', () => {
  const { path: coopPath, data } = toProduct(coopRow(), 'coop');
  assert.equal(coopPath, 'stores/coop/products/3465933');
  assert.deepEqual([data.id, data.price, data.image_url, data.removed_at], ['coop:3465933', 1.95, coopRow().image_url, null]);
  assert.ok(['naturaplan', 'bio', 'avocado', 'avocados', 'stuck'].every((term) => data.search_prefixes[term]));
  const same = { name: 'Bœuf haché Straße', category: 'Viande > Æbleskiver' };
  assert.deepEqual(toProduct(coopRow(same), 'coop').data.search_prefixes, toProduct(row('1', same), 'migros').data.search_prefixes);

  const lines = [coopRow(), row('2'), coopRow({ id: 'migros:5' }), coopRow({ store: 'aldi', id: 'aldi:6' })].map((item) => JSON.stringify(item));
  assert.deepEqual(parseExport(lines.join('\n'), 'coop').skipped, ['line 2: not a coop product', 'line 3: invalid id', 'line 4: not a coop product']);
  assert.throws(() => parseExport(lines.join('\n'), 'aldi'), /Unsupported store aldi/);
});

test('a Coop import never touches Migros documents and reruns without writes', async () => {
  const migros = { data: { ...toProduct(row('3465933'), 'migros').data }, updateTime: 'old' };
  const client = fakeClient(new Map([[path('3465933'), migros]]));
  const products = exportOf(coopRow());
  assert.equal((await importProducts(products, { client, write: true, log: () => {} })).written, 1);
  assert.deepEqual([...client.documents.keys()].sort(), [path('3465933', 'coop'), path('3465933')]);
  assert.equal(client.documents.get(path('3465933')).updateTime, 'old');
  assert.equal(client.documents.get(path('3465933', 'coop')).data.id, 'coop:3465933');
  assert.equal((await importProducts(products, { client, write: true, log: () => {} })).written, 0);
});

// Lines of `swiss-grocery export coop.jsonl --store coop --fields id,store,name,brand,size,price,image_url,category,removed_at
// --include-removed` (SWISS_GROCERY_COOP_LANG=fr, 4 November 2026); image URLs are shortened to their product.
const coopLine = (number, name, brand, size, price, category, image = '001') =>
  coopRow({ id: `coop:${number}`, name, brand, size, price, category, image_url: `https://www.coop.ch/img/produkte/310_310/RGB/${number}_${image}.jpg` });
const coopExport = [
  coopLine('6506829', '1664 Blonde Bière 12x25cl', 'Kronenbourg', '12x25cl', 11.95, 'Bières en multipacks'),
  coopLine('3376082', 'Acqua Eau minérale Panna non gazéifiée 6x1.5l', 'Acqua Panna', '6x1.5l', 5.95, 'Eau minérale plate'),
  coopLine('6097874', '2go Drinks énergétiques 6x25cl', '2 go', '6x25cl', 6, 'Boissons énergétiques'),
  coopLine('7251132', '9.0 Original canette 3x  50cl', 'Oettinger', '3x  50cl', 4.05, 'Bières blondes en canette', '900'),
  coopLine('4826443', 'Ail frais 1 pièce', 'Coop', '1 pce', 1.3, 'Oignons & Ail'),
  // Two vintages of one wine: one name, two products. Wines have no brand, and some no price.
  coopLine('1014349008', '19 Crimes Cabernet Sauvignon/Syrah South Eastern Australia', null, '75 cl', 7.95, 'Vins rouges'),
  coopLine('1014349009', '19 Crimes Cabernet Sauvignon/Syrah South Eastern Australia', null, '75 cl', 7.95, 'Vins rouges'),
  coopLine('1019605001', 'Alava DOC Tempranillo El Carretil Bodegas y Viñedos Artadi', null, '75 cl', null, 'Vins rouges'),
];

test('Coop names keep the title Coop shows and sizes come from the size column of swiss-cli', () => {
  const { products, skipped } = parseExport(coopExport.map((item) => JSON.stringify(item)).join('\n'), 'coop');
  assert.deepEqual(skipped, ['line 8: invalid price']);
  assert.deepEqual(products.map(({ data }) => [data.name, data.size_text, data.brand]), [
    ['1664 Blonde Bière 12x25cl', '12x25cl', 'Kronenbourg'],
    ['Acqua Eau minérale Panna non gazéifiée 6x1.5l', '6x1.5l', 'Acqua Panna'],
    ['2go Drinks énergétiques 6x25cl', '6x25cl', '2 go'],
    ['9.0 Original canette 3x 50cl', '3x 50cl', 'Oettinger'],
    ['Ail frais 1 pièce', '1 pce', 'Coop'],
    ['19 Crimes Cabernet Sauvignon/Syrah South Eastern Australia', '75 cl', null],
    ['19 Crimes Cabernet Sauvignon/Syrah South Eastern Australia', '75 cl', null],
  ]);
  const [beer] = products;
  assert.deepEqual([beer.path, beer.data.id, beer.data.source_name, beer.data.variant], [path('6506829', 'coop'), 'coop:6506829', '1664 Blonde Bière 12x25cl', null]);
  assert.ok(['16', '1664', 'blonde', 'biere', 'bieres', '12x25cl', 'multipacks'].every((term) => beer.data.search_prefixes[term]));
  assert.equal(beer.data.search_prefixes.kronenbourg, undefined, 'like Migros, only the name and the last category are indexed');
  // Migros keeps its brand, name and variant order, and also reads swiss-cli's size column.
  const migros = toProduct(row('1', { name: 'Tourist', brand: 'Frey', size: '100 g' }), 'migros').data;
  assert.deepEqual([migros.name, migros.size_text], ['Frey Tourist', '100 g']);
  assert.equal(toProduct(row('1', { size: '100 g', size_text: '0.1 kg' }), 'migros').data.size_text, '0.1 kg');
  assert.deepEqual(toProduct(coopRow({ size: 7 }), 'coop'), { error: 'invalid size' });
  assert.deepEqual(parseExport(JSON.stringify(coopRow({ size: 7 })), 'coop').skipped, ['line 1: invalid size']);
  assert.deepEqual(toProduct(coopRow({ store: 'aldi' }), 'aldi'), { error: 'not a aldi product' });
});

test('a Coop export is imported once, reruns write nothing and a confirmed delisting only sets removed_at', async () => {
  const client = fakeClient();
  const products = parseExport(coopExport.map((item) => JSON.stringify(item)).join('\n'), 'coop').products;
  assert.deepEqual([(await importProducts(products, { client, log: () => {} })).counts.create, client.commits.length], [7, 0]);
  assert.equal((await importProducts(products, { client, write: true, log: () => {} })).written, 7);
  assert.ok([...client.documents.keys()].every((key) => key.startsWith('stores/coop/products/')));
  assert.equal((await importProducts(products, { client, write: true, log: () => {} })).written, 0);
  const delisted = exportOf(coopRow({ id: 'coop:6506829', price: null, removed_at: REMOVED_AT }));
  assert.equal((await importProducts(delisted, { client, write: true, log: () => {} })).counts.remove, 1);
  assert.deepEqual([client.documents.get(path('6506829', 'coop')).data.removed_at, client.documents.get(path('6506829', 'coop')).data.price], [STORED_REMOVED_AT, 11.95]);
});

test('parseArgs reads only its arguments: the same arguments give the same options in any environment', () => {
  const saved = process.env.FIRESTORE_EMULATOR_HOST;
  const args = ['export.jsonl', '--store', 'migros', '--write', '--project', 'command-o', '--sizes', 'manifest.jsonl'];
  try {
    const options = [undefined, '127.0.0.1:8080'].map((host) => {
      if (host) process.env.FIRESTORE_EMULATOR_HOST = host;
      else delete process.env.FIRESTORE_EMULATOR_HOST;
      return parseArgs(args);
    });
    assert.deepEqual(options[0], options[1]);
    assert.deepEqual([options[0].write, options[0].project, options[0].sizes], [true, 'command-o', 'manifest.jsonl']);
  } finally {
    if (saved === undefined) delete process.env.FIRESTORE_EMULATOR_HOST;
    else process.env.FIRESTORE_EMULATOR_HOST = saved;
  }
  assert.equal(parseArgs(['export.jsonl', '--store', 'migros']).write, false);
  assert.throws(() => parseArgs(['export.jsonl', '--store', 'migros', '--write', '--project', 'other']), /only targets command-o/);
  assert.throws(() => parseArgs(['export.jsonl', '--store', 'migros', '--max-writes', '0']), /--max-writes/);
  assert.throws(() => parseArgs([]), /Usage/);
  assert.throws(() => parseArgs(['export.jsonl']), /--store must be migros or coop/);
  assert.throws(() => parseArgs(['export.jsonl', '--store', 'denner']), /--store must be migros or coop/);
  assert.equal(parseArgs(['export.jsonl', '--store', 'coop']).store, 'coop');
});

test('--sizes rejects a missing, flag-like or empty manifest filename', () => {
  for (const args of [
    ['export.jsonl', '--store', 'migros', '--sizes'],
    ['export.jsonl', '--store', 'migros', '--sizes', '--write'],
    ['export.jsonl', '--store', 'migros', '--sizes', ''],
  ]) {
    assert.throws(() => parseArgs(args), /--sizes requires a value/);
  }
});

test('the Firestore target is chosen explicitly: never production by default, never two at once', async () => {
  const local = { FIRESTORE_EMULATOR_HOST: ' 127.0.0.1:8080 ' };
  assert.deepEqual(firestoreTarget(null, local), { emulator: '127.0.0.1:8080' });
  assert.deepEqual(firestoreTarget('command-o', {}), { project: 'command-o' });
  assert.throws(() => firestoreTarget(null, {}), /No Firestore target/);
  assert.throws(() => firestoreTarget(null, { FIRESTORE_EMULATOR_HOST: '  ' }), /No Firestore target/);
  assert.throws(() => firestoreTarget('command-o', local), /choose one target/);
  assert.throws(() => firestoreTarget(null, { FIRESTORE_EMULATOR_HOST: 'firestore.googleapis.com:443' }), /must be a local host:port/);
  // firestoreClient refuses before any request or credential lookup.
  for (const target of [undefined, null, {}, { emulator: '' }, { project: 'other' }]) {
    await assert.rejects(firestoreClient(target), /firestoreClient needs/, JSON.stringify(target));
  }
  await assert.rejects(firestoreClient({ emulator: 'example.com:8080' }), /must be a local host:port/);
});

test('firebase-tools must be the pinned release CI uses, whose login modules the tool reads', () => {
  const root = mkdtempSync(join(tmpdir(), 'firebase-tools-'));
  try {
    assert.throws(() => firebaseTools(root), /npm install --prefix tools\/catalog-import\/tmp\/firebase-tools firebase-tools@15\.32\.1.*FIREBASE_TOOLS_ROOT/);
    mkdirSync(join(root, 'firebase-tools'));
    const install = (version) => writeFileSync(join(root, 'firebase-tools', 'package.json'), JSON.stringify({ name: 'firebase-tools', version }));
    install('15.30.2');
    assert.throws(() => firebaseTools(root), /firebase-tools 15\.30\.2 .* needs 15\.32\.1/);
    install(FIREBASE_TOOLS_VERSION);
    assert.equal(typeof firebaseTools(root), 'function');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
  const ci = readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url), 'utf8');
  assert.equal(/FIREBASE_TOOLS_VERSION: "([^"]+)"/.exec(ci)?.[1], FIREBASE_TOOLS_VERSION);
});

test('skippedReport shows the first skipped lines and counts the rest', () => {
  const skipped = Array.from({ length: 23 }, (_, i) => `line ${i + 1}: invalid price`);
  const report = skippedReport(skipped);
  assert.deepEqual([report.length, report[0], report.at(-2), report.at(-1)], [21, 'Skipped line 1: invalid price', 'Skipped line 20: invalid price', '... and 3 more skipped lines']);
  assert.deepEqual(skippedReport(skipped.slice(0, 20)).length, 20);
  assert.deepEqual(skippedReport([]), []);
  assert.deepEqual(skippedReport(skipped, 1), ['Skipped line 1: invalid price', '... and 22 more skipped lines']);
});

test('the update mask and the unchanged check both follow the fields toProduct builds', () => {
  const product = toProduct(row('1', { size_text: '1 l' }), 'migros');
  const { writes } = planWrites([product], new Map());
  assert.deepEqual(writes[0].updateMask.fieldPaths, Object.keys(product.data));
  // A field added to the product shape is written and compared with nothing else to update.
  const stored = new Map([[product.path, { data: product.data, updateTime: 't1' }]]);
  const extended = { ...product, data: { ...product.data, unit_price: 1.95 } };
  const plan = planWrites([extended], stored);
  assert.deepEqual([plan.counts.update, plan.writes[0].updateMask.fieldPaths.at(-1)], [1, 'unit_price']);
  assert.equal(planWrites([product], stored).counts.unchanged, 1);
});

// CI sets CATALOG_IMPORT_REQUIRE_EMULATOR, so a missing emulator fails the run instead of skipping it.
const requireEmulator = process.env.CATALOG_IMPORT_REQUIRE_EMULATOR === 'true';
const emulatorSkip = !requireEmulator && !process.env.FIRESTORE_EMULATOR_HOST && 'needs FIRESTORE_EMULATOR_HOST';

test('imports into the Firestore emulator, marks a delisted product removed and reruns without writes', { skip: emulatorSkip }, async () => {
  const host = emulatorHost();
  assert.ok(host, 'FIRESTORE_EMULATOR_HOST is not set: run this test with the Firestore emulator (see README.md)');
  const client = await firestoreClient(firestoreTarget(null, process.env));
  assert.equal(client.target, `emulator ${host}`);
  const number = `test-${Date.now()}`;
  const products = exportOf(row(number, { name: 'Œufs frais', brand: 'Migros Bio', size_text: '6 pièces', price: 4.6 }));
  assert.equal(products[0].data.name, 'Migros Bio Œufs frais');
  assert.equal((await importProducts(products, { client, write: true, log: () => {} })).written, 1);
  const [[, document]] = await client.get([path(number)]);
  assert.deepEqual(document.data, { ...products[0].data, price: 4.6 });
  assert.equal((await importProducts(products, { client, write: true, log: () => {} })).written, 0);

  // A rename replaces the whole search_prefixes map in Firestore, not only the new keys.
  const renamed = exportOf(row(number, { name: 'Lait entier', brand: 'Migros Bio', size_text: '6 pièces', price: 4.6 }));
  assert.equal((await importProducts(renamed, { client, write: true, log: () => {} })).written, 1);
  const [[, renamedDocument]] = await client.get([path(number)]);
  assert.deepEqual(renamedDocument.data.search_prefixes, renamed[0].data.search_prefixes);
  assert.equal(renamedDocument.data.search_prefixes.oeufs, undefined);
  await importProducts(products, { client, write: true, log: () => {} });

  const removal = exportOf(row(number, { price: null, removed_at: REMOVED_AT }));
  assert.equal((await importProducts(removal, { client, write: true, log: () => {} })).written, 1);
  const [[, removed]] = await client.get([path(number)]);
  assert.deepEqual({ ...removed.data, removed_at: Date.parse(removed.data.removed_at) }, { ...document.data, removed_at: Date.parse(REMOVED_AT) });
  assert.equal((await importProducts(removal, { client, write: true, log: () => {} })).written, 0);

  // The same product number at Coop is a separate document, and the Migros one is left as it is.
  const coop = exportOf(coopLine(number, '1664 Blonde Bière 12x25cl', 'Kronenbourg', '12x25cl', 11.95, 'Bières en multipacks'));
  assert.equal((await importProducts(coop, { client, write: true, log: () => {} })).written, 1);
  const [[, coopDocument]] = await client.get([path(number, 'coop')]);
  assert.deepEqual(coopDocument.data, coop[0].data);
  assert.equal((await client.get([path(number)]))[0][1].updateTime, removed.updateTime);
  assert.equal((await importProducts(coop, { client, write: true, log: () => {} })).written, 0);
});
