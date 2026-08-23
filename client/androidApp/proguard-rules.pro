# JNA находит методы C ABI по именам интерфейса во время выполнения. R8 не
# видит эти вызовы статически, поэтому переименование сломало бы Rust-ядро
# только в release-сборке.
-keep interface com.wolfy.ffi.CoreLibrary { *; }
-keep class com.sun.jna.** { *; }
-dontwarn java.awt.**
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

# Ktor выбирает сетевой движок и сериализацию через ServiceLoader. R8 не
# видит строковые имена провайдеров в META-INF/services и иначе может удалить
# OkHttp только из release APK.
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }
-keep class * implements io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
