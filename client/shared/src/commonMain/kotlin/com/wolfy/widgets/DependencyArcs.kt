package com.wolfy.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wolfy.ffi.GrammarChunk
import com.wolfy.ffi.Token
import com.wolfy.theme.Curves
import com.wolfy.theme.WolfyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Граф синтаксических связей: главные слова групп стоят в ряд, дуги ведут к
 * сказуемому.
 *
 * Скобы конструкций под текстом отвечают на вопрос «какие слова работают
 * вместе», но не показывают направление: кто здесь исполнитель, а над чем
 * действие. Дуга от группы к сказуемому отвечает именно на это, а порядок,
 * в котором дуги разворачиваются, и есть объяснение фразы — сначала
 * подлежащее и сказуемое, потом всё остальное.
 *
 * Строится только по тому, что дало ядро: группы ([GrammarChunk]) с их
 * главными словами и ролями. Неоднозначную связь движок сам пропускает —
 * неверный граф хуже неполного, потому что читатель, увидевший неверный
 * разбор, перестаёт верить и верному.
 */
@Composable
fun DependencyArcs(
    tokens: List<Token>,
    chunks: List<GrammarChunk>,
    modifier: Modifier = Modifier,
) {
    // Вершина группы — её главное слово, его и рисует граф. Порядок групп на
    // полотне — порядок их вершин во фразе, как слова стоят в предложении.
    val ordered = chunks.sortedBy { it.head }
    val nodes = ordered.map { chunk ->
        ArcNode(
            text = tokens.getOrNull(chunk.head)?.text
                ?: (chunk.start until chunk.end)
                    .mapNotNull(tokens::getOrNull)
                    .lastOrNull()?.text
                    .orEmpty(),
            tint = chunk.tint,
            caption = shortRole(chunk.title),
        )
    }
    val root = ordered.indexOfFirst { it.role == "predicate" }

    if (nodes.size < 2 || root < 0 || nodes.any { it.text.isBlank() }) {
        GraphEmptyNote()
        return
    }

    ArcGraph(nodes = nodes, root = root, modifier = modifier)
}

/** Сообщение вместо графа: молчаливая пустота выглядела бы поломкой. */
@Composable
fun GraphEmptyNote() {
    Text(
        text = "Связей во фразе не нашлось. Ядро пропускает неоднозначные, " +
            "неверный граф хуже неполного.",
        style = WolfyTheme.typography.caption,
        color = WolfyTheme.colors.inkMuted,
    )
}

/** Главное слово графа: текст, цвет части речи и короткая подпись роли. */
private class ArcNode(val text: String, val tint: String, val caption: String)

/** Место под одно слово на полотне. */
private val NODE_SLOT = 116.dp

/** Ниже этой ширины полотно не сжимается — вместо этого масштабируется целиком. */
private val GRAPH_WIDTH = 280.dp

private val GRAPH_HEIGHT = 132.dp

/** Строка слов стоит над нижним краем полотна. */
private val BASELINE_UP = 34.dp

/** На этой высоте над словом дуга пристыковывается к нему. */
private val ATTACH_UP = 22.dp

/** Подъём дуги: базовый и добавка за каждое слово между связанными. */
private val LIFT_BASE = 26.dp
private val LIFT_STEP = 18.dp
private val LIFT_MAX = 78.dp

@Composable
private fun ArcGraph(
    nodes: List<ArcNode>,
    root: Int,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val palette = colors.partsOfSpeech
    val motion = WolfyTheme.motion
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Полотно шире контейнера масштабируется целиком, как svg в вебе:
        // ужимать места под слова значило бы столкнуть их лбами.
        val naturalWidth = maxOf(GRAPH_WIDTH, NODE_SLOT * nodes.size)
        val scale = minOf(1f, maxWidth / naturalWidth)
        val wordStyle = WolfyTheme.typography.body
        val captionStyle = WolfyTheme.typography.sectionLabel

        val plan = remember(nodes, root, density, wordStyle, captionStyle) {
            density.buildPlan(
                measurer = measurer,
                wordStyle = wordStyle,
                captionStyle = captionStyle,
                nodes = nodes,
                root = root,
                width = naturalWidth,
            )
        }

        // Дуги разворачиваются по очереди со своим шагом: последовательность
        // и есть объяснение. «Меньше движения» гасит и это — все дуги видны
        // сразу, нулевой длительности.
        val progress = remember(nodes.size) {
            List(nodes.size) { Animatable(if (motion.calm == 0) 1f else 0f) }
        }
        LaunchedEffect(nodes, motion) {
            progress.forEachIndexed { index, animatable ->
                launch {
                    if (motion.stagger > 0 && index > 0) delay(index * motion.stagger.toLong())
                    animatable.animateTo(1f, tween(motion.calm, easing = Curves.Paper))
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(GRAPH_HEIGHT * scale),
            contentAlignment = Alignment.TopCenter,
        ) {
            Canvas(
                Modifier
                    // Холст живёт в естественных координатах; к размеру
                    // контейнера его приводит общий масштаб слоя.
                    .size(naturalWidth, GRAPH_HEIGHT)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    },
            ) {
                drawGraph(
                    plan = plan,
                    progress = progress.map { it.value },
                    muted = colors.inkMuted,
                    tintOf = { tag -> palette.forTag(tag) ?: colors.ink },
                )
            }
        }
    }
}

/** Слово с его подписью и местом на полотне. */
private class PlacedNode(
    val word: TextLayoutResult,
    val caption: TextLayoutResult,
    val center: Float,
    val tint: String,
)

/** Дуга между двумя словами: путь и точка, куда смотрит стрелка. */
private class PlacedLink(val path: Path, val toX: Float, val attachY: Float)

private class ArcsPlan(
    val nodes: List<PlacedNode>,
    val links: List<PlacedLink>,
    val baselineTop: Float,
    val captionStep: Float,
)

/**
 * Раскладка считается один раз и заранее: мерить текст каждый кадр значило
 * бы шестьдесят раз в секунду измерять одно и то же.
 */
private fun Density.buildPlan(
    measurer: TextMeasurer,
    wordStyle: TextStyle,
    captionStyle: TextStyle,
    nodes: List<ArcNode>,
    root: Int,
    width: Dp,
): ArcsPlan {
    val slot = NODE_SLOT.toPx()
    val widthPx = width.toPx()
    val origin = (widthPx - slot * nodes.size) / 2f

    val placed = nodes.mapIndexed { index, node ->
        PlacedNode(
            word = measurer.measure(node.text, wordStyle),
            caption = measurer.measure(node.caption, captionStyle),
            center = origin + (index + 0.5f) * slot,
            tint = node.tint,
        )
    }

    val baselineTop = (GRAPH_HEIGHT - BASELINE_UP).toPx()
    val attachY = baselineTop - ATTACH_UP.toPx()
    val links = placed.indices.filter { it != root }.map { from ->
        val toX = placed[root].center
        val lift = minOf(
            LIFT_MAX.toPx(),
            LIFT_BASE.toPx() + kotlin.math.abs(from - root) * LIFT_STEP.toPx(),
        )
        PlacedLink(
            path = Path().apply {
                moveTo(placed[from].center, attachY)
                cubicTo(placed[from].center, attachY - lift, toX, attachY - lift, toX, attachY)
            },
            toX = toX,
            attachY = attachY,
        )
    }

    return ArcsPlan(
        nodes = placed,
        links = links,
        baselineTop = baselineTop,
        captionStep = 16.dp.toPx(),
    )
}

private fun DrawScope.drawGraph(
    plan: ArcsPlan,
    progress: List<Float>,
    muted: Color,
    tintOf: (String) -> Color,
) {
    // Слова и подписи ролей. Цвет слова — тот же, каким оно покрашено на
    // странице: два ответа на вопрос «что здесь красное» быть не должно.
    plan.nodes.forEach { node ->
        drawText(
            textLayoutResult = node.word,
            color = tintOf(node.tint),
            topLeft = Offset(node.center - node.word.size.width / 2f, plan.baselineTop - node.word.size.height),
        )
        drawText(
            textLayoutResult = node.caption,
            color = muted,
            topLeft = Offset(node.center - node.caption.size.width / 2f, plan.baselineTop + plan.captionStep),
        )
    }

    // Частичная отрисовка дуги идёт через PathMeasure: кубическая кривая
    // дорисовываться сама не умеет.
    val measure = PathMeasure()
    val segment = Path()
    val stroke = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)

    plan.links.forEachIndexed { index, link ->
        val done = progress[index]
        if (done <= 0.01f) return@forEachIndexed

        if (done >= 0.995f) {
            drawPath(link.path, muted, style = stroke)
            drawArrowHead(link, muted)
        } else {
            measure.setPath(link.path, false)
            segment.reset()
            measure.getSegment(0f, measure.length * done, segment, true)
            drawPath(segment, muted, style = stroke)
        }
    }
}

/** Наконечник смотрит вниз, на главное слово дуги. */
private fun DrawScope.drawArrowHead(link: PlacedLink, color: Color) {
    val size = 3.5.dp.toPx()
    val head = Path().apply {
        moveTo(link.toX - size, link.attachY - size)
        lineTo(link.toX + size, link.attachY - size)
        lineTo(link.toX, link.attachY + size * 0.7f)
        close()
    }
    drawPath(head, color, style = Fill)
}

/** Короткая подпись роли под словом: полные названия в своё место не лезут. */
private fun shortRole(role: String): String = when (role.lowercase()) {
    "подлежащее" -> "подл."
    "сказуемое" -> "сказ."
    "дополнение" -> "доп."
    "дополнение сказуемого" -> "часть"
    "обстоятельство" -> "обст."
    "связка" -> "связь"
    else -> role.take(6)
}
