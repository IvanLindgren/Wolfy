// Состав сборки клиента.
//
// Три модуля: shared — весь общий код и интерфейс, androidApp и desktopApp —
// тонкие обёртки под платформы. Правило простое: если код попал в androidApp
// или desktopApp, значит он действительно не может жить в общем модуле.

rootProject.name = "wolfy-client"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Compose Multiplatform публикует часть артефактов только сюда.
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

include(":shared", ":androidApp", ":desktopApp")
