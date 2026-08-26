# JNA регистрирует методы через нативный код и рефлексию. ProGuard не может
# вывести эти связи статически, поэтому весь мост должен остаться неизменным.
-keep class com.sun.jna.** { *; }
-keep interface com.wolfy.ffi.CoreLibrary { *; }
# Платформенные JNA-мосты (например, удержание экрана) тоже вызываются по
# имени метода. Правило общее, чтобы следующая маленькая системная функция не
# сломалась только после обфускации release-пакета.
-keep interface * implements com.sun.jna.Library { *; }
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}
-dontwarn com.sun.jna.**

# Ktor и kotlinx.coroutines находят эти реализации через ServiceLoader.
# Ссылка на имя класса лежит в META-INF/services, поэтому статический анализ
# ProGuard её не видит и без правил удаляет провайдер из release-пакета.
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }
-keep class * implements io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
