package com.wolfy.ui.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wolfy.data.companion.CompanionPersonality
import com.wolfy.data.companion.CompanionProfile
import com.wolfy.data.companion.CompanionRepository
import com.wolfy.data.companion.CompanionState
import com.wolfy.data.companion.MBTI_CODES
import com.wolfy.data.companion.MAX_DESCRIPTION
import com.wolfy.data.companion.PHRASE_COUNT
import com.wolfy.data.companion.validateProfile
import com.wolfy.data.companion.unicodeLength
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Состояние генерации набора реплик.
 *
 * Серверный вызов один: сто реплик создаются целиком или не создаются вовсе.
 * Ошибка не прячет честную причину и оставляет кнопку повтора.
 */
sealed interface PackRequest {
    data object Idle : PackRequest
    data object Loading : PackRequest
    data class Failure(val message: String, val retryable: Boolean) : PackRequest
}

/**
 * Логика экрана компаньона.
 *
 * Держит черновик мастера и не пускает в синхронизацию ничего, пока читатель
 * не нажал «Сохранить». Экран не знает про хранилище, хранилище не знает про
 * сеть: здесь только переходы состояний и проверки.
 */
@OptIn(ExperimentalUuidApi::class)
class CompanionViewModel(private val repository: CompanionRepository) {

    var state by mutableStateOf(CompanionState())
        private set

    var packRequest by mutableStateOf<PackRequest>(PackRequest.Idle)
        private set

    /** Шаг мастера: 0 — посадочная, 1..6 — шаги, 7 — создан. */
    var step by mutableStateOf(0)
        private set

    /** Экран подтверждения удаления. */
    var confirmDelete by mutableStateOf(false)
        private set

    fun restore() {
        repository.restore()
        state = repository.state.value
        step = if (state.profile == null) 0 else 7
    }

    /** Подхватывает sync и изменения из читалки, не сбрасывая открытый мастер. */
    fun refreshFromRepository() {
        state = repository.state.value
        if (state.draft == null) {
            if (state.profile == null && step == 7) step = 0
            if (state.profile != null && step == 0) step = 7
        }
    }

    fun startCreation() {
        val existing = state.editing ?: CompanionProfile(
            id = randomId(),
            name = "",
        )
        repository.saveDraft(existing)
        state = repository.state.value
        step = 1
    }

    fun skipCreation() {
        step = 7
    }

    fun back() {
        if (step > 1) {
            step -= 1
        } else {
            step = if (state.profile == null) 0 else 7
        }
    }

    fun next() {
        if (step in 1..5) step += 1
    }

    fun editAppearance() {
        step = 2
    }

    fun editPersonality() {
        step = 4
    }

    fun updateDraft(transform: (CompanionProfile) -> CompanionProfile) {
        val draft = state.editing ?: return
        repository.saveDraft(transform(draft))
        state = repository.state.value
    }

    fun setPersonality(key: String, value: Int) {
        updateDraft { it.copy(personality = it.personality.with(key, value)) }
    }

    /** Проверка перед сохранением: имя обязано быть, остальное мягко чинится. */
    fun draftValid(): Boolean {
        val draft = state.editing ?: return false
        return validateProfile(draft).valid
    }

    fun descriptionLength(): Int {
        val draft = state.editing ?: return 0
        return draft.description.unicodeLength()
    }

    fun descriptionLimit(): Int = MAX_DESCRIPTION

    fun mbtiOptions(): List<String> = MBTI_CODES.sorted()

    /**
     * Сохраняет профиль: он впервые уезжает в синхронизацию.
     *
     * Внешность менялась и офлайн: локальное сохранение не требует сети, а
     * синхронизация донесёт профиль при первой возможности.
     */
    fun save() {
        val draft = state.editing ?: return
        if (!draftValid()) return
        repository.save(draft)
        state = repository.state.value
        step = 7
    }

    /** Включает или выключает реплики при чтении, персонажа не трогает. */
    fun setReactionsEnabled(on: Boolean) {
        updateSaved { it.copy(reactionsEnabled = on) }
    }

    fun reactionsEnabled(): Boolean = state.profile?.reactionsEnabled ?: true

    /** Режим в читалке: `off` скрывает персонажа, `quiet` убирает реплики. */
    fun setReaderMode(mode: String) {
        updateSaved { it.copy(readerMode = mode) }
    }

    fun readerMode(): String = state.profile?.readerMode ?: "active"

    fun revokeAiConsent() {
        updateSaved { it.copy(aiConsentAt = 0) }
    }

    private fun updateSaved(transform: (CompanionProfile) -> CompanionProfile) {
        val profile = state.profile ?: return
        repository.save(transform(profile))
        state = repository.state.value
    }

    /**
     * Успех генерации: pack уже сохранён в репозитории вызывающей стороной.
     */
    fun markPackReady() {
        state = repository.state.value
        packRequest = PackRequest.Idle
    }

    fun markPackLoading() {
        packRequest = PackRequest.Loading
    }

    fun markPackFailed(message: String, retryable: Boolean) {
        packRequest = PackRequest.Failure(message, retryable)
    }

    fun askDelete() {
        confirmDelete = true
    }

    fun cancelDelete() {
        confirmDelete = false
    }

    /** Удаление сразу показывает пустое состояние и оставляет tombstone. */
    fun delete() {
        repository.delete()
        state = repository.state.value
        confirmDelete = false
        step = 0
    }

    private fun randomId(): String {
        // Идентификатор придумывает клиент: создание работает в самолёте.
        return Uuid.random().toString()
    }
}
