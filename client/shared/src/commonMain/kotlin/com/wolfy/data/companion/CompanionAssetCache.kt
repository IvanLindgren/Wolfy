package com.wolfy.data.companion

import com.wolfy.resources.Res
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * Манифест runtime-пака.
 *
 * Порядок слоёв фиксируется манифестом: ни один клиент не угадывает z-index
 * по имени папки. Файл генерируется tools/companions/normalize.mjs и
 * проверяется tools/companions/validate.mjs.
 */
@Serializable
data class CompanionManifest(
    val schemaVersion: Int = 1,
    val packId: String = "",
    val packVersion: Int = 1,
    val canvas: Canvas = Canvas(),
    val layerOrder: List<String> = emptyList(),
    val assets: List<ManifestAsset> = emptyList(),
) {
    @Serializable
    data class Canvas(val width: Int = 1024, val height: Int = 1024)

    @Serializable
    data class ManifestAsset(
        val id: String,
        val slot: String,
        val file: String,
        val tags: List<String> = emptyList(),
        val incompatibleWith: List<String> = emptyList(),
        val anchorsVersion: Int = 1,
    )
}

/**
 * Фигуры слоя. Пак валидирован, поэтому парсер знает только эти три вида:
 * всё остальное означало бы дыру в валидаторе.
 */
sealed interface CompanionShape {
    val fill: String

    data class Path(override val fill: String, val path: androidx.compose.ui.graphics.Path, val evenOdd: Boolean) : CompanionShape
    data class Oval(override val fill: String, val cx: Float, val cy: Float, val rx: Float, val ry: Float) : CompanionShape
    data class Rect(override val fill: String, val x: Float, val y: Float, val w: Float, val h: Float) : CompanionShape
}

/** Разобранный слой. */
data class CompanionAsset(val id: String, val shapes: List<CompanionShape>) {
    companion object {
        const val TOKEN_SKIN = "var(--wolfy-skin)"
        const val TOKEN_HAIR = "var(--wolfy-hair)"
        const val TOKEN_OUTFIT = "var(--wolfy-outfit)"
        const val TOKEN_ACCENT = "var(--wolfy-accent)"
        const val TOKEN_INK = "var(--wolfy-ink)"
    }
}

/**
 * Кеш слоёв: парсит runtime SVG по требованию и держит разобранные пути.
 *
 * Парс один раз на ассет: редактор листает причёски быстро, и повторный разбор
 * разметки на каждый кадр был бы заметен. Кеш растёт только по запрошенным
 * слоям: весь каталог целиком при открытии редактора не декодируется.
 */
class CompanionAssetCache(private val loadBytes: suspend (String) -> ByteArray) {
    var manifest: CompanionManifest? = null
        private set

    private val assets = mutableMapOf<String, CompanionAsset>()
    private val pending = mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<CompanionAsset?>>()
    private val mutex = kotlinx.coroutines.sync.Mutex()

    /** Читает манифест. Повторный вызов ничего не перечитывает. */
    suspend fun ensureLoaded(): CompanionManifest? {
        manifest?.let { return it }
        val loaded = mutex.withLock { manifest }
        if (loaded != null) {
            manifest = loaded
            return loaded
        }
        val parsed = runCatching {
            manifestJson.decodeFromString(CompanionManifest.serializer(), loadBytes(MANIFEST).decodeToString())
        }.getOrNull()
        mutex.withLock { if (manifest == null) manifest = parsed }
        return manifest
    }

    /**
     * Разобранный слой или null, если файла нет. Первый вызов может грузить
     * байты: парс выполняется в вызывающем контексте, поэтому при первом
     * показе читалки фигура догружается вне кадра отрисовки.
     */
    suspend fun get(assetId: String): CompanionAsset? {
        assets[assetId]?.let { return it }
        ensureLoaded() ?: return null
        val file = manifest?.assets?.firstOrNull { it.id == assetId }?.file ?: return null
        val (deferred, shouldLoad) = mutex.withLock {
            assets[assetId]?.let { ready ->
                val done = kotlinx.coroutines.CompletableDeferred<CompanionAsset?>()
                done.complete(ready)
                return@withLock done to false
            }
            pending[assetId]?.let { return@withLock it to false }
            kotlinx.coroutines.CompletableDeferred<CompanionAsset?>().also { pending[assetId] = it } to true
        }
        if (!shouldLoad) return deferred.await()
        val parsed = runCatching {
            parseSvg(loadBytes(file).decodeToString(), assetId)
        }.getOrNull()
        mutex.withLock {
            parsed?.let { assets[assetId] = it }
            pending.remove(assetId)
        }
        deferred.complete(parsed)
        return parsed
    }

    companion object {
        private const val MANIFEST = "files/companions/manifest.json"
        private val manifestJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        /** Общий кеш приложения: слои одинаковы на всех экранах. */
        val global = CompanionAssetCache { path -> Res.readBytes(path) }

        /**
         * Парсер runtime SVG.
         *
         * Поддерживает ровно то, что разрешено валидатором: path, ellipse,
         * circle, rect с fill-токенами и матричные обёртки. Всё прочее —
         * признак повреждённого пака, слой пропускается.
         */
        fun parseSvg(xml: String, assetId: String): CompanionAsset? {
            val shapes = mutableListOf<CompanionShape>()
            val matrixStack = ArrayDeque<FloatArray>().apply { addLast(IDENT.copyOf()) }
            val fillStack = ArrayDeque<String>().apply { addLast(CompanionAsset.TOKEN_INK) }
            val ruleStack = ArrayDeque<Boolean>().apply { addLast(false) }
            val tagRegex = Regex("""<(\/?)(g|path|ellipse|circle|rect)\b([^>]*?)(\/?)>""")
            val fillRegex = Regex("""fill="([^"]+)"""")
            val ruleRegex = Regex("""fill-rule="([^"]+)"""")
            val transformRegex = Regex("""transform="([^"]+)"""")
            val dRegex = Regex("""d="([^"]+)"""")
            var match = tagRegex.find(xml)
            while (match != null) {
                val closing = match.groupValues[1]
                val tag = match.groupValues[2]
                val attrs = match.groupValues[3]
                val selfClosing = match.groupValues[4] == "/"
                val nextFrom = match.range.last + 1
                when {
                    closing == "/" && tag == "g" -> {
                        if (matrixStack.size > 1) {
                            matrixStack.removeLast()
                            fillStack.removeLast()
                            ruleStack.removeLast()
                        }
                    }
                    tag == "g" -> {
                        val matrix = parseTransform(transformRegex.find(attrs)?.groupValues?.get(1))
                        matrixStack.addLast(mul(matrixStack.last(), matrix))
                        fillStack.addLast(fillRegex.find(attrs)?.groupValues?.get(1) ?: fillStack.last())
                        ruleStack.addLast(ruleRegex.find(attrs)?.groupValues?.get(1) == "evenodd" || ruleStack.last())
                        if (selfClosing) {
                            matrixStack.removeLast()
                            fillStack.removeLast()
                            ruleStack.removeLast()
                        }
                    }
                    tag == "path" -> {
                        val d = dRegex.find(attrs)?.groupValues?.get(1)
                        if (d != null) {
                            val fill = normalizeFill(fillRegex.find(attrs)?.groupValues?.get(1) ?: fillStack.last())
                            val evenOdd = ruleRegex.find(attrs)?.groupValues?.get(1) == "evenodd" || ruleStack.last()
                            val matrix = mul(matrixStack.last(), parseTransform(transformRegex.find(attrs)?.groupValues?.get(1)))
                            val path = buildPath(d, matrix)
                            if (evenOdd) path.fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                            shapes.add(CompanionShape.Path(fill, path, evenOdd))
                        }
                    }
                    tag == "ellipse" || tag == "circle" -> {
                        val fill = normalizeFill(fillRegex.find(attrs)?.groupValues?.get(1) ?: fillStack.last())
                        val matrix = mul(matrixStack.last(), parseTransform(transformRegex.find(attrs)?.groupValues?.get(1)))
                        val rx = if (tag == "circle") num(attrs, "r") else num(attrs, "rx")
                        val ry = if (tag == "circle") num(attrs, "r") else num(attrs, "ry")
                        shapes.add(CompanionShape.Path(fill, ellipsePath(
                            num(attrs, "cx"), num(attrs, "cy"), rx, ry, matrix,
                        ), evenOdd = false))
                    }
                    tag == "rect" -> {
                        val fill = normalizeFill(fillRegex.find(attrs)?.groupValues?.get(1) ?: fillStack.last())
                        val matrix = mul(matrixStack.last(), parseTransform(transformRegex.find(attrs)?.groupValues?.get(1)))
                        shapes.add(CompanionShape.Path(fill, rectPath(
                            num(attrs, "x"), num(attrs, "y"), num(attrs, "width"), num(attrs, "height"), matrix,
                        ), evenOdd = false))
                    }
                }
                match = tagRegex.find(xml, nextFrom)
            }
            if (shapes.isEmpty()) return null
            return CompanionAsset(assetId, shapes)
        }

        private fun normalizeFill(raw: String): String = when (raw.lowercase()) {
            "white", "#ffffff" -> "#FFFFFF"
            "black", "#000000" -> CompanionAsset.TOKEN_INK
            else -> raw
        }

        private fun num(attrs: String, name: String): Float =
            Regex(name + """="(-?[\d.]+)"""").find(attrs)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

        private val IDENT = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)

        private fun parseTransform(spec: String?): FloatArray {
            if (spec.isNullOrBlank()) return IDENT.copyOf()
            var result = IDENT.copyOf()
            val call = Regex("""(matrix|translate|scale)\s*\(([^)]*)\)""")
            for (match in call.findAll(spec)) {
                val values = match.groupValues[2]
                    .split(Regex("[\\s,]+"))
                    .filter { it.isNotBlank() }
                    .mapNotNull { it.toFloatOrNull() }
                val next = when (match.groupValues[1]) {
                    "matrix" -> values.takeIf { it.size == 6 }?.toFloatArray()
                    "translate" -> values.takeIf { it.size in 1..2 }?.let { parts ->
                        floatArrayOf(1f, 0f, 0f, 1f, parts[0], parts.getOrElse(1) { 0f })
                    }
                    "scale" -> values.takeIf { it.size in 1..2 }?.let { parts ->
                        floatArrayOf(parts[0], 0f, 0f, parts.getOrElse(1) { parts[0] }, 0f, 0f)
                    }
                    else -> null
                } ?: continue
                result = mul(result, next)
            }
            return result
        }

        private fun mul(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
            a[0] * b[0] + a[2] * b[1],
            a[1] * b[0] + a[3] * b[1],
            a[0] * b[2] + a[2] * b[3],
            a[1] * b[2] + a[3] * b[3],
            a[0] * b[4] + a[2] * b[5] + a[4],
            a[1] * b[4] + a[3] * b[5] + a[5],
        )

        private fun tx(matrix: FloatArray, x: Float, y: Float): Pair<Float, Float> = Pair(
            matrix[0] * x + matrix[2] * y + matrix[4],
            matrix[1] * x + matrix[3] * y + matrix[5],
        )

        /** Эллипс превращается в четыре кубические дуги до transform. */
        private fun ellipsePath(
            cx: Float,
            cy: Float,
            rx: Float,
            ry: Float,
            matrix: FloatArray,
        ): androidx.compose.ui.graphics.Path {
            val path = androidx.compose.ui.graphics.Path()
            val k = 0.5522848f
            fun point(x: Float, y: Float) = tx(matrix, x, y)
            val (sx, sy) = point(cx + rx, cy)
            path.moveTo(sx, sy)
            fun curve(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) {
                val p1 = point(x1, y1)
                val p2 = point(x2, y2)
                val p = point(x, y)
                path.cubicTo(p1.first, p1.second, p2.first, p2.second, p.first, p.second)
            }
            curve(cx + rx, cy + k * ry, cx + k * rx, cy + ry, cx, cy + ry)
            curve(cx - k * rx, cy + ry, cx - rx, cy + k * ry, cx - rx, cy)
            curve(cx - rx, cy - k * ry, cx - k * rx, cy - ry, cx, cy - ry)
            curve(cx + k * rx, cy - ry, cx + rx, cy - k * ry, cx + rx, cy)
            path.close()
            return path
        }

        private fun rectPath(
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            matrix: FloatArray,
        ): androidx.compose.ui.graphics.Path {
            val path = androidx.compose.ui.graphics.Path()
            val corners = listOf(
                tx(matrix, x, y),
                tx(matrix, x + width, y),
                tx(matrix, x + width, y + height),
                tx(matrix, x, y + height),
            )
            path.moveTo(corners[0].first, corners[0].second)
            for (corner in corners.drop(1)) path.lineTo(corner.first, corner.second)
            path.close()
            return path
        }

        /**
         * Строит Compose-путь из d. Дуги паком не используются, поэтому их
         * отсутствие в парсере безопасно: валидатор не пропустит такую фигуру.
         */
        private fun buildPath(d: String, matrix: FloatArray): androidx.compose.ui.graphics.Path {
            val path = androidx.compose.ui.graphics.Path()
            val tokens = Regex("[MmLlHhVvCcZz]|-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?")
                .findAll(d).map { it.value }.toList()
            var i = 0
            var cx = 0f
            var cy = 0f
            var cmd = 'M'
            fun number(): Float = tokens[i++].toFloat()
            while (i < tokens.size) {
                val token = tokens[i]
                if (token.length == 1 && token[0].isLetter()) {
                    cmd = token[0]
                    i += 1
                    if (cmd == 'Z' || cmd == 'z') {
                        path.close()
                        continue
                    }
                }
                val relative = cmd.isLowerCase()
                fun pair(x: Float, y: Float): Pair<Float, Float> = tx(matrix, x, y)
                when (cmd.uppercaseChar()) {
                    'M' -> {
                        val x = if (relative) cx + number() else number()
                        val y = if (relative) cy + number() else number()
                        val (px, py) = pair(x, y)
                        path.moveTo(px, py)
                        cx = x
                        cy = y
                        cmd = if (cmd == 'M') 'L' else 'l'
                    }
                    'L' -> {
                        cx = if (relative) cx + number() else number()
                        cy = if (relative) cy + number() else number()
                        val (px, py) = pair(cx, cy)
                        path.lineTo(px, py)
                    }
                    'H' -> {
                        cx = if (relative) cx + number() else number()
                        val (px, py) = pair(cx, cy)
                        path.lineTo(px, py)
                    }
                    'V' -> {
                        cy = if (relative) cy + number() else number()
                        val (px, py) = pair(cx, cy)
                        path.lineTo(px, py)
                    }
                    'C' -> {
                        val x1 = if (relative) cx + number() else number()
                        val y1 = if (relative) cy + number() else number()
                        val x2 = if (relative) cx + number() else number()
                        val y2 = if (relative) cy + number() else number()
                        val x = if (relative) cx + number() else number()
                        val y = if (relative) cy + number() else number()
                        val (p1x, p1y) = pair(x1, y1)
                        val (p2x, p2y) = pair(x2, y2)
                        val (px, py) = pair(x, y)
                        path.cubicTo(p1x, p1y, p2x, p2y, px, py)
                        cx = x
                        cy = y
                    }
                    else -> {
                        // Неизвестная команда: остаток пути пропускается.
                        i = tokens.size
                    }
                }
            }
            return path
        }
    }
}
