package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/**
 * Pictures of the redesigned screens at the head unit's size, for review before anyone installs a
 * build. CI publishes build/reports/di-connect-ui on the releases repo's ui-renders branch.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "en-w1280dp-h720dp-land-mdpi")
class DiConnectUiPreviewTest {
    @Test fun home() = capture("home") { }

    @Test fun settingsOverview() = capture("settings") {
        ReflectionHelpers.setField(it, "page", "settings")
        ReflectionHelpers.setField(it, "settingsCategory", SettingsCategory.OVERVIEW)
    }

    @Test fun settingsConnection() = capture("settings-connection") {
        ReflectionHelpers.setField(it, "page", "settings")
        ReflectionHelpers.setField(it, "settingsCategory", SettingsCategory.CONNECTION)
    }

    private fun capture(name: String, open: (DiPlayActivity) -> Unit) {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            open(activity)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val metrics = activity.resources.displayMetrics
            val width = (activity.resources.configuration.screenWidthDp * metrics.density).toInt()
            val height = (activity.resources.configuration.screenHeightDp * metrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(image))
                val file = File("build/reports/di-connect-ui/$name.png")
                file.parentFile.mkdirs()
                file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { image.recycle() }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
