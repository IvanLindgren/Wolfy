package com.wolfy.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import com.wolfy.platform.PickedPhoto
import kotlinx.serialization.json.Json

/**
 * Клиент сервера Wolfy.
 *
 * Через сервер идёт только то, чего нельзя сделать на устройстве: контекстный
 * перевод, синхронизация и распознавание страницы по фото. Разбор слова сюда
 * не ходит — он считается ядром локально и обязан быть мгновенным.
 *
 * @param baseUrl адрес сервиса.
 * @param token сессионный токен Читавука: аккаунт общий, свой вход у Wolfy
 *   отсутствует.
 */
class WolfyApi(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val client: HttpClient = defaultClient(),
) {
    suspend fun login(email: String, password: String): LoginResult = try {
        val response = client.post("$baseUrl/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    email = email.trim(),
                    password = password,
                    device = LoginDevice(name = "Wolfy", platform = "compose"),
                ),
            )
        }
        when (response.status) {
            HttpStatusCode.OK -> LoginResult.Ready(response.body<LoginResponse>().token)
            HttpStatusCode.Unauthorized -> LoginResult.Failed("Неверная почта или пароль.")
            HttpStatusCode.Forbidden -> LoginResult.Failed("Сначала подтвердите почту в Читавуке.")
            else -> LoginResult.Failed("Вход сейчас недоступен.")
        }
    } catch (e: Exception) {
        LoginResult.Failed("Нет связи с сервером.")
    }

    suspend fun discoveryProfile(): DiscoveryProfileResult = authorizedGet("/v1/discovery/profile") {
        DiscoveryProfileResult.Ready(it.body())
    }

    suspend fun saveDiscoveryProfile(profile: DiscoveryProfile): DiscoveryProfileResult {
        val token = tokenProvider() ?: return DiscoveryProfileResult.SignedOut
        return try {
            val response = client.put("$baseUrl/v1/discovery/profile") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(profile)
            }
            when (response.status) {
                HttpStatusCode.OK -> DiscoveryProfileResult.Ready(response.body())
                HttpStatusCode.Unauthorized -> DiscoveryProfileResult.SignedOut
                else -> DiscoveryProfileResult.Failed("Не получилось сохранить интересы.")
            }
        } catch (e: Exception) {
            DiscoveryProfileResult.Failed("Нет связи с сервером.")
        }
    }

    suspend fun discoveryFeed(cursor: Int = 0): DiscoveryFeedResult {
        val token = tokenProvider() ?: return DiscoveryFeedResult.SignedOut
        return try {
            val response = client.get("$baseUrl/v1/discovery/feed") {
                header("Authorization", "Bearer $token")
                parameter("cursor", cursor)
                parameter("limit", 30)
            }
            when (response.status) {
                HttpStatusCode.OK -> DiscoveryFeedResult.Ready(response.body())
                HttpStatusCode(428, "Precondition Required") -> DiscoveryFeedResult.NeedsOnboarding
                HttpStatusCode.Unauthorized -> DiscoveryFeedResult.SignedOut
                else -> DiscoveryFeedResult.Failed("Лента сейчас недоступна.")
            }
        } catch (e: Exception) {
            DiscoveryFeedResult.Failed("Нет связи с сервером.")
        }
    }

    suspend fun likeDiscoveryItem(itemId: String): ActionResult = authorizedPost(
        "/v1/discovery/items/$itemId/like",
    )

    suspend fun downloadDiscoveryItem(item: DiscoveryItem): DownloadResult {
        val token = tokenProvider() ?: return DownloadResult.SignedOut
        return try {
            val response = client.post("$baseUrl/v1/discovery/items/${item.id}/add") {
                header("Authorization", "Bearer $token")
                timeout { requestTimeoutMillis = 120_000 }
            }
            when (response.status) {
                HttpStatusCode.OK -> DownloadResult.Ready(
                    bytes = response.body(),
                    fileName = safeFileName(item.title) + ".epub",
                )
                HttpStatusCode.Unauthorized -> DownloadResult.SignedOut
                else -> DownloadResult.Failed("Не получилось скачать книгу.")
            }
        } catch (e: Exception) {
            DownloadResult.Failed("Нет связи с сервером.")
        }
    }

    private suspend fun authorizedPost(path: String): ActionResult {
        val token = tokenProvider() ?: return ActionResult.SignedOut
        return try {
            val response = client.post(baseUrl + path) {
                header("Authorization", "Bearer $token")
            }
            when (response.status) {
                HttpStatusCode.OK -> ActionResult.Ready
                HttpStatusCode.Unauthorized -> ActionResult.SignedOut
                else -> ActionResult.Failed("Действие не сохранилось.")
            }
        } catch (e: Exception) {
            ActionResult.Failed("Нет связи с сервером.")
        }
    }

    private suspend fun authorizedGet(
        path: String,
        ready: suspend (io.ktor.client.statement.HttpResponse) -> DiscoveryProfileResult,
    ): DiscoveryProfileResult {
        val token = tokenProvider() ?: return DiscoveryProfileResult.SignedOut
        return try {
            val response = client.get(baseUrl + path) {
                header("Authorization", "Bearer $token")
            }
            when (response.status) {
                HttpStatusCode.OK -> ready(response)
                HttpStatusCode.Unauthorized -> DiscoveryProfileResult.SignedOut
                else -> DiscoveryProfileResult.Failed("Профиль сейчас недоступен.")
            }
        } catch (e: Exception) {
            DiscoveryProfileResult.Failed("Нет связи с сервером.")
        }
    }
    /**
     * Переводит текст в контексте.
     *
     * Ошибки не бросаются наружу исключениями: перевод — необязательная часть
     * карточки, и падать из-за него нельзя. Вызывающий получает результат,
     * который умеет быть неудачей.
     */
    suspend fun translate(text: String, source: String = "EN", target: String = "RU"): TranslateResult {
        // Аккаунт здесь не нужен. Читатель, поставивший приложение, должен
        // получить перевод в первую же минуту: без него книга на чужом языке
        // остаётся книгой на чужом языке, а требовать регистрацию за то, ради
        // чего приложение и ставят, — верный способ его удалить.
        //
        // Токен всё же отправляется, если он есть: вошедшему сервер даёт
        // предел частоты выше, чем случайному адресу.
        val token = tokenProvider()

        return try {
            val response = client.post("$baseUrl/v1/translate") {
                token?.let { header("Authorization", "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(TranslateRequest(text = text, source = source, target = target))
            }
            when (response.status) {
                HttpStatusCode.OK -> TranslateResult.Ready(response.body<TranslateResponse>().text)
                HttpStatusCode.TooManyRequests ->
                    TranslateResult.Failed("слишком много переводов подряд, подождите минуту")
                HttpStatusCode.ServiceUnavailable -> TranslateResult.Failed("перевод сейчас недоступен")
                else -> TranslateResult.Failed("перевод не получился")
            }
        } catch (e: Exception) {
            // Сюда попадает всё сетевое: нет интернета, оборвалось соединение,
            // не отвечает сервер. Для читателя это одно и то же.
            TranslateResult.Failed("нет связи с сервером")
        }
    }

    /**
     * Обменивается изменениями библиотеки.
     *
     * Один запрос в обе стороны: отправляем своё и свой курсор, получаем
     * назад чужое — и своё тоже, уже с присвоенными ревизиями, чтобы не
     * гадать, дошло ли.
     */
    suspend fun sync(payload: SyncPayload): SyncResult {
        val token = tokenProvider() ?: return SyncResult.Failed("нужно войти в аккаунт")

        return try {
            val response = client.post("$baseUrl/v1/sync") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            when (response.status) {
                HttpStatusCode.OK -> SyncResult.Ready(response.body())
                HttpStatusCode.Unauthorized -> SyncResult.Failed("нужно войти заново")
                HttpStatusCode.PayloadTooLarge ->
                    SyncResult.Failed("библиотека слишком велика для одной отправки")
                else -> SyncResult.Failed("сервер не принял изменения")
            }
        } catch (e: Exception) {
            SyncResult.Failed("нет связи с сервером")
        }
    }

    /**
     * Распознаёт страницу бумажной книги по снимку.
     *
     * Ответ бывает долгим — модель смотрит на картинку, — и это единственный
     * запрос приложения, ради которого не грех показать ожидание.
     */
    suspend fun recognize(photo: PickedPhoto): OcrResult {
        val token = tokenProvider() ?: return OcrResult.Failed("нужно войти в аккаунт")

        return try {
            val response = client.post("$baseUrl/v1/ocr") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(OcrRequest(image = encodeBase64(photo.bytes), mime = photo.mime))
                timeout { requestTimeoutMillis = 120_000 }
            }
            when (response.status) {
                HttpStatusCode.OK -> {
                    val text = response.body<OcrResponse>().text
                    if (text.isBlank()) {
                        OcrResult.Failed("на снимке не нашлось текста")
                    } else {
                        OcrResult.Ready(text)
                    }
                }
                HttpStatusCode.Unauthorized -> OcrResult.Failed("нужно войти заново")
                HttpStatusCode.PayloadTooLarge -> OcrResult.Failed("снимок слишком большой")
                HttpStatusCode.ServiceUnavailable ->
                    OcrResult.Failed("распознавание сейчас недоступно")
                else -> OcrResult.Failed("не получилось распознать страницу")
            }
        } catch (e: Exception) {
            OcrResult.Failed("нет связи с сервером")
        }
    }

    companion object {
        fun defaultClient(): HttpClient = HttpClient {
            // Распознавание снимка идёт секунды, а иногда и десятки секунд:
            // модель смотрит на картинку. Общий таймаут поднят под него, а
            // короткие запросы от этого не страдают — они и так отвечают быстро.
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    },
                )
            }
        }
    }
}

sealed interface LoginResult {
    data class Ready(val token: String) : LoginResult
    data class Failed(val message: String) : LoginResult
}

@Serializable
private data class LoginRequest(
    val email: String,
    val password: String,
    val device: LoginDevice,
)

@Serializable
private data class LoginDevice(
    val id: String = "",
    val name: String,
    val platform: String,
)

@Serializable
private data class LoginResponse(val token: String)

@Serializable
data class DiscoveryProfile(
    val englishLevel: String = "",
    val genres: List<String> = emptyList(),
    val onboardingComplete: Boolean = false,
)

@Serializable
data class DiscoveryItem(
    val id: String,
    val contentType: String = "book",
    val title: String,
    val author: String = "",
    val summary: String,
    val genres: List<String> = emptyList(),
    val level: String = "B2",
    val coverUrl: String = "",
    val pageUrl: String = "",
    val liked: Boolean = false,
    val added: Boolean = false,
)

@Serializable
data class DiscoveryPage(
    val items: List<DiscoveryItem> = emptyList(),
    val nextCursor: Int = 0,
    val hasMore: Boolean = false,
)

sealed interface DiscoveryProfileResult {
    data class Ready(val profile: DiscoveryProfile) : DiscoveryProfileResult
    data class Failed(val message: String) : DiscoveryProfileResult
    data object SignedOut : DiscoveryProfileResult
}

sealed interface DiscoveryFeedResult {
    data class Ready(val page: DiscoveryPage) : DiscoveryFeedResult
    data class Failed(val message: String) : DiscoveryFeedResult
    data object NeedsOnboarding : DiscoveryFeedResult
    data object SignedOut : DiscoveryFeedResult
}

sealed interface ActionResult {
    data object Ready : ActionResult
    data object SignedOut : ActionResult
    data class Failed(val message: String) : ActionResult
}

sealed interface DownloadResult {
    data class Ready(val bytes: ByteArray, val fileName: String) : DownloadResult
    data object SignedOut : DownloadResult
    data class Failed(val message: String) : DownloadResult
}

private fun safeFileName(title: String): String = title.map { character ->
    if (character.isLetterOrDigit() || character in " -_") character else '_'
}.joinToString("").trim().ifBlank { "standard-ebook" }

/** Результат распознавания страницы. */
sealed interface OcrResult {
    data class Ready(val text: String) : OcrResult
    data class Failed(val message: String) : OcrResult
}

@Serializable
private data class OcrRequest(val image: String, val mime: String)

@Serializable
private data class OcrResponse(val text: String = "", val model: String = "")

/** Результат обмена с сервером. */
sealed interface SyncResult {
    data class Ready(val payload: SyncPayload) : SyncResult

    /**
     * Обмен не состоялся.
     *
     * Это не авария: библиотека уже лежит на устройстве и работает без сети.
     * Сообщение пишется для читателя, а не для лога.
     */
    data class Failed(val message: String) : SyncResult
}

/** Результат перевода. */
sealed interface TranslateResult {
    data class Ready(val text: String) : TranslateResult
    data class Failed(val message: String) : TranslateResult
}

@Serializable
private data class TranslateRequest(
    val text: String,
    val source: String,
    val target: String,
)

@Serializable
private data class TranslateResponse(
    val text: String,
    val cached: Boolean = false,
)
