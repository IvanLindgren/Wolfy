package com.wolfy.ui.companion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.wolfy.data.companion.CompanionAssetCache
import com.wolfy.data.companion.CompanionManifest

/**
 * Каталог слоёв для редактора: перечень ассетов по слотам и человеческие имена.
 *
 * Каждому предмету соответствует подпись: картинка без имени недоступна тем,
 * кто слушает экран. Имена строятся из слота и номера, потому что исходный
 * пак не несёт смысловых названий вариантов.
 */
object CompanionCatalog {
    private val slotTitles = mapOf(
        "base" to "Основа", "hair" to "Причёска", "brows" to "Брови", "eyes" to "Глаза",
        "nose" to "Нос", "mouth" to "Рот", "beard" to "Борода", "body" to "Одежда",
        "accessoryFront" to "Аксессуар", "accessoryBack" to "Спина", "gesture" to "Жест",
    )

    /** Имя предмета: слот и номер, «none» честно зовётся «Нет». */
    fun label(assetId: String): String {
        val parts = assetId.split('.')
        if (parts.size != 2) return assetId
        val slot = parts[0]
        val variant = parts[1]
        if (variant == "none") return "Нет"
        val title = slotTitles[slot] ?: slot
        val number = variant.toIntOrNull()
        return if (number == null) title else "$title $number"
    }

    fun slotTitle(slot: String): String = slotTitles[slot] ?: slot
}

/**
 * Помнит список ассетов по слотам. Пока манифест не дочитался, список пуст:
 * редактор честно подождёт кадр, вместо того чтобы показывать пустоту.
 */
@Composable
fun rememberCompanionCatalog(): Map<String, List<CompanionManifest.ManifestAsset>> {
    val cache = remember { CompanionAssetCache.global }
    val manifest by produceState<CompanionManifest?>(initialValue = cache.manifest) {
        value = cache.ensureLoaded()
    }
    return remember(manifest) {
        val grouped = (manifest?.assets ?: emptyList()).groupBy { it.slot }
        grouped
    }
}
