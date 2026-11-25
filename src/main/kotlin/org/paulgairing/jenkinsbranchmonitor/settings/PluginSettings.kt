package org.paulgairing.jenkinsbranchmonitor.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@Service
@State(name = "JenkinsBranchMonitorSettings", storages = [Storage("jenkinsBranchMonitor.xml")])
class PluginSettings : PersistentStateComponent<PluginSettings> {

    var serverUrl: String = ""
    var username: String = ""
    var pollIntervalIdleSec: Int = 60
    var pollIntervalRunningSec: Int = 10

    /** Keep branches monitored after they were pushed, even when another branch gets checked out. */
    var trackRecentPushes: Boolean = true
    var recentPushRetentionMin: Int = 120

    /** How long a finished (green) pushed build stays visible before its row disappears. */
    var doneLingerMin: Int = 15

    /** Also watch repositories whose directory mapping has VCS `<None>`. */
    var watchVcsDisabledRoots: Boolean = false

    /** Also watch Git roots on disk that are not registered in the IDE. */
    var watchUnregisteredRoots: Boolean = false

    /** Maximum log size shown in the log tab (tail), in MB. */
    var maxLogMb: Int = 2

    /** Scroll to the first error line when opening the log of a red build. */
    var jumpToFirstError: Boolean = true

    /** Ask before Pull / Update Project when the Jenkins build of the branch is red. */
    var pullGuardEnabled: Boolean = true
    var pullGuardIncludeUnstable: Boolean = true

    /** Comma-separated names of table columns the user has hidden. */
    var hiddenColumns: String = ""

    /** One mapping per line: `<remote-url-regex> => <job-url-template>` (placeholders {repo}, {branch}, {branchEnc}). */
    var manualMappings: String = ""

    override fun getState(): PluginSettings = this

    override fun loadState(state: PluginSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): PluginSettings =
            ApplicationManager.getApplication().getService(PluginSettings::class.java)
    }
}
