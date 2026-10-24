package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GwmAndroidAutoHudRenderTest {
    @Test fun formattedArrivalFitsTheMeasuredCarPlayHudWindow() {
        val view = GwmAndroidAutoHeadUpView(RuntimeEnvironment.getApplication())
        view.setAnchor(GwmAndroidAutoHeadUpView.Anchor.LEFT)
        view.setArrivalText("10:31 AM")
        view.setGuidance(ClusterTurnGuidance(3, 0, 300, "Next road"))
        view.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 480, 240)
        val bitmap = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        view.draw(Canvas(bitmap))
        var arrivalPixels = 0
        for (y in 0 until 240) for (x in 0 until 480) {
            val alpha = Color.alpha(bitmap.getPixel(x, y))
            if (x >= 144 || y < 96 || y >= 192) assertEquals("Outside HUD aperture $x,$y", 0, alpha)
            if (y in 160..191 && alpha > 0) arrivalPixels++
        }
        assertTrue("Formatted ETA must draw the second row", arrivalPixels > 30)
        val output = File("build/reports/gwm-cluster").apply { mkdirs() }
        File(output, "android-auto-hud-480x240.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        view.setArrivalText(null)
        view.setGuidance(null)
        bitmap.eraseColor(Color.TRANSPARENT)
        view.draw(Canvas(bitmap))
        assertEquals(0, Color.alpha(bitmap.getPixel(60, 170)))
        bitmap.recycle()
    }
}
