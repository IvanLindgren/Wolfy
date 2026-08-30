package com.wolfy.ui.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.withTransform
import com.wolfy.data.companion.CompanionAppearance
import com.wolfy.data.companion.CompanionAsset
import com.wolfy.data.companion.CompanionAssetCache
import com.wolfy.data.companion.CompanionManifest
import com.wolfy.theme.WolfyTheme

/**
 * Отрисовка компаньона слоями пака.
 *
 * Рендерер принимает внешность как данные и ничего не знает о хранении: слои
 * приходят из кеша [CompanionAssetCache], палитра разворачивает токены цветов.
 * Парсер графики свой и маленький, потому что пак валидирован и содержит ровно
 * три вида фигур: пути, эллипсы и прямоугольники.
 *
 * Фигура не перекрывает текст: размер задаёт родитель, здесь только слои и
 * палитра. Догрузка слоёв идёт мимо кадра: открытие читалки ассетов не ждёт.
 *
 * ## Почему анимация здесь, а не поверх фигуры
 *
 * Снаружи фигуру можно было бы покачивать целиком через `graphicsLayer`, и
 * ровно это раньше и делалось: покачивание на три пикселя и наклон на
 * семь десятых градуса. Издали такое движение неотличимо от неподвижности, а
 * ближе — от дрожания.
 *
 * Живым персонажа делает не движение целиком, а движение частей: веки
 * смыкаются, рот раскрывается, корпус дышит. Слои рисуются по одному, поэтому
 * каждому можно назначить своё преобразование — и покадровая анимация,
 * которой в паке нет и быть не может, оказывается не нужна.
 */
@Composable
fun CompanionFigure(
    appearance: CompanionAppearance,
    modifier: Modifier = Modifier,
    /** Поза на этом кадре; по умолчанию фигура неподвижна. */
    pose: CompanionPose = CompanionPose.Still,
) {
    val cache = remember { CompanionAssetCache.global }
    val manifest by produceState<CompanionManifest?>(initialValue = null) {
        value = cache.ensureLoaded()
    }
    val assets by produceState<List<LayeredAsset>>(initialValue = emptyList(), appearance, manifest) {
        val current = manifest ?: return@produceState
        val resolved = mutableListOf<LayeredAsset>()
        for (slot in current.layerOrder) {
            val assetId = appearance.asset(slot)
            if (assetId.endsWith(".none") && slot != "base") continue
            val fallbackId = if (slot == "base") "base.base" else "$slot.none"
            // Слот запоминается рядом с разобранным слоем: по нему риг
            // отличает веки от причёски. Выводить его из идентификатора на
            // каждом кадре значило бы резать строку в цикле отрисовки.
            (cache.get(assetId) ?: cache.get(fallbackId))?.let { resolved.add(LayeredAsset(slot, it)) }
        }
        value = resolved
    }
    val palette = rememberPalette(appearance)
    val canvas = manifest
    val contentBounds = remember(assets) { assets.contentBounds() }
    Box(modifier) {
        if (canvas != null && assets.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                // Некоторые причёски и украшения выходят за условные
                // 1024×1024 исходного пака. Масштаб по размеру холста их
                // обрезал и позволял рисунку вылезать в соседний текст.
                // Вписываем реальные границы всех выбранных слоёв.
                val bounds = contentBounds ?: Rect(
                    0f,
                    0f,
                    canvas.canvas.width.toFloat(),
                    canvas.canvas.height.toFloat(),
                )
                val inset = size.minDimension * 0.035f
                val availableWidth = (size.width - inset * 2f).coerceAtLeast(1f)
                val availableHeight = (size.height - inset * 2f).coerceAtLeast(1f)
                val factor = minOf(
                    availableWidth / bounds.width.coerceAtLeast(1f),
                    availableHeight / bounds.height.coerceAtLeast(1f),
                )
                val left = (size.width - bounds.width * factor) / 2f - bounds.left * factor
                val top = (size.height - bounds.height * factor) / 2f - bounds.top * factor
                // Общее движение фигуры: дыхание, наклон и смещение. Опора —
                // низ по центру: персонаж стоит на земле, а не висит вокруг
                // своего геометрического центра.
                val ground = Offset(size.width / 2f, size.height)
                withTransform({
                    translate(pose.slide * size.width, pose.rise * size.height)
                    if (pose.tilt != 0f) rotate(pose.tilt, ground)
                    if (pose.breath != 0f) scale(1f, 1f + pose.breath, ground)
                    translate(left, top)
                    scale(factor, factor, pivot = Offset.Zero)
                }) {
                    for (layer in assets) {
                        drawLayer(layer, palette, pose)
                    }
                }
            }
        }
    }
}

/** Слой вместе со слотом, в который его поставила внешность. */
private data class LayeredAsset(val slot: String, val asset: CompanionAsset)

private fun List<LayeredAsset>.contentBounds(): Rect? {
    var combined: Rect? = null
    for (layer in this) {
        for (shape in layer.asset.shapes) {
            val next = when (shape) {
                is com.wolfy.data.companion.CompanionShape.Path -> shape.path.getBounds()
                is com.wolfy.data.companion.CompanionShape.Oval -> Rect(
                    shape.cx - shape.rx,
                    shape.cy - shape.ry,
                    shape.cx + shape.rx,
                    shape.cy + shape.ry,
                )
                is com.wolfy.data.companion.CompanionShape.Rect -> Rect(
                    shape.x,
                    shape.y,
                    shape.x + shape.w,
                    shape.y + shape.h,
                )
            }
            combined = combined?.let { current ->
                Rect(
                    minOf(current.left, next.left),
                    minOf(current.top, next.top),
                    maxOf(current.right, next.right),
                    maxOf(current.bottom, next.bottom),
                )
            } ?: next
        }
    }
    return combined
}

/**
 * Слой со своим преобразованием.
 *
 * Веки и рот двигаются вокруг собственного центра, а не вокруг центра фигуры:
 * глаз, сомкнувшийся вокруг подбородка, — это не моргание.
 */
private fun DrawScope.drawLayer(layer: LayeredAsset, palette: CompanionPalette, pose: CompanionPose) {
    val squeeze = when (layer.slot) {
        SLOT_EYES -> 1f - pose.lids * LID_CLOSE
        SLOT_MOUTH -> 1f + pose.mouth * MOUTH_OPEN
        else -> 1f
    }
    if (squeeze == 1f) {
        drawAsset(layer.asset, palette)
        return
    }
    val bounds = listOf(layer).contentBounds() ?: run {
        drawAsset(layer.asset, palette)
        return
    }
    withTransform({ scale(1f, squeeze, pivot = bounds.center) }) {
        drawAsset(layer.asset, palette)
    }
}

/** Насколько закрытое веко сжимает глаз. Не в ноль: щёлочка честнее пустоты. */
private const val LID_CLOSE = 0.88f

/** Насколько раскрывается рот на речи. */
private const val MOUTH_OPEN = 1.1f

private const val SLOT_EYES = "eyes"
private const val SLOT_MOUTH = "mouth"

private fun DrawScope.drawAsset(asset: CompanionAsset, palette: CompanionPalette) {
    for (shape in asset.shapes) {
        val color = palette.resolve(shape.fill) ?: continue
        when (shape) {
            is com.wolfy.data.companion.CompanionShape.Path -> drawPath(shape.path, color, style = Fill)
            is com.wolfy.data.companion.CompanionShape.Oval -> drawOval(
                color,
                topLeft = Offset(shape.cx - shape.rx, shape.cy - shape.ry),
                size = Size(shape.rx * 2f, shape.ry * 2f),
            )
            is com.wolfy.data.companion.CompanionShape.Rect -> drawRect(
                color,
                topLeft = Offset(shape.x, shape.y),
                size = Size(shape.w, shape.h),
            )
        }
    }
}

@Composable
private fun rememberPalette(appearance: CompanionAppearance): CompanionPalette {
    val colors = WolfyTheme.colors
    return remember(appearance.skin, appearance.hairColor, appearance.outfitColor, appearance.accentColor, colors) {
        CompanionPalette(
            skin = CompanionPalettes.of(appearance.skin) ?: CompanionPalettes.defaultSkin,
            hair = CompanionPalettes.of(appearance.hairColor) ?: CompanionPalettes.defaultHair,
            outfit = CompanionPalettes.of(appearance.outfitColor) ?: CompanionPalettes.defaultOutfit,
            accent = CompanionPalettes.of(appearance.accentColor) ?: CompanionPalettes.defaultAccent,
            ink = colors.ink,
        )
    }
}

/** Палитра рендера: имена из профиля в цвета. */
data class CompanionPalette(
    val skin: Color,
    val hair: Color,
    val outfit: Color,
    val accent: Color,
    val ink: Color,
) {
    /** Токен из SVG в цвет; незнакомый токен пропускается. */
    fun resolve(token: String): Color? = when (token) {
        CompanionAsset.TOKEN_SKIN -> skin
        CompanionAsset.TOKEN_HAIR -> hair
        CompanionAsset.TOKEN_OUTFIT -> outfit
        CompanionAsset.TOKEN_ACCENT -> accent
        CompanionAsset.TOKEN_INK -> ink
        "#FFFFFF" -> Color.White
        else -> null
    }
}

/** Именованные цвета палитры редактора: тёплые, газетные, без кислоты. */
object CompanionPalettes {
    val defaultSkin = Color(0xFFF2C6A0)
    val defaultHair = Color(0xFF2E2A28)
    val defaultOutfit = Color(0xFF8C3B2E)
    val defaultAccent = Color(0xFFC9A227)

    val skinOptions: List<Pair<String, Color>> = listOf(
        "paper" to Color(0xFFF7E1CE),
        "light" to Color(0xFFF2C6A0),
        "tan" to Color(0xFFDBA97E),
        "brown" to Color(0xFF8D5A3B),
        "deep" to Color(0xFF5C3A25),
    )
    val hairOptions: List<Pair<String, Color>> = listOf(
        "ink" to Color(0xFF1A1816),
        "chestnut" to Color(0xFF5A3825),
        "auburn" to Color(0xFF7A4A2B),
        "sand" to Color(0xFFB98F5E),
        "gray" to Color(0xFF8B8B8B),
    )
    val outfitOptions: List<Pair<String, Color>> = listOf(
        "brick" to Color(0xFF8C3B2E),
        "navy" to Color(0xFF274357),
        "forest" to Color(0xFF4C6B44),
        "slate" to Color(0xFF4A4E57),
        "plum" to Color(0xFF5C3A56),
    )
    val accentOptions: List<Pair<String, Color>> = listOf(
        "gold" to Color(0xFFC9A227),
        "copper" to Color(0xFFB06A3B),
        "steel" to Color(0xFF9AA5AE),
        "cream" to Color(0xFFEADFC8),
    )

    /** Цвет по имени из профиля. Незнакомое имя даёт запасной на рендере. */
    fun of(name: String): Color? {
        for (group in listOf(skinOptions, hairOptions, outfitOptions, accentOptions)) {
            for ((key, value) in group) if (key == name) return value
        }
        return null
    }
}
