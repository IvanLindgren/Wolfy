package com.wolfy.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.onDownload
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
import com.wolfy.platform.BrowserAuthLauncher
import com.wolfy.platform.deviceName
import com.wolfy.platform.devicePlatform
import com.wolfy.ffi.DictionaryEntry
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
    private val deviceProvider: () -> DeviceInfo = {
        DeviceInfo(id = "", name = deviceName(), platform = devicePlatform())
    },
    private val client: HttpClient = defaultClient(),
) {
    suspend fun signIn(email: String, password: String): AuthOutcome = try {
        val response = client.post("$baseUrl/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                AuthRequest(
                    email = email.trim(),
                    password = password,
                    device = deviceProvider(),
                ),
            )
        }
        when (response.status) {
            HttpStatusCode.OK -> response.body<AuthResponse>().let {
                AuthOutcome.SignedIn(
                    token = it.token,
                    email = it.email.ifBlank { it.user?.email.orEmpty().ifBlank { email.trim() } },
                    name = it.name.ifBlank { it.displayName.ifBlank { it.user?.displayName.orEmpty() } },
                )
            }
            HttpStatusCode.Forbidden -> {
                val reason = response.authMessage()
                if (reason.contains("unverified", ignoreCase = true) ||
                    reason.contains("подтверд", ignoreCase = true)
                ) {
                    AuthOutcome.EmailNotConfirmed(email.trim())
                } else {
                    AuthOutcome.Refused(reason.ifBlank { "Вход запрещён." })
                }
            }
            HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized, HttpStatusCode.Conflict ->
                AuthOutcome.Refused(response.authMessage().ifBlank { "Неверная почта или пароль." })
            else -> AuthOutcome.Refused("Вход сейчас недоступен.")
        }
    } catch (_: Exception) {
        AuthOutcome.Offline
    }

    suspend fun signUp(email: String, password: String, name: String): AuthOutcome = try {
        val response = client.post("$baseUrl/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                AuthRequest(
                    email = email.trim(),
                    password = password,
                    name = name.trim(),
                    device = deviceProvider(),
                ),
            )
        }
        when (response.status) {
            HttpStatusCode.Accepted -> AuthOutcome.AwaitingEmail(email.trim())
            HttpStatusCode.OK -> response.body<AuthResponse>().let {
                if (it.token.isNotBlank()) AuthOutcome.SignedIn(
                    it.token,
                    it.email.ifBlank { it.user?.email.orEmpty().ifBlank { email.trim() } },
                    it.name.ifBlank { it.displayName.ifBlank { it.user?.displayName.orEmpty().ifBlank { name.trim() } } },
                )
                else AuthOutcome.AwaitingEmail(email.trim())
            }
            HttpStatusCode.BadRequest, HttpStatusCode.Conflict ->
                AuthOutcome.Refused(response.authMessage().ifBlank { "Не получилось создать аккаунт." })
            HttpStatusCode.NotImplemented ->
                AuthOutcome.Refused("На этом сервере регистрация не настроена.")
            else -> AuthOutcome.Refused("Регистрация сейчас недоступна.")
        }
    } catch (_: Exception) {
        AuthOutcome.Offline
    }

    suspend fun resendVerification(email: String): Boolean = try {
        val response = client.post("$baseUrl/v1/auth/resend-verification") {
            contentType(ContentType.Application.Json)
            setBody(ResendRequest(email.trim()))
        }
        response.status.value in 200..299
    } catch (_: Exception) {
        false
    }

    suspend fun capabilities(): Capabilities = try {
        val response = client.get("$baseUrl/healthz")
        if (response.status == HttpStatusCode.OK) response.body() else Capabilities()
    } catch (_: Exception) {
        Capabilities()
    }

    /**
     * Google открывается только в системном браузере. Сервер обменивает код
     * на ID token, Читавук связывает или создаёт общий аккаунт, а сессия
     * возвращается POST-запросом на одноразовый loopback-адрес приложения.
     */
    suspend fun signInWithGoogle(launcher: BrowserAuthLauncher): AuthOutcome = try {
        val callback = launcher.launch { returnUrl ->
            socialStart("/v1/auth/google/start", SocialStartRequest(returnUrl = returnUrl))
        }
        callback.error?.let { return AuthOutcome.Refused(it) }
        val status = callback.parameters["status"]?.toIntOrNull() ?: 0
        val payload = callback.parameters["payload"].orEmpty()
        if (status in 200..299) authOutcome(payload)
        else AuthOutcome.Refused(
            callback.parameters["error"].orEmpty().ifBlank {
                authError(payload).ifBlank { "Не удалось войти через Google." }
            },
        )
    } catch (error: Exception) {
        AuthOutcome.Refused(error.message ?: "Не удалось войти через Google.")
    }

    /** Первый вход через Яндекс одновременно регистрирует общий аккаунт. */
    suspend fun signInWithYandex(launcher: BrowserAuthLauncher): AuthOutcome = try {
        val callback = launcher.launch { returnUrl ->
            socialStart(
                "/v1/auth/yandex/start",
                SocialStartRequest(returnUrl = returnUrl, returnTarget = "desktop"),
            )
        }
        callback.error?.let { return AuthOutcome.Refused("Вход через Яндекс отменён.") }
        val code = callback.parameters["code"].orEmpty()
        if (code.isBlank()) return AuthOutcome.Refused("Яндекс не вернул код входа.")
        val response = client.post("$baseUrl/v1/auth/yandex/complete") {
            contentType(ContentType.Application.Json)
            setBody(YandexCompleteRequest(code, deviceProvider()))
        }
        val payload = response.body<String>()
        if (response.status.value in 200..299) authOutcome(payload)
        else AuthOutcome.Refused(authError(payload).ifBlank { "Не удалось войти через Яндекс." })
    } catch (error: Exception) {
        AuthOutcome.Refused(error.message ?: "Не удалось войти через Яндекс.")
    }

    private suspend fun socialStart(path: String, request: SocialStartRequest): String {
        val response = client.post(baseUrl + path) {
            contentType(ContentType.Application.Json)
            setBody(request.copy(device = deviceProvider()))
        }
        if (response.status != HttpStatusCode.OK) {
            val message = runCatching { response.body<AuthError>().message() }.getOrDefault("")
            error(message.ifBlank { "Этот способ входа сейчас недоступен." })
        }
        return response.body<SocialStartResponse>().authorizationUrl.ifBlank {
            error("Сервер не вернул адрес входа.")
        }
    }

    private fun authOutcome(payload: String): AuthOutcome = runCatching {
        val response = authJson.decodeFromString<AuthResponse>(payload)
        if (response.token.isBlank()) return@runCatching AuthOutcome.Refused("Сессия не получена.")
        AuthOutcome.SignedIn(
            token = response.token,
            email = response.email.ifBlank { response.user?.email.orEmpty() },
            name = response.name.ifBlank {
                response.displayName.ifBlank { response.user?.displayName.orEmpty() }
            },
        )
    }.getOrElse { AuthOutcome.Refused("Ответ сервера входа не разобран.") }

    private fun authError(payload: String): String = runCatching {
        authJson.decodeFromString<AuthError>(payload).message()
    }.getOrDefault("")

    /** Совместимость со старым экраном ленты. */
    suspend fun login(email: String, password: String): LoginResult = when (val result = signIn(email, password)) {
        is AuthOutcome.SignedIn -> LoginResult.Ready(result.token)
        is AuthOutcome.AwaitingEmail -> LoginResult.Failed("Проверьте почту.")
        is AuthOutcome.EmailNotConfirmed -> LoginResult.Failed("Сначала подтвердите почту в Читавуке.")
        is AuthOutcome.Refused -> LoginResult.Failed(result.message)
        AuthOutcome.Offline -> LoginResult.Failed("Нет связи с сервером.")
    }

    private suspend fun io.ktor.client.statement.HttpResponse.authMessage(): String = runCatching {
        body<AuthError>().message()
    }.getOrDefault("")

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

    /** Толкование с сервера для устройства без скачанного словаря. */
    suspend fun define(word: String): DefineResult = try {
        val response = client.get("$baseUrl/v1/define") {
            parameter("word", word)
        }
        when (response.status) {
            HttpStatusCode.OK -> DefineResult.Ready(response.body())
            HttpStatusCode.NotFound -> DefineResult.Missing
            HttpStatusCode.ServiceUnavailable -> DefineResult.Failed
            else -> DefineResult.Failed
        }
    } catch (e: Exception) {
        DefineResult.Failed
    }

    /** Скачивает gzip-архив словаря, отдавая UI долю принятых байтов. */
    suspend fun downloadDictionary(onProgress: (Float?) -> Unit): DictionaryDownloadResult = try {
        val response = client.get("$baseUrl/v1/dictionary") {
            timeout { requestTimeoutMillis = 120_000 }
            onDownload { received, total ->
                onProgress(total?.takeIf { it > 0L }?.let { received.toFloat() / it.toFloat() })
            }
        }
        if (response.status == HttpStatusCode.OK) {
            DictionaryDownloadResult.Ready(response.body())
        } else {
            DictionaryDownloadResult.Failed(
                "Сервер не отдал словарь (HTTP ${response.status.value}).",
            )
        }
    } catch (e: Exception) {
        DictionaryDownloadResult.Failed("Нет связи с сервером словаря.")
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

    // --- Открытая библиотека ---

    /**
     * Ищет книги в Открытой библиотеке.
     *
     * Поиск идёт через свой сервер, а не напрямую в каталог: один канал
     * наружу, один ограничитель частоты и одна точка, где ответ каталога
     * превращается в понятные приложению поля.
     */
    suspend fun searchCatalogue(query: String): CatalogueResult = try {
        val response = client.get("$baseUrl/v1/library/catalogue") {
            parameter("q", query.trim())
            parameter("limit", 24)
        }
        when (response.status) {
            HttpStatusCode.OK -> CatalogueResult.Ready(response.body<CatalogueResponse>().books)
            HttpStatusCode.TooManyRequests ->
                CatalogueResult.Failed("Слишком много поисков подряд, подождите минуту.")
            else -> CatalogueResult.Failed("Каталог сейчас недоступен.")
        }
    } catch (e: Exception) {
        CatalogueResult.Failed("Нет связи с сервером.")
    }

    /**
     * Скачивает книгу каталога через защищённый загрузчик сервера.
     *
     * У находки бывает несколько ссылок — сначала EPUB из архива, затем
     * послойный текст. Пробуем по порядку до первой удачи: у части отскоков
     * производного EPUB нет, и текстовая версия лучше пустого ответа.
     */
    suspend fun downloadCatalogueBook(book: CatalogueBook): RemoteBookResult {
        var failure: String? = null
        for (address in book.urls) {
            when (val attempt = fetchRemoteBook(address)) {
                is RemoteBookResult.Ready -> return attempt
                is RemoteBookResult.Failed -> failure = attempt.message
            }
        }
        return RemoteBookResult.Failed(failure ?: "Книгу не удалось скачать.")
    }

    /**
     * Скачивает книгу с публичного HTTPS-адреса через сервер.
     *
     * Напрямую приложение могло бы и само, но проверку адреса при каждом
     * перенаправлении, предел размера и опознание формата по содержимому
     * держат в одном месте — на сервере.
     */
    suspend fun fetchRemoteBook(address: String): RemoteBookResult = try {
        val response = client.post("$baseUrl/v1/library/fetch") {
            contentType(ContentType.Application.Json)
            setBody(RemoteBookRequest(url = address.trim()))
            timeout { requestTimeoutMillis = 180_000 }
        }
        when {
            response.status == HttpStatusCode.OK -> {
                val name = remoteFileName(
                    disposition = response.headers["Content-Disposition"],
                    address = address,
                )
                RemoteBookResult.Ready(bytes = response.body(), fileName = name)
            }
            response.status == HttpStatusCode.TooManyRequests ->
                RemoteBookResult.Failed("Сервер уже качает другую книгу, подождите немного.")
            else -> {
                // Тело ошибки короткое и человеческое: «по ссылке нет
                // поддерживаемой книги…» годится для показа без переделки.
                val note = runCatching {
                    response.body<ApiErrorBody>().error
                }.getOrNull()
                RemoteBookResult.Failed(note ?: "Книгу по ссылке сейчас не удалось скачать.")
            }
        }
    } catch (e: Exception) {
        RemoteBookResult.Failed("Нет связи с сервером.")
    }

    private fun remoteFileName(disposition: String?, address: String): String {
        val encoded = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
            .find(disposition.orEmpty())?.groupValues?.get(1)
        return decodePercent(encoded ?: address.substringAfterLast('/')).ifBlank { "book" }
    }

    /**
     * Разбирает percent-кодирование заголовка.
     *
     * Своя реализация вместо платформенной по той же причине, что и Base64
     * выше: двадцать строк надёжнее оговорки про API 26.
     */
    private fun decodePercent(value: String): String {
        val bytes = ArrayList<Byte>(value.length)
        var index = 0
        while (index < value.length) {
            val symbol = value[index]
            val byte = if (symbol == '%' && index + 2 < value.length) {
                value.substring(index + 1, index + 3).toIntOrNull(16)
            } else {
                null
            }
            if (byte != null) {
                bytes.add(byte.toByte())
                index += 3
            } else {
                symbol.toString().encodeToByteArray().forEach(bytes::add)
                index += 1
            }
        }
        return bytes.toByteArray().decodeToString()
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
data class DeviceInfo(val id: String, val name: String, val platform: String)

sealed interface AuthOutcome {
    data class SignedIn(val token: String, val email: String, val name: String) : AuthOutcome
    data class AwaitingEmail(val email: String) : AuthOutcome
    data class EmailNotConfirmed(val email: String) : AuthOutcome
    data class Refused(val message: String) : AuthOutcome
    data object Offline : AuthOutcome
}

@Serializable
data class Capabilities(
    val signIn: Boolean = false,
    val register: Boolean = false,
    val resend: Boolean = false,
    val google: Boolean = false,
    val yandex: Boolean = false,
)

@Serializable
private data class AuthRequest(
    val email: String,
    val password: String,
    val name: String = "",
    val device: DeviceInfo,
)

@Serializable
private data class ResendRequest(val email: String)

@Serializable
private data class AuthResponse(
    val token: String = "",
    val email: String = "",
    val name: String = "",
    val displayName: String = "",
    val user: AuthUser? = null,
)

@Serializable
private data class AuthUser(
    val email: String = "",
    val displayName: String = "",
)

@Serializable
private data class SocialStartRequest(
    val returnUrl: String,
    val returnTarget: String = "",
    val device: DeviceInfo = DeviceInfo("", "", ""),
)

@Serializable
private data class SocialStartResponse(val authorizationUrl: String = "")

@Serializable
private data class YandexCompleteRequest(val code: String, val device: DeviceInfo)

@Serializable
private data class AuthError(
    val error: String = "",
    val message: String = "",
    val code: String = "",
) {
    fun message(): String = message.ifBlank { error.ifBlank { code } }
}

private val authJson = Json { ignoreUnknownKeys = true }

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

sealed interface DefineResult {
    data class Ready(val entry: DictionaryEntry) : DefineResult
    data object Missing : DefineResult
    data object Failed : DefineResult
}

sealed interface DictionaryDownloadResult {
    data class Ready(val bytes: ByteArray) : DictionaryDownloadResult
    data class Failed(val message: String) : DictionaryDownloadResult
}

// --- Открытая библиотека ---

/** Находка поиска по Открытой библиотеке. */
@Serializable
data class CatalogueBook(
    /** Номер работы в каталоге вида «OL267218W». */
    val id: String,
    val title: String,
    val author: String = "",
    val year: Int = 0,
    /** Ссылки на скачивание по убыванию предпочтительности. */
    val urls: List<String> = emptyList(),
)

sealed interface CatalogueResult {
    data class Ready(val books: List<CatalogueBook>) : CatalogueResult
    data class Failed(val message: String) : CatalogueResult
}

@Serializable
private data class CatalogueResponse(val books: List<CatalogueBook> = emptyList())

@Serializable
private data class RemoteBookRequest(val url: String)

@Serializable
private data class ApiErrorBody(val error: String = "", val message: String = "")

/** Результат скачивания книги с публичного адреса. */
sealed interface RemoteBookResult {
    data class Ready(val bytes: ByteArray, val fileName: String) : RemoteBookResult
    data class Failed(val message: String) : RemoteBookResult
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
