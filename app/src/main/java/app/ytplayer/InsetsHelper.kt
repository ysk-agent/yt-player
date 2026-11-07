package app.ytplayer

import android.app.Activity
import android.util.TypedValue
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android 15 (targetSdk 35) enforces edge-to-edge: the activity content is
 * laid out from the very top of the window, sliding under the status bar and
 * the AppCompat action bar, and behind the navigation bar at the bottom
 * (verified with emulator screenshots: the main screen's top button row was
 * drawn under the status bar and the filter chips were covered by the action
 * bar; the player screen's bottom control row was covered by the nav bar).
 *
 * This applies the system-bar insets — plus the action bar's height at the
 * top, since the action bar keeps its normal slot below the status bar while
 * the content starts at y=0 — as padding on the activity's root view, so no
 * content is covered. The root view's own XML padding is preserved.
 */
object InsetsHelper {

    fun applyToContentRoot(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) ?: return
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        val actionBarPx = actionBarHeightPx(activity)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top + actionBarPx,
                baseRight + bars.right,
                baseBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun actionBarHeightPx(activity: Activity): Int {
        val tv = TypedValue()
        return if (activity.theme.resolveAttribute(
                androidx.appcompat.R.attr.actionBarSize, tv, true
            )
        ) {
            TypedValue.complexToDimensionPixelSize(
                tv.data, activity.resources.displayMetrics
            )
        } else {
            0
        }
    }
}
