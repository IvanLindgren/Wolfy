package com.wolfy.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
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
    /**
     * Переводит текст в контексте.
     *
     * Ошибки не бросаются наружу исключениями: перевод — необязательная часть
     * карточки, и падать из-за него нельзя. Вызывающий получает результат,
     * который умеет быть неудачей.
     */
    suspend fun translate(text: String, source: String = "EN", target: String = "RU"): TranslateResult {
        val token = tokenProvider()
            ?: return TranslateResult.Failed("нужно войти в аккаунт")

        return try {
            val response = client.post("$baseUrl/v1/translate") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(TranslateRequest(text = text, source = source, target = target))
            }
            when (response.status) {
                HttpStatusCode.OK -> TranslateResult.Ready(response.body<TranslateResponse>().text)
                HttpStatusCode.Unauthorized -> TranslateResult.Failed("нужно войти заново")
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
