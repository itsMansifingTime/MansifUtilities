/**
 * Parse Hypixel wiki "Salvaging Godrolls" wikitext table → JSON rows.
 * Table format: https://wiki.hypixel.net/Ananke_Feather
 */
export function parseSalvageTable(wikitext) {
  const start = wikitext.indexOf("Salvaging Godrolls");
  const end = wikitext.indexOf("==Usage==", start >= 0 ? start : 0);
  const slice = wikitext.slice(
    start >= 0 ? start : 0,
    end >= 0 ? end : wikitext.length,
  );

  let curItem = null;
  const out = [];

  for (const block of slice.split("|-")) {
    const joined = block.replace(/\s+/g, " ").trim();
    if (!joined.includes("{{Attr|")) continue;

    const idMatch = joined.match(/\{\{ID\|([^}]+)\}\}/);
    if (idMatch) curItem = idMatch[1].trim();

    const attrMatches = joined.matchAll(
      /\{\{Attr\|([^}]+)\}\}\s*\|\s*\{\{Attr\|([^}]+)\}\}\s*\|\s*(\d+)/g,
    );
    for (const m of attrMatches) {
      if (!curItem) continue;
      out.push({
        itemId: curItem.toUpperCase().replace(/'/g, "").replace(/\s+/g, "_"),
        itemName: curItem,
        attr1: m[1].trim(),
        attr2: m[2].trim(),
        feathers: Number(m[3]),
      });
    }
  }
  return out;
}
