#!/usr/bin/env node
// Sets every trip's openOrderCount (#449) from its orders, once, when the counter rules are deployed.
// AI assistance: Claude Code. Dry run unless --write; target as in tools/catalog-import.
import { pathToFileURL } from 'node:url';
import { documentName, firestoreClient, firestoreTarget } from '../catalog-import/import.mjs';

/** Must match OrderStatus.blocksTripCompletion in the app and isSettledOrderStatus in firestore.rules. */
export const OPEN_STATUSES = new Set(['PENDING', 'ACCEPTED', 'READY_FOR_HANDOFF']);
const BATCH_SIZE = 400; // Firestore commits hold at most 500 writes.

/**
 * One write per trip without a counter. The rules block creating or settling orders on such a trip,
 * so its open orders cannot change while they are counted; trips that already have a counter are
 * kept by the order writes and never rewritten. The trip's updateTime guards each write.
 */
export function planCounterWrites(trips, orders) {
  const open = new Map();
  for (const [, { data }] of orders) {
    if (OPEN_STATUSES.has(data.status)) open.set(data.tripId, (open.get(data.tripId) ?? 0) + 1);
  }
  return trips
    .map(([path, { data, updateTime }]) => ({ path, updateTime, count: open.get(path.split('/')[1]) ?? 0, stored: data.openOrderCount }))
    .filter(({ stored }) => stored === undefined)
    .map(({ path, updateTime, count }) => ({
      update: { name: documentName(path), fields: { openOrderCount: { integerValue: String(count) } } },
      updateMask: { fieldPaths: ['openOrderCount'] },
      currentDocument: { updateTime },
    }));
}

export async function backfill({ client, write, log }) {
  const [trips, orders] = await Promise.all([
    client.query('trips', ['openOrderCount']),
    client.query('orders', ['tripId', 'status']),
  ]);
  const writes = planCounterWrites(trips, orders);
  log(`${trips.length} trips, ${orders.length} orders, ${writes.length} counters to set on ${client.target}`);
  if (write) for (let i = 0; i < writes.length; i += BATCH_SIZE) await client.commit(writes.slice(i, i + BATCH_SIZE));
  return writes.length;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const args = process.argv.slice(2);
  const project = args.includes('--project') ? args[args.indexOf('--project') + 1] : null;
  const client = await firestoreClient(firestoreTarget(project, process.env));
  await backfill({ client, write: args.includes('--write'), log: console.log });
}
