// Обёртка под Windows: окно, иконка и сборка установщика.

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
        }
    }
}

// Каталог, куда кладётся собранное ядро на Rust.
//
// Ядро не собирается Gradle: у него свой инструмент, и запускать cargo из
// каждой сборки клиента значило бы ждать его без нужды. Задача только
// переносит уже собранную библиотеку туда, где её найдут и запуск из
// исходников, и установщик.
val coreLibDir = layout.buildDirectory.dir("coreLib")

val copyCoreLibrary by tasks.registering(Copy::class) {
    description = "Кладёт собранное ядро на Rust рядом с приложением"
    from(rootProject.layout.projectDirectory.dir("../core/target/release")) {
        include("wolfy_core.dll", "libwolfy_core.so", "libwolfy_core.dylib")
    }
    into(coreLibDir)
}

// Всё, что читает этот каталог, обязано дождаться копирования: иначе Gradle
// вправе выполнить задачи в любом порядке и упаковать пустоту.
tasks.named("jvmProcessResources") { dependsOn(copyCoreLibrary) }
tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(copyCoreLibrary)
}

compose.desktop {
    application {
        mainClass = "com.wolfy.desktop.MainKt"

        // Запуск из исходников: JNA ищет библиотеку там, куда её положила
        // задача выше. В установленном приложении она лежит рядом с
        // исполняемым файлом, и путь не нужен.
        jvmArgs += "-Djna.library.path=${coreLibDir.get().asFile.absolutePath}"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Wolfy"
            packageVersion = "1.0.0"
            description = "Читалка английских книг"
            vendor = "Wolfy"

            // Ядро едет в установщик как ресурс приложения.
            appResourcesRootDir.set(coreLibDir)

            windows {
                // Постоянный UUID: без него каждая новая сборка ставится
                // рядом со старой вместо обновления.
                upgradeUuid = "8f2b4c31-9d5e-4a7f-b6c8-1e3d5a7f9b2c"
                menuGroup = "Wolfy"
            }
        }
    }
}
