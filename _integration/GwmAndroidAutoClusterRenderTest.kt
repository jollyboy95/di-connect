package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** Render the actual Android view, not a separate approximation of the theme. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GwmAndroidAutoClusterRenderTest {
    @Test fun rendersWithClearNavigationAndStockWarningRegions() {
        val background = Color.rgb(20, 125, 210)
        for ((width, height) in listOf(1920 to 720, 1280 to 480, 1600 to 600)) {
            val speed = if (width == 1280) 120f else 0f
            val view = GwmAndroidAutoClusterView(RuntimeEnvironment.getApplication())
            view.setSnapshot(GwmVehicleData.Snapshot(speed, "P", 75f, 789f, 26f,
                SystemClock.elapsedRealtime(), 34618f))
            val cover = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(130, 90, 170))
            }
            ReflectionHelpers.setField(view, "music", ClusterMusicState.Snapshot(
                CarPlayNowPlaying(title = "Turn The Lights Off", artist = "Kato, Jon",
                    durationMillis = 240000L, elapsedMillis = 100000L, playing = false), cover))
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(background)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            // Exercise the media card independent of the user's persisted visibility preference.
            view.javaClass.getDeclaredMethod("drawMusic", Canvas::class.java,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType).apply {
                isAccessible = true
            }.invoke(view, canvas, width.toFloat(), height.toFloat())
            val output = File("build/reports/gwm-cluster")
            output.mkdirs()
            File(output, "android-auto-${width}x${height}-${speed.toInt()}.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            fun unchanged(left: Float, top: Float, right: Float, bottom: Float) {
                for (y in (height * top).toInt() until (height * bottom).toInt())
                    for (x in (width * left).toInt() until (width * right).toInt())
                        assertEquals("Overlay entered protected area at $x,$y", background, bitmap.getPixel(x,y))
            }
            unchanged(.75f, .40f, .99f, .75f) // Google's unmodifiable directions card
            unchanged(.89f, .17f, .99f, .40f) // factory door and assistance warnings
            unchanged(.05f, .82f, .20f, .93f) // factory speed-limit and lane symbols
            unchanged(.76f, .92f, .82f, .99f) // factory drive mode\n            unchanged(.44f, .92f, .55f, .99f) // factory READY below the information row
            val charcoal = Color.rgb(17, 24, 32)
            assertEquals(charcoal, bitmap.getPixel((width*.50f).toInt(), (height*.06f).toInt()))
            assertEquals(charcoal, bitmap.getPixel((width*.535f).toInt(), (height*.055f).toInt()))
            assertEquals(charcoal, bitmap.getPixel((width*.24f).toInt(), (height*.85f).toInt()))
            assertEquals(charcoal, bitmap.getPixel((width*.88f).toInt(), (height*.22f).toInt()))
            assertEquals(background, bitmap.getPixel((width*.08f).toInt(), (height*.49f).toInt()))\n            unchanged(.48f, .80f, .70f, .91f) // open map between compact modules
            bitmap.recycle()
            cover.recycle()
        }
    }
}


