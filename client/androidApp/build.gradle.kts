// Android-обёртка.
//
// Это обычный Android-модуль, а не KMP: приложению не нужны общие исходники,
// оно только подключает :shared. Здесь живёт то, чего не бывает на Windows, —
// манифест, Activity, иконка и подпись релиза.

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val dictionaryAssets = layout.buildDirectory.dir("generated/dictionaryAssets")
val wolfyServerUrl = providers.gradleProperty("wolfyServerUrl")
    .orElse(providers.environmentVariable("WOLFY_SERVER_URL"))
    // Адрес эмулятора до сервера на машине разработчика. Для настоящего APK
    // production-адрес обязательно задаётся свойством или окружением.
    .orElse("http://10.0.2.2:8080")
val wolfyReleaseServerUrl = providers.gradleProperty("wolfyReleaseServerUrl")
    .orElse(providers.environmentVariable("WOLFY_RELEASE_SERVER_URL"))
    .orElse("https://wolfy.citavuk.ru")

// Релизный ключ — локальный секрет. Файл намеренно не попадает в Git: клон
// репозитория может собирать debug без него, а `packageRelease` ниже честно
// остановится, если ключ не настроен.
val signingProperties = Properties()
val signingPropertiesFile = rootProject.file("release-signing.properties")
if (signingPropertiesFile.isFile) {
    signingPropertiesFile.inputStream().use(signingProperties::load)
}
fun signingValue(name: String): String = providers.gradleProperty("wolfySigning.$name")
    .orElse(providers.environmentVariable("WOLFY_SIGNING_${name.uppercase()}"))
    .orElse(signingProperties.getProperty(name).orEmpty())
    .get()
val signingReady = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { signingValue(it).isNotBlank() }
val prepareBundledDictionary by tasks.registering(Sync::class) {
    description = "Кладёт офлайн-словарь в APK"
    from(rootProject.layout.projectDirectory.file("../dist/wolfy_dictionary.tsv.gz")) {
        // AAPT считает .gz транспортной упаковкой, распаковывает файл и
        // выбрасывает расширение. Собственное расширение сохраняет байты gzip
        // как есть, чтобы общий установщик мог проверить и распаковать их сам.
        rename { "wolfy_dictionary.wfd" }
    }
    from(rootProject.layout.projectDirectory.file("../THIRD_PARTY_NOTICES.md"))
    into(dictionaryAssets)
}

android {
    namespace = "com.wolfy.android"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    buildFeatures {
        // Один источник версии для манифеста и фоновой проверки обновлений.
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.wolfy.reader"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 11
        versionName = "1.0.11"
        buildConfigField("String", "WOLFY_SERVER_URL", "\"${wolfyServerUrl.get()}\"")
    }

    signingConfigs {
        create("release") {
            if (signingReady) {
                storeFile = rootProject.file(signingValue("storeFile"))
                storePassword = signingValue("storePassword")
                keyAlias = signingValue("keyAlias")
                keyPassword = signingValue("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("String", "WOLFY_SERVER_URL", "\"${wolfyReleaseServerUrl.get()}\"")
            if (signingReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].assets.srcDir(dictionaryAssets)
}

tasks.named("preBuild") { dependsOn(prepareBundledDictionary) }

tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst {
        check(signingReady) {
            "Для release APK задайте client/release-signing.properties или WOLFY_SIGNING_*"
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(libs.androidx.activity.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
