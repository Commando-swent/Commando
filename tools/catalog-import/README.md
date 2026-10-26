# Catalogue import (Migros and Coop)

`import.mjs` copies a swiss-cli export of one store's products into
`stores/{store}/products/{product number}` of the `(default)` database of `command-o`, with the
schema `ProductRepositoryFirestore` reads: `id` (`migros:<number>` or `coop:<number>`), `name`,
`price` (CHF), `image_url` (or `null`), `removed_at` (`null` when listed) and `search_prefixes`, the
2- to 15-character prefixes of the words of the name and of the last category segment, normalized
like `normalizeProductSearchText`. Only Migros and Coop publish a full catalogue; items of other
chains are entered by hand in the app.

**Prerequisites:** Node.js 20+. For `command-o`: the Firebase CLI signed in (`firebase login`) with
access to the project. The tool stores no key. Firestore rules and indexes are not deployed by it;
deploy the `search_prefixes` field override of `firestore.indexes.json` before a full import.

## Input

A JSON Lines file of one store's products, exported from swiss-cli, a tool maintained in a separate
repository. Each line is one JSON object with `id` (`migros:<number>` or `coop:<number>`), `store`
(`migros` or `coop`), `name`, `price` (CHF), `image_url` (optional), `category` (optional, for
example `Lait > Lait UHT`) and `removed_at` (`null` or absent for listed products). Keep exports in
`tools/catalog-import/` (for example `tools/catalog-import/coop.jsonl`), whose `.gitignore` keeps
them out of Git.

Invalid lines (another store, bad id, blank name, a price that is not a positive CHF amount in cents,
a non-HTTP image URL, duplicates) are skipped and listed with their line numbers. The importer marks
a product removed only if the export contains it with a valid `removed_at` timestamp; such a line
only needs a valid `id` and `removed_at`, and may have no price.

## Run (from the repository root)

`command-o` is on the free Spark plan: 50,000 Firestore reads and 20,000 writes a day, shared with
the app. Every run against it, dry runs included, first reads one document per export line, so try
an export on the emulator first and run the production commands once.

- Tests: `node --test tools/catalog-import/import.test.mjs`. The end-to-end test also runs, as in CI,
  against a Firestore emulator of the demo project:
  `npx firebase-tools@15.32.1 emulators:exec --project demo-commando --config firebase.firestore-open-test.json --only firestore "node --test tools/catalog-import/import.test.mjs"`.
  With `CATALOG_IMPORT_REQUIRE_EMULATOR=true`, it fails instead of skipping without the emulator.
- Emulator import (no production access): lists the skipped lines and imports the export into an
  empty emulator, discarded afterwards:
  `npx firebase-tools@15.32.1 emulators:exec --project demo-commando --config firebase.firestore-open-test.json --only firestore "node tools/catalog-import/import.mjs tools/catalog-import/coop.jsonl --store coop --write"`.
- Optional production dry run (no writes, but one read per export line):
  `node tools/catalog-import/import.mjs tools/catalog-import/coop.jsonl --store coop`. It checks the
  project and database and prints how many products would be created, updated, marked removed or
  left unchanged.
- Approved import:
  `node tools/catalog-import/import.mjs tools/catalog-import/coop.jsonl --store coop --write --project command-o`.
  Writes go in atomic batches of 400. `--max-writes N` stops after N writes, to leave the app part
  of the daily write allowance; it does not reduce reads, as every run first reads all the export's
  documents. With a local `FIRESTORE_EMULATOR_HOST`, every run targets the emulator instead.
- Verify: in the app's catalogue, search for a few imported products, and for one marked removed,
  which must not appear. Do not rerun a full dry run to check the import.

## Reruns and removals

Documents are keyed by the store's product number, so a rerun updates them in place, and unchanged
products are not rewritten: rerunning resumes a stopped import. Each write replaces only the fields
above, keeps any other field, and fails if the document changed since it was read (rerun then).
The tool only reads and writes the documents named in the export, all under `stores/{store}`: one
store's import never touches another store, and products missing from a partial export are never
deleted. A product exported with `removed_at` gets that `removed_at` if it exists and is active, and
is never created; only `removed_at` is written, so its stored price and other fields are kept. A
product listed again is reactivated.
