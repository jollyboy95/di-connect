package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GwmCarPlayHudWindowTest {
    private fun render(guidance: ClusterTurnGuidance): Bitmap {
        val view = GwmHeadUpView(RuntimeEnvironment.getApplication())
        view.setAnchor(GwmHeadUpView.Anchor.LEFT)
        view.setGuidance(guidance)
        view.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 480, 240)
        return Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.TRANSPARENT)
            view.draw(Canvas(it))
        }
    }

    @Test fun carPlayGuidanceStaysInsideTheVisibleWindscreenWindow() {
        val arrival = System.currentTimeMillis() / 1000L + 600L
        val bitmap = render(ClusterTurnGuidance(3, 0, 300, "Next road", arrivalEpochSeconds = arrival))
        var drawn = 0
        for (y in 0 until 240) for (x in 0 until 480) {
            val alpha = Color.alpha(bitmap.getPixel(x, y))
            if (x >= 120 || y < 100 || y >= 188) assertEquals("Outside HUD window $x,$y", 0, alpha)
            if (alpha > 0) drawn++
        }
        assertTrue("guidance must draw", drawn > 200)
    }

    @Test fun roundaboutExitsAreNumberedNotOneFixedPicture() {
        val first = render(ClusterTurnGuidance(11, 1, 300, ""))
        val second = render(ClusterTurnGuidance(11, 2, 300, ""))
        var changed = 0
        for (y in 0 until 240) for (x in 0 until 480) if (first.getPixel(x, y) != second.getPixel(x, y)) changed++
        assertTrue("exit 1 and exit 2 must differ", changed > 0)
    }
}
