package org.paulg786.jenkinsbranchmonitor.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import org.paulg786.jenkinsbranchmonitor.core.BuildPollerService
import org.paulg786.jenkinsbranchmonitor.core.JenkinsClient
import org.paulg786.jenkinsbranchmonitor.core.JobDiscoveryService
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class SettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private val serverUrlField = JBTextField()
    private val usernameField = JBTextField()
    private val tokenField = JBPasswordField()
    private val idleIntervalField = JBTextField()
    private val runningIntervalField = JBTextField()
    private val trackPushesCheckBox = JBCheckBox("Keep recently pushed branches monitored after switching branches")
    private val pushRetentionField = JBTextField()
    private val doneLingerField = JBTextField()
    private val mappingsArea = JBTextArea(6, 60)
    private val watchVcsDisabledCheckBox = JBCheckBox("Also watch Git repositories whose VCS mapping is set to <None>")
    private val watchUnregisteredCheckBox = JBCheckBox("Also watch Git repositories on disk that are not registered in the IDE")
    private val maxLogMbField = JBTextField()
    private val jumpToFirstErrorCheckBox = JBCheckBox("Jump to the first error line when opening a red build's log")
    private val pullGuardCheckBox = JBCheckBox("Warn before Pull / Update Project when the branch's Jenkins build is red")
    private val pullGuardUnstableCheckBox = JBCheckBox("Treat unstable (yellow) builds as red")

    override fun getDisplayName(): String = "Branch Monitor for Jenkins"

    override fun createComponent(): JComponent {
        val testButton = JButton("Test Connection")
        testButton.addActionListener { testConnection() }
        mappingsArea.lineWrap = false
        tokenField.toolTipText = "Leave empty to keep the stored token"
        val built = FormBuilder.createFormBuilder()
            .addLabeledComponent("Server URL:", serverUrlField)
            .addLabeledComponent("Username:", usernameField)
            .addLabeledComponent("API token:", tokenField)
            .addComponent(testButton)
            .addLabeledComponent("Poll interval idle (s):", idleIntervalField)
            .addLabeledComponent("Poll interval while building (s):", runningIntervalField)
            .addComponent(trackPushesCheckBox)
            .addLabeledComponent("Keep pushed branches for (min):", pushRetentionField)
            .addLabeledComponent("Keep finished (green) builds visible (min):", doneLingerField)
            .addSeparator()
            .addComponent(watchVcsDisabledCheckBox)
            .addComponent(watchUnregisteredCheckBox)
            .addTooltip("Branch and remote of such repositories are read with the IDE's Git executable — pushes there are not tracked.")
            .addSeparator()
            .addLabeledComponent("Max. log size shown in the IDE (MB):", maxLogMbField)
            .addComponent(jumpToFirstErrorCheckBox)
            .addSeparator()
            .addComponent(pullGuardCheckBox)
            .addComponent(pullGuardUnstableCheckBox)
            .addSeparator()
            .addComponent(
                JBLabel(
                    "<html>Manual job mappings, one per line:<br>" +
                        "<code>&lt;remote-url-regex&gt; =&gt; &lt;job-url-template&gt;</code><br>" +
                        "Placeholders: <code>{repo}</code>, <code>{branch}</code>, <code>{branchEnc}</code> " +
                        "(URL-encoded branch). Lines starting with # are comments.</html>"
                )
            )
            .addComponentFillVertically(JBScrollPane(mappingsArea), 4)
            .panel
        panel = built
        reset()
        return built
    }

    override fun isModified(): Boolean {
        val s = PluginSettings.getInstance()
        return serverUrlField.text.trim().trimEnd('/') != s.serverUrl ||
            usernameField.text.trim() != s.username ||
            idleIntervalField.text.trim() != s.pollIntervalIdleSec.toString() ||
            runningIntervalField.text.trim() != s.pollIntervalRunningSec.toString() ||
            trackPushesCheckBox.isSelected != s.trackRecentPushes ||
            pushRetentionField.text.trim() != s.recentPushRetentionMin.toString() ||
            doneLingerField.text.trim() != s.doneLingerMin.toString() ||
            mappingsArea.text != s.manualMappings ||
            watchVcsDisabledCheckBox.isSelected != s.watchVcsDisabledRoots ||
            watchUnregisteredCheckBox.isSelected != s.watchUnregisteredRoots ||
            maxLogMbField.text.trim() != s.maxLogMb.toString() ||
            jumpToFirstErrorCheckBox.isSelected != s.jumpToFirstError ||
            pullGuardCheckBox.isSelected != s.pullGuardEnabled ||
            pullGuardUnstableCheckBox.isSelected != s.pullGuardIncludeUnstable ||
            String(tokenField.password).isNotEmpty()
    }

    override fun apply() {
        val s = PluginSettings.getInstance()
        s.serverUrl = serverUrlField.text.trim().trimEnd('/')
        s.username = usernameField.text.trim()
        s.pollIntervalIdleSec = idleIntervalField.text.trim().toIntOrNull()?.coerceIn(5, 3600) ?: 60
        s.pollIntervalRunningSec = runningIntervalField.text.trim().toIntOrNull()?.coerceIn(5, 3600) ?: 10
        s.trackRecentPushes = trackPushesCheckBox.isSelected
        s.recentPushRetentionMin = pushRetentionField.text.trim().toIntOrNull()?.coerceIn(1, 24 * 60) ?: 120
        s.doneLingerMin = doneLingerField.text.trim().toIntOrNull()?.coerceIn(0, 24 * 60) ?: 15
        s.manualMappings = mappingsArea.text
        s.watchVcsDisabledRoots = watchVcsDisabledCheckBox.isSelected
        s.watchUnregisteredRoots = watchUnregisteredCheckBox.isSelected
        s.maxLogMb = maxLogMbField.text.trim().toIntOrNull()?.coerceIn(1, 50) ?: 2
        s.jumpToFirstError = jumpToFirstErrorCheckBox.isSelected
        s.pullGuardEnabled = pullGuardCheckBox.isSelected
        s.pullGuardIncludeUnstable = pullGuardUnstableCheckBox.isSelected
        val token = String(tokenField.password)
        if (token.isNotEmpty()) {
            CredentialsStore.setToken(s.serverUrl, token)
            tokenField.text = ""
        }
        JobDiscoveryService.getInstance().invalidateCache()
        ProjectManager.getInstance().openProjects.forEach { project ->
            if (!project.isDisposed) project.getService(BuildPollerService::class.java).refreshNow()
        }
    }

    override fun reset() {
        val s = PluginSettings.getInstance()
        serverUrlField.text = s.serverUrl
        usernameField.text = s.username
        idleIntervalField.text = s.pollIntervalIdleSec.toString()
        runningIntervalField.text = s.pollIntervalRunningSec.toString()
        trackPushesCheckBox.isSelected = s.trackRecentPushes
        pushRetentionField.text = s.recentPushRetentionMin.toString()
        doneLingerField.text = s.doneLingerMin.toString()
        mappingsArea.text = s.manualMappings
        watchVcsDisabledCheckBox.isSelected = s.watchVcsDisabledRoots
        watchUnregisteredCheckBox.isSelected = s.watchUnregisteredRoots
        maxLogMbField.text = s.maxLogMb.toString()
        jumpToFirstErrorCheckBox.isSelected = s.jumpToFirstError
        pullGuardCheckBox.isSelected = s.pullGuardEnabled
        pullGuardUnstableCheckBox.isSelected = s.pullGuardIncludeUnstable
        tokenField.text = ""
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun testConnection() {
        val url = serverUrlField.text.trim().trimEnd('/')
        if (url.isEmpty()) {
            Messages.showErrorDialog("Please enter a server URL first.", "Branch Monitor for Jenkins")
            return
        }
        val user = usernameField.text.trim()
        val token = String(tokenField.password).ifEmpty { CredentialsStore.getToken(url) ?: "" }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { JenkinsClient(url, user, token).testConnection() }
            ApplicationManager.getApplication().invokeLater {
                result.fold(
                    { Messages.showInfoMessage("Connection successful.", "Branch Monitor for Jenkins") },
                    { Messages.showErrorDialog("Connection failed: ${it.message}", "Branch Monitor for Jenkins") }
                )
            }
        }
    }
}
