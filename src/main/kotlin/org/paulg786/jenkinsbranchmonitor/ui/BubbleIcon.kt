package org.paulg786.jenkinsbranchmonitor.ui

import com.intellij.ui.JBColor
import org.paulg786.jenkinsbranchmonitor.model.BuildState
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/** The colored bubble: blue = building, green = success, red = failed, yellow = unstable, gray = everything else. */
class BubbleIcon(private val state: BuildState, private val size: Int = 12) : Icon {

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = colorOf(state)
            g2.fillOval(x, y, size, size)
        } finally {
            g2.dispose()
        }
    }

    override fun getIconWidth(): Int = size

    override fun getIconHeight(): Int = size

    companion object {
        fun colorOf(state: BuildState): JBColor = when (state) {
            BuildState.RUNNING -> JBColor(0x3592C4, 0x3592C4)
            BuildState.SUCCESS -> JBColor(0x59A869, 0x499C54)
            BuildState.FAILURE -> JBColor(0xDB5860, 0xC75450)
            BuildState.UNSTABLE -> JBColor(0xEDA200, 0xF0A732)
            else -> JBColor(0x9AA7B0, 0x6E6E6E)
        }
    }
}
