package org.paulgairing.jenkinsbranchmonitor.core

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

/**
 * Per-workspace statistics about MY pushes and their build outcomes.
 * Counts only builds attributed to own pushes — never other people's builds.
 */
@Service(Service.Level.PROJECT)
@State(name = "JenkinsBranchMonitorStats", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PushStatsService : PersistentStateComponent<PushStatsService.Stats> {

    class Stats {
        var pushes: Int = 0
        var green: Int = 0
        var red: Int = 0
    }

    private var stats = Stats()

    override fun getState(): Stats = stats
    override fun loadState(state: Stats) {
        stats = state
    }

    fun pushRecorded() {
        stats.pushes++
    }

    fun greenRecorded() {
        stats.green++
    }

    fun redRecorded() {
        stats.red++
    }

    fun reset() {
        stats = Stats()
    }

    companion object {
        fun getInstance(project: Project): PushStatsService = project.getService(PushStatsService::class.java)
    }
}
