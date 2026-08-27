/**
 * Визуальные фиксчерсы: собирает HTML-лист с персонажами из runtime-пака.
 * Запуск: node tools/companions/fixtures.mjs
 */
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const out = join(root, "client", "assets", "companions", "notionists-v1", "runtime");
const manifest = JSON.parse(readFileSync(join(out, "manifest.json"), "utf8"));

const bySlot = {};
for (const a of manifest.assets) {
  (bySlot[a.slot] ??= []).push(a);
}
const pick = (slot, idx = 0) => {
  const list = (bySlot[slot] ?? []).filter((a) => !a.id.endsWith(".none"));
  return list[idx] ?? list[0];
};

const CHARACTERS = [
  { name: "A", skin: "#F2C6A0", hair: "#2E2A28", outfit: "#8C3B2E", accent: "#C9A227", ink: "#1A1816",
    parts: { body: 0, hair: 0, brows: 0, eyes: 0, nose: 0, mouth: 0, beard: -1, gesture: -1, accessoryFront: -1 } },
  { name: "B", skin: "#8D5A3B", hair: "#101010", outfit: "#274357", accent: "#B0B7C0", ink: "#1A1816",
    parts: { body: 1, hair: 1, brows: 1, eyes: 1, nose: 1, mouth: 1, beard: -1, gesture: 0, accessoryFront: -1 } },
  { name: "C", skin: "#F7D9C4", hair: "#7A4A2B", outfit: "#4C6B44", accent: "#8C3B2E", ink: "#1A1816",
    parts: { body: 2, hair: 2, brows: 2, eyes: 2, nose: 2, mouth: 2, beard: -1, gesture: -1, accessoryFront: -1 } },
];

function layerSvg(asset, colors) {
  const xml = readFileSync(join(out, asset.file), "utf8");
  return xml
    .replace(/var\(--wolfy-skin\)/g, colors.skin)
    .replace(/var\(--wolfy-hair\)/g, colors.hair)
    .replace(/var\(--wolfy-outfit\)/g, colors.outfit)
    .replace(/var\(--wolfy-accent\)/g, colors.accent)
    .replace(/var\(--wolfy-ink\)/g, colors.ink);
}

const cells = CHARACTERS.map((c) => {
  const stack = manifest.layerOrder
    .map((slot) => {
      const idx = c.parts[slot] ?? 0;
      if (idx < 0) return "";
      const asset = pick(slot, idx);
      if (!asset) return "";
      return layerSvg(asset, c).replace(/<svg[^>]*>|<\/svg>/g, "");
    })
    .join("\n");
  return `<div class="cell"><svg viewBox="0 0 1024 1024" width="360" height="360">${stack}</svg><span>${c.name}</span></div>`;
});

const html = `<!doctype html><meta charset="utf-8">
<style>body{background:#F7F2E9;display:flex;gap:16px;padding:16px;font-family:sans-serif}
.cell{display:flex;flex-direction:column;align-items:center}span{margin-top:4px}</style>
${cells.join("\n")}
<!-- якоря: кресты на первом персонаже -->
<script>void 0;</script>`;
mkdirSync(join(out, "..", "fixtures"), { recursive: true });
const file = join(out, "..", "fixtures", "characters.html");
writeFileSync(file, html);
console.log(file);
