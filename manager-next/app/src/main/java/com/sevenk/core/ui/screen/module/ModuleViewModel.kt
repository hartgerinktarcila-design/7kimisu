package com.sevenk.core.ui.screen.module

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sevenk.core.data.model.Module
import com.sevenk.core.ui.util.listModules
import com.sevenk.core.ui.util.toggleModule as toggleModuleUtil
import com.sevenk.core.ui.util.undoUninstallModule as undoUninstallModuleUtil
import com.sevenk.core.ui.util.uninstallModule as uninstallModuleUtil
import org.json.JSONArray

data class ModuleUiState(
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val modules: List<Module> = emptyList(),
    /** 待确认的卸载请求（弹确认框用） */
    val pendingUninstall: Module? = null,
)

class ModuleViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ModuleUiState())
    val uiState: StateFlow<ModuleUiState> = _uiState.asStateFlow()

    private var fetchJob: Job? = null

    fun fetchModuleList() {
        if (fetchJob?.isActive == true) return
        fetchJob = viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            runCatching {
                val modules = withContext(Dispatchers.IO) { loadModules() }
                _uiState.update { it.copy(modules = modules) }
            }.onFailure { e ->
                Log.e(TAG, "fetchModuleList failed", e)
            }
            _uiState.update { it.copy(isRefreshing = false, hasLoaded = true) }
        }
    }

    private fun loadModules(): List<Module> {
        val result = listModules()
        val array = JSONArray(result)
        return (0 until array.length())
            .asSequence()
            .map { array.getJSONObject(it) }
            .map { obj ->
                Module(
                    id = obj.getString("id"),
                    name = obj.optString("name"),
                    author = obj.optString("author", "Unknown"),
                    version = obj.optString("version", "Unknown"),
                    versionCode = obj.optInt("versionCode", 0),
                    description = obj.optString("description"),
                    enabled = obj.getBoolean("enabled"),
                    update = obj.optBoolean("update"),
                    remove = obj.getBoolean("remove"),
                    updateJson = obj.optString("updateJson"),
                    hasWebUi = obj.optBoolean("web"),
                    hasActionScript = obj.optBoolean("action"),
                    metamodule = (obj.optInt("metamodule") != 0) || obj.optBoolean("metamodule"),
                )
            }.toList()
    }

    fun toggleModule(module: Module, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                toggleModuleUtil(module.id, !module.enabled)
            }
            onResult(success)
            fetchModuleList()
        }
    }

    fun requestUninstall(module: Module) {
        _uiState.update { it.copy(pendingUninstall = module) }
    }

    fun dismissUninstall() {
        _uiState.update { it.copy(pendingUninstall = null) }
    }

    fun confirmUninstall(onResult: (Boolean) -> Unit) {
        val module = _uiState.value.pendingUninstall ?: return
        _uiState.update { it.copy(pendingUninstall = null) }
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                uninstallModuleUtil(module.id)
            }
            onResult(success)
            fetchModuleList()
        }
    }

    fun undoUninstall(module: Module, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                undoUninstallModuleUtil(module.id)
            }
            onResult(success)
            fetchModuleList()
        }
    }

    companion object {
        private const val TAG = "ModuleViewModel"
    }
}
