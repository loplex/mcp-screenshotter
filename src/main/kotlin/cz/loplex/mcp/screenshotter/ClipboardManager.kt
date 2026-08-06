package cz.loplex.mcp.screenshotter

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException

class ClipboardManager {
    
    private val clipboard by lazy {
        Toolkit.getDefaultToolkit().systemClipboard
    }

    /**
     * Reads the current text from the clipboard.
     * Returns null if the clipboard is empty or does not contain text.
     */
    fun getText(): String? {
        return try {
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                clipboard.getData(DataFlavor.stringFlavor) as String
            } else {
                null
            }
        } catch (e: UnsupportedFlavorException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Sets the clipboard content to the provided text.
     */
    fun setText(text: String) {
        val selection = StringSelection(text)
        clipboard.setContents(selection, selection)
    }
}
