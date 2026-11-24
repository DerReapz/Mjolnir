package app.mjolnir.android.ui

import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED
import android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
import android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatKeyboardTest {
    @Test fun chatWindowResizesInsteadOfPanning() {
        // Today's default (unspecified) and an explicit pan both become resize, so the window no longer slides up.
        assertEquals(SOFT_INPUT_ADJUST_RESIZE, withResize(SOFT_INPUT_ADJUST_UNSPECIFIED) and SOFT_INPUT_MASK_ADJUST)
        assertEquals(SOFT_INPUT_ADJUST_RESIZE, withResize(SOFT_INPUT_ADJUST_PAN) and SOFT_INPUT_MASK_ADJUST)
        // The visibility part of the mode is kept.
        assertEquals(SOFT_INPUT_STATE_HIDDEN or SOFT_INPUT_ADJUST_RESIZE, withResize(SOFT_INPUT_STATE_HIDDEN or SOFT_INPUT_ADJUST_PAN))
    }
}
