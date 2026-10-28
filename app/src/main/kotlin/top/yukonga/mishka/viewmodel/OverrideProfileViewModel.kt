package top.yukonga.mishka.viewmodel

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.data.repository.ConfigValidationException
import top.yukonga.mishka.data.repository.ImportError
import top.yukonga.mishka.data.repository.OverrideInputError
import top.yukonga.mishka.domain.model.OverrideFormat
import top.yukonga.mishka.domain.model.OverrideProfile
import top.yukonga.mishka.domain.model.orderedOverrideIds
import top.yukonga.mishka.domain.repository.OverrideProfileRepository
import top.yukonga.mishka.domain.repository.SubscriptionRepository
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.util.describe

@Immutable
data class OverrideProfileUiState(
    val profiles: ImmutableList<OverrideProfile> = persistentListOf(),
    val isLoading: Boolean = false,
    val error: String = "",
)

class OverrideProfileViewModel(
    private val repository: OverrideProfileRepository,
    private val subscriptions: SubscriptionRepository,
    private val serviceController: ProxyServiceController,
    private val storage: PlatformStorage,
    private val context: Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OverrideProfileUiState(profiles = repository.profiles.value))
    val uiState: StateFlow<OverrideProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { repository.profiles.collect { list -> _uiState.update { it.copy(profiles = list) } } }
    }

    fun clearError() = _uiState.update { it.copy(error = "") }
    suspend fun readContent(id: String): String = repository.readContent(id)

    fun addRemote(name: String, url: String, format: OverrideFormat, onComplete: () -> Unit) =
        launchOperation(R.string.error_import_failed) { repository.addRemote(name, url, format); onComplete() }

    fun addLocal(name: String, fileName: String, format: OverrideFormat, content: String, onComplete: () -> Unit) =
        launchOperation(R.string.error_import_failed) { repository.addLocal(name, fileName, format, content); onComplete() }

    fun addBlank(name: String, format: OverrideFormat, onComplete: () -> Unit) =
        launchOperation(R.string.error_save_failed) { repository.addBlank(name, format); onComplete() }

    fun edit(profile: OverrideProfile, name: String, url: String, format: OverrideFormat, onComplete: () -> Unit) =
        launchOperation(R.string.error_save_failed) {
            repository.edit(profile.id, name, url, format)
            if (format != profile.format) restartIfUsed(profile.id)
            onComplete()
        }

    fun saveContent(id: String, content: String, onComplete: () -> Unit) =
        launchOperation(R.string.error_save_failed) { repository.saveContent(id, content); restartIfUsed(id); onComplete() }

    fun update(id: String) = launchOperation(R.string.error_update_failed) { repository.update(id); restartIfUsed(id) }

    fun updateAll() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            var succeeded = 0
            val failures = mutableListOf<String>()
            var restart = false
            try {
                _uiState.value.profiles.filter { it.isRemote }.forEach { profile ->
                    try {
                        repository.update(profile.id)
                        succeeded++
                        if (repository.isUsedByCurrent(profile.id)) restart = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        failures += "${profile.name}: ${localizedMessage(e)}"
                    }
                }
                if (restart) restartCurrent()
                showToast(context.getString(R.string.override_update_all_result, succeeded, failures.size))
                _uiState.update { it.copy(error = failures.joinToString("\n")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update { it.copy(error = context.getString(R.string.error_update_failed, localizedMessage(e))) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun delete(id: String) = launchOperation(R.string.error_save_failed) {
        val used = repository.isUsedByCurrent(id)
        repository.delete(id)
        if (storage.getString(StorageKeys.RULES_OVERRIDE_ID, "") == id) {
            storage.putString(StorageKeys.RULES_OVERRIDE_ID, "")
        }
        if (used) restartCurrent()
    }

    fun setSelection(subscriptionId: String, overrideIds: List<String>, sortPreference: List<String>, onComplete: () -> Unit) =
        launchOperation(R.string.error_save_failed) {
            val subscription = subscriptions.subscriptions.value.find { it.id == subscriptionId }
            repository.setSelection(subscriptionId, overrideIds, sortPreference)
            val changed = subscription?.orderedOverrideIds != overrideIds
            if (changed && subscriptions.getActiveId() == subscriptionId) serviceController.restartWhenReady(subscriptionId)
            onComplete()
        }

    /**
     * 规则设置页覆写的锚点解析：优先按 [StorageKeys.RULES_OVERRIDE_ID] 持久化的 id 找——
     * 覆写名是随界面语言变的本地化字符串，按名匹配在切换语言后失效。id 未命中时按当前
     * 语言的 [name] 兜底（兼容旧版本按名创建的覆写），命中后回写 id 完成迁移。
     */
    fun resolveRulesOverride(name: String): OverrideProfile? {
        val profiles = repository.profiles.value
        val anchoredId = storage.getString(StorageKeys.RULES_OVERRIDE_ID, "")
        profiles.find { it.id == anchoredId }?.let { return it }
        val byName = profiles.find { it.name == name && it.format == OverrideFormat.Yaml }
        if (byName != null) storage.putString(StorageKeys.RULES_OVERRIDE_ID, byName.id)
        return byName
    }

    /**
     * 规则设置页保存入口：确保 [resolveRulesOverride] 锚定的本地 YAML 覆写存在并写入
     * [content]，不存在则以 [name] 新建并记下其 id 为锚点；随后确保它关联到
     * [subscriptionId]（追加到末尾，让它的 rules 最后生效）。
     * 内容或关联发生变化、且是当前激活订阅时，触发按需重启使新规则生效。
     */
    fun saveRulesOverride(
        subscriptionId: String,
        name: String,
        content: String,
        onComplete: () -> Unit = {},
    ) = launchOperation(R.string.error_save_failed) {
        val existing = resolveRulesOverride(name)
        var contentChanged = false
        val id = if (existing != null) {
            val old = runCatching { repository.readContent(existing.id) }.getOrNull()
            if (old != content) {
                repository.saveContent(existing.id, content)
                contentChanged = true
            }
            existing.id
        } else {
            contentChanged = true
            val created = repository.addLocal(name, "rules.yaml", OverrideFormat.Yaml, content)
            storage.putString(StorageKeys.RULES_OVERRIDE_ID, created.id)
            created.id
        }
        val subscription = subscriptions.subscriptions.value.find { it.id == subscriptionId }
        var selectionChanged = false
        if (subscription != null && id !in subscription.overrideIds) {
            val newIds = subscription.orderedOverrideIds + id
            repository.setSelection(subscriptionId, newIds, newIds)
            selectionChanged = true
        }
        if ((contentChanged || selectionChanged) && subscriptions.getActiveId() == subscriptionId) {
            serviceController.restartWhenReady(subscriptionId)
        }
        onComplete()
    }

    private suspend fun restartIfUsed(id: String) { if (repository.isUsedByCurrent(id)) restartCurrent() }
    private fun restartCurrent() { subscriptions.getActiveId()?.let(serviceController::restartWhenReady) }

    private fun launchOperation(@StringRes errorKey: Int, block: suspend () -> Unit) {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = "") }
        viewModelScope.launch {
            val error = try {
                block(); ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e is ConfigValidationException) context.getString(R.string.error_validation_failed, e.describe())
                else context.getString(errorKey, localizedMessage(e))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
            _uiState.update { it.copy(error = error) }
        }
    }

    private fun localizedMessage(e: Throwable): String = when (e) {
        is OverrideInputError.NameRequired -> context.getString(R.string.override_error_name_required)
        is OverrideInputError.InvalidUrl -> context.getString(R.string.override_error_invalid_url)
        is ImportError.HttpStatus -> context.getString(R.string.override_error_http_status, e.code)
        else -> e.describe()
    }
}
