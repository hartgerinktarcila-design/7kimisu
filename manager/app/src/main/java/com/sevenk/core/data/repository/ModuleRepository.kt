package com.sevenk.core.data.repository

import com.sevenk.core.data.model.Module
import com.sevenk.core.data.model.ModuleUpdateInfo

interface ModuleRepository {
    suspend fun getModules(): Result<List<Module>>
    suspend fun checkUpdate(module: Module): Result<ModuleUpdateInfo>
}
