package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.Shadows.shadowOf
import android.os.Looper

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

    @Test fun settingsCluster() = capture("settings-cluster") {
        ReflectionHelpers.setField(it, "page", "settings")
        ReflectionHelpers.setField(it, "settingsCategory", SettingsCategory.CLUSTER)
    }

    @Test fun settingsClimate() = capture("settings-climate") {
        ReflectionHelpers.setField(it, "page", "settings")
        ReflectionHelpers.setField(it, "settingsCategory", SettingsCategory.CLIMATE)
    }

    @Test fun settingsAppearance() = capture("settings-appearance") {
        ReflectionHelpers.setField(it, "page", "settings")
        ReflectionHelpers.setField(it, "settingsCategory", SettingsCategory.APPEARANCE)
    }

    @Test fun themes() = capture("themes") { ReflectionHelpers.setField(it, "page", "themes") }

    // Di-Connect 2.0 Home preview (phase 1): drawn directly at the head unit's 1920x1080.
    private fun home2(name: String, width: Int = 1920, height: Int = 1080, setup: (HomeDashboardView) -> Unit) {
        val view = HomeDashboardView(org.robolectric.RuntimeEnvironment.getApplication())
        view.fixedTime = java.util.GregorianCalendar(2026, 10, 21, 12, 41).time
        setup(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(image))
            val file = File("build/reports/di-connect-ui/$name.png")
            file.parentFile.mkdirs()
            file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }

    private fun HomeDashboardView.sample() {
        connected = true
        connectionName = "Android Auto · Galaxy"
        mapNote = "Preview placeholder · the phone's live map is shown here"
        media = HomeDashboardView.Media("Spotify · Android Auto", "Song title", "Artist name", true, .42f)
        turn = HomeDashboardView.Turn(com.shilapi.xcertplay.host.R.drawable.ic_turn_card_turn_left, "300 m", "Hunter St", "Arrive 12:58 PM · 6.2 km")
    }

    @Test fun home2Connected() = home2("home2-connected") { it.sample() }
    @Test fun home2ConnectedDay() = home2("home2-connected-day") { it.sample(); it.night = false }
    @Test fun home2Offline() = home2("home2-offline") { it.media = HomeDashboardView.Media("FM radio", "Radio", "Choose a station", false, null) }
    @Test fun home2DriverTemperature() = home2("home2-climate-driver") { it.sample(); it.driverTemp = 24.5f; it.popover = HomeDashboardView.Popover.DRIVER_TEMP }
    @Test fun home2Fan() = home2("home2-climate-fan") { it.sample(); it.popover = HomeDashboardView.Popover.FAN }
    @Test fun home2Settings() = home2("home2-settings") { it.sample(); it.popover = HomeDashboardView.Popover.SETTINGS }
    @Test fun home2LeftHandDrive() = home2("home2-lhd") { it.sample(); it.rightHandDrive = false }

    @Test fun home2LauncherPickerFullLargeFont() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.5f)
        home2("home2-launcher-picker-full-large-font") { it.sample(); it.popover = HomeDashboardView.Popover.SETTINGS }
    }

    @Test
    @Config(qualifiers = "ar-rSA-ldrtl-w600dp-h360dp-land-xhdpi")
    fun home2LauncherPickerCompactArabicLargeFont() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.5f)
        home2("home2-launcher-picker-compact-ar-large-font", 1200, 720) {
            it.sample()
            it.popover = HomeDashboardView.Popover.SETTINGS
        }
    }

    @Test fun liveProjectionLetsMapTouchesThroughAndKeepsExpandAndModalTouches() {
        val view = HomeDashboardView(org.robolectric.RuntimeEnvironment.getApplication())
        view.useLiveProjection { }
        view.layout(0, 0, 1280, 720)
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            fun down(x: Float, y: Float): Boolean {
                val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
                return try { view.onTouchEvent(event) } finally { event.recycle() }
            }
            val bounds = view.projectionBounds(1280, 720)
            assertFalse(down(bounds.centerX(), bounds.centerY()))
            assertTrue(down(bounds.right - 30, bounds.top + 40))
            view.popover = HomeDashboardView.Popover.SETTINGS
            assertTrue(down(bounds.centerX(), bounds.centerY()))
        } finally {
            bitmap.recycle()
        }
    }

    private fun capture(name: String, open: (DiPlayActivity) -> Unit) {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            open(activity)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            // Let deferred work (gallery pictures, the climate read) land before the picture is taken.
            shadowOf(Looper.getMainLooper()).idle()
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
