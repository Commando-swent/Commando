# Catalogue import (Migros and Coop)

`import.mjs` copies a swiss-cli export of one store's products into
`stores/{store}/products/{product number}` of the `(default)` database of `command-o`, with the
schema `ProductRepositoryFirestore` reads: `id` (`migros:<number>` or `coop:<number>`), `name`,
`price` (CHF), `image_url` (or `null`), `removed_at` (`null` when listed), `size_text`, `source_name`,
`brand` and `variant` (each or `null`) and
`search_prefixes`, the 2- to 15-character prefixes of the words of the name and of the last category segment, normalized
like `normalizeProductSearchText`, and `search_rank`, their [relevance tiers](#search-ranking). Only
Migros and Coop publish a full catalogue; items of other
chains are entered by hand in the app.

**Prerequisites:** Node.js 20+. For `command-o`: the Firebase CLI signed in (`firebase login`) with
access to the project. The tool stores no key: it reads the CLI's login through modules internal to
firebase-tools, so it only accepts firebase-tools 15.32.1, the release CI uses, and stops on any
other. Install that release next to the tool (its `tmp/` folder is ignored by Git):

```bash
npm install --prefix tools/catalog-import/tmp/firebase-tools firebase-tools@15.32.1
npx firebase-tools@15.32.1 login
export FIREBASE_TOOLS_ROOT=tools/catalog-import/tmp/firebase-tools/node_modules
```

A global install of that exact release also works without `FIREBASE_TOOLS_ROOT`. Firestore rules and
indexes are not deployed by the tool; deploy the `search_prefixes` and `search_rank` field overrides
of `firestore.indexes.json` before a full import.

**Target:** every run names its target. With `FIRESTORE_EMULATOR_HOST` (a local `host:port`) it
uses that emulator; otherwise it needs `--project command-o`, dry runs included. Neither, both, or a
non-local emulator host stops the run before any read.

## Input

A JSON Lines file of one store's products, exported from swiss-cli, a tool maintained in a separate
repository. Each line is one JSON object with `id` (`migros:<number>` or `coop:<number>`), `store`
(`migros` or `coop`), `name`, `price` (CHF), `image_url` (optional), `category` (optional, for
example `Lait > Lait UHT`), `brand`, `variant`, `version_text` and `size` or `size_text` (optional) and `removed_at`
(`null` or absent for listed products); other fields are ignored. Only the shown `name` differs by
store, and the store's parts are kept in `source_name`, `brand` and `variant`:

- Migros splits its title, so `name` is brand, name and variant, each once (`Frey Tourist Lait`).
  swiss-cli keeps the Migros line as `version_text` (and as `variant` only when sure): listed products
  that would show the same name and size without a variant take it (`Essie Vernis à ongle 954 Off Beat
  Chic`); `variant` stays `null`. Only `--write` full exports: a sample can undo these names.
- Coop's title is already the name Coop shows, brand and size included (`1664 Blonde Bière 12x25cl`,
  brand `Kronenbourg`), so `name` is that title with its spaces cleaned and the brand is not added.

The importer stores the cleaned source taxonomy as `category_path` and a broad `category`, which the
app filters on and both chains share: Migros's 18 departments (the first segment of its path). Coop
exports one flat category (565 such as `Vins rouges` or `Schokoladen-Snacks`), which
`coop-categories.json` files under one of those departments. A missing category, or a Coop one not in
that file, gives `Autres`; a dry run lists such Coop categories so the file can be completed. A later
export that omits the category keeps the stored `category_path`, and `category` is always derived from
it, so editing `coop-categories.json` regroups stored products on the next import.

Keep exports in `tools/catalog-import/` (for example `tools/catalog-import/coop.jsonl`) and generated
files in `tools/catalog-import/tmp/`; its `.gitignore` keeps both out of Git.

### Export from swiss-cli

In the swiss-cli checkout, fetch the whole catalogue in French (a `--category` or `--limit` fetch is
only a sample, and never marks products removed), then export the fields above with the products it
no longer lists:

```bash
SWISS_GROCERY_COOP_LANG=fr swiss-grocery fetch coop
swiss-grocery export coop.jsonl --store coop --include-removed --fields id,store,name,brand,variant,version_text,size,price,image_url,category,removed_at
```

Migros is the same with `migros` and `SWISS_GROCERY_MIGROS_LANG=fr` (in PowerShell, set
`$env:SWISS_GROCERY_COOP_LANG = "fr"` first). Checked on 300 Coop products fetched on 4 November 2026:

- IDs are `coop:<Coop article number>`, the code of Coop's product page, so they stay stable.
- Prices are the CHF price paid today, a promotion included; some wines have none and are skipped.
- `brand` is empty for wines (Coop gives their origin there) and Coop has no `variant`.
- Images are `https://www.coop.ch/img/…` URLs; categories are one segment (`Bières en multipacks`).
- swiss-cli sets `removed_at` only after a complete crawl, once Coop answers that the product is gone.

### Sizes (`--sizes`)

Migros exports often have no size. `--sizes sizes.jsonl` fills it from a JSON Lines file with one
`{"data": {"id": "migros:<number>", "size_text": "100g"}}` object per line; other lines and fields
are ignored. Limits:

- A size in the export always wins; the file only fills missing ones, matched by `id`. A Migros size
  equal to the variant is missing: swiss-cli writes the variant line there when Migros gives no
  quantity (`doux` as both).
- Sizes are not checked for freshness: a stale file keeps stale sizes until it is regenerated.
- A row without a size keeps the size already stored, so a rerun without `--sizes` erases nothing;
  a size can be replaced this way but not cleared.
- One line that is not JSON stops the run before any read or write.

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
  `node tools/catalog-import/import.mjs tools/catalog-import/coop.jsonl --store coop --project command-o`. It checks the
  project and database and prints how many products would be created, updated, marked removed or
  left unchanged.
- Approved import:
  `node tools/catalog-import/import.mjs tools/catalog-import/coop.jsonl --store coop --write --project command-o`.
  Migros takes the same commands with `--store migros` and an optional `--sizes` file.
  Writes go in atomic batches of 400. `--max-writes N` stops after N writes, to leave the app part
  of the daily write allowance; it does not reduce reads, as every run first reads all the export's
  documents. Unset `FIRESTORE_EMULATOR_HOST` first: with it, `--project` is refused.
- Verify: in the app's catalogue, search for a few imported products, and for one marked removed,
  which must not appear. Do not rerun a full dry run to check the import.

## Migration to categories and search ranks

Products imported before categories and ranking have no `category`, `category_path` or `search_rank`.
One import of each store's latest full export adds them; its dry run must list only those fields (and,
for Migros, the `name` and `search_prefixes` of `version_text` renames) under "Updated products per
field". `stores/{store}.categories` and `search_rank_version`, which turn on the app's category chips
and ranked search, are written only once a run has committed every product write. Within the daily
allowance (about 16,300 reads per Coop run, 29,900 per Migros run):

1. Coop: dry run, then `--write` (about 32,600 reads and 16,300 writes).
2. Migros: dry run on another day.
3. Migros: `--write --max-writes 14000` on two days; the second run writes the rest and the metadata.

A stopped run is resumed by rerunning it: products already updated are unchanged and not rewritten.

## Search ranking

`search_rank` gives each key of `search_prefixes` a tier; the app queries the first search word's tiers
1 to 7 in order, each by document ID, continuing into the next when one ends mid-page (an empty tier
costs one read). A word's home department is where the catalogue's categories name it: fresh produce
if it does, else the one with the most category levels naming it, skipping `Animaux`, `Bébé et enfant`
and `Beauté & santé` while a general one also does. Words also match their plural (`s`, `x`). Homes
come from the whole import, so only write full exports; a later import can move products between tiers.

| Tier | The prefix is… | Example |
|---|---|---|
| 1 | the product's own first word (brand skipped), for fresh produce (not ready-made) | `banane`: Bananes |
| 2 | its own first word, in the word's home department | `lait`: Lait entier |
| 3 | a word of its category path, in the home department | `fromage`: Mini-Babybel |
| 4 | its own first word or a category word, in another department | `lait`: milk chocolate |
| 5 | only the start of its first word or of a category part | `lait`: Laitue iceberg |
| 6 | any of these, in an audience department that is not the home | `poulet`: cat food |
| 7 | in any other word | `pomme`: Vinaigre de pomme |

### Relevance evaluation

`relevance-evaluation.mjs` preserves the query lists, relevance predicates and scoring method used
for this change. It reads local exports only, parses and ranks them with the current importer, and
does not connect to Firestore:

```bash
node tools/catalog-import/relevance-evaluation.mjs <migros.jsonl> <coop.jsonl>
node tools/catalog-import/relevance-evaluation.mjs <migros.jsonl> <coop.jsonl> --holdout
```

The development set contains 38 queries and the untouched holdout set contains 18. For every store
and query, the script compares document-ID order with `search_rank` tier then document-ID order.
It reports the macro-average top-five precision over store/query pairs having at least one relevant
catalogue product; pairs with no relevant product are displayed but cannot be scored.

The November 2026 evaluation used these ignored full exports:

| Store | File | Size | SHA-256 |
|---|---|---:|---|
| Migros | `migros-2026-11-08.jsonl` | 13,561,442 bytes | `B91B977C510E17C13F6D156073FFF6F8C6F14322175169FD0FC9D8C8A9B3B3D0` |
| Coop | `coop.jsonl` | 4,712,087 bytes | `47BE105D055571C9D3A421B928607186EF87F9ADD56A6F2A920E019F47335F1C` |

The development run scores 74 store/query pairs at 32% before and 94% after, with a relevant first
result moving from 17/74 to 70/74. The holdout run scores 36 pairs at 32% before and 93% after, with
a relevant first result moving from 11/36 to 35/36. The exports stay out of Git because they are
large swiss-cli catalogue snapshots; reproduce them with the full-export commands above and compare
their hashes when the original snapshots are available.

## Reruns and removals

Documents are keyed by the store's product number, so a rerun updates them in place, and unchanged
products are not rewritten: rerunning resumes a stopped import. A changed brand, name or variant changes
`name` and replaces the whole `search_prefixes` and `search_rank` maps, so prefixes of the old name stop
matching. An update writes only its changed fields (a dry run counts them per field), keeps any other
field, and fails if the document changed since it was read (rerun then).
The tool only reads and writes the documents named in the export, all under `stores/{store}`: one
store's import never touches another store, and products missing from a partial export are never
deleted. A product exported with `removed_at` gets that `removed_at` if it exists and is active, and
is never created; only `removed_at` is written, so its stored price and other fields are kept. A
product listed again is reactivated.

## Known limits of the source data

- Search covers the name and the last category only, as in the app: a Coop brand missing from the
  title (`Kronenbourg` for `1664 Blonde Bière`) does not match.
- Only the first search word sets the tier, and a tier keeps document ID order: `pomme` ranks fresh
  potatoes (`Pommes de terre`) with apples. Incomplete prefixes such as `lai` or `pom` put most
  matches in tier 5, so those intermediate results often retain document-ID order. A product missing
  from the export gets no `search_rank` and no ranked result; swiss-cli's full export with
  `--include-removed` lists every product.
- Prices are those of the day of the fetch, promotions included, until the next import.
- Coop titles include the size, so it shows in the name; `size_text` is stored but not shown yet.
- swiss-cli's own `sync --to firebase` writes another schema to the same paths: never point it at
  `command-o`, whose catalogue comes from this tool only.
