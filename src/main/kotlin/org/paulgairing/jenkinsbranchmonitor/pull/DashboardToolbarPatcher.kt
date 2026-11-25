package org.paulgairing.jenkinsbranchmonitor.pull

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Component
import java.awt.Container
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.Timer

/**
 * The Git branches tree ("Update Selected" button in the Git tool window) creates its update action with `new`
 * instead of looking it up in the ActionManager, so replacing the registered action does not reach it.
 * This patcher finds the branches toolbar while the VCS tool window is visible and swaps that action
 * for a [PullGuardAction] inside the toolbar's own action group. Restored on dispose.
 */
@Service(Service.Level.PROJECT)
class DashboardToolbarPatcher(private val project: Project) : Disposable {

    private val patched: MutableMap<DefaultActionGroup, MutableList<Pair<AnAction, AnAction>>> =
        Collections.synchronizedMap(WeakHashMap())
    private var timer: Timer? = null

    fun start() {
        if (timer != null) return
        timer = Timer(SCAN_INTERVAL_MS) { scan() }.apply {
            isRepeats = true
            start()
        }
    }

    private fun scan() {
        if (project.isDisposed) return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.VCS) ?: return
        if (!toolWindow.isVisible) return
        val roots = toolWindow.contentManager.contents.mapNotNull { it.component } + toolWindow.component
        roots.forEach { visit(it) }
    }

    private fun visit(c: Component) {
        if (c is ActionToolbar) {
            (c.actionGroup as? DefaultActionGroup)?.let { patchGroup(it) }
        }
        if (c is Container) c.components.forEach { visit(it) }
    }

    private fun patchGroup(group: DefaultActionGroup) {
        for (child in group.childActionsOrStubs) {
            when {
                child is PullGuardAction -> Unit
                child.javaClass.name == DASHBOARD_UPDATE_CLASS -> {
                    val wrapper = PullGuardAction(PullGuardService.Kind.UPDATE_SELECTED, child)
                    if (runCatching { group.replaceAction(child, wrapper) }.getOrDefault(false)) {
                        patched.getOrPut(group) { mutableListOf() }.add(wrapper to child)
                        thisLogger().info("Pull guard installed in Git branches toolbar")
                    }
                }
                child is DefaultActionGroup -> patchGroup(child)
                child is ActionGroup -> Unit
            }
        }
    }

    override fun dispose() {
        timer?.stop()
        timer = null
        synchronized(patched) {
            patched.forEach { (group, pairs) ->
                pairs.forEach { (wrapper, original) -> runCatching { group.replaceAction(wrapper, original) } }
            }
            patched.clear()
        }
    }

    companion object {
        private const val SCAN_INTERVAL_MS = 2000
        const val DASHBOARD_UPDATE_CLASS =
            "git4idea.ui.branch.dashboard.BranchesDashboardActions\$UpdateSelectedBranchAction"
    }
}
