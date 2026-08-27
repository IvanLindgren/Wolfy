/**
 * Валидатор runtime-пака компаньона. Падает с ненулевым кодом, если:
 * у SVG не тот viewBox, встречаются опасные элементы, неизвестный слот,
 * дублирующийся ID, отсутствующий файл, неверная версия якорей или файл
 * больше лимита. Запуск: node tools/companions/validate.mjs
 */
import { readFileSync, existsSync, statSync, readdirSync } from "node:fs";
import { join, dirname, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const pack = join(root, "client", "assets", "companions", "notionists-v1", "runtime");
const errors = [];

function filesBelow(directory) {
  const result = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const full = join(directory, entry.name);
    if (entry.isDirectory()) result.push(...filesBelow(full));
    else result.push(full);
  }
  return result;
}

const manifest = JSON.parse(readFileSync(join(pack, "manifest.json"), "utf8"));
if (manifest.schemaVersion !== 1) errors.push("schemaVersion должен быть 1");
if (manifest.packId !== "notionists-wolfy-v1") errors.push("неожиданный packId");
if (manifest.canvas.width !== 1024 || manifest.canvas.height !== 1024) errors.push("холст обязан быть 1024x1024");

const SLOTS = ["accessoryBack", "base", "body", "hair", "brows", "eyes", "nose", "mouth", "beard", "gesture", "accessoryFront"];
for (const slot of SLOTS) {
  if (!manifest.layerOrder.includes(slot)) errors.push(`в layerOrder нет слота ${slot}`);
}
if (manifest.layerOrder.length !== SLOTS.length) errors.push("layerOrder содержит неизвестные слоты");

const allowedFills = new Set([
  ...manifest.colorTokens.map((t) => `var(${t})`),
  "#FFFFFF",
]);
const ids = new Set();
let totalBytes = 0;
const MAX_FILE = 48 * 1024;
const seen = new Set();

for (const asset of manifest.assets) {
  if (ids.has(asset.id)) errors.push(`дублирующийся ID: ${asset.id}`);
  ids.add(asset.id);
  if (!SLOTS.includes(asset.slot)) errors.push(`${asset.id}: неизвестный слот ${asset.slot}`);
  if (asset.anchorsVersion !== 1) errors.push(`${asset.id}: anchorsVersion должен быть 1`);
  const file = join(pack, asset.file);
  if (!existsSync(file)) {
    errors.push(`${asset.id}: файл не найден: ${asset.file}`);
    continue;
  }
  if (seen.has(asset.file)) errors.push(`файл упомянут дважды: ${asset.file}`);
  seen.add(asset.file);
  const size = statSync(file).size;
  totalBytes += size;
  if (size > MAX_FILE) errors.push(`${asset.file}: ${size} байт, лимит ${MAX_FILE}`);
  const xml = readFileSync(file, "utf8");
  const vb = /viewBox="0 0 1024 1024"/.test(xml);
  if (!vb) errors.push(`${asset.file}: viewBox обязан быть "0 0 1024 1024"`);
  for (const bad of ["<script", "<image", "<foreignObject", "<filter", "<text", "<style", "xlink:href", "url("]) {
    if (xml.toLowerCase().includes(bad.toLowerCase())) errors.push(`${asset.file}: запрещённый фрагмент ${bad}`);
  }
  for (const fill of xml.matchAll(/fill="([^"]+)"/g)) {
    if (!allowedFills.has(fill[1])) errors.push(`${asset.file}: незнакомый цвет ${fill[1]}`);
  }
  for (const transform of xml.matchAll(/transform="([^"]+)"/g)) {
    for (const fn of transform[1].matchAll(/([A-Za-z]+)\s*\(/g)) {
      if (!["matrix", "translate", "scale"].includes(fn[1])) {
        errors.push(`${asset.file}: transform ${fn[1]} не поддерживается клиентом`);
      }
    }
  }
  for (const path of xml.matchAll(/\bd="([^"]+)"/g)) {
    for (const command of path[1].matchAll(/[AaCcHhLlMmQqSsTtVvZz]/g)) {
      if (!/[CcHhLlMmVvZz]/.test(command[0])) {
        errors.push(`${asset.file}: команда пути ${command[0]} не поддерживается клиентом`);
      }
    }
  }
}
// "none" должен быть у каждого необязательного слота с исходниками.
// accessoryBack в Notionists v1 исходников не имеет и остаётся пустым слотом.
for (const slot of ["body", "hair", "brows", "eyes", "nose", "mouth", "beard", "gesture", "accessoryFront"]) {
  if (!manifest.assets.some((a) => a.slot === slot && a.id.endsWith(".none"))) {
    errors.push(`у слота ${slot} нет варианта none`);
  }
}
// Каждый файл каталога слоёв должен быть упомянут в манифесте.
const layersDir = join(pack, "layers");
for (const file of filesBelow(layersDir)) {
  const rel = `layers/${relative(layersDir, file).split(sep).join("/")}`;
  if (!seen.has(rel)) errors.push(`файл вне манифеста: ${rel}`);
}

const mb = totalBytes / 1024 / 1024;
if (mb > 8) errors.push(`пак ${mb.toFixed(2)} MB, бюджет 8 MB`);

// Копии пакa для KMP-ресурсов и web/public обязаны совпадать с источником:
// расходятся — значит кто-то правил одну сторону руками.
for (const copy of [
  join(root, "client", "shared", "src", "commonMain", "composeResources", "files", "companions"),
  join(root, "web", "public", "companions"),
]) {
  const manifestCopy = join(copy, "manifest.json");
  if (!existsSync(manifestCopy)) {
    errors.push(`нет копии пакa: ${copy}`);
    continue;
  }
  if (readFileSync(manifestCopy, "utf8") !== readFileSync(join(pack, "manifest.json"), "utf8")) {
    errors.push(`manifest.json расходится с источником: ${copy}`);
  }
  for (const asset of manifest.assets) {
    const p = join(copy, asset.file);
    if (!existsSync(p) || !readFileSync(p).equals(readFileSync(join(pack, asset.file)))) {
      errors.push(`слой расходится с источником: ${asset.file} в ${copy}`);
    }
  }
  const expected = new Set(manifest.assets.map((asset) => asset.file));
  const copyLayers = join(copy, "layers");
  if (existsSync(copyLayers)) {
    for (const file of filesBelow(copyLayers)) {
      const rel = `layers/${relative(copyLayers, file).split(sep).join("/")}`;
      if (!expected.has(rel)) errors.push(`лишний слой в копии: ${rel} в ${copy}`);
    }
  }
}

if (errors.length) {
  console.error(`runtime-пак не прошёл проверку (${errors.length}):`);
  for (const e of errors) console.error("  " + e);
  process.exit(1);
}
console.log(`runtime-пак в порядке: ${manifest.assets.length} слоёв, ${mb.toFixed(2)} MB`);
