package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The theme turn card moves on its own, per theme, without disturbing the rest of the layout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterThemeGuidanceCardTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearOffsets() {
        ClusterThemeStore.resetGuidanceCard(context, "aurora")
        ClusterThemeStore.resetGuidanceCard(context, "horizon")
    }

    @Test fun theCardMovesInTwoPercentStepsAndIsClamped() {
        assertEquals(0 to 0, ClusterThemeStore.guidanceOffset(context, "aurora"))
        assertEquals(2 to 0, ClusterThemeStore.moveGuidanceCard(context, "aurora", 1, 0))
        assertEquals(4 to -2, ClusterThemeStore.moveGuidanceCard(context, "aurora", 1, -1))
        repeat(40) { ClusterThemeStore.moveGuidanceCard(context, "aurora", 1, 1) }
        assertEquals(20 to 20, ClusterThemeStore.guidanceOffset(context, "aurora"))
        repeat(40) { ClusterThemeStore.moveGuidanceCard(context, "aurora", -1, -1) }
        assertEquals(-20 to -20, ClusterThemeStore.guidanceOffset(context, "aurora"))
    }

    @Test fun offsetsBelongToOneThemeAndReset() {
        ClusterThemeStore.moveGuidanceCard(context, "aurora", 2, 1)
        assertEquals(4 to 2, ClusterThemeStore.guidanceOffset(context, "aurora"))
        assertEquals(0 to 0, ClusterThemeStore.guidanceOffset(context, "horizon"))
        ClusterThemeStore.resetGuidanceCard(context, "aurora")
        assertEquals(0 to 0, ClusterThemeStore.guidanceOffset(context, "aurora"))
    }

    @Test fun onlyTheTurnCardMovesOnAurora() {
        val before = render()
        ClusterThemeStore.moveGuidanceCard(context, "aurora", -1, 0)
        repeat(9) { ClusterThemeStore.moveGuidanceCard(context, "aurora", -1, 0) }
        val after = render()
        val w = before.width
        val h = before.height
        // This point sat inside the card's original right-hand column and is now open map.
        assertNotEquals("the turn card leaves its old place",
            before.getPixel((w * .90f).toInt(), (h * .70f).toInt()),
            after.getPixel((w * .90f).toInt(), (h * .70f).toInt()))
        // The speed and flow on the left never move with the guidance card.
        assertEquals("the speed area is untouched",
            before.getPixel((w * .12f).toInt(), (h * .40f).toInt()),
            after.getPixel((w * .12f).toInt(), (h * .40f).toInt()))
        before.recycle()
        after.recycle()
    }

    private fun render(): Bitmap {
        val w = 960
        val h = 360
        val theme = ClusterThemes.byId("aurora") ?: error("Aurora is missing")
        val view = ThemedClusterView(context, theme, ClusterConnection.ANDROID_AUTO, preview = true)
        view.setSnapshot(GwmVehicleData.Snapshot(54f, "D", 58f, 523f, 19f, SystemClock.elapsedRealtime(), 34_868f, 35f))
        view.setGuidance(ClusterTurnGuidance(3, 0, 1200, "Pacific Hwy", arrivalEpochSeconds = 1_793_239_080L,
            remainingSeconds = 1_680L, remainingMeters = 24_000L))
        view.setPreviewMusic(ClusterMusicState.Snapshot(CarPlayNowPlaying(title = "Joy.", artist = "RAYE",
            durationMillis = 200_000L, elapsedMillis = 90_000L)))
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(22, 48, 65))
        view.draw(Canvas(bitmap))
        return bitmap
    }
}
