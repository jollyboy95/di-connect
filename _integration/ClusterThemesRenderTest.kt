package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every theme, on both connections, drawn by the real renderer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterThemesRenderTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun everyThemeDrawsAndAndroidAutoKeepsGooglesCardClear() {
        val (width, height) = 1920 to 720
        val background = Color.rgb(20, 125, 210)
        val output = File("build/reports/gwm-cluster/themes").apply { mkdirs() }
        for (connection in ClusterConnection.entries) for (theme in ClusterThemes.availableFor(connection)) {
            if (theme.native != null) continue
            val view = ThemedClusterView(context, theme, connection, preview = true)
            view.setSnapshot(GwmVehicleData.Snapshot(54f, "D", 58f, 523f, 19f, SystemClock.elapsedRealtime(), 34868f, 35f))
            view.setGuidance(ClusterTurnGuidance(3, 0, 300, "Pacific Hwy", arrivalEpochSeconds = System.currentTimeMillis() / 1000 + 900))
            view.setPreviewMusic(ClusterMusicState.Snapshot(CarPlayNowPlaying(title = "Joy.", artist = "RAYE",
                durationMillis = 200_000L, elapsedMillis = 90_000L)))
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(background)
            view.draw(Canvas(bitmap))
            File(output, "${theme.id}-${connection.name.lowercase()}.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            var drawn = 0
            for (y in 0 until height step 4) for (x in 0 until width / 3 step 4) if (bitmap.getPixel(x, y) != background) drawn++
            assertTrue("${theme.id} on $connection draws its instruments", drawn > 500)
            if (connection == ClusterConnection.ANDROID_AUTO && theme.dim == 0) {
                for (y in (height * .42f).toInt() until (height * .74f).toInt() step 3)
                    for (x in (width * .76f).toInt() until (width * .99f).toInt() step 3)
                        assertEquals("${theme.id} covers Google's card at $x,$y", background, bitmap.getPixel(x, y))
            }
            if (connection == ClusterConnection.CARPLAY) {
                assertNotEquals("${theme.id} draws the CarPlay directions card", background,
                    bitmap.getPixel((width * .77f).toInt(), (height * .45f).toInt()))
            }
            bitmap.recycle()
        }
    }

    @Test fun choicesArePerConnectionUnlessOneThemeIsUsedForBoth() {
        ClusterThemeStore.setSameForBoth(context, false)
        ClusterThemeStore.select(context, ClusterConnection.ANDROID_AUTO, "rally")
        ClusterThemeStore.select(context, ClusterConnection.CARPLAY, "aviator")
        assertEquals("rally", ClusterThemeStore.selected(context, ClusterConnection.ANDROID_AUTO).id)
        assertEquals("aviator", ClusterThemeStore.selected(context, ClusterConnection.CARPLAY).id)
        ClusterThemeStore.setSameForBoth(context, true)
        assertEquals("rally", ClusterThemeStore.selected(context, ClusterConnection.CARPLAY).id)
        // Modern belongs to Android Auto; with one theme for both, CarPlay keeps its own original.
        ClusterThemeStore.select(context, ClusterConnection.ANDROID_AUTO, ClusterThemes.MODERN)
        assertEquals(ClusterThemes.GLASS, ClusterThemeStore.selected(context, ClusterConnection.CARPLAY).id)
        ClusterThemeStore.setSameForBoth(context, false)
    }

    @Test fun galleryPreviewsRenderForEveryTheme() {
        for (connection in ClusterConnection.entries) for (theme in ClusterThemes.availableFor(connection)) {
            val preview = ClusterThemePreview.render(context, theme, connection, 480, 180)
            assertEquals(480, preview.width)
            preview.recycle()
        }
    }

    @Test fun theHudTakesEachThemesColourButStaysReadable() {
        for (theme in ClusterThemes.all) {
            val style = HudThemeStyle.forTheme(theme)
            for (color in listOf(style.arrow, style.text)) {
                val hsv = FloatArray(3)
                Color.colorToHSV(color, hsv)
                assertEquals("${theme.id} must be opaque on the HUD", 255, Color.alpha(color))
                assertTrue("${theme.id} is too dim for the HUD", hsv[2] >= HudThemeStyle.MIN_BRIGHTNESS - 0.01f)
            }
        }
        assertEquals(HudThemeStyle.PLAIN, HudThemeStyle.forTheme(ClusterThemes.byId(ClusterThemes.MODERN)!!))
        val nightOps = HudThemeStyle.forTheme(ClusterThemes.byId("night-ops")!!)
        assertTrue(Color.red(nightOps.arrow) > 200 && Color.green(nightOps.arrow) < 120)
    }

    @Test fun theDriverCanTurnTheHudThemeOff() {
        ClusterThemeStore.select(context, ClusterConnection.ANDROID_AUTO, "ember")
        assertTrue(ClusterThemeStore.hudFollowsTheme(context))
        assertNotEquals(HudThemeStyle.PLAIN, HudThemeStyle.of(context, ClusterConnection.ANDROID_AUTO))
        ClusterThemeStore.setHudFollowsTheme(context, false)
        assertEquals(HudThemeStyle.PLAIN, HudThemeStyle.of(context, ClusterConnection.ANDROID_AUTO))
        ClusterThemeStore.setHudFollowsTheme(context, true)
    }

    @Test fun androidAutosHudArrowIsDrawnInTheThemeColour() {
        ClusterThemeStore.select(context, ClusterConnection.ANDROID_AUTO, "ember")
        val hud = GwmAndroidAutoHeadUpView(context)
        hud.setGuidance(ClusterTurnGuidance(3, 0, 300, "Pacific Hwy"))
        hud.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY))
        hud.layout(0, 0, 480, 240)
        val bitmap = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888)
        hud.draw(Canvas(bitmap))
        var amber = 0
        for (y in 0 until 240) for (x in 0 until 480) {
            val p = bitmap.getPixel(x, y)
            if (Color.red(p) > 200 && Color.green(p) in 60..170 && Color.blue(p) < 70) amber++
        }
        bitmap.recycle()
        assertTrue("Ember's HUD arrow should be amber", amber > 40)
    }
}
