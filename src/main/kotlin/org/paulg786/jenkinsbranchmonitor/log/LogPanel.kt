package org.paulg786.jenkinsbranchmonitor.log

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.find.EditorSearchSession
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.paulg786.jenkinsbranchmonitor.core.JenkinsClient
import org.paulg786.jenkinsbranchmonitor.model.BranchBuildStatus
import org.paulg786.jenkinsbranchmonitor.model.BuildState
import org.paulg786.jenkinsbranchmonitor.settings.PluginSettings
import org.paulg786.jenkinsbranchmonitor.ui.BubbleIcon
import org.paulg786.jenkinsbranchmonitor.ui.StatusTexts
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Read-only view of one Jenkins build log (GET `logText/progressiveText` only).
 *
 * Uses a regular IntelliJ [ConsoleView] — the same component as Run/Services consoles — so
 * console plugins like Grep Console (filters, highlighting, folding, grep) apply automatically.
 * Running builds are tailed every 2 s; error lines are highlighted and navigable.
 */
class LogPanel(private val project: Project, initialStatus: BranchBuildStatus) : JPanel(BorderLayout()), Disposable {

    var status: BranchBuildStatus = initialStatus
        private set
    val buildUrl: String = initialStatus.build!!.url

    private val console: ConsoleView = createConsole()
    private val errorsConsole: ConsoleView = createConsole()

    private val cards = CardLayout()
    private val cardPanel = JPanel(cards)
    private val headerLabel = JBLabel()
    private val noticeLabel = JBLabel()

    private val generation = AtomicInteger()
    private var pending = StringBuilder()
    private var nextOffset = 0L
    private var errorsOnly = false
    @Volatile
    private var disposed = false

    init {
        cardPanel.add(console.component, CARD_FULL)
        cardPanel.add(errorsConsole.component, CARD_ERRORS)
        installTypeToSearch(console)
        installTypeToSearch(errorsConsole)

        headerLabel.border = JBUI.Borders.empty(4, 6, 2, 6)
        noticeLabel.border = JBUI.Borders.empty(0, 6, 4, 6)
        noticeLabel.isVisible = false
        val header = JPanel(BorderLayout())
        header.add(headerLabel, BorderLayout.NORTH)
        header.add(noticeLabel, BorderLayout.SOUTH)

        val top = JPanel(BorderLayout())
        top.add(createToolbar(), BorderLayout.NORTH)
        top.add(header, BorderLayout.SOUTH)
        add(top, BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)
        reload()
    }

    private fun createConsole(): ConsoleView {
        val builder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
        builder.setViewer(true)
        builder.addFilter(ErrorLineFilter)
        val view = builder.console
        Disposer.register(this, view)
        return view
    }

    /** Called by the poller hook so the header follows the live state. */
    fun update(newStatus: BranchBuildStatus) {
        if (newStatus.build?.url != buildUrl) return
        status = newStatus
        updateHeader(loading = false)
    }

    private fun updateHeader(loading: Boolean) {
        headerLabel.icon = BubbleIcon(status.state)
        headerLabel.text = "${status.branch.displayName}  #${status.build?.number ?: "?"}  ·  " +
            StatusTexts.resultText(status) + if (loading) "  (loading…)" else ""
    }

    private fun showNotice(text: String, error: Boolean = false) {
        noticeLabel.text = text
        noticeLabel.foreground = if (error) JBColor.RED else UIUtil.getContextHelpForeground()
        noticeLabel.isVisible = true
    }

    // ---- loading ----

    fun reload() {
        val gen = generation.incrementAndGet()
        console.clear()
        errorsConsole.clear()
        pending = StringBuilder()
        nextOffset = 0
        noticeLabel.isVisible = false
        updateHeader(loading = true)

        val settings = PluginSettings.getInstance()
        val maxMb = settings.maxLogMb.coerceIn(1, 50)
        AppExecutorUtil.getAppExecutorService().execute {
            val client = JenkinsClient.fromSettings()
            if (client == null) {
                onEdt(gen) {
                    updateHeader(loading = false)
                    showNotice("No Jenkins server configured.", error = true)
                }
                return@execute
            }
            try {
                val chunk = client.logTail(buildUrl, maxMb * 1024L * 1024L)
                onEdt(gen) {
                    updateHeader(loading = false)
                    if (chunk.truncated) {
                        val totalMb = chunk.nextOffset / (1024.0 * 1024.0)
                        showNotice("Showing the last $maxMb MB of %.1f MB — use “Open Full Log in Editor” for more.".format(totalMb))
                    }
                    appendRaw(chunk.text, chunk.moreData)
                    nextOffset = chunk.nextOffset
                    whenFlushed(gen) {
                        if (isRed() && settings.jumpToFirstError) {
                            jumpToError(forward = true, fromStart = true)
                        } else {
                            scrollToEnd()
                        }
                    }
                    if (chunk.moreData) scheduleTail(gen, client)
                }
            } catch (e: Exception) {
                onEdt(gen) {
                    updateHeader(loading = false)
                    showNotice("Could not load log: ${e.message ?: e.javaClass.simpleName}", error = true)
                }
            }
        }
    }

    private fun scheduleTail(gen: Int, client: JenkinsClient) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (disposed || gen != generation.get()) return@schedule
            try {
                val chunk = client.progressiveText(buildUrl, nextOffset)
                onEdt(gen) {
                    appendRaw(chunk.text, chunk.moreData)
                    nextOffset = chunk.nextOffset
                    if (chunk.moreData) scheduleTail(gen, client) else showNotice("Build finished — log complete.")
                }
            } catch (e: Exception) {
                // transient network problem: keep trying while the panel is open
                if (!disposed && gen == generation.get()) scheduleTail(gen, client)
            }
        }, 2, TimeUnit.SECONDS)
    }

    /** Prints complete lines only; an incomplete last line waits for the next chunk (unless the log is finished). */
    private fun appendRaw(raw: String, moreData: Boolean) {
        pending.append(raw)
        val text: String
        if (moreData) {
            val idx = pending.lastIndexOf("\n")
            if (idx < 0) return
            text = pending.substring(0, idx + 1)
            pending = StringBuilder(pending.substring(idx + 1))
        } else {
            text = pending.toString()
            pending = StringBuilder()
        }
        val clean = JenkinsClient.cleanLog(text)
        if (clean.isEmpty()) return
        console.print(clean, ConsoleViewContentType.NORMAL_OUTPUT)
        if (errorsOnly) whenFlushed(generation.get()) { rebuildErrorsView() }
    }

    // ---- error navigation ----

    private fun activeEditor(): Editor? = (if (errorsOnly) errorsConsole else console).editorOrNull()

    private fun errorLines(editor: Editor): List<Int> {
        val doc = editor.document
        val chars = doc.immutableCharSequence
        return (0 until doc.lineCount).filter { line ->
            val start = doc.getLineStartOffset(line)
            val end = doc.getLineEndOffset(line)
            end > start && ERROR_PATTERN.containsMatchIn(chars.subSequence(start, end))
        }
    }

    private fun jumpToError(forward: Boolean, fromStart: Boolean = false) {
        val ed = activeEditor() ?: return
        val lines = errorLines(ed)
        if (lines.isEmpty()) {
            if (fromStart) scrollToEnd()
            return
        }
        val caretLine = ed.caretModel.logicalPosition.line
        val target = when {
            fromStart -> lines.first()
            forward -> lines.firstOrNull { it > caretLine } ?: lines.first()
            else -> lines.lastOrNull { it < caretLine } ?: lines.last()
        }
        // Moving the caret away from the end also stops the console's auto-scroll.
        ed.caretModel.moveToOffset(ed.document.getLineStartOffset(target))
        ed.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    private fun scrollToEnd() {
        val view = if (errorsOnly) errorsConsole else console
        (view as? ConsoleViewImpl)?.scrollToEnd() ?: view.requestScrollingToEnd()
    }

    private fun rebuildErrorsView() {
        val ed = console.editorOrNull() ?: return
        val doc = ed.document
        val lines = sortedSetOf<Int>()
        errorLines(ed).forEach { line ->
            for (l in (line - CONTEXT_LINES)..(line + CONTEXT_LINES)) {
                if (l >= 0 && l < doc.lineCount) lines.add(l)
            }
        }
        val sb = StringBuilder()
        if (lines.isEmpty()) {
            sb.append("No error lines detected.\n")
        } else {
            var previous = -2
            val chars = doc.immutableCharSequence
            for (l in lines) {
                if (previous >= 0 && l != previous + 1) sb.append("…\n")
                sb.append(chars, doc.getLineStartOffset(l), doc.getLineEndOffset(l)).append('\n')
                previous = l
            }
        }
        errorsConsole.clear()
        errorsConsole.print(sb.toString(), ConsoleViewContentType.NORMAL_OUTPUT)
    }

    // ---- search ----

    /**
     * Speed-search style: typing a printable character in the (read-only) log opens IntelliJ's regular
     * find bar with that character, so you can simply start typing like in any other IDE list/tree.
     */
    private fun installTypeToSearch(view: ConsoleView) {
        val editor = view.editorOrNull() ?: return
        val component = editor.contentComponent
        val typeToSearch = object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) {
                val ch = e.keyChar
                if (ch == KeyEvent.CHAR_UNDEFINED || Character.isISOControl(ch)) return
                if (e.isControlDown || e.isAltDown || e.isMetaDown) return
                e.consume()
                startSearch(editor, ch.toString())
            }
        }
        // Run before the editor's own key handling, so the read-only console does not react to the keystroke.
        val existing = component.keyListeners
        existing.forEach { component.removeKeyListener(it) }
        component.addKeyListener(typeToSearch)
        existing.forEach { component.addKeyListener(it) }
    }

    private fun startSearch(editor: Editor, appendText: String? = null) {
        val session = EditorSearchSession.get(editor) ?: EditorSearchSession.start(editor, project).also {
            if (appendText != null) it.setTextInField("")
        }
        if (appendText != null) session.setTextInField(session.textInField + appendText)
        val field = session.component.searchTextComponent
        field.requestFocusInWindow()
        field.caretPosition = field.document.length
    }

    // ---- toolbar ----

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup()
        group.add(object : DumbAwareAction("Find in Log", "Search the log (or just start typing)", AllIcons.Actions.Find) {
            override fun actionPerformed(e: AnActionEvent) {
                activeEditor()?.let { startSearch(it) }
            }
        })
        group.addSeparator()
        group.add(object : DumbAwareAction("Previous Error", "Jump to the previous error line", AllIcons.Actions.PreviousOccurence) {
            override fun actionPerformed(e: AnActionEvent) = jumpToError(forward = false)
        })
        group.add(object : DumbAwareAction("Next Error", "Jump to the next error line", AllIcons.Actions.NextOccurence) {
            override fun actionPerformed(e: AnActionEvent) = jumpToError(forward = true)
        })
        group.add(object : DumbAwareAction("Scroll to End", "Jump to the end and follow new output", AllIcons.RunConfigurations.Scroll_down) {
            override fun actionPerformed(e: AnActionEvent) = scrollToEnd()
        })
        group.add(object : ToggleAction("Errors Only", "Show only error lines (±$CONTEXT_LINES lines of context)", AllIcons.General.Filter) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = errorsOnly
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                errorsOnly = state
                if (state) whenFlushed(generation.get()) { rebuildErrorsView() }
                cards.show(cardPanel, if (state) CARD_ERRORS else CARD_FULL)
            }
        })
        group.addSeparator()
        group.add(object : DumbAwareAction("Reload", "Load the log again", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = reload()
        })
        group.add(object : DumbAwareAction("Open in Browser", "Open the Jenkins console page", AllIcons.General.Web) {
            override fun actionPerformed(e: AnActionEvent) = BrowserUtil.browse(buildUrl.trimEnd('/') + "/console")
        })
        group.add(object : DumbAwareAction(
            "Open Full Log in Editor", "Download the log (last $FULL_LOG_MB MB at most) into a read-only editor tab",
            AllIcons.Actions.MenuOpen
        ) {
            override fun actionPerformed(e: AnActionEvent) = openFullLog()
        })
        val toolbar = ActionManager.getInstance().createActionToolbar("JenkinsBranchMonitorLog", group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    private fun openFullLog() {
        val fileName = "${status.branch.repoName}-${status.branch.branch ?: "detached"}-${status.build?.number ?: 0}"
            .replace(Regex("[^A-Za-z0-9._-]"), "_") + ".log"
        object : Task.Backgroundable(project, "Downloading Jenkins log", true) {
            private var text: String? = null
            override fun run(indicator: ProgressIndicator) {
                val client = JenkinsClient.fromSettings() ?: return
                text = JenkinsClient.cleanLog(client.logTail(buildUrl, FULL_LOG_MB * 1024L * 1024L).text)
            }

            override fun onSuccess() {
                val content = text ?: return
                val file = LightVirtualFile(fileName, PlainTextFileType.INSTANCE, content)
                file.isWritable = false
                FileEditorManager.getInstance(project).openFile(file, true)
            }
        }.queue()
    }

    // ---- helpers ----

    private fun ConsoleView.editorOrNull(): Editor? = (this as? ConsoleViewImpl)?.editor

    /** Runs [block] once the console has rendered all printed text (console output is buffered). */
    private fun whenFlushed(gen: Int, block: () -> Unit) {
        val impl = console as? ConsoleViewImpl
        if (impl == null) {
            onEdt(gen, block)
            return
        }
        impl.performWhenNoDeferredOutput { onEdt(gen, block) }
    }

    private fun isRed() = status.state == BuildState.FAILURE || status.state == BuildState.UNSTABLE

    private fun onEdt(gen: Int, block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(
            { if (!disposed && gen == generation.get()) block() },
            Condition<Any?> { disposed || project.isDisposed }
        )
    }

    override fun dispose() {
        disposed = true
        generation.incrementAndGet()
    }

    /** Colors error lines red (with error stripe mark); other console filters (e.g. Grep Console) still apply. */
    private object ErrorLineFilter : Filter {
        override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
            if (!ERROR_PATTERN.containsMatchIn(line)) return null
            val start = entireLength - line.length
            val end = start + line.trimEnd('\n', '\r').length
            if (end <= start) return null
            return Filter.Result(start, end, null, ERROR_ATTRIBUTES)
        }
    }

    companion object {
        private const val CARD_FULL = "full"
        private const val CARD_ERRORS = "errors"
        private const val CONTEXT_LINES = 2
        private const val FULL_LOG_MB = 30

        val ERROR_PATTERN = Regex(
            """\[ERROR]|BUILD FAILURE|\bFAILED\b|\bFAILURE\b|\w+(Exception|Error)\b|^ERROR:|Tests run:.*(Failures|Errors): [1-9]"""
        )
        private val ERROR_COLOR = JBColor(0xC62828, 0xFF6B68)
        private val ERROR_ATTRIBUTES = TextAttributes(ERROR_COLOR, null, null, null, Font.PLAIN).apply {
            errorStripeColor = ERROR_COLOR
        }
    }
}
