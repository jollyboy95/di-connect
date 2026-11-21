package com.shilapi.xcertplay

import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import org.junit.Assert.assertEquals
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

    @Test fun defaultHomeInAppearanceFullLargeFont() = captureDefaultHomeInAppearance("full")

    @Test
    @Config(qualifiers = "ar-rSA-ldrtl-w600dp-h360dp-land-xhdpi")
    fun defaultHomeInAppearanceCompactArabicLargeFont() = captureDefaultHomeInAppearance("compact-ar")

    private fun captureDefaultHomeInAppearance(size: String) {
        org.robolectric.RuntimeEnvironment.setFontScale(1.5f)
        capture("settings-appearance-default-home-$size", scrollTo = com.shilapi.xcertplay.host.R.string.settings_home_default_app) {
            ReflectionHelpers.setField(it, "page", "settings")
            ReflectionHelpers.setField(it, "settingsCategory", SettingsCategory.APPEARANCE)
        }
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
        night = true
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
        var expansions = 0
        withDashboard { view, bitmap ->
            view.useLiveProjection { expansions++ }
            view.draw(Canvas(bitmap))
            val bounds = view.projectionBounds(1280, 720)
            assertFalse(touch(view, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY()))
            val expandX = bounds.right - 26
            val expandY = 34f
            assertTrue(touch(view, MotionEvent.ACTION_DOWN, expandX, expandY))
            assertTrue(touch(view, MotionEvent.ACTION_UP, expandX, expandY))
            assertEquals(1, expansions)
            view.popover = HomeDashboardView.Popover.SETTINGS
            assertTrue(touch(view, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY()))
        }
    }

    @Test fun settingsCloseButtonClosesItsPanel() {
        withDashboard { view, bitmap ->
            view.popover = HomeDashboardView.Popover.SETTINGS
            view.draw(Canvas(bitmap))
            touch(view, MotionEvent.ACTION_UP, 1128f, 104f)
            assertEquals(HomeDashboardView.Popover.NONE, view.popover)
        }
    }

    @Test fun dockMusicOpensSourcesAndMapReturnsToTheEmbeddedView() {
        var expansions = 0
        withDashboard { view, bitmap ->
            val canvas = Canvas(bitmap)
            view.useLiveProjection { expansions++ }
            view.draw(canvas)
            touch(view, MotionEvent.ACTION_UP, 1222f, 285f)
            assertEquals(HomeDashboardView.Popover.SOURCES, view.popover)
            view.draw(canvas)
            touch(view, MotionEvent.ACTION_UP, 1222f, 196f)
            assertEquals(HomeDashboardView.Popover.NONE, view.popover)
            view.draw(canvas)
            touch(view, MotionEvent.ACTION_UP, 1222f, 196f)
            assertEquals(0, expansions)
        }
    }

    @Test fun liveMapHasATransparentViewportInBothThemes() {
        withDashboard { view, bitmap ->
            view.useLiveProjection { }
            for (night in listOf(true, false)) {
                view.night = night
                bitmap.eraseColor(Color.TRANSPARENT)
                view.draw(Canvas(bitmap))
                val bounds = view.projectionBounds(1280, 720)
                assertEquals(Color.TRANSPARENT, bitmap.getPixel(bounds.centerX().toInt(), bounds.centerY().toInt()))
                assertEquals(255, Color.alpha(bitmap.getPixel(8, 8)))
            }
        }
    }

    @Test fun liveMapViewportKeepsTheProjectionAspectRatio() {
        withDashboard { view, _ ->
            val bounds = view.projectionBounds(1200, 720)
            assertEquals(1200f / 720f, bounds.width() / bounds.height(), .001f)
            val full = view.projectionBounds(1920, 1080)
            assertTrue(full.width() >= 1380f)
            assertEquals(915f, full.bottom, .01f)
            assertTrue(view.projectionBounds(0, 720).isEmpty)
        }
    }

    @Test fun albumArtChangesTheMediaShelfTint() {
        withDashboard { view, bitmap ->
            view.night = true
            val red = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            val blue = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            try {
                view.media = view.media.copy(art = red)
                view.draw(Canvas(bitmap))
                val redTint = bitmap.getPixel(978, 162)
                view.media = view.media.copy(art = blue)
                view.draw(Canvas(bitmap))
                val blueTint = bitmap.getPixel(978, 162)
                assertTrue(Color.red(redTint) > Color.red(blueTint))
                assertTrue(Color.blue(blueTint) > Color.blue(redTint))
            } finally {
                red.recycle()
                blue.recycle()
            }
        }
    }

    @Test fun settingsMenuCoversTheLiveProjection() {
        withDashboard { view, bitmap ->
            view.useLiveProjection { }
            view.popover = HomeDashboardView.Popover.SETTINGS
            view.draw(Canvas(bitmap))
            val bounds = view.projectionBounds(1280, 720)
            assertEquals(255, Color.alpha(bitmap.getPixel(bounds.centerX().toInt(), bounds.centerY().toInt())))
        }
    }

    @Test fun liveViewportMirrorsWithoutChangingItsSizeOnANarrowWindow() {
        withDashboard { view, _ ->
            val right = view.projectionBounds(1200, 720)
            view.rightHandDrive = false
            val left = view.projectionBounds(1200, 720)
            assertEquals(right.width(), left.width(), .01f)
            assertEquals(right.height(), left.height(), .01f)
            assertEquals(right.top, left.top, .01f)
            assertEquals(1200f, right.left + left.right, .01f)
            assertTrue(right.left < left.left)
        }
    }

    @Test fun defaultLauncherStaysDisabledUntilTheDriverOpensThePicker() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val alias = ComponentName(activity.packageName, "com.shilapi.xcertplay.HomeLauncher")
        val pm = activity.packageManager
        assertFalse(pm.getActivityInfo(alias, PackageManager.MATCH_DISABLED_COMPONENTS).enabled)
        withDashboard(activity) { view, bitmap ->
            view.popover = HomeDashboardView.Popover.SETTINGS
            view.draw(Canvas(bitmap))
            touch(view, MotionEvent.ACTION_DOWN, 240f, 525f)
            touch(view, MotionEvent.ACTION_UP, 240f, 525f)
        }
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.getComponentEnabledSetting(alias))
        assertEquals(android.provider.Settings.ACTION_HOME_SETTINGS, shadowOf(activity).nextStartedActivity.action)
    }

    @Test fun daylightHasALighterBackdropThanNightMode() {
        withDashboard { view, bitmap ->
            fun backdropRed(night: Boolean): Int {
                view.night = night
                view.draw(Canvas(bitmap))
                return Color.red(bitmap.getPixel(8, 8))
            }
            val day = backdropRed(false)
            assertTrue(day - backdropRed(true) > 65)
            assertTrue(day < 185)
        }
    }

    @Test fun nightIsTheDefaultAndTheHeadlightChoicePersists() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        assertFalse(HomeTheme.followsHeadlights(context))
        HomeTheme.saveFollowsHeadlights(context, true)
        assertTrue(HomeTheme.followsHeadlights(context))
        HomeTheme.saveFollowsHeadlights(context, false)
        assertFalse(HomeTheme.followsHeadlights(context))
    }

    @Test fun headlightsOverrideTheTimeOfDay() {
        assertFalse(homeNightMode(0, 23))
        assertTrue(homeNightMode(1, 12))
    }

    @Test fun missingOrUnsupportedHeadlightSignalUsesLocalTime() {
        assertTrue(homeNightMode(null, 5))
        assertFalse(homeNightMode(null, 6))
        assertFalse(homeNightMode(-1, 18))
        assertTrue(homeNightMode(2, 19))
    }

    @Test fun home2LiveNightGlass() = home2("home2-live-night-glass") {
        it.sample()
        it.night = true
        it.useLiveProjection { }
    }

    @Test fun home2LiveDayGlass() = home2("home2-live-day-glass") {
        it.sample()
        it.night = false
        it.useLiveProjection { }
    }

    private fun withDashboard(
        context: android.content.Context = org.robolectric.RuntimeEnvironment.getApplication(),
        action: (HomeDashboardView, Bitmap) -> Unit,
    ) {
        val view = HomeDashboardView(context)
        view.layout(0, 0, 1280, 720)
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        try {
            action(view, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun touch(view: HomeDashboardView, action: Int, x: Float, y: Float): Boolean {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        return try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    private fun capture(name: String, scrollTo: Int? = null, open: (DiPlayActivity) -> Unit) {
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
            scrollTo?.let { label ->
                fun find(view: View, text: String): TextView? {
                    if (view is TextView && view.text.toString() == text) return view
                    if (view is ViewGroup) {
                        return (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it), text) }
                    }
                    return null
                }
                val row = requireNotNull(find(root, activity.getString(label)))
                val scroll = generateSequence(row.parent) { it.parent }.filterIsInstance<ScrollView>().first()
                val rect = Rect(0, 0, row.width, row.height)
                scroll.offsetDescendantRectToMyCoords(row, rect)
                scroll.scrollTo(0, (rect.bottom - scroll.height + scroll.paddingBottom).coerceAtLeast(0))
                assertTrue(rect.top >= scroll.scrollY)
                assertTrue(rect.bottom <= scroll.scrollY + scroll.height)
            }
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
