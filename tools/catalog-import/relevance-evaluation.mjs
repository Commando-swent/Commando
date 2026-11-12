// Read-only offline evaluation of document-ID ordering against the importer's relevance tiers.
// Usage: node relevance-evaluation.mjs <migros.jsonl> <coop.jsonl> [--holdout]
//        [--top N] [--show query,query]
import { readFileSync } from 'node:fs';
import { normalizeSearchText, parseExport } from './import.mjs';

const USAGE =
  'Usage: node tools/catalog-import/relevance-evaluation.mjs <migros.jsonl> <coop.jsonl> [--holdout] [--top N] [--show query,query]';

function options(args) {
  const files = [];
  let holdout = false;
  let show = [];
  let top = 5;
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (argument === '--holdout') holdout = true;
    else if (argument === '--top' || argument === '--show') {
      const value = args[index + 1];
      if (!value || value.startsWith('--')) throw new Error(`${argument} requires a value. ${USAGE}`);
      if (argument === '--top') top = Number(value);
      else show = value.split(',');
      index += 1;
    } else if (argument.startsWith('--')) {
      throw new Error(`Unexpected argument ${argument}. ${USAGE}`);
    } else {
      files.push(argument);
    }
  }
  if (files.length !== 2 || !Number.isInteger(top) || top < 1) throw new Error(USAGE);
  return { files, holdout, show, top };
}

const words = (query) =>
  [...new Set(normalizeSearchText(query).split(/[^a-z0-9]+/).filter((word) => word.length >= 2).map((word) => word.slice(0, 15)))].slice(0, 5);
const normalized = (text) => normalizeSearchText(text ?? '');
const department = (product, expression) => expression.test(normalized(product.category));
const FRUIT_AND_VEGETABLES = /^fruits & legumes$/;
const DAIRY = /^produits laitiers/;
const MEAT = /^viandes/;
const BAKERY = /^boulangerie/;
const PANTRY = /^pates, condiments/;
const DRINKS = /^boissons/;
const WINE = /^vins/;
const SNACKS = /^snacks/;
const HOUSEHOLD = /^menage/;
const BEAUTY = /^beaute/;
const readyMade = (product) =>
  /prets? a (l.emploi|consommer|cuire)|tranche/.test(normalized(product.category_path));

// What a shopper entering each development query most likely wants. These predicates were written
// while tuning the tiers and judge department, category path and product name.
const DEVELOPMENT_QUERIES = {
  pomme: (product) => department(product, FRUIT_AND_VEGETABLES) && !readyMade(product) && /\bpommes?\b/.test(normalized(product.name)) && !/terre/.test(normalized(product.name)),
  poire: (product) => department(product, FRUIT_AND_VEGETABLES) && !readyMade(product) && /\bpoires?\b/.test(normalized(product.name)),
  banane: (product) => department(product, FRUIT_AND_VEGETABLES) && /bananes|plantain/.test(normalized(`${product.category_path} ${product.name}`)),
  orange: (product) => department(product, FRUIT_AND_VEGETABLES) && /\boranges?\b/.test(normalized(product.name)),
  citron: (product) => department(product, FRUIT_AND_VEGETABLES) && /\bcitrons?\b/.test(normalized(product.name)),
  fraise: (product) => department(product, FRUIT_AND_VEGETABLES) && /\bfraises?\b/.test(normalized(product.name)),
  tomate: (product) => department(product, FRUIT_AND_VEGETABLES) && !readyMade(product) && /\btomates?\b/.test(normalized(product.name)),
  carotte: (product) => department(product, FRUIT_AND_VEGETABLES) && !readyMade(product) && /\bcarottes?\b/.test(normalized(product.name)),
  salade: (product) => department(product, FRUIT_AND_VEGETABLES) && /salade|laitue/.test(normalized(`${product.category_path} ${product.name}`)),
  oignon: (product) => department(product, FRUIT_AND_VEGETABLES) && /\boignons?\b/.test(normalized(product.name)),
  'pomme de terre': (product) => department(product, FRUIT_AND_VEGETABLES) && !readyMade(product) && /pommes? de terre/.test(normalized(product.name)),
  avocat: (product) => department(product, FRUIT_AND_VEGETABLES) && /\bavocats?\b/.test(normalized(product.name)),
  lait: (product) => department(product, DAIRY) && /\blait\b/.test(normalized(product.name)) && !/chocolat|riz au lait|cafe|yog|dessert/.test(normalized(product.name)),
  beurre: (product) => department(product, DAIRY) && /\bbeurre\b/.test(normalized(product.name)) && !/biscuit/.test(normalized(product.name)),
  fromage: (product) => /fromage/.test(normalized(product.category_path)),
  yogourt: (product) => department(product, DAIRY) && /yog|yaourt/.test(normalized(`${product.category_path} ${product.name}`)),
  oeufs: (product) => /\boeufs?\b/.test(normalized(product.category_path)) && /\boeufs?\b/.test(normalized(product.name)),
  creme: (product) => department(product, DAIRY) && /\bcreme\b/.test(normalized(product.name)),
  pain: (product) => department(product, BAKERY) && /\bpains?\b/.test(normalized(`${product.name} ${product.category_path}`)),
  riz: (product) => department(product, PANTRY) && /\briz\b/.test(normalized(product.name)) && !/galette|lait/.test(normalized(product.name)),
  pates: (product) => department(product, PANTRY) && /\bpates\b/.test(normalized(product.category_path)),
  farine: (product) => /\bfarines?\b/.test(normalized(product.category_path)) && /\bfarine/.test(normalized(product.name)),
  sucre: (product) => /\bsucres?\b/.test(normalized(product.category_path)) && /\bsucre/.test(normalized(product.name)),
  cafe: (product) => department(product, DRINKS) && /\bcafes?\b/.test(normalized(product.category_path)),
  the: (product) => department(product, DRINKS) && /\bthes?\b/.test(normalized(product.category_path)),
  eau: (product) => department(product, DRINKS) && /\beaux?\b/.test(normalized(`${product.category_path} ${product.name}`)),
  jus: (product) => department(product, DRINKS) && /\bjus\b/.test(normalized(`${product.category_path} ${product.name}`)),
  biere: (product) => department(product, WINE) && /\bbieres?\b/.test(normalized(product.category_path)),
  vin: (product) => department(product, WINE) && /\bvins?\b/.test(normalized(product.category_path)),
  poulet: (product) => department(product, MEAT) && /\bpoulets?\b/.test(normalized(product.name)),
  boeuf: (product) => department(product, MEAT) && /\bboeuf\b|\bbeef\b/.test(normalized(`${product.name} ${product.category_path}`)),
  jambon: (product) => department(product, MEAT) && /\bjambon\b/.test(normalized(product.name)),
  saumon: (product) => department(product, MEAT) && /\bsaumon\b/.test(normalized(product.name)),
  chocolat: (product) => department(product, SNACKS) && /chocolat/.test(normalized(product.category_path)),
  chips: (product) => department(product, SNACKS) && /\bchips\b/.test(normalized(product.category_path)),
  'papier toilette': (product) => /papier (toilette|hygienique)/.test(normalized(`${product.category_path} ${product.name}`)),
  lessive: (product) => department(product, HOUSEHOLD) && /lessive/.test(normalized(`${product.category_path} ${product.name}`)),
  dentifrice: (product) => department(product, BEAUTY) && /dentifrice/.test(normalized(`${product.category_path} ${product.name}`)),
};

// These predicates were kept separate and were never used to tune the ranking rules.
const HOLDOUT_QUERIES = {
  concombre: (product) => department(product, FRUIT_AND_VEGETABLES) && /\bconcombres?\b/.test(normalized(product.name)),
  champignon: (product) => department(product, FRUIT_AND_VEGETABLES) && /champignon/.test(normalized(`${product.name} ${product.category_path}`)),
  ail: (product) => department(product, FRUIT_AND_VEGETABLES) && /\bail\b/.test(normalized(product.name)),
  kiwi: (product) => department(product, FRUIT_AND_VEGETABLES) && /\bkiwis?\b/.test(normalized(product.name)),
  mangue: (product) => department(product, FRUIT_AND_VEGETABLES) && /\bmangues?\b/.test(normalized(product.name)),
  raisin: (product) => department(product, FRUIT_AND_VEGETABLES) && /\braisins?\b/.test(normalized(product.name)) && !/sec/.test(normalized(product.name)),
  courgette: (product) => department(product, FRUIT_AND_VEGETABLES) && /\bcourgettes?\b/.test(normalized(product.name)),
  mozzarella: (product) => department(product, DAIRY) && /mozzarella/.test(normalized(product.name)),
  thon: (product) => (department(product, PANTRY) || department(product, MEAT)) && /\bthon\b/.test(normalized(product.name)),
  pizza: (product) => /pizza/.test(normalized(product.category_path)),
  cereales: (product) => /cereales|muesli|flocons/.test(normalized(product.category_path)),
  miel: (product) => /\bmiels?\b/.test(normalized(product.category_path)) && /\bmiel\b/.test(normalized(product.name)),
  confiture: (product) => /confiture/.test(normalized(product.category_path)) && /confiture/.test(normalized(product.name)),
  huile: (product) => department(product, PANTRY) && /\bhuiles?\b/.test(normalized(product.category_path)),
  vinaigre: (product) => department(product, PANTRY) && /vinaigre/.test(normalized(product.category_path)),
  sel: (product) => department(product, PANTRY) && /\bsels?\b/.test(normalized(`${product.category_path} ${product.name}`)) && !/chips|cacahu|amande/.test(normalized(product.name)),
  croissant: (product) => (department(product, BAKERY) || /viennoiserie|pains/.test(normalized(product.category_path))) && /croissant/.test(normalized(product.name)) && !/jambon/.test(normalized(product.name)),
  glace: (product) => /glace/.test(normalized(product.category_path)),
};

const percent = (number) => `${Math.round(number * 100)}%`.padStart(4);
const first = (products, relevant) => {
  const index = products.findIndex(relevant);
  return index < 0 ? '-' : String(index + 1);
};
const precision = (products, relevant, top) =>
  products.length === 0
    ? 0
    : products.slice(0, top).filter(relevant).length / Math.min(top, products.length);
const compareIds = (left, right) => (left.id < right.id ? -1 : left.id > right.id ? 1 : 0);

const run = options(process.argv.slice(2));
const queries = run.holdout ? HOLDOUT_QUERIES : DEVELOPMENT_QUERIES;
const stores = { migros: run.files[0], coop: run.files[1] };
const rows = [];
for (const [store, file] of Object.entries(stores)) {
  const products = parseExport(readFileSync(file, 'utf8'), store).products
    .filter((product) => product.data)
    .map((product) => ({ id: product.path.split('/').pop(), ...product.data }));
  for (const [query, relevant] of Object.entries(queries)) {
    const terms = words(query);
    const hits = products.filter((product) => terms.every((term) => product.search_prefixes[term]));
    const before = [...hits].sort(compareIds);
    const after = [...hits].sort(
      (left, right) =>
        left.search_rank[terms[0]] - right.search_rank[terms[0]] ||
        compareIds(left, right),
    );
    rows.push({
      store,
      query,
      hits: hits.length,
      relevantCount: hits.filter(relevant).length,
      firstBefore: first(before, relevant),
      firstAfter: first(after, relevant),
      precisionBefore: precision(before, relevant, run.top),
      precisionAfter: precision(after, relevant, run.top),
      after,
      relevant,
    });
  }
}

console.log(
  `store   query            matches relevant | first relevant: today -> ranked | top-${run.top} relevant: today -> ranked`,
);
for (const row of rows) {
  const flag =
    row.relevantCount === 0
      ? '  (none relevant in catalogue)'
      : row.precisionAfter < 0.6
        ? '  <== weak'
        : '';
  console.log(
    `${row.store.padEnd(7)} ${row.query.padEnd(16)} ${String(row.hits).padStart(7)} ${String(row.relevantCount).padStart(8)} | ${row.firstBefore.padStart(14)} -> ${row.firstAfter.padEnd(6)} | ${percent(row.precisionBefore)} -> ${percent(row.precisionAfter)}${flag}`,
  );
}

// Macro-average each store/query precision. Rows without a relevant catalogue product are shown
// above but not scored, because no ordering can return a relevant item for them.
const scored = rows.filter((row) => row.relevantCount > 0);
const average = (field) =>
  scored.reduce((sum, row) => sum + row[field], 0) / scored.length;
console.log(
  `\nAverage top-${run.top} relevance over ${scored.length} store/query pairs: today ${percent(average('precisionBefore'))} -> ranked ${percent(average('precisionAfter'))}`,
);
console.log(
  `Relevant result first: today ${scored.filter((row) => row.firstBefore === '1').length}/${scored.length} -> ranked ${scored.filter((row) => row.firstAfter === '1').length}/${scored.length}`,
);
for (const row of rows.filter(
  (candidate) =>
    run.show.includes(candidate.query) ||
    (run.show.includes('weak') &&
      candidate.relevantCount > 0 &&
      candidate.precisionAfter < 0.6),
)) {
  const term = words(row.query)[0];
  console.log(`\n${row.store} "${row.query}" ranked top ${run.top}:`);
  row.after.slice(0, run.top).forEach((product, index) => {
    console.log(
      `  ${index + 1}. ${row.relevant(product) ? 'OK ' : 'no '} T${product.search_rank[term]} ${product.name} | ${product.category_path}`,
    );
  });
}
