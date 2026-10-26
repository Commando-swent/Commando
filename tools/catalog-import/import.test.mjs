// AI assistance: Claude Code. Run with: node --test tools/catalog-import/import.test.mjs
import assert from 'node:assert/strict';
import test from 'node:test';
import { emulatorHost, firestoreClient, importProducts, parseArgs, parseExport, searchPrefixes, toProduct } from './import.mjs';

const row = (number, overrides = {}) =>
  ({ id: `migros:${number}`, store: 'migros', name: 'Lait entier', price: 1.95, image_url: null, category: 'Lait > Lait UHT', ...overrides });
const exportOf = (...rows) => parseExport(rows.map((item) => JSON.stringify(item)).join('\n'), rows[0]?.store ?? 'migros').products;
const path = (number, store = 'migros') => `stores/${store}/products/${number}`;
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
    },
  });
  assert.equal(toProduct(row('1', { price: 3, image_url: '' }), 'migros').data.price, 3);
  assert.equal(toProduct(row('1', { image_url: '  ' }), 'migros').data.image_url, null);
  assert.equal(toProduct(row('1', { price: 0.1 + 0.2 }), 'migros').data.price, 0.3);
  // Boundaries of the app's Int cents: CHF 0.01 and Int.MAX_VALUE cents.
  assert.equal(toProduct(row('1', { price: 0.01 }), 'migros').data.price, 0.01);
  assert.equal(toProduct(row('1', { price: 21_474_836.47 }), 'migros').data.price, 21_474_836.47);
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
  const removedAt = '2026-10-20T08:00:00Z';
  const removal = await importProducts(exportOf(row('1', { removed_at: removedAt }), row('2', { removed_at: removedAt })), { client, write: true, log: () => {} });
  assert.deepEqual([removal.counts.remove, removal.counts.removedNotInFirestore, removal.written], [1, 1, 1]);
  assert.equal(client.documents.get(path('1')).data.removed_at, '2026-10-20T08:00:00.000Z');
  assert.equal(client.documents.get(path('1')).data.name, 'Lait entier');
  assert.equal(client.documents.has(path('2')), false);
  assert.equal((await importProducts(exportOf(row('1', { removed_at: removedAt })), { client, write: true, log: () => {} })).written, 0);
  await importProducts(exportOf(row('1')), { client, write: true, log: () => {} });
  assert.equal(client.documents.get(path('1')).data.removed_at, null);
});

test('delisted rows need only an id and removed_at, while listed rows still need a valid price', () => {
  const removedAt = '2026-10-20T08:00:00Z';
  // Below one cent, CHF 0.00, a fraction of a cent, or above Int.MAX_VALUE cents.
  for (const price of [undefined, null, 0, -1, '2.50', 1.955, 0.000000001, 0.004, 0.001, 21_474_836.48, 1e300, Infinity, NaN]) {
    const delisted = row('1', { price, name: null, image_url: 'ftp://x', category: 3, removed_at: removedAt });
    assert.deepEqual(toProduct(delisted, 'migros'), { path: path('1'), data: null, removedAt }, `delisted, price ${price}`);
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
    { id: 'migros:2', store: 'migros', removed_at: removedAt },
    { id: 'migros:3', store: 'migros', name: 'Pain' },
    { id: 'migros:4', store: 'migros', name: 'Pain', price: 2, removed_at: 0 },
  ];
  const { products, skipped } = parseExport(lines.map((item) => JSON.stringify(item)).join('\n'), 'migros');
  assert.deepEqual([products.map((product) => product.path), skipped], [[path('2')], ['line 2: invalid price', 'line 3: invalid removed_at']]);
});

test('a delisted product without a price is marked removed, keeps its other fields and reruns without writes', async () => {
  const listed = { ...toProduct(row('1'), 'migros').data, price_history: [1.9] };
  const client = fakeClient(new Map([[path('1'), { data: structuredClone(listed), updateTime: 'old' }]]));
  const removedAt = '2026-10-20T08:00:00Z';
  const products = exportOf(
    row('1', { price: undefined, removed_at: removedAt }),
    row('2', { price: null, removed_at: removedAt }),
    row('3', { price: 'n/a', removed_at: removedAt }),
  );
  const removal = await importProducts(products, { client, write: true, log: () => {} });
  assert.deepEqual(removal.counts, { create: 0, update: 0, remove: 1, unchanged: 0, removedNotInFirestore: 2 });
  assert.equal(removal.written, 1);
  assert.deepEqual(client.documents.get(path('1')).data, { ...listed, removed_at: '2026-10-20T08:00:00.000Z' });
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

test('parseArgs is a dry run unless --write, and production writes need --project command-o', () => {
  const env = process.env.FIRESTORE_EMULATOR_HOST;
  delete process.env.FIRESTORE_EMULATOR_HOST;
  try {
    assert.equal(parseArgs(['export.jsonl', '--store', 'migros']).write, false);
    assert.throws(() => parseArgs(['export.jsonl', '--store', 'migros', '--write']), /Production write blocked/);
    assert.throws(() => parseArgs(['export.jsonl', '--store', 'migros', '--write', '--project', 'other']), /only targets command-o/);
    assert.equal(parseArgs(['export.jsonl', '--store', 'migros', '--write', '--project', 'command-o']).write, true);
    assert.throws(() => parseArgs(['export.jsonl', '--store', 'migros', '--max-writes', '0']), /--max-writes/);
    assert.throws(() => parseArgs([]), /Usage/);
    assert.throws(() => parseArgs(['export.jsonl']), /--store must be migros or coop/);
    assert.throws(() => parseArgs(['export.jsonl', '--store', 'denner']), /--store must be migros or coop/);
    assert.equal(parseArgs(['export.jsonl', '--store', 'coop']).store, 'coop');
  } finally {
    if (env !== undefined) process.env.FIRESTORE_EMULATOR_HOST = env;
  }
});

// CI sets CATALOG_IMPORT_REQUIRE_EMULATOR, so a missing emulator fails the run instead of skipping it.
const requireEmulator = process.env.CATALOG_IMPORT_REQUIRE_EMULATOR === 'true';
const emulatorSkip = !requireEmulator && !process.env.FIRESTORE_EMULATOR_HOST && 'needs FIRESTORE_EMULATOR_HOST';

test('imports into the Firestore emulator, marks a delisted product removed and reruns without writes', { skip: emulatorSkip }, async () => {
  // Without an emulator host, firestoreClient would target command-o: never let this test get there.
  const host = emulatorHost();
  assert.ok(host, 'FIRESTORE_EMULATOR_HOST is not set: run this test with the Firestore emulator (see README.md)');
  const client = await firestoreClient(host);
  const number = `test-${Date.now()}`;
  const products = exportOf(row(number, { name: 'Œufs frais', price: 4.6 }));
  assert.equal((await importProducts(products, { client, write: true, log: () => {} })).written, 1);
  const [[, document]] = await client.get([path(number)]);
  assert.deepEqual(document.data, { ...products[0].data, price: 4.6 });
  assert.equal((await importProducts(products, { client, write: true, log: () => {} })).written, 0);

  const removedAt = '2026-10-20T08:00:00Z';
  const removal = exportOf(row(number, { price: null, removed_at: removedAt }));
  assert.equal((await importProducts(removal, { client, write: true, log: () => {} })).written, 1);
  const [[, removed]] = await client.get([path(number)]);
  assert.deepEqual({ ...removed.data, removed_at: Date.parse(removed.data.removed_at) }, { ...document.data, removed_at: Date.parse(removedAt) });
  assert.equal((await importProducts(removal, { client, write: true, log: () => {} })).written, 0);
});
