/**
 * Regenerate ananke-godrolls.json from https://wiki.hypixel.net/Ananke_Feather
 * (Salvaging Godrolls table).
 *
 * Usage:
 *   node scripts/sync-ananke-godrolls.mjs
 *   node scripts/sync-ananke-godrolls.mjs path/to/wikitext.txt
 */
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(__dirname, "..");
const WIKI_PAGE = "Ananke_Feather";
const WIKI_URL = "https://wiki.hypixel.net/Ananke_Feather";
const API =
  "https://wiki.hypixel.net/api.php?action=parse&page=" +
  encodeURIComponent(WIKI_PAGE) +
  "&prop=wikitext&format=json";
const DEFAULT_WIKITEXT = path.join(root, "data", "ananke-feather-salvage.wikitext");
const OUT = path.join(root, "src/main/resources/assets/mansifutilities/ananke-godrolls.json");

import { parseSalvageTable } from "./parse-ananke-wiki.mjs";

async function fetchWikitext() {
  const res = await fetch(API, {
    headers: { "User-Agent": "MansifUtilities/1.0 (ananke sync script)" },
  });
  if (!res.ok) throw new Error(`API HTTP ${res.status}`);
  const json = await res.json();
  const w = json?.parse?.wikitext?.["*"];
  if (!w || w.startsWith("<!DOCTYPE")) throw new Error("API returned non-wikitext");
  return w;
}

async function main() {
  let wikitext;
  const argPath = process.argv[2];
  if (argPath) {
    wikitext = fs.readFileSync(argPath, "utf8");
    console.error("Using wikitext file:", argPath);
  } else {
    try {
      wikitext = await fetchWikitext();
      console.error("Fetched live wikitext from", WIKI_URL);
    } catch (e) {
      console.error("Live fetch failed:", e.message);
      wikitext = fs.readFileSync(DEFAULT_WIKITEXT, "utf8");
      console.error("Using bundled snapshot:", DEFAULT_WIKITEXT);
    }
  }

  const rows = parseSalvageTable(wikitext);
  if (rows.length === 0) {
    console.error("No rows parsed — check wikitext format.");
    process.exit(1);
  }

  const payload = {
    wiki: WIKI_URL,
    wikiSection: "Salvaging Godrolls",
    rows,
  };

  fs.writeFileSync(OUT, JSON.stringify(payload, null, 2) + "\n", "utf8");
  console.error(`Wrote ${rows.length} rows to ${OUT}`);
}

main();
