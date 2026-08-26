// Обёртка под Windows: окно, иконка и сборка установщика.

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// Позволяет выпускать новый установщик, пока предыдущий EXE ещё открыт
// Windows Installer и потому заблокирован для перезаписи.
providers.gradleProperty("wolfyBuildDir").orNull?.let { directory ->
    layout.buildDirectory.set(project.layout.projectDirectory.dir(directory))
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
            // Панель «вас ждёт книга» рисуется здесь, до всякой композиции
            // приложения: ей нужны сами примитивы, а не только окно.
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
        }
    }
}

// Каталог, куда кладётся собранное ядро на Rust.
val coreLibDir = layout.buildDirectory.dir("coreLib")

// Внутри — подкаталог `common`, и это не украшение. Compose складывает в
// установщик не сам каталог ресурсов, а его подкаталоги с именами платформ;
// файл, положенный в корень, молча не попадает никуда, и приложение
// устанавливается без ядра. Собирается всегда ровно одна библиотека — под ту
// систему, на которой запустили cargo, — поэтому `common`, а не `windows-x64`.
val coreLibFiles = coreLibDir.map { it.dir("common") }
val coreLibraryFileName = when {
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "wolfy_core.dll"
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "libwolfy_core.dylib"
    else -> "libwolfy_core.so"
}

// Небольшой отдельный процесс ставит MSI после закрытия Wolfy. Он обязательно
// едет внутри установщика: требовать от пользователя Go или скрипты нельзя.
val updaterOutput = rootProject.layout.projectDirectory.file("../server/build/wolfy-updater.exe")
val buildWindowsUpdater by tasks.registering(Exec::class) {
    description = "Собирает внешний процесс автоматического обновления"
    workingDir(rootProject.layout.projectDirectory.dir("../server"))
    commandLine("go", "build", "-trimpath", "-ldflags=-s -w", "-o", updaterOutput.asFile.absolutePath, "./cmd/updater")
    inputs.file(rootProject.layout.projectDirectory.file("../server/go.mod"))
    inputs.dir(rootProject.layout.projectDirectory.dir("../server/cmd/updater"))
    outputs.file(updaterOutput)
    doFirst { updaterOutput.asFile.parentFile.mkdirs() }
}

// Установщик обязан быть автономным. Поэтому его нативная часть собирается
// той же Gradle-цепочкой, что и клиент: нельзя случайно выпустить пакет без
// DLL или положить в него библиотеку от предыдущей версии исходников.
// Cargo сам пропускает работу, когда входные файлы не менялись.
val buildCoreLibrary by tasks.registering(Exec::class) {
    description = "Собирает нативное ядро Wolfy для установщика"
    workingDir(rootProject.layout.projectDirectory.dir("../core"))
    commandLine("cargo", "build", "--release", "--locked")

    inputs.file(rootProject.layout.projectDirectory.file("../core/Cargo.toml"))
    inputs.file(rootProject.layout.projectDirectory.file("../core/Cargo.lock"))
    inputs.dir(rootProject.layout.projectDirectory.dir("../core/src"))
    inputs.dir(rootProject.layout.projectDirectory.dir("../core/data"))
    outputs.file(rootProject.layout.projectDirectory.file("../core/target/release/$coreLibraryFileName"))
}

val copyCoreLibrary by tasks.registering(Copy::class) {
    description = "Кладёт ядро и офлайн-словарь рядом с приложением"
    dependsOn(buildCoreLibrary, buildWindowsUpdater)
    from(rootProject.layout.projectDirectory.dir("../core/target/release")) {
        include("wolfy_core.dll", "libwolfy_core.so", "libwolfy_core.dylib")
    }
    from(rootProject.layout.projectDirectory.dir("../dist")) {
        include("wolfy_dictionary.tsv.gz")
    }
    from(rootProject.layout.projectDirectory.file("../THIRD_PARTY_NOTICES.md"))
    from(updaterOutput)
    // Заставка. Её рисует tools/build_splash.py из тех же стикера, шрифта и
    // палитры, что и приложение, — чтобы через две секунды окно не оказалось
    // непохожим на то, что читатель уже увидел.
    from(project.file("icons")) {
        include("splash.png", "splash@2x.png")
    }
    into(coreLibFiles)
}

// Всё, что читает этот каталог, обязано дождаться копирования: иначе Gradle
// вправе выполнить задачи в любом порядке и упаковать пустоту.
tasks.named("jvmProcessResources") { dependsOn(copyCoreLibrary) }
tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(copyCoreLibrary)
}

// JDK, которым собирается установщик.
//
// jpackage входит только в полный JDK, а запускают сборку обычно из-под той
// урезанной сборки, что приносит с собой IDE. Toolchain решает это раз и
// навсегда: Gradle возьмёт подходящий JDK или скачает его.
//
// Обычно это JDK 17, но на Windows ARM64 дистрибутивов Temurin 17 нет вовсе,
// а foojay их не скачает — там сборку ведёт JDK 21 (WOLFY_PACKAGING_JVM=21).
//
// Спрашиваем toolchain только когда действительно собирают установщик:
// обращение к нему разрешает — и при необходимости качает — JDK прямо во время
// конфигурации, и делать это ради `desktopRun` или тестов незачем.
val packagingRequested = gradle.startParameter.taskNames.any { name ->
    name.contains("package", ignoreCase = true) || name.contains("Distributable")
}
val packagingJdkVersion = providers.gradleProperty("wolfyPackagingJdk")
    .orElse(providers.environmentVariable("WOLFY_PACKAGING_JDK"))
    .map { it.toInt() }
    .orElse(17)
val wolfyServerUrl = providers.gradleProperty("wolfyServerUrl")
    .orElse(providers.environmentVariable("WOLFY_SERVER_URL"))
    .orElse("http://localhost:8080")

/*
 * Версия приложения — одна строка на всю сборку.
 *
 * Раньше их было три: `packageVersion` установщика, константа в общем модуле и
 * литерал в `Main.kt`. Автообновление сравнивает манифест сервера именно с
 * последним, и стоило поднять версию в двух местах из трёх — обновление молча
 * переставало предлагаться, потому что установленная версия «уже свежая».
 *
 * Отсюда значение попадает и в установщик, и в launcher: в установленном
 * приложении нет ни терминала, ни его переменных среды, поэтому версия
 * запекается тем же способом, что и адрес API.
 */
val wolfyVersion = "1.0.11"
val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

// Compose Desktop не всегда считает javaOptions входом app-image. Тогда MSI
// получает новый ProductVersion, а launcher остаётся со старой версией и
// адресом сервера из кэша. Автообновление сравнивает именно версию launcher,
// поэтому оба значения объявлены явными входами упаковочных задач.
tasks.matching {
    it.name.contains("Distributable", ignoreCase = true) ||
        it.name.contains("packageRelease", ignoreCase = true)
}.configureEach {
    inputs.property("wolfyVersion", wolfyVersion)
    inputs.property("wolfyServerUrl", wolfyServerUrl)
}

// Путь к ядру для запуска из исходников.
//
// Только для него: в установленном приложении библиотека приезжает ресурсом, и
// куда именно её распаковали — знает сам установленный экземпляр. Добавить этот
// путь в общие jvmArgs значило бы зашить каталог машины разработчика в чужой
// установщик, где он и мусор, и обещание, которого никто не выполнит.
tasks.matching { it.name == "run" || it.name == "runRelease" }.configureEach {
    if (this is JavaExec) {
        jvmArgs("-Djna.library.path=" + coreLibFiles.get().asFile.absolutePath)
    }
}

compose.desktop {
    application {
        mainClass = "com.wolfy.desktop.MainKt"

        // Заставка.
        //
        // От щелчка по значку до окна проходит около двух секунд, и быстрее
        // JVM со Skia не стартует. Но всё это время на экране не происходит
        // ровно ничего, и читатель щёлкает по значку второй раз. Заставку
        // рисует сам запускатель JVM, до загрузки первого класса приложения,
        // — она появляется за десятую долю секунды.
        //
        // `$APPDIR` подставляет запускатель установленного приложения; при
        // запуске из исходников подстановки не происходит, файла по такому
        // пути нет, и JVM молча обходится без заставки. Разработчику она и не
        // нужна, а прятать довод за условием — лишняя развилка в сборке.
        jvmArgs += "-splash:\$APPDIR/resources/splash.png"
        // В установленном приложении нет терминала и его переменных среды.
        // Поэтому production-адрес API запекается в launcher при сборке;
        // WOLFY_SERVER_URL при запуске всё ещё может его переопределить.
        jvmArgs += "-Dwolfy.server.url=${wolfyServerUrl.get()}"
        jvmArgs += "-Dwolfy.version=$wolfyVersion"
        // Skiko FrameWatcher периодически вызывает System.gc() после серии
        // кадров. На Windows это давало измеренную STW-паузу 63 мс. G1 сам
        // выбирает безопасный момент для сборки, явные вызовы Wolfy не нужны.
        jvmArgs += "-XX:+DisableExplicitGC"

        // JNA находит C-функции динамически. Обычный shrink/optimize не видит
        // эти обращения и удаляет в release-сборке необходимые методы самой
        // JNA (в частности Native.dispose). Правила сохраняют мост целиком.
        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        if (packagingRequested) {
            javaHome = javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(packagingJdkVersion.get()))
            }.get().metadata.installationPath.asFile.absolutePath
        }

        nativeDistributions {
            // jpackage создаёт нативный пакет только для ОС, на которой идёт
            // сборка. Так GitHub Actions честно выпускает MSI на Windows и
            // DEB на Linux, не пытаясь собрать Windows-инсталлятор на Linux.
            if (isWindowsHost) {
                targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            } else {
                targetFormats(TargetFormat.Deb)
            }
            packageName = "Wolfy"
            packageVersion = wolfyVersion
            // Латиницей, и не по недосмотру: установщик собирает WiX, а строки
            // он пишет в кодовой странице 1252 — кириллица в неё не влезает и
            // роняет сборку целиком (LGHT0311). Название приложения при этом
            // остаётся своим, а описание в списке программ Windows читают
            // вторым планом, если читают вообще.
            description = "Wolfy - reading English books"
            vendor = "Wolfy"

            // Ядро едет в установщик как ресурс приложения.
            appResourcesRootDir.set(coreLibDir)

            windows {
                // Постоянный UUID: без него каждая новая сборка ставится
                // рядом со старой вместо обновления.
                upgradeUuid = "8f2b4c31-9d5e-4a7f-b6c8-1e3d5a7f9b2c"
                menuGroup = "Wolfy"
                // Иконка нужна в .ico, а не в png: Windows берёт из файла
                // размер под место, где рисует, — 16 точек в углу окна и 256
                // в проводнике. Один png она растянула бы в оба конца.
                iconFile.set(project.file("icons/wolfy.ico"))
            }
        }
    }
}
