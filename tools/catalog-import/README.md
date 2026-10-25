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

A JSON Lines export of one store from [swiss-cli](https://github.com/othmanehousni/swiss-cli) (one
product per line), for example with French names:

```bash
swiss-grocery fetch coop --lang fr
swiss-grocery export coop.jsonl --store coop --fields id,store,name,price,image_url,category,removed_at
```

Add `--include-removed` to also mark delisted products as removed (see below). Invalid lines (another
store, bad id, blank name, a price that is not a positive CHF amount in cents, a non-HTTP image URL,
duplicates) are skipped and listed with their line numbers.

## Run (from the repository root)

- Tests: `node --test tools/catalog-import/import.test.mjs`. With the emulator, the end-to-end test
  also runs: `firebase emulators:exec --only firestore "node --test tools/catalog-import/import.test.mjs"`.
- Dry run (default, reads only): `node tools/catalog-import/import.mjs coop.jsonl --store coop`. It
  checks the project and database, reads the export's documents only (one read each) and prints how
  many would be created, updated, marked removed or left unchanged.
- Approved import, after reviewing the dry run:
  `node tools/catalog-import/import.mjs coop.jsonl --store coop --write --project command-o`.
  Writes go in atomic batches of 400; `--max-writes N` stops after N writes (Spark allows 20,000 a
  day). With a local `FIRESTORE_EMULATOR_HOST`, every run targets the emulator instead.
- Verify: run the dry run again; it must report only `unchanged` products. Then search for a product
  in the app's catalogue.

## Reruns and removals

Documents are keyed by the store's product number, so a rerun updates them in place, and unchanged
products are not rewritten: rerunning resumes a stopped import. Each write replaces only the fields
above, keeps any other field, and fails if the document changed since it was read (rerun then).
The tool only reads and writes the documents named in the export, all under `stores/{store}`: one
store's import never touches another store, and products missing from a partial export are never
deleted. A product exported with `removed_at` gets that `removed_at` if it exists and is active, and
is never created; a product listed again is reactivated. Do not run swiss-cli's `sync --to firebase`
on `command-o`: it replaces whole documents and drops `search_prefixes`.
