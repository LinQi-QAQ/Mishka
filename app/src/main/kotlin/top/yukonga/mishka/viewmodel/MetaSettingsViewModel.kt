package top.yukonga.mishka.viewmodel

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.mishka.R
import top.yukonga.mishka.data.GeoDataUpdater
import top.yukonga.mishka.data.repository.OverrideJsonStore
import top.yukonga.mishka.domain.model.ConfigurationOverride
import top.yukonga.mishka.domain.model.SnifferOverride
import top.yukonga.mishka.domain.model.TlsFragmentOverride
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.service.ProfileFileOps
import top.yukonga.mishka.util.describe
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MetaSettingsScreen 的 ViewModel：统一延迟 / Geodata / TCP 并发 / 进程查找模式 / 嗅探器，
 * 以及 Geo 数据的应用内更新。
 */
class MetaSettingsViewModel(
    private val store: OverrideJsonStore,
    private val geoDataUpdater: GeoDataUpdater,
    private val context: Context,
) : ViewModel() {

    val state: StateFlow<MetaSettingsUiState> = store.state
        .map { it.toMetaUi() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = store.state.value.toMetaUi(),
        )

    private val _geoState = MutableStateFlow(GeoDataUiState())
    val geoState: StateFlow<GeoDataUiState> = _geoState.asStateFlow()

    init { refreshGeoInfo() }

    fun update(transform: (ConfigurationOverride) -> ConfigurationOverride) {
        store.update(transform)
    }

    fun updateTlsFragment(transform: (TlsFragmentOverride) -> TlsFragmentOverride) {
        store.update { state ->
            val current = state.tlsFragment ?: TlsFragmentOverride()
            val next = transform(current)
            val allNull = next == TlsFragmentOverride()
            state.copy(tlsFragment = if (allNull) null else next)
        }
    }

    fun updateSniffer(transform: (SnifferOverride) -> SnifferOverride) {
        store.update { state ->
            val current = state.sniffer ?: SnifferOverride()
            val next = transform(current)
            val allNull = next == SnifferOverride()
            state.copy(sniffer = if (allNull) null else next)
        }
    }

    fun refreshGeoInfo() {
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) { readGeoSummary() }
            _geoState.update { it.copy(summary = summary) }
        }
    }

    /** 现有 Geo 文件里最新一份的 mtime 即数据新旧（下载更新与预制提取都会刷新它）。 */
    private fun readGeoSummary(): String {
        val dir = ProfileFileOps.getGeodataDir(context)
        val files = GeoDataUpdater.SOURCES.map { File(dir, it.second) }.filter { it.isFile }
        if (files.isEmpty()) return context.getString(R.string.meta_geo_summary_missing)
        val latest = files.maxOf { it.lastModified() }
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(latest))
        return context.getString(R.string.meta_geo_summary, date)
    }

    fun updateGeoData() {
        if (_geoState.value.isUpdating) return
        _geoState.update { it.copy(isUpdating = true) }
        viewModelScope.launch {
            try {
                geoDataUpdater.update()
                showToast(context.getString(R.string.meta_geo_update_done))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showToast(context.getString(R.string.meta_geo_update_failed, e.describe()))
            } finally {
                _geoState.update { it.copy(isUpdating = false) }
            }
            refreshGeoInfo()
        }
    }
}

@Immutable
data class MetaSettingsUiState(
    val unifiedDelay: Boolean? = null,
    val geodataMode: Boolean? = null,
    val tcpConcurrent: Boolean? = null,
    val findProcessMode: String? = null,
    val sniffer: SnifferOverride? = null,
    val tlsFragment: TlsFragmentOverride? = null,
)

@Immutable
data class GeoDataUiState(
    val isUpdating: Boolean = false,
    val summary: String = "",
)

@Immutable
data class GeoDataUiState(
    val isUpdating: Boolean = false,
    val summary: String = "",
)

private fun ConfigurationOverride.toMetaUi() = MetaSettingsUiState(
    unifiedDelay = unifiedDelay,
    geodataMode = geodataMode,
    tcpConcurrent = tcpConcurrent,
    findProcessMode = findProcessMode,
    sniffer = sniffer,
    tlsFragment = tlsFragment,
)
