/**
 * Контрольный лист: каждый актив поверх base, чтобы увидеть положение слоя.
 * Запуск: node tools/companions/sheet.mjs
 */
import { readFileSync, writeFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const out = join(root, "client", "assets", "companions", "notionists-v1", "runtime");
const manifest = JSON.parse(readFileSync(join(out, "manifest.json"), "utf8"));

const colors = { skin: "#F2C6A0", hair: "#2E2A28", outfit: "#8C3B2E", accent: "#C9A227", ink: "#1A1816" };
function layerSvg(file) {
  return readFileSync(join(out, file), "utf8")
    .replace(/var\(--wolfy-skin\)/g, colors.skin)
    .replace(/var\(--wolfy-hair\)/g, colors.hair)
    .replace(/var\(--wolfy-outfit\)/g, colors.outfit)
    .replace(/var\(--wolfy-accent\)/g, colors.accent)
    .replace(/var\(--wolfy-ink\)/g, colors.ink);
}
const base = manifest.assets.find((a) => a.slot === "base");
const baseBody = layerSvg(base.file).replace(/<svg[^>]*>|<\/svg>/g, "");

const cells = [];
for (const asset of manifest.assets) {
  if (asset.slot === "base") continue;
  const body = layerSvg(asset.file).replace(/<svg[^>]*>|<\/svg>/g, "");
  const label = asset.id;
  cells.push(
    `<div class="cell"><svg viewBox="150 30 724 724" width="150" height="150">${baseBody}${body}</svg><span title="${label}">${label}</span></div>`,
  );
}

const html = `<!doctype html><meta charset="utf-8">
<style>body{background:#F7F2E9;display:flex;flex-wrap:wrap;gap:8px;padding:8px;font-family:sans-serif;font-size:10px}
.cell{display:flex;flex-direction:column;align-items:center;width:154px}span{overflow:hidden;max-width:150px;white-space:nowrap}</style>
${cells.join("\n")}`;
writeFileSync(join(out, "..", "fixtures", "sheet.html"), html);
console.log("ok");
