// AI assistance: Claude Code. Run with: node --test tools/trip-counters/backfill.test.mjs
import assert from 'node:assert/strict';
import test from 'node:test';
import { documentName, emulatorHost, firestoreClient, firestoreTarget } from '../catalog-import/import.mjs';
import { backfill, planCounterWrites } from './backfill.mjs';

const trip = (id, openOrderCount) => [`trips/${id}`, { data: { openOrderCount }, updateTime: `t-${id}` }];
const order = (tripId, status) => [`orders/${tripId}-${status}`, { data: { tripId, status } }];

test('counts only open orders, for trips without a counter only', () => {
  const trips = [trip('legacy', undefined), trip('drifted', -1), trip('correct', 1), trip('empty', undefined)];
  const orders = [
    order('legacy', 'PENDING'), order('legacy', 'ACCEPTED'), order('legacy', 'DELIVERED'),
    order('drifted', 'READY_FOR_HANDOFF'), order('correct', 'PENDING'), order('correct', 'REJECTED'),
  ];
  const writes = planCounterWrites(trips, orders);
  const counts = Object.fromEntries(writes.map(({ update, currentDocument }) =>
    [update.name.split('/').at(-1), [update.fields.openOrderCount.integerValue, currentDocument.updateTime]]));
  // A trip with a counter is never rewritten: order writes may be moving it while orders are read.
  assert.deepEqual(counts, { legacy: ['2', 't-legacy'], empty: ['0', 't-empty'] });
  assert.deepEqual(writes[0].updateMask, { fieldPaths: ['openOrderCount'] });
});

test('writes only with --write', async () => {
  const commits = [];
  const client = {
    target: 'fake',
    query: async (collection) => (collection === 'trips' ? [trip('a', undefined)] : [order('a', 'PENDING')]),
    commit: async (writes) => commits.push(writes.length),
  };
  assert.equal(await backfill({ client, write: false, log: () => {} }), 1);
  assert.deepEqual(commits, []);
  assert.equal(await backfill({ client, write: true, log: () => {} }), 1);
  assert.deepEqual(commits, [1]);
});

// CI runs this file in the Firestore emulator; without one (and without the CI flag) it is skipped.
const emulatorSkip = !process.env.CATALOG_IMPORT_REQUIRE_EMULATOR && !emulatorHost() && 'needs FIRESTORE_EMULATOR_HOST';

test('sets the counters in the Firestore emulator, then has nothing left to write', { skip: emulatorSkip }, async () => {
  const client = await firestoreClient(firestoreTarget(null, process.env));
  const id = `backfill-${Date.now()}`;
  const set = (path, fields) => ({ update: { name: documentName(path), fields } });
  const text = (value) => ({ stringValue: value });
  await client.commit([
    set(`trips/${id}-legacy`, { status: text('PUBLISHED') }),
    set(`trips/${id}-drifted`, { status: text('PUBLISHED'), openOrderCount: { integerValue: '5' } }),
    set(`orders/${id}-open`, { tripId: text(`${id}-legacy`), status: text('PENDING') }),
    set(`orders/${id}-done`, { tripId: text(`${id}-legacy`), status: text('DELIVERED') }),
  ]);
  await backfill({ client, write: true, log: () => {} });
  const stored = await client.get([`trips/${id}-legacy`, `trips/${id}-drifted`]);
  assert.deepEqual(stored.map(([, { data }]) => data.openOrderCount), [1, 5]);
  assert.equal(await backfill({ client, write: true, log: () => {} }), 0);
});
