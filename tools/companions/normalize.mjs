/**
 * РќРѕСЂРјР°Р»РёР·Р°С†РёСЏ РёСЃС…РѕРґРЅС‹С… РєРѕРјРїРѕРЅРµРЅС‚РѕРІ Notionists v1 РІ runtime-РїР°Рє.
 *
 * РСЃС…РѕРґРЅС‹Рµ SVG СЌРєСЃРїРѕСЂС‚РёСЂРѕРІР°РЅС‹ РёР· Figma РєР°Р¶РґС‹Р№ СЃРѕ СЃРІРѕРµР№ СЂР°РјРєРѕР№: РїРѕР·РёС†РёСЏ
 * РєРѕРјРїРѕРЅРµРЅС‚Р° РЅР° РѕР±С‰РµРј В«С…РѕР»СЃС‚Рµ РїРµСЂСЃРѕРЅР°Р¶Р°В» РІ С„Р°Р№Р»Р°С… РЅРµ СЃРѕС…СЂР°РЅРµРЅР°. РћРЅР°
 * РІРѕСЃСЃС‚Р°РЅР°РІР»РёРІР°РµС‚СЃСЏ РїРѕ 18 РіРѕС‚РѕРІС‹Рј РїСЂРёРјРµСЂР°Рј: РјР°СЃРєР° РєРѕРјРїРѕРЅРµРЅС‚Р° РґРѕР»Р¶РЅР° С†РµР»РёРєРѕРј
 * Р»РµС‡СЊ РІ РѕРґРЅРѕС†РІРµС‚РЅСѓСЋ РјР°СЃРєСѓ РїСЂРёРјРµСЂР°. РљР°РЅРґРёРґР°С‚С‹ РїРѕР»РѕР¶РµРЅРёСЏ РґР°СЋС‚ РїР°СЂС‹ СЂР°РјРѕРє
 * РѕРґРёРЅР°РєРѕРІРѕРіРѕ СЂР°Р·РјРµСЂР°, РїРѕР±РµР¶РґР°РµС‚ РїРѕР»РѕР¶РµРЅРёРµ СЃ РЅР°РёР±РѕР»СЊС€РёРј РїРѕРєСЂС‹С‚РёРµРј РєРѕРјРїРѕРЅРµРЅС‚Р°.
 * РЎРјРµС‰РµРЅРёРµ РѕС‚РЅРѕСЃРёС‚РµР»СЊРЅРѕ base, РїРѕРґС‚РІРµСЂР¶РґС‘РЅРЅРѕРµ РІ РЅРµСЃРєРѕР»СЊРєРёС… РїСЂРёРјРµСЂР°С…, СЃС‡РёС‚Р°РµС‚СЃСЏ
 * РєР°РЅРѕРЅРёС‡РµСЃРєРёРј.
 *
 * РќРµ РїРѕРґС‚РІРµСЂР¶РґС‘РЅРЅС‹Рµ РєРѕРјРїРѕРЅРµРЅС‚С‹ РІ runtime РЅРµ РїРѕРїР°РґР°СЋС‚: РїРѕР·РёС†РёСЏ Р±РµР·
 * РїРѕРґС‚РІРµСЂР¶РґРµРЅРёСЏ РѕР·РЅР°С‡Р°РµС‚ РїРµСЂРµРєРѕС€РµРЅРЅРѕРіРѕ РїРµСЂСЃРѕРЅР°Р¶Р° РІ РїСЂРѕРґР°РєС€РµРЅРµ.
 *
 * Р—Р°РїСѓСЃРє: node tools/companions/normalize.mjs
 */
import { readFileSync, writeFileSync, mkdirSync, readdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const src = join(root, "client", "assets", "companions", "notionists-v1");
const out = join(src, "runtime");

const CANVAS = 1024;
const SLOT_DIRS = {
  base: "base",
  body: "bodies",
  hair: "hair",
  brows: "brows",
  eyes: "eyes",
  nose: "noses",
  mouth: "mouths",
  beard: "beards",
  accessoryFront: "accessories",
  gesture: "gestures",
};

// Р‘РµР»РѕРјСѓ РёСЃС…РѕРґРЅРёРєР° СЃРјС‹СЃР» Р·Р°РґР°С‘С‚ СЃР»РѕС‚; Р±РµР»С‹Р№ СЃРєР»РµСЂС‹ РіР»Р°Р·Р° Рё Р±СѓРјР°РіРё РІ Р¶РµСЃС‚Р°С… РЅРµ
// РїРµСЂРµРєСЂР°С€РёРІР°РµС‚СЃСЏ. РўРѕРєРµРЅС‹ СЂР°Р·РІРѕСЂР°С‡РёРІР°РµС‚ СЂРµРЅРґРµСЂРµСЂ РєР°Р¶РґРѕР№ РїР»Р°С‚С„РѕСЂРјС‹.
const FILL_MAP = {
  base: { "#FFFFFF": "var(--wolfy-skin)", "#000000": "var(--wolfy-ink)" },
  body: { "#FFFFFF": "var(--wolfy-outfit)", "#000000": "var(--wolfy-ink)" },
  hair: { "#FFFFFF": "var(--wolfy-hair)", "#000000": "var(--wolfy-ink)" },
  beard: { "#FFFFFF": "var(--wolfy-hair)", "#000000": "var(--wolfy-ink)" },
  brows: { "#FFFFFF": "var(--wolfy-hair)", "#000000": "var(--wolfy-ink)" },
  eyes: { "#FFFFFF": "#FFFFFF", "#000000": "var(--wolfy-ink)" },
  nose: { "#FFFFFF": "#FFFFFF", "#000000": "var(--wolfy-ink)" },
  mouth: { "#FFFFFF": "#FFFFFF", "#000000": "var(--wolfy-ink)" },
  accessoryFront: { "#FFFFFF": "var(--wolfy-accent)", "#000000": "var(--wolfy-ink)" },
  accessoryBack: { "#FFFFFF": "var(--wolfy-accent)", "#000000": "var(--wolfy-ink)" },
  gesture: { "#FFFFFF": "#FFFFFF", "#000000": "var(--wolfy-ink)" },
};

// ---------- Р°С„С„РёРЅРЅС‹Рµ РїСЂРµРѕР±СЂР°Р·РѕРІР°РЅРёСЏ ----------

const IDENT = [1, 0, 0, 1, 0, 0];
function mul(m, n) {
  return [
    m[0] * n[0] + m[2] * n[1],
    m[1] * n[0] + m[3] * n[1],
    m[0] * n[2] + m[2] * n[3],
    m[1] * n[2] + m[3] * n[3],
    m[0] * n[4] + m[2] * n[5] + m[4],
    m[1] * n[4] + m[3] * n[5] + m[5],
  ];
}
function apply(m, [x, y]) {
  return [m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]];
}
const RAD = Math.PI / 180;
function parseTransform(s) {
  let m = IDENT;
  const re = /(matrix|translate|scale|rotate)\(([^)]*)\)/g;
  let t;
  while ((t = re.exec(s))) {
    const args = t[2].split(/[\s,]+/).filter(Boolean).map(Number);
    let f = IDENT;
    if (t[1] === "translate") f = [1, 0, 0, 1, args[0], args[1] ?? 0];
    else if (t[1] === "scale") f = [args[0], 0, 0, args[1] ?? args[0], 0, 0];
    else if (t[1] === "rotate") {
      const a = args[0] * RAD;
      const [px, py] = args.length > 2 ? [args[1], args[2]] : [0, 0];
      const r = [Math.cos(a), Math.sin(a), -Math.sin(a), Math.cos(a), 0, 0];
      f = mul(mul([1, 0, 0, 1, px, py], r), [1, 0, 0, 1, -px, -py]);
    } else f = args;
    m = mul(m, f);
  }
  return m;
}

// ---------- РіРµРѕРјРµС‚СЂРёСЏ ----------

function samplePath(d) {
  if (/[aA]/.test(d.replace(/[^aA]/g, ""))) throw new Error("РґСѓРіР° РІ path РЅРµ РїРѕРґРґРµСЂР¶Р°РЅР°");
  const tokens = d.match(/[MLCQHVZmlcqhvz]|-?[\d.]+/g) ?? [];
  const pts = [];
  let i = 0;
  let cx = 0, cy = 0, sx = 0, sy = 0;
  let cmd = null;
  const num = () => parseFloat(tokens[i++]);
  while (i < tokens.length) {
    const t = tokens[i];
    if (/[MLCQHVZmlcqhvz]/.test(t)) {
      cmd = t;
      i += 1;
      if (cmd.toUpperCase() === "Z") {
        pts.push([sx, sy]);
        cx = sx; cy = sy;
        continue;
      }
    }
    const rel = cmd === cmd.toLowerCase();
    switch (cmd.toUpperCase()) {
      case "M": {
        const x = rel ? cx + num() : num();
        const y = rel ? cy + num() : num();
        if (!rel) { sx = x; sy = y; }
        cx = x; cy = y;
        pts.push([cx, cy]);
        if (cmd === "M") cmd = "L";
        if (cmd === "m") cmd = "l";
        break;
      }
      case "L": {
        cx = rel ? cx + num() : num();
        cy = rel ? cy + num() : num();
        pts.push([cx, cy]);
        break;
      }
      case "H": cx = rel ? cx + num() : num(); pts.push([cx, cy]); break;
      case "V": cy = rel ? cy + num() : num(); pts.push([cx, cy]); break;
      case "C": case "Q": {
        const n = cmd.toUpperCase() === "C" ? 3 : 2;
        const x0 = cx, y0 = cy;
        const cp = [];
        for (let k = 0; k < n; k += 1) cp.push([rel ? cx + num() : num(), rel ? cy + num() : num()]);
        for (let s = 1; s <= 8; s += 1) pts.push(bezier(x0, y0, cp, s / 8, n));
        [cx, cy] = cp[n - 1];
        break;
      }
      case "S": {
        const x2 = rel ? cx + num() : num();
        const y2 = rel ? cy + num() : num();
        const x = rel ? cx + num() : num();
        const y = rel ? cy + num() : num();
        for (let s = 1; s <= 8; s += 1) pts.push(bezier(cx, cy, [[x2, y2], [x, y]], s / 8, 2));
        cx = x; cy = y;
        break;
      }
      default:
        throw new Error(`РЅРµРёР·РІРµСЃС‚РЅР°СЏ РєРѕРјР°РЅРґР° path: ${cmd}`);
    }
  }
  return pts;
}

function bezier(x0, y0, cp, t, n) {
  const mt = 1 - t;
  if (n === 3) {
    const [x1, y1] = cp[0], [x2, y2] = cp[1], [x3, y3] = cp[2];
    return [
      mt * mt * mt * x0 + 3 * mt * mt * t * x1 + 3 * mt * t * t * x2 + t * t * t * x3,
      mt * mt * mt * y0 + 3 * mt * mt * t * y1 + 3 * mt * t * t * y2 + t * t * t * y3,
    ];
  }
  const [x1, y1] = cp[0], [x2, y2] = cp[1];
  return [
    mt * mt * x0 + 2 * mt * t * x1 + t * t * x2,
    mt * mt * y0 + 2 * mt * t * y1 + t * t * y2,
  ];
}

/** Р Р°Р·Р±РѕСЂ С„Р°Р№Р»Р°: С„РёРіСѓСЂС‹ СЃ Р°Р±СЃРѕР»СЋС‚РЅС‹РјРё РєРѕРѕСЂРґРёРЅР°С‚Р°РјРё. РќР°СЃР»РµРґРѕРІР°РЅРёРµ fill Рё
 * fill-rule РѕС‚ РіСЂСѓРїРї СѓС‡РёС‚С‹РІР°РµС‚СЃСЏ: Сѓ РєРѕРјРїРѕРЅРµРЅС‚РѕРІ РїСѓС‚СЊ С‡Р°СЃС‚Рѕ РЅРµ РёРјРµРµС‚ СЃРІРѕРµРіРѕ
 * fill Рё РєСЂР°СЃРёС‚СЃСЏ РіСЂСѓРїРїРѕР№. */
function parseSvg(xml) {
  const vb = /viewBox="0 0 ([\d.]+) ([\d.]+)"/.exec(xml);
  if (!vb) throw new Error("РЅРµС‚ viewBox");
  const shapes = [];
  const stack = [{ mtx: IDENT, fill: "#000000", rule: "nonzero" }];
  const tagRe = /<(\/?)(g|svg)\b([^>]*)>|<(path|ellipse|rect|circle)\b([^>]*?)(\/?)>/g;
  let m;
  while ((m = tagRe.exec(xml))) {
    if (m[1] === "/") {
      if (stack.length > 1) stack.pop();
      continue;
    }
    if (m[2] !== undefined) {
      const attrs = m[3];
      const top = stack[stack.length - 1];
      const tf = /transform="([^"]+)"/.exec(attrs);
      const rawFill = (/fill="([^"]+)"/.exec(attrs) ?? [])[1];
      const rawRule = (/fill-rule="([^"]+)"/.exec(attrs) ?? [])[1];
      stack.push({
        mtx: mul(top.mtx, tf ? parseTransform(tf[1]) : IDENT),
        fill: rawFill !== undefined ? normFill(rawFill) : top.fill,
        rule: rawRule !== undefined ? rawRule.toLowerCase() : top.rule,
      });
      continue;
    }
    const tag = m[4];
    const attrs = m[5];
    const top = stack[stack.length - 1];
    const tf = /transform="([^"]+)"/.exec(attrs);
    const mtx = mul(top.mtx, tf ? parseTransform(tf[1]) : IDENT);
    const rawFill = (/fill="([^"]+)"/.exec(attrs) ?? [])[1];
    const rawRule = (/fill-rule="([^"]+)"/.exec(attrs) ?? [])[1];
    const fill = rawFill !== undefined ? normFill(rawFill) : top.fill;
    const rule = rawRule !== undefined ? rawRule.toLowerCase() : top.rule;
    if (tag === "path") {
      const d = /d="([^"]+)"/.exec(attrs);
      if (!d) continue;
      const abs = samplePath(d[1]).map((p) => apply(mtx, p));
      const subs = [];
      const rawTokens = d[1].match(/[MLCQHVZmlcqhvz]|-?[\d.]+/g) ?? [];
      let i = 0, cx = 0, cy = 0, sx = 0, sy = 0, cmd = null;
      const num = () => parseFloat(rawTokens[i++]);
      const acc = [];
      const flush = () => { if (acc.length) subs.push(acc.splice(0)); };
      while (i < rawTokens.length) {
        const t = rawTokens[i];
        if (/[MLCQHVZmlcqhvz]/.test(t)) {
          if (t.toUpperCase() === "M") flush();
          cmd = t;
          i += 1;
          if (cmd.toUpperCase() === "Z") { cx = sx; cy = sy; continue; }
        }
        const rel = cmd === cmd.toLowerCase();
        switch (cmd.toUpperCase()) {
          case "M": {
            const x = rel ? cx + num() : num();
            const y = rel ? cy + num() : num();
            if (!rel) { sx = x; sy = y; }
            cx = x; cy = y;
            acc.push([cx, cy]);
            if (cmd === "M") cmd = "L";
            if (cmd === "m") cmd = "l";
            break;
          }
          case "L": {
            cx = rel ? cx + num() : num();
            cy = rel ? cy + num() : num();
            acc.push([cx, cy]);
            break;
          }
          case "H": cx = rel ? cx + num() : num(); acc.push([cx, cy]); break;
          case "V": cy = rel ? cy + num() : num(); acc.push([cx, cy]); break;
          case "C": case "Q": {
            const n = cmd.toUpperCase() === "C" ? 3 : 2;
            const x0 = cx, y0 = cy;
            const cp = [];
            for (let k = 0; k < n; k += 1) cp.push([rel ? cx + num() : num(), rel ? cy + num() : num()]);
            for (let s = 1; s <= 4; s += 1) acc.push(bezier(x0, y0, cp, s / 4, n));
            [cx, cy] = cp[n - 1];
            break;
          }
          case "S": {
            const x2 = rel ? cx + num() : num();
            const y2 = rel ? cy + num() : num();
            const x = rel ? cx + num() : num();
            const y = rel ? cy + num() : num();
            for (let s = 1; s <= 4; s += 1) acc.push(bezier(cx, cy, [[x2, y2], [x, y]], s / 4, 2));
            cx = x; cy = y;
            break;
          }
          default:
            throw new Error(`РЅРµРёР·РІРµСЃС‚РЅР°СЏ РєРѕРјР°РЅРґР° path: ${cmd}`);
        }
      }
      flush();
      shapes.push({
        tag,
        fill,
        rule,
        d: d[1],
        mtx,
        pts: abs,
        subs: subs.map((pts) => ({ pts: pts.map((p) => apply(mtx, p)), box: bboxOf(pts.map((p) => apply(mtx, p))) })),
      });
    } else if (tag === "ellipse" || tag === "circle") {
      const cx = parseFloat((/cx="(-?[\d.]+)"/.exec(attrs) ?? [])[1] ?? "0");
      const cy = parseFloat((/cy="(-?[\d.]+)"/.exec(attrs) ?? [])[1] ?? "0");
      const rx = tag === "circle" ? parseFloat((/r="([\d.]+)"/.exec(attrs) ?? [])[1] ?? "0") : parseFloat((/rx="([\d.]+)"/.exec(attrs) ?? [])[1] ?? "0");
      const ry = tag === "circle" ? rx : parseFloat((/ry="([\d.]+)"/.exec(attrs) ?? [])[1] ?? "0");
      const pts = [];
      for (let s = 0; s < 24; s += 1) {
        const a = (s / 24) * Math.PI * 2;
        pts.push(apply(mtx, [cx + rx * Math.cos(a), cy + ry * Math.sin(a)]));
      }
      shapes.push({ tag, fill, rule, cx, cy, rx, ry, mtx, pts, subs: [{ pts, box: bboxOf(pts) }] });
    } else {
      const x = parseFloat((/x="(-?[\d.]+)"/.exec(attrs) ?? [])[1] ?? "0");
      const y = parseFloat((/y="(-?[\d.]+)"/.exec(attrs) ?? [])[1] ?? "0");
      const w = parseFloat((/width="([\d.]+)"/.exec(attrs) ?? [])[1] ?? "0");
      const h = parseFloat((/height="([\d.]+)"/.exec(attrs) ?? [])[1] ?? "0");
      const pts = [[x, y], [x + w, y], [x + w, y + h], [x, y + h]].map((p) => apply(mtx, p));
      shapes.push({ tag, fill, rule, x, y, w, h, mtx, pts, subs: [{ pts, box: bboxOf(pts) }] });
    }
  }
  return { viewBox: [parseFloat(vb[1]), parseFloat(vb[2])], shapes };
}

function normFill(raw) {
  const v = raw.toLowerCase();
  if (v === "white") return "#FFFFFF";
  if (v === "black") return "#000000";
  return v.toUpperCase();
}

function bboxOf(pts) {
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const [x, y] of pts) {
    if (x < x0) x0 = x;
    if (y < y0) y0 = y;
    if (x > x1) x1 = x;
    if (y > y1) y1 = y;
  }
  return { x0, y0, x1, y1, w: x1 - x0, h: y1 - y0 };
}

// ---------- СЂР°СЃС‚РµСЂРёР·Р°С†РёСЏ ----------

const RS = 0.25; // РјР°СЃС€С‚Р°Р± РјР°СЃРѕРє: РїРёРєСЃРµР»СЊ РјР°СЃРєРё = 4px РёСЃС…РѕРґРЅРёРєР°

/**
 * РњР°СЃРєР° С„РёРіСѓСЂ РјРµС‚РѕРґРѕРј С‡С‘С‚РЅРѕ-РЅРµС‡С‘С‚РЅРѕРіРѕ Р·Р°Р»РёРІРѕС‡РЅРѕРіРѕ РїСЂР°РІРёР»Р°. Р’РѕР·РІСЂР°С‰Р°РµС‚
 * {x0, y0, w, h, data: Uint8Array} РІ РєРѕРѕСЂРґРёРЅР°С‚Р°С… РјР°СЃРєРё.
 */
function rasterize(polygons, box) {
  const w = Math.ceil(box.w * RS) + 2;
  const h = Math.ceil(box.h * RS) + 2;
  const data = new Uint8Array(w * h);
  for (const poly of polygons) {
    const pts = poly.map(([x, y]) => [(x - box.x0) * RS + 1, (y - box.y0) * RS + 1]);
    let minY = Infinity, maxY = -Infinity;
    for (const [, y] of pts) {
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
    for (let row = Math.max(0, Math.floor(minY)); row <= Math.min(h - 1, Math.ceil(maxY)); row += 1) {
      const y = row + 0.5;
      const xs = [];
      for (let i = 0, j = pts.length - 1; i < pts.length; j = i, i += 1) {
        const [xi, yi] = pts[i];
        const [xj, yj] = pts[j];
        if (yi > y !== yj > y) {
          xs.push(xj + ((y - yj) / (yi - yj)) * (xi - xj));
        }
      }
      xs.sort((a, b) => a - b);
      for (let k = 0; k + 1 < xs.length; k += 2) {
        const from = Math.max(0, Math.ceil(xs[k] - 0.5));
        const to = Math.min(w - 1, Math.floor(xs[k + 1] - 0.5));
        for (let col = from; col <= to; col += 1) data[row * w + col] = 1;
      }
    }
  }
  return { x0: box.x0, y0: box.y0, w, h, data };
}

function maskArea(mask) {
  let n = 0;
  for (const v of mask.data) n += v;
  return n;
}

/** Р”РѕР»СЏ РїРёРєСЃРµР»РµР№ РєРѕРјРїРѕРЅРµРЅС‚Р°, РїРѕРїР°РІС€РёС… РІ РјР°СЃРєСѓ РїСЂРёРјРµСЂР°, РїСЂРё СЃРјРµС‰РµРЅРёРё off. */
function recall(compMask, targetMask, dx, dy) {
  // dx, dy вЂ” СЃРјРµС‰РµРЅРёРµ РІ РєРѕРѕСЂРґРёРЅР°С‚Р°С… РёСЃС…РѕРґРЅРёРєР°, РїРµСЂРµРІРѕРґРёРј РІ РїРёРєСЃРµР»Рё РјР°СЃРєРё.
  const sx = Math.round(dx * RS), sy = Math.round(dy * RS);
  let hit = 0, total = 0;
  for (let row = 0; row < compMask.h; row += 1) {
    const tr = row + sy;
    if (tr < 0 || tr >= targetMask.h) {
      // РЎС‚СЂРѕРєР° РІРЅРµ РїСЂРёРјРµСЂР°: СЃС‡РёС‚Р°РµРј РЅРµРїРѕРєСЂС‹С‚РѕР№, С‚РѕР»СЊРєРѕ РµСЃР»Рё РІ РЅРµР№ РµСЃС‚СЊ РµРґРёРЅРёС†С‹.
      for (let col = 0; col < compMask.w; col += 1) total += compMask.data[row * compMask.w + col];
      continue;
    }
    for (let col = 0; col < compMask.w; col += 1) {
      const v = compMask.data[row * compMask.w + col];
      if (!v) continue;
      total += 1;
      const tc = col + sx;
      if (tc >= 0 && tc < targetMask.w && targetMask.data[tr * targetMask.w + tc]) hit += 1;
    }
  }
  return total === 0 ? 0 : hit / total;
}

// ---------- Р·Р°РіСЂСѓР·РєР° ----------

const components = [];
for (const [slot, dir] of Object.entries(SLOT_DIRS)) {
  const dirPath = join(src, "library", "vector", dir);
  for (const f of readdirSync(dirPath).sort()) {
    if (!f.endsWith(".svg")) continue;
    const num0 = f.replace(/\.svg$/, "");
    const id = `${slot}.${num0 === "none" ? "none" : num0.replace(/^0+/, "").padStart(2, "0")}`;
    const parsed = parseSvg(readFileSync(join(dirPath, f), "utf8"));
    components.push({
      slot,
      file: `${dir}/${f}`,
      id,
      shapes: parsed.shapes,
      box: bboxOf(parsed.shapes.flatMap((s) => s.pts)),
    });
  }
}

const examples = readdirSync(join(src, "library", "examples", "vector"))
  .filter((f) => f.endsWith(".svg"))
  .sort()
  .map((f) => {
    const parsed = parseSvg(readFileSync(join(src, "library", "examples", "vector", f), "utf8"));
    const allBox = bboxOf(parsed.shapes.flatMap((s) => s.pts));
    const white = parsed.shapes.filter((s) => s.fill === "#FFFFFF");
    const black = parsed.shapes.filter((s) => s.fill === "#000000");
    const canvasBox = { x0: allBox.x0, y0: allBox.y0, w: allBox.w, h: allBox.h };
    return {
      file: f,
      box: canvasBox,
      whiteMask: rasterize(white.map((s) => s.pts), allBox),
      blackMask: rasterize(black.map((s) => s.pts), allBox),
      shapes: parsed.shapes.map((s) => ({ ...s, box: bboxOf(s.pts) })),
    };
  });

// ---------- СЃРѕРїРѕСЃС‚Р°РІР»РµРЅРёРµ ----------

// РљРѕРјРїРѕРЅРµРЅС‚ РёС‰РµРј РїРѕ СЃРІРѕРёРј С„РёРіСѓСЂР°Рј РѕРґРЅРѕРіРѕ С†РІРµС‚Р°: РјР°СЃРєР° РєРѕРјРїРѕРЅРµРЅС‚Р° РѕР±СЏР·Р°РЅР°
// Р»РµС‡СЊ РІ РѕРґРЅРѕС†РІРµС‚РЅСѓСЋ РјР°СЃРєСѓ РїСЂРёРјРµСЂР°. РџРѕРєСЂС‹С‚РёРµ 0.75 РґРѕСЃС‚Р°С‚РѕС‡РЅРѕ, С‡С‚РѕР±С‹
// РїРµСЂРµСЂРёСЃРѕРІР°РЅРЅС‹Рµ РїСѓС‚Рё РїСЂРёРјРµСЂРѕРІ СЃС‡РёС‚Р°Р»РёСЃСЊ СЃРѕРІРїР°РґРµРЅРёРµРј, Р° С‡СѓР¶Р°СЏ С„РёРіСѓСЂР° РЅРµС‚.
const RECALL_TOL = 0.75;

/** РљР°РЅРґРёРґР°С‚С‹-СЃРјРµС‰РµРЅРёСЏ РїРѕ СЃРѕРІРїР°РґРµРЅРёСЋ СЂР°РјРѕРє РїРѕРґРїСѓС‚РµР№ С‚РѕРіРѕ Р¶Рµ С†РІРµС‚Р°:
 * Сѓ РїСЂРёРјРµСЂР° РєРѕРЅС‚СѓСЂ Р±С‹РІР°РµС‚ СЂР°Р·Р±РёС‚ РёРЅР°С‡Рµ, С‡РµРј Сѓ РєРѕРјРїРѕРЅРµРЅС‚Р°. */
function candidateOffsets(variant, example) {
  const cands = [];
  const compSubs = variant.shapes.flatMap((s) => s.subs ?? [{ pts: s.pts, box: bboxOf(s.pts) }]);
  const exSubs = example.shapes
    .filter((s) => s.fill === variant.fill)
    .flatMap((s) => s.subs ?? [{ pts: s.pts, box: bboxOf(s.pts) }]);
  for (const cs of compSubs) {
    for (const es of exSubs) {
      const rw = es.box.w / cs.box.w;
      const rh = es.box.h / cs.box.h;
      if (rw < 0.82 || rw > 1.22 || rh < 0.82 || rh > 1.22) continue;
      cands.push([es.box.x0 - cs.box.x0, es.box.y0 - cs.box.y0]);
    }
  }
  return cands;
}

/**
 * РџРѕР»РѕР¶РµРЅРёРµ РєРѕРјРїРѕРЅРµРЅС‚Р° РІ РїСЂРёРјРµСЂРµ: Р»СѓС‡С€РµРµ РїРѕРєСЂС‹С‚РёРµ РµРіРѕ РјР°СЃРєРё С‚РѕРіРѕ Р¶Рµ С†РІРµС‚Р°.
 * Р’РѕР·РІСЂР°С‰Р°РµС‚ { offset, recall, fill } РёР»Рё null.
 */
function matchInExample(comp, example) {
  const white = comp.shapes.filter((s) => s.fill === "#FFFFFF");
  const black = comp.shapes.filter((s) => s.fill === "#000000");
  const variants = [];
  if (white.length) variants.push({ fill: "#FFFFFF", shapes: white });
  if (black.length) variants.push({ fill: "#000000", shapes: black });
  let best = null;
  for (const variant of variants) {
    const vBox = bboxOf(variant.shapes.flatMap((s) => s.pts));
    const mask = rasterize(variant.shapes.map((s) => s.pts), vBox);
    const target = variant.fill === "#FFFFFF" ? example.whiteMask : example.blackMask;
    for (const [cx, cy] of candidateOffsets(variant, example)) {
      for (let ddx = -4; ddx <= 4; ddx += 2) {
        for (let ddy = -4; ddy <= 4; ddy += 2) {
          const off = [cx + ddx, cy + ddy];
          // РЎРјРµС‰РµРЅРёРµ РјР°СЃРєРё РєРѕРјРїРѕРЅРµРЅС‚Р° РІ РјР°СЃРєРµ РїСЂРёРјРµСЂР°: РїРёРєСЃРµР»СЊ РјР°СЃРєРё
          // РєРѕРјРїРѕРЅРµРЅС‚Р° vBox.x0 СЃРѕРѕС‚РІРµС‚СЃС‚РІСѓРµС‚ РїСЂРёРјРµСЂСѓ РІ off + vBox.x0.
          const dx = off[0] + vBox.x0 - example.box.x0;
          const dy = off[1] + vBox.y0 - example.box.y0;
          const r = recall(mask, target, dx, dy);
          if (r < RECALL_TOL) continue;
          const score = r * (variant.fill === "#FFFFFF" ? 1.001 : 1);
          if (!best || score > best.score) {
            best = { score, offset: off, fill: variant.fill };
          }
        }
      }
    }
  }
  return best ? { offset: best.offset, fill: best.fill, recall: best.score } : null;
}

const baseComp = components.find((c) => c.slot === "base");
if (!baseComp) throw new Error("РЅРµС‚ base");

if (process.env.DEBUG_BASE) {
  for (const ex of examples) {
    const b = matchInExample(baseComp, ex);
    console.log("base", ex.file, b ? `${b.fill} r=${b.recall.toFixed(3)} off=[${b.offset.map((v) => v.toFixed(1))}]` : "РЅРµС‚");
  }
  const body1 = components.find((c) => c.id === "body.01");
  for (const ex of examples.slice(0, 6)) {
    const b = body1 ? matchInExample(body1, ex) : null;
    console.log("body.01", ex.file, b ? `${b.fill} r=${b.recall.toFixed(3)} off=[${b.offset.map((v) => v.toFixed(1))}]` : "РЅРµС‚");
  }
  for (const slot of ["hair", "beard", "nose", "mouth", "gesture", "accessoryFront"]) {
    const requested = process.env.DEBUG_COMPONENT;
    const comp = components.find((c) => c.slot === slot && c.id === requested)
      ?? components.find((c) => c.slot === slot && !c.id.endsWith("none"));
    if (!comp) continue;
    if (requested === comp.id) console.log("component-box", comp.id, JSON.stringify(comp.box));
    const obs = [];
    for (const ex of examples) {
      const b = matchInExample(comp, ex);
      const bb = matchInExample(baseComp, ex);
      if (!b || !bb) continue;
      obs.push(`${ex.file}:${b.fill} r=${b.recall.toFixed(2)} rel=[${(b.offset[0] - bb.offset[0]).toFixed(0)},${(b.offset[1] - bb.offset[1]).toFixed(0)}]`);
    }
    console.log(comp.id, obs.join("  "));
  }
  process.exit(0);
}

const relOffsets = new Map(); // id -> [ {dx, dy, weight} ] вЂ” СЃС‹СЂС‹Рµ РЅР°Р±Р»СЋРґРµРЅРёСЏ
for (const ex of examples) {
  const baseOff = matchInExample(baseComp, ex);
  if (!baseOff) continue;
  for (const comp of components) {
    if (comp === baseComp) continue;
    const off = matchInExample(comp, ex);
    if (!off) continue;
    const rel = {
      dx: off.offset[0] - baseOff.offset[0],
      dy: off.offset[1] - baseOff.offset[1],
      weight: off.recall,
    };
    const cur = relOffsets.get(comp.id) ?? [];
    cur.push(rel);
    relOffsets.set(comp.id, cur);
  }
}

/**
 * РљР°РЅРѕРЅРёС‡РµСЃРєРѕРµ РѕС‚РЅРѕСЃРёС‚РµР»СЊРЅРѕРµ СЃРјРµС‰РµРЅРёРµ: РЅР°Р±Р»СЋРґРµРЅРёСЏ СЃРѕР±РёСЂР°СЋС‚СЃСЏ РІ РєР»Р°СЃС‚РµСЂС‹ СЃ
 * С‰РµРґСЂС‹Рј РґРѕРїСѓСЃРєРѕРј. РџРѕР±РµР¶РґР°РµС‚ РєР»Р°СЃС‚РµСЂ СЃ РЅР°РёР±РѕР»СЊС€РёРј С‡РёСЃР»РѕРј С‚РѕС‡РЅС‹С… СЃРѕРІРїР°РґРµРЅРёР№
 * (recall РЅРµ РЅРёР¶Рµ 0.96), РїСЂРё СЂР°РІРµРЅСЃС‚РІРµ: РїРѕ СЃСѓРјРјР°СЂРЅРѕРјСѓ recall. РљРѕРјРїРѕРЅРµРЅС‚
 * РїСЂРёРЅРёРјР°РµС‚СЃСЏ, РµСЃР»Рё Сѓ РєР»Р°СЃС‚РµСЂР° РµСЃС‚СЊ С‚РѕС‡РЅРѕРµ СЃРѕРІРїР°РґРµРЅРёРµ Р»РёР±Рѕ РЅРµ РјРµРЅРµРµ С‚СЂС‘С…
 * РЅР°Р±Р»СЋРґРµРЅРёР№: РѕРґРЅРѕ РЅРµС‚РѕС‡РЅРѕРµ СЃРѕРІРїР°РґРµРЅРёРµ РјРѕР¶РµС‚ Р±С‹С‚СЊ С‡СѓР¶РѕР№ РїРѕС…РѕР¶РµР№ С„РёРіСѓСЂРѕР№.
 */
function canonicalOffset(obs) {
  if (obs.length === 0) return null;
  const TOL = 24;
  const PERFECT = 0.96;
  const clusters = [];
  for (const seed of obs) {
    const members = obs.filter((o) => Math.abs(o.dx - seed.dx) <= TOL && Math.abs(o.dy - seed.dy) <= TOL);
    if (members.length < 2) continue;
    const weight = members.reduce((a, o) => a + o.weight, 0);
    const perfect = members.filter((o) => o.weight >= PERFECT).length;
    clusters.push({ members, weight, perfect });
  }
  if (clusters.length === 0) {
    // РћРґРёРЅРѕС‡РЅС‹Рµ РЅР°Р±Р»СЋРґРµРЅРёСЏ РїСЂРёРЅРёРјР°СЋС‚СЃСЏ С‚РѕР»СЊРєРѕ С‚РѕС‡РЅС‹РјРё.
    const solo = obs.find((o) => o.weight >= PERFECT);
    if (!solo) return null;
    return { dx: solo.dx, dy: solo.dy, votes: 1, share: 1 };
  }
  let best = clusters[0];
  for (const c of clusters) {
    if (c.perfect > best.perfect || (c.perfect === best.perfect && c.weight > best.weight)) best = c;
  }
  if (best.perfect === 0 && best.members.length < 3) return null;
  const wsum = best.weight;
  return {
    dx: best.members.reduce((a, o) => a + o.dx * o.weight, 0) / wsum,
    dy: best.members.reduce((a, o) => a + o.dy * o.weight, 0) / wsum,
    votes: best.members.length,
    share: best.members.length / obs.length,
  };
}

// ---------- РІС‹Р±РѕСЂ MVP-РЅР°Р±РѕСЂР° Рё РіРµРЅРµСЂР°С†РёСЏ ----------

const WANTED = { base: 1, body: 6, hair: 8, brows: 4, eyes: 6, nose: 4, mouth: 6, beard: 3, accessoryFront: 1, gesture: 2 };

// Р”РѕРїСѓСЃС‚РёРјС‹Рµ РѕРєРЅР° РїРѕР»РѕР¶РµРЅРёСЏ СЃР»РѕСЏ, РґРѕР»Рё СЂР°РјРєРё base: С‡РµСЂС‚С‹ Р»РёС†Р° Р¶РёРІСѓС‚ РЅР° Р»РёС†Рµ,
// Рё СЃРѕРІРїР°РґРµРЅРёРµ В«РЅРѕСЃ РЅР° СѓС…РµВ» РѕР±СЏР·Р°РЅРѕ РѕС‚Р±СЂР°СЃС‹РІР°С‚СЊСЃСЏ РґР°Р¶Рµ РїСЂРё РІС‹СЃРѕРєРѕРј recall.
const SLOT_WINDOWS = {
  body: { x0: 0.0, x1: 1.0, y0: 0.2, y1: 1.0 },
  hair: { x0: 0.1, x1: 0.9, y0: -0.05, y1: 0.3 },
  brows: { x0: 0.28, x1: 0.72, y0: 0.06, y1: 0.2 },
  eyes: { x0: 0.26, x1: 0.74, y0: 0.06, y1: 0.3 },
  nose: { x0: 0.34, x1: 0.6, y0: 0.14, y1: 0.28 },
  mouth: { x0: 0.34, x1: 0.62, y0: 0.17, y1: 0.3 },
  beard: { x0: 0.25, x1: 0.75, y0: 0.15, y1: 0.42 },
  accessoryFront: { x0: 0.2, x1: 0.8, y0: -0.02, y1: 0.22 },
  gesture: { x0: 0.05, x1: 0.95, y0: 0.2, y1: 0.85 },
};

const baseBox = baseComp.box;
const scale = Math.min((CANVAS * 0.9) / baseBox.w, (CANVAS * 0.96) / baseBox.h);
const baseX = (CANVAS - baseBox.w * scale) / 2;
const baseY = (CANVAS - baseBox.h * scale) / 2;
const anchored = new Map();
for (const comp of components) {
  if (comp.slot === "base") continue;
  const can = canonicalOffset(relOffsets.get(comp.id) ?? []);
  if (!can) continue;
  const win = SLOT_WINDOWS[comp.slot];
  if (win) {
    const fx = (can.dx + comp.box.w / 2) / baseBox.w;
    const fy = (can.dy + comp.box.h / 2) / baseBox.h;
    if (fx < win.x0 || fx > win.x1 || fy < win.y0 || fy > win.y1) continue;
  }
  if (!anchored.has(comp.slot)) anchored.set(comp.slot, []);
  anchored.get(comp.slot).push({ comp, off: can, votes: can.votes });
}

// Носы в исходном Notionists экспортированы маленькими обрезанными SVG и в
// готовых примерах совпадают с другими короткими штрихами лица. Поэтому
// строгий поиск геометрического отпечатка не набирает достаточно голосов и
// раньше оставлял пользователю единственный пункт «Нет». Для этого слота
// безопаснее канонический центр лица: итог всё равно проходит геометрический
// валидатор и остаётся внутри окна nose.
if (!(anchored.get("nose") ?? []).some(({ comp }) => !comp.id.endsWith(".none"))) {
  // База нарисована в три четверти и смотрит вправо: геометрический центр
  // головы находится заметно левее реального центра лица.
  const targetX = baseComp.box.w * 0.67;
  const targetY = baseComp.box.h * 0.21;
  const noses = components
    .filter((comp) => comp.slot === "nose" && !comp.id.endsWith(".none"))
    .slice(0, WANTED.nose);
  anchored.set("nose", noses.map((comp) => ({
    comp,
    off: {
      dx: targetX - comp.box.w / 2,
      dy: targetY - comp.box.h / 2,
      votes: 0,
      share: 0,
    },
    votes: 0,
  })));
}

// Hair/11 имеет точное положение в примере 013. Без явного приоритета три
// неточных совпадения с другими большими чёрными силуэтами побеждают одно
// точное и поднимают причёску над головой.
const hairOffsets = new Map([
  ["hair.11", { dx: -264, dy: -286 }],
]);
const hairs = (anchored.get("hair") ?? [])
  .filter(({ comp }) => !comp.id.endsWith(".none"))
  .map(({ comp, off, votes }) => ({
    comp,
    off: hairOffsets.get(comp.id)
      ? { ...hairOffsets.get(comp.id), votes: off.votes, share: off.share }
      : off,
    votes,
  }));
if (hairs.length > 0) anchored.set("hair", hairs);

// Маленькие SVG губ геометрически почти неотличимы от коротких штрихов носа
// и бровей. В исходных готовых персонажах это иногда даёт уверенное, но
// неверное совпадение в верхней части лица. Рот у всех совместимых голов
// находится в одной безопасной зоне, поэтому нормализуем найденные варианты
// относительно её центра, сохраняя исходный размер каждого рисунка.
const mouths = (anchored.get("mouth") ?? [])
  .filter(({ comp }) => !comp.id.endsWith(".none"))
  .slice(0, WANTED.mouth);
if (mouths.length > 0) {
  const targetX = baseComp.box.w * 0.5;
  const targetY = baseComp.box.h * 0.38;
  anchored.set("mouth", mouths.map(({ comp }) => ({
    comp,
    off: {
      dx: targetX - comp.box.w / 2,
      dy: targetY - comp.box.h / 2,
      votes: 0,
      share: 0,
    },
    votes: 0,
  })));
}

// У сплошной бороды большой чёрный силуэт, поэтому поиск по маске ошибочно
// совмещает её с волосами и одеждой. Эти смещения взяты из исходных собранных
// персонажей: Beard/2 из примера 013, Beard/3 из примера 002.
const beardOffsets = new Map([
  ["beard.02", { dx: 122.5, dy: 314 }],
  ["beard.03", { dx: 127, dy: 329 }],
]);
const beards = (anchored.get("beard") ?? [])
  .filter(({ comp }) => !comp.id.endsWith(".none"))
  .map(({ comp, off, votes }) => ({
    comp,
    off: beardOffsets.get(comp.id)
      ? { ...beardOffsets.get(comp.id), votes: 0, share: 0 }
      : off,
    votes: beardOffsets.has(comp.id) ? 0 : votes,
  }));
if (beards.length > 0) anchored.set("beard", beards);


mkdirSync(join(out, "layers"), { recursive: true });
import { rmSync } from "node:fs";
rmSync(join(out, "layers"), { recursive: true, force: true });
rmSync(join(out, "manifest.json"), { force: true });
mkdirSync(join(out, "layers"), { recursive: true });
const manifestAssets = [];
const skipped = [];

function emit(comp, off, empty = false) {
  const relFile = `${comp.slot}/${comp.id.split(".")[1]}.svg`;
  mkdirSync(join(out, "layers", comp.slot), { recursive: true });
  if (empty) {
    writeFileSync(
      join(out, "layers", relFile),
      `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${CANVAS} ${CANVAS}"></svg>\n`,
    );
  } else {
    // Р­Р»РµРјРµРЅС‚С‹ РїРµСЂРµСЃРѕР±РёСЂР°СЋС‚СЃСЏ: fill РЅР°СЃР»РµРґРѕРІР°Р»СЃСЏ РіСЂСѓРїРїР°РјРё Рё РѕР±СЏР·Р°РЅ СЃС‚Р°С‚СЊ
    // СЏРІРЅС‹Рј С‚РѕРєРµРЅРѕРј, Р° РіСЂСѓРїРїРѕРІС‹Рµ transform Р·Р°РїРµРєР°СЋС‚СЃСЏ РІ РѕР±С‘СЂС‚РєСѓ РєР°Р¶РґРѕРіРѕ
    // СЌР»РµРјРµРЅС‚Р°: Сѓ Р¶РµСЃС‚РѕРІ rotate, Сѓ РІРѕР»РѕСЃ РїРµСЂРµРЅРѕСЃ, Рё Р±РµР· РЅРёС… СЃР»РѕР№ СѓРµРґРµС‚.
    const token = (fill) => FILL_MAP[comp.slot][fill] ?? "var(--wolfy-ink)";
    const mtxStr = (mtx) => {
      if (mtx.every((v, idx) => v === IDENT[idx])) return "";
      return `<g transform="matrix(${mtx.map((v) => +v.toFixed(6)).join(" ")})">`;
    };
    const parts = comp.shapes
      .filter((s) => s.fill === "#FFFFFF" || s.fill === "#000000")
      .map((s) => {
        const attrs = [`fill="${token(s.fill)}"`];
        if (s.rule === "evenodd") attrs.push('fill-rule="evenodd"');
        let el;
        if (s.tag === "path") el = `<path d="${s.d}" ${attrs.join(" ")}/>`;
        else if (s.tag === "ellipse") el = `<ellipse cx="${s.cx}" cy="${s.cy}" rx="${s.rx}" ry="${s.ry}" ${attrs.join(" ")}/>`;
        else if (s.tag === "circle") el = `<circle cx="${s.cx}" cy="${s.cy}" r="${s.rx}" ${attrs.join(" ")}/>`;
        else el = `<rect x="${s.x}" y="${s.y}" width="${s.w}" height="${s.h}" ${attrs.join(" ")}/>`;
        const wrap = mtxStr(s.mtx);
        return wrap ? `${wrap}${el}</g>` : el;
      });
    if (parts.length === 0) throw new Error(`${comp.id}: РЅРµС‚ РІРёРґРёРјС‹С… СЌР»РµРјРµРЅС‚РѕРІ`);
    const tf = `translate(${(baseX + off.dx * scale).toFixed(2)} ${(baseY + off.dy * scale).toFixed(2)}) scale(${scale.toFixed(6)})`;
    writeFileSync(
      join(out, "layers", relFile),
      `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${CANVAS} ${CANVAS}">
  <g transform="${tf}">
  ${parts.join("\n  ")}
  </g>
</svg>
`,
    );
  }
  manifestAssets.push({
    id: comp.id,
    slot: comp.slot,
    file: `layers/${relFile}`,
    tags: [],
    incompatibleWith: [],
    anchorsVersion: 1,
  });
}

emit(baseComp, { dx: 0, dy: 0 });
// none обязан быть у каждого необязательного слота, даже где ничего не
// подтвердилось: профиль может ссылаться на пустой слой.
for (const slot of Object.keys(SLOT_DIRS)) {
  if (slot === "base") continue;
  if ((anchored.get(slot) ?? []).some(({ comp }) => comp.id.endsWith(".none"))) continue;
  const none = components.find((c) => c.slot === slot && c.id.endsWith(".none"));
  if (none && !manifestAssets.some((a) => a.id === none.id)) emit(none, { dx: 0, dy: 0 }, true);
}
for (const [slot, list] of anchored) {
  list.sort((a, b) => b.votes - a.votes || a.comp.id.localeCompare(b.comp.id));
  const want = WANTED[slot] ?? 4;
  list.slice(0, want).forEach(({ comp, off }) => emit(comp, off));
  list.slice(want).forEach(({ comp, votes }) => skipped.push(`${comp.id} (примеров: ${votes})`));
}

const layerOrder = ["accessoryBack", "base", "body", "hair", "brows", "eyes", "nose", "mouth", "beard", "gesture", "accessoryFront"];
const manifest = {
  schemaVersion: 1,
  packId: "notionists-wolfy-v1",
  packVersion: 1,
  license: "CC0-1.0",
  canvas: { width: CANVAS, height: CANVAS },
  layerOrder,
  colorTokens: ["--wolfy-skin", "--wolfy-hair", "--wolfy-outfit", "--wolfy-accent", "--wolfy-ink"],
  anchors: {
    head: { x: Math.round(baseX + baseBox.w * scale * 0.5), y: Math.round(baseY + baseBox.h * scale * 0.1) },
    neck: { x: Math.round(baseX + baseBox.w * scale * 0.5), y: Math.round(baseY + baseBox.h * scale * 0.235) },
    shoulderLeft: { x: Math.round(baseX + baseBox.w * scale * 0.24), y: Math.round(baseY + baseBox.h * scale * 0.3) },
    shoulderRight: { x: Math.round(baseX + baseBox.w * scale * 0.76), y: Math.round(baseY + baseBox.h * scale * 0.3) },
  },
  assets: manifestAssets,
};
writeFileSync(join(out, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n");

console.log(`runtime: ${manifestAssets.length} СЃР»РѕС‘РІ, scale=${scale.toFixed(4)}`);
for (const [slot, list] of anchored) {
  console.log(`  ${slot}: РїРѕРґС‚РІРµСЂР¶РґРµРЅРѕ ${list.length}, РІР·СЏС‚Рѕ ${Math.min(list.length, WANTED[slot] ?? 4)}`);
}
if (skipped.length) console.log(`Р·Р° РєР°РґСЂРѕРј: ${skipped.length}`);
