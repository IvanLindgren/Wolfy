package com.wolfy.data.companion

import com.wolfy.data.library.currentTimeMillis
import com.wolfy.data.library.LibraryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Состояние компаньона на устройстве.
 *
 * [profile] — сохранённый профиль, тот, что уезжает в синхронизацию.
 * [tombstone] — запись, помеченная удалённой и ещё не отправленная серверу:
 * без неё удаление на одном устройстве ожило бы после первой же синхронизации.
 * [draft] — черновик редактора: существует только на этом устройстве и не
 * синхронизируется, пока читатель не нажал «Сохранить». Черновик переживает
 * закрытие приложения: недоделанная внешность не должна пропасть из-за звонка.
 */
data class CompanionState(
    val profile: CompanionProfile? = null,
    val tombstone: CompanionProfile? = null,
    val draft: CompanionProfile? = null,
) {
    /** Профиль или черновик, если сохранённого ещё нет: редактор смотрит сюда. */
    val editing: CompanionProfile? get() = draft ?: profile

    /** Запись для отправки: tombstone или локально изменённый профиль. */
    val outgoing: CompanionProfile?
        get() = tombstone ?: profile?.takeIf { !it.deleted && it.rev == 0L }
}

/**
 * Репозиторий компаньона.
 *
 * Хранит профиль в именованной записи [LibraryStore]: как и библиотека, это
 * маленький JSON, живущий на устройстве. Синхронизация читает [state] напрямую
 * и пишет через [save] и [applyServer]; сетевых вызовов здесь нет по правилу
 * «репозиторий знает данные, сервис знает сеть».
 */
class CompanionRepository(private val store: LibraryStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow(CompanionState())
    val state: StateFlow<CompanionState> = _state.asStateFlow()

    /** Загружает сохранённое. Вызывается один раз при старте приложения. */
    fun restore() {
        if (_state.value.profile != null || _state.value.tombstone != null) return
        val saved = store.load(STORE_KEY)?.let { text ->
            runCatching { json.decodeFromString(CompanionProfile.serializer(), text) }.getOrNull()
        }
        val draft = store.load(DRAFT_KEY)?.let { text ->
            runCatching { json.decodeFromString(CompanionProfile.serializer(), text) }.getOrNull()
        }
        _state.value = CompanionState(
            profile = saved?.takeUnless { it.deleted },
            tombstone = saved?.takeIf { it.deleted },
            draft = draft,
        )
    }

    /**
     * Сохраняет профиль целиком и снимает черновик.
     *
     * Идентификатор придумывает клиент: создание обязано работать в самолёте,
     * как и добавление книги. Хеш характера пересчитывается здесь же: он
     * должен описывать сохранённое состояние, а не историю правок.
     */
    fun save(profile: CompanionProfile) {
        val now = currentTimeMillis()
        val fixed = profile.copy(
            createdAt = profile.createdAt.takeIf { it != 0L } ?: now,
            updatedAt = now,
            deleted = false,
            profileHash = profileHash(profile),
            // Ноль здесь означает локальное изменение. Сервер после обмена
            // вернёт назначенную монотонную ревизию через applyServer.
            rev = 0,
        )
        store.save(STORE_KEY, json.encodeToString(CompanionProfile.serializer(), fixed))
        store.save(DRAFT_KEY, "")
        _state.value = CompanionState(profile = fixed, draft = null)
    }

    /**
     * Черновик только в памяти.
     *
     * Нужен для непрерывных жестов — протяжки ползунка характера. Запись на
     * диск делает [saveDraft] по окончании жеста: она сериализует профиль
     * целиком и делает fsync, и на каждом кадре это стоит заметного подвисания.
     * Потерять при внезапном закрытии можно только незавершённое движение
     * пальца.
     */
    fun holdDraft(draft: CompanionProfile) {
        _state.value = _state.value.copy(draft = draft)
    }

    /** Обновляет черновик на диске, не трогая сохранённый профиль. */
    fun saveDraft(draft: CompanionProfile?) {
        if (draft == null) {
            store.save(DRAFT_KEY, "")
            _state.value = _state.value.copy(draft = null)
            return
        }
        store.save(DRAFT_KEY, json.encodeToString(CompanionProfile.serializer(), draft))
        _state.value = _state.value.copy(draft = draft)
    }

    /**
     * Помечает компаньона удалённым и показывает это сразу.
     *
     * Профиль переезжает в [CompanionState.tombstone]: запись нужна синхронизации,
     * чтобы донести удаление до сервера, и не должна ожить, если сервер ответит
     * позже сохранённой ревизии.
     */
    fun delete(): CompanionProfile? {
        val current = _state.value.profile ?: return null
        val tombstone = current.copy(deleted = true, updatedAt = currentTimeMillis())
        store.save(STORE_KEY, json.encodeToString(CompanionProfile.serializer(), tombstone))
        store.save(DRAFT_KEY, "")
        _state.value = CompanionState(tombstone = tombstone, draft = null)
        return tombstone
    }

    /** Записывает сохранённый phrase pack в профиль. */
    fun attachPhrasePack(pack: CompanionPhrasePack) {
        val profile = _state.value.profile ?: return
        save(profile.copy(phrasePack = pack))
    }

    /** Заменяет phrase pack и локальную ревизию без изменения остальных полей. */
    fun replacePack(pack: CompanionPhrasePack, rev: Long) {
        val profile = _state.value.profile ?: return
        val fixed = profile.copy(phrasePack = pack, rev = rev)
        store.save(STORE_KEY, json.encodeToString(CompanionProfile.serializer(), fixed))
        _state.value = _state.value.copy(profile = fixed)
    }

    /**
     * Применяет профиль с сервера.
     *
     * Серверная ревизия выигрывает у местной: она монотонная и её назначает
     * сервер. Черновик не трогается — он местный, и серверу о нём неизвестно.
     */
    fun applyServer(remote: CompanionProfile?, sent: CompanionProfile? = null) {
        if (remote == null) return
        val state = _state.value
        // Tombstone гаснет только в ответ на отправку именно этого удаления.
        // Иначе ответ старого запроса мог бы потерять удаление, сделанное пока
        // сеть ещё отвечала.
        if (state.tombstone != null) {
            val acknowledged = sent == state.tombstone && sent.deleted && remote.deleted && remote.rev >= sent.rev
            if (acknowledged) {
                store.save(STORE_KEY, "")
                _state.value = CompanionState(profile = null, draft = state.draft)
            }
            return
        }
        val local = state.profile
        // Ноль означает локальную правку. Серверный ответ может заменить её
        // только если эта точная версия входила в запрос.
        if (local?.rev == 0L && local != sent) return
        if (local != null && local.rev >= remote.rev) return
        if (remote.deleted) return
        store.save(STORE_KEY, json.encodeToString(CompanionProfile.serializer(), remote))
        _state.value = CompanionState(profile = remote, draft = state.draft)
    }

    fun clearTombstone() {
        val state = _state.value
        if (state.tombstone != null) {
            store.save(STORE_KEY, "")
            _state.value = state.copy(tombstone = null)
        }
    }

    private companion object {
        const val STORE_KEY = "companion_profile"
        const val DRAFT_KEY = "companion_draft"
    }
}
