package com.sevenk.core.data.repository

import com.sevenk.core.data.model.RepoModule

interface ModuleRepoRepository {
    suspend fun fetchModules(): Result<List<RepoModule>>
}
