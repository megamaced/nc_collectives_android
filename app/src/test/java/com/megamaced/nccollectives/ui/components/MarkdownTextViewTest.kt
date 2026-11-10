package com.megamaced.nccollectives.ui.components

import android.app.Activity
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Read mode's page body: its text can be selected and copied, and its links
 * still open on a tap. `setTextIsSelectable` replaces the movement method, so
 * the order the two are set in decides whether links work at all.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real text layout, so a touch lands on the word it aims at.
class MarkdownTextViewTest {
    private val text = "Read the guide before you start"

    @Test
    fun theBodyIsSelectable_andKeepsTheLinkMovementMethod() {
        val view = markdownTextView(activity())

        assertTrue(view.isTextSelectable)
        assertTrue("was ${view.movementMethod}", view.movementMethod is LinkMovementMethod)
    }

    @Test
    fun aTapOnALink_stillOpensIt() {
        var opened = 0
        val view = shown(withLinkOn = "guide") { opened++ }

        tap(view, text.indexOf("guide") + 2)

        assertEquals(1, opened)
    }

    @Test
    fun liftingTheFingerAfterALongPress_keepsTheSelectionItMade() {
        val view = shown(withLinkOn = null)
        val offset = text.indexOf("before") + 2
        val (x, y) = pointAt(view, offset)
        val downTime = SystemClock.uptimeMillis()

        view.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0))
        assertTrue("the long press should be handled", view.performLongClick(x, y))
        assertEquals("before", view.selectedText())
        view.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime + 600, MotionEvent.ACTION_UP, x, y, 0))
        ShadowLooper.idleMainLooper()

        // LinkMovementMethod clears the selection on any release outside a
        // link; this one must not reach it.
        assertEquals("before", view.selectedText())
    }

    private fun activity(): Activity =
        Robolectric
            .buildActivity(Activity::class.java)
            .setup()
            .get()

    private fun shown(
        withLinkOn: String?,
        onLink: () -> Unit = {},
    ): TextView {
        val activity = activity()
        val view = markdownTextView(activity)
        val spannable = SpannableString(text)
        if (withLinkOn != null) {
            val start = text.indexOf(withLinkOn)
            spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = onLink()
                },
                start,
                start + withLinkOn.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        view.text = spannable
        activity.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        ShadowLooper.idleMainLooper()
        return view
    }

    private fun pointAt(
        view: TextView,
        offset: Int,
    ): Pair<Float, Float> {
        val layout = view.layout
        val line = layout.getLineForOffset(offset)
        val x = view.totalPaddingLeft + layout.getPrimaryHorizontal(offset)
        val y = view.totalPaddingTop + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
        return x to y
    }

    private fun tap(
        view: TextView,
        offset: Int,
    ) {
        val (x, y) = pointAt(view, offset)
        val downTime = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0))
        ShadowLooper.idleMainLooper()
    }

    private fun TextView.selectedText(): String =
        text.subSequence(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0)).toString()
}
