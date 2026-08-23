// Общий модуль: весь интерфейс и вся логика клиента.
//
// Android и Windows отличаются тремя вещами — как загрузить ядро на Rust, где
// лежат файлы пользователя и какой HTTP-движок использовать. Всё остальное
// живёт здесь и пишется один раз.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        // Мост к ядру одинаков на обеих платформах — он написан на JNA и
        // лежит в общем для них каталоге. Отдельного набора исходников под
        // «оба JVM-таргета» в шаблоне иерархии нет, поэтому подключаем каталог
        // руками к каждому.
        androidMain.get().kotlin.srcDir("src/jvmSharedMain/kotlin")
        named("desktopMain").get().kotlin.srcDir("src/jvmSharedMain/kotlin")

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.compose.ui.backhandler)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)

            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.navigation.compose)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            // На Android JNA нужна именно как aar: обычный jar не несёт
            // нативных частей под ABI устройства и падает при загрузке.
            implementation("${libs.jna.get()}@aar")
            // OkHttp на Android: он уже есть в системе и умеет то, чего CIO
            // не умеет, — прозрачно ходить через системные настройки сети.
            implementation(libs.ktor.client.okhttp)
            // Выбор файла на Android идёт через чужой экран, а его запуск
            // регистрируется в композиции — это отсюда.
            implementation(libs.androidx.activity.compose)
            // FileProvider: снимок страницы передаётся системной камере
            // ссылкой, а file-ссылку с Android 7 передавать нельзя.
            implementation(libs.androidx.core.ktx)
        }

        named("desktopMain").dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.cio)
            implementation(libs.jna)
        }
    }
}

// Шрифты и прочие ассеты. Класс ресурсов кладём в собственный пакет: имя по
// умолчанию собирается из координат модуля и меняется вместе с ними, а импорт
// в коде должен быть стабильным.
compose.resources {
    publicResClass = true
    packageOfResClass = "com.wolfy.resources"
    generateResClass = auto
}

android {
    namespace = "com.wolfy.shared"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].apply {
        // Собранное ядро на Rust: сюда его кладёт tools/build_core.sh android.
        jniLibs.srcDirs("src/androidMain/jniLibs")
    }
}
