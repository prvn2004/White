package open.source.white.ui.reader

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView

class HighlightWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    var onHighlightRequested: (() -> Unit)? = null

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        if (callback == null) return super.startActionMode(callback, type)
        val wrapped = object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val created = callback.onCreateActionMode(mode, menu)
                if (created) {
                    menu.add(Menu.NONE, HIGHLIGHT_ITEM_ID, 1, "Highlight")
                }
                return created
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                return callback.onPrepareActionMode(mode, menu)
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId == HIGHLIGHT_ITEM_ID) {
                    onHighlightRequested?.invoke()
                    return true
                }
                return callback.onActionItemClicked(mode, item)
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                callback.onDestroyActionMode(mode)
            }
        }
        return super.startActionMode(wrapped, type)
    }

    companion object {
        private const val HIGHLIGHT_ITEM_ID = 0x00F17E
    }
}

