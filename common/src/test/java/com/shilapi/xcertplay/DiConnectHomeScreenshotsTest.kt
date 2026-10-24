package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiConnectHomeScreenshotsTest {
    @Test @Config(sdk = [29], qualifiers = "en-w320dp-h700dp")
    fun compactHomeAtLargeFont() = capture("compact-en")

    @Test @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
    fun fullHomeAtLargeFont() = capture("full-en")

    @Test @Config(sdk = [29], qualifiers = "ar-ldrtl-w320dp-h700dp")
    fun compactRtlHomeAtLargeFont() = capture("compact-ar")

    private fun capture(name: String) {
        RuntimeEnvironment.setFontScale(1.5f)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            ReflectionHelpers.setField(activity, "page", "home")
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val metrics = activity.resources.displayMetrics
            val width = (activity.resources.configuration.screenWidthDp * metrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, width, root.measuredHeight)
            val aa = descendants(root).filterIsInstance<TextView>().single {
                it.text.toString() == activity.getString(R.string.di_connect_android_auto)
            }
            assertTrue("Android Auto entry must be laid out", aa.width > 0 && aa.height > 0)
            val layout = aa.layout
            if (layout != null) for (line in 0 until layout.lineCount) {
                assertEquals("Android Auto label must fit", 0, layout.getEllipsisCount(line))
            }
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(image))
                val file = File("build/reports/di-connect-home/$name.png")
                file.parentFile.mkdirs()
                file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { image.recycle() }
        } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
