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

// Скачивание JDK по требованию.
//
// Установщик под Windows собирает jpackage, а jpackage есть только в полном
// JDK. На машине разработчика чаще всего стоит не он, а урезанная сборка из
// Android Studio или IDEA — с ней собирается всё, кроме установщика, и
// узнаётся об этом в последний момент. Плагин позволяет Gradle скачать нужный
// JDK самому; на обычные сборки это не влияет, потому что до упаковки дело не
// доходит.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
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
