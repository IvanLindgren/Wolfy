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

    defaultConfig {
        applicationId = "com.wolfy.reader"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 3
        versionName = "0.1.2"
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
