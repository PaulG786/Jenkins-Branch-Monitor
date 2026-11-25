package org.paulg786.jenkinsbranchmonitor.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/** Per-project repository settings, stored in the (not shared) workspace file. */
@Service(Service.Level.PROJECT)
@State(name = "JenkinsBranchMonitorRepositories", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ProjectRepoSettings : PersistentStateComponent<ProjectRepoSettings> {

    /** Repository names (lower case) that are not monitored in this project. */
    var excludedRepos: MutableList<String> = mutableListOf()

    fun isExcluded(repoName: String): Boolean = repoName.lowercase() in excludedRepos

    override fun getState(): ProjectRepoSettings = this

    override fun loadState(state: ProjectRepoSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(project: Project): ProjectRepoSettings = project.getService(ProjectRepoSettings::class.java)
    }
}
