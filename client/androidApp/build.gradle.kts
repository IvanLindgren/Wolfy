// Android-обёртка.
//
// Это обычный Android-модуль, а не KMP: приложению не нужны общие исходники,
// оно только подключает :shared. Здесь живёт то, чего не бывает на Windows, —
// манифест, Activity, иконка и подпись релиза.

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
        versionCode = 8
        versionName = "0.1.7"
        buildConfigField("String", "WOLFY_SERVER_URL", "\"${wolfyServerUrl.get()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].assets.srcDir(dictionaryAssets)
}

tasks.named("preBuild") { dependsOn(prepareBundledDictionary) }

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
