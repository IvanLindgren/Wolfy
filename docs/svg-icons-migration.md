# Миграция на SVG-иконки

## Созданные иконки

В `client/shared/src/commonMain/composeResources/drawable/` созданы 7 векторных иконок:

- `ic_books.xml` — раскрытая книга (для навигации Books)
- `ic_shelves.xml` — корешки книг (для навигации Shelves)
- `ic_discover.xml` — компас (для навигации Discover)
- `ic_cards.xml` — карты веером (для навигации Cards)
- `ic_gear.xml` — шестерёнка (для навигации More)
- `ic_reading_settings.xml` — слайдеры (для настроек чтения)
- `ic_recap.xml` — круговая стрелка (для "Вспомнить сюжет")

## Как подключить

### Вариант 1: Обновить существующую функцию NavGlyph

В файле `client/shared/src/commonMain/kotlin/com/wolfy/widgets/NavIcon.kt`:

```kotlin
import androidx.compose.material3.Icon
import org.jetbrains.compose.resources.painterResource
import com.wolfy.resources.Res

@Composable
fun NavGlyph(icon: NavIcon, tint: Color, modifier: Modifier = Modifier) {
    val resource = when (icon) {
        NavIcon.Books -> Res.drawable.ic_books
        NavIcon.Shelves -> Res.drawable.ic_shelves
        NavIcon.Discover -> Res.drawable.ic_discover
        NavIcon.Cards -> Res.drawable.ic_cards
        NavIcon.More -> Res.drawable.ic_gear
        NavIcon.Reading -> Res.drawable.ic_reading_settings
        NavIcon.Recap -> Res.drawable.ic_recap
    }
    
    Icon(
        painter = painterResource(resource),
        contentDescription = icon.name,
        tint = tint,
        modifier = modifier.size(22.dp)
    )
}
```

### Вариант 2: Поддержка обоих вариантов (переходный период)

```kotlin
@Composable
fun NavGlyph(icon: NavIcon, tint: Color, modifier: Modifier = Modifier, useSvg: Boolean = false) {
    if (useSvg) {
        val resource = when (icon) {
            NavIcon.Books -> Res.drawable.ic_books
            NavIcon.Shelves -> Res.drawable.ic_shelves
            NavIcon.Discover -> Res.drawable.ic_discover
            NavIcon.Cards -> Res.drawable.ic_cards
            NavIcon.More -> Res.drawable.ic_gear
            NavIcon.Reading -> Res.drawable.ic_reading_settings
            NavIcon.Recap -> Res.drawable.ic_recap
        }
        Icon(
            painter = painterResource(resource),
            contentDescription = icon.name,
            tint = tint,
            modifier = modifier.size(22.dp)
        )
    } else {
        // Старый Canvas-based код
        Canvas(modifier.size(22.dp)) {
            when (icon) {
                NavIcon.Books -> drawBook(tint)
                NavIcon.Shelves -> drawShelves(tint)
                NavIcon.Discover -> drawDiscover(tint)
                NavIcon.Cards -> drawCards(tint)
                NavIcon.More -> drawGear(tint)
                NavIcon.Reading -> drawReadingSettings(tint)
                NavIcon.Recap -> drawRecap(tint)
            }
        }
    }
}
```

## Преимущества SVG-иконок

1. **Качество на любых DPI** — векторная графика масштабируется без потери качества
2. **Меньше кода** — не нужно вручную описывать Path для каждой иконки
3. **Легче редактировать** — можно открыть в Figma/Inkscape и изменить
4. **Консистентность** — все иконки в едином стиле
5. **Производительность** — Icon() быстрее чем Canvas с множеством drawPath

## Тестирование

После подключения проверьте:
1. Все экраны с навигацией (Books, Shelves, Discover, Cards)
2. Настройки чтения (Reading settings)
3. Кнопка "Вспомнить сюжет" в читалке

Иконки должны выглядеть четко на всех разрешениях экрана.

## Откат

Если возникнут проблемы, просто верните старый код Canvas-based из NavIcon.kt.
SVG-файлы можно оставить в проекте для будущего использования.
