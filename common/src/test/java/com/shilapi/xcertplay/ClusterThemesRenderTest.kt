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
        val catalogue = sequenceOf(File("../_integration/themes.json"), File("_integration/themes.json"))
            .first { it.isFile }.readText().let(ThemePacks::parse)
        val renderThemes = (ClusterThemes.builtIn + catalogue).distinctBy { it.id }
        for (connection in ClusterConnection.entries) for (theme in renderThemes.filter { it.availableFor(connection) }) {
            if (theme.native != null) continue
            val view = ThemedClusterView(context, theme, connection, preview = true)
            view.setSnapshot(GwmVehicleData.Snapshot(54f, "D", 58f, 523f, 19f, SystemClock.elapsedRealtime(), 34868f, 35f, 1800f))
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
            // A theme with its own map window has Google's card inside that window instead.
            // The full-map instrument layout draws Di-Connect's directions instead (turn card hidden).
            if (connection == ClusterConnection.ANDROID_AUTO && theme.dim == 0 && theme.mapWindow == null &&
                theme.layout !in setOf(ClusterTheme.Layout.BAVARIAN, ClusterTheme.Layout.TURBINE_LUXURY, ClusterTheme.Layout.DAYLIGHT_FUTURE)) {
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

    @Test fun conceptsRenderGuidanceOverMapAtFullAndCompactSizes() {
        val catalogue = sequenceOf(File("../_integration/themes.json"), File("_integration/themes.json"))
            .first { it.isFile }.readText().let(ThemePacks::parse)
        val layouts = setOf(ClusterTheme.Layout.TURBINE_LUXURY, ClusterTheme.Layout.PERFORMANCE_COCKPIT,
            ClusterTheme.Layout.MUSIC_PANORAMA, ClusterTheme.Layout.DAYLIGHT_FUTURE)
        val concepts = catalogue.filter { it.layout in layouts }
        assertEquals(4,concepts.size)
        val output=File("build/reports/gwm-cluster/themes").apply{mkdirs()}
        val artwork=Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888)
        val cover=Canvas(artwork)
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.shader=android.graphics.LinearGradient(0f,0f,300f,300f,Color.rgb(21,61,75),Color.rgb(215,163,105),android.graphics.Shader.TileMode.CLAMP)
        cover.drawRect(0f,0f,300f,300f,paint);paint.shader=null
        paint.color=Color.argb(140,10,30,40)
        for(i in 0..8) cover.drawRect(i*42f,140f-i*8f,i*42f+25f,300f,paint)
        for(theme in concepts) for((w,h) in listOf(1920 to 720,960 to 360)) {
            val view=ThemedClusterView(context,theme,ClusterConnection.ANDROID_AUTO,preview=true)
            view.setSnapshot(GwmVehicleData.Snapshot(72f,"D",82f,380f,22f,SystemClock.elapsedRealtime(),35350f,-15f,2400f))
            view.setPreviewMusic(ClusterMusicState.Snapshot(CarPlayNowPlaying(title="Midnight Sun",artist="Ásgeir",
                durationMillis=277000L,elapsedMillis=134000L),artwork))
            view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h)
            fun render(guidance:ClusterTurnGuidance?):Bitmap {
                view.setGuidance(guidance)
                val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(bitmap)
                canvas.drawColor(Color.rgb(22,48,65))
                // Schematic test map only. Production always uses the phone's video.
                paint.color=Color.rgb(31,64,74)
                for(i in 0..22) for(j in 0..10) {
                    val x=i*w/22f;val y=j*h/10f
                    canvas.drawRect(x+3f,y+3f,x+w/29f,y+h/15f,paint)
                }
                paint.color=Color.rgb(82,112,129);paint.strokeWidth=h*.005f
                for(i in -5..20) canvas.drawLine(i*w/14f,0f,(i+3)*w/14f,h.toFloat(),paint)
                val route=android.graphics.Path().apply{moveTo(.5f*w,h.toFloat());cubicTo(.44f*w,.7f*h,.6f*w,.6f*h,.52f*w,.4f*h);lineTo(.56f*w,0f)}
                paint.style=android.graphics.Paint.Style.STROKE;paint.strokeWidth=h*.014f;paint.color=Color.rgb(71,215,218);canvas.drawPath(route,paint);paint.style=android.graphics.Paint.Style.FILL
                view.draw(canvas)
                return bitmap
            }
            val empty=render(null)
            val active=render(ClusterTurnGuidance(3,0,1200,"Pacific Hwy",arrivalEpochSeconds=1793239080L,
                remainingSeconds=1680L,remainingMeters=24000L))
            var changed=0
            for(y in 0 until h step 4) for(x in 0 until w step 4) if(empty.getPixel(x,y)!=active.getPixel(x,y)) changed++
            assertTrue("${theme.name} preview hides its guidance",changed>100)
            File(output,"${theme.id}-concept-${w}.png").outputStream().use{active.compress(Bitmap.CompressFormat.PNG,100,it)}
            empty.recycle();active.recycle()
        }
        artwork.recycle()
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

    @Test fun themePacksAddDrawableThemesAndSkipUnknownStyles() {
        val pack = """{"version": 1, "themes": [
            {"id": "Sunset", "name": "Sunset", "category": "everyday", "layout": "orbit", "panel": "frost",
             "accent": "#FF8A3D", "charge": "#40C0FF", "surface": "#A0101018"},
            {"id": "future", "name": "Future", "layout": "hologram"},
            {"id": "", "name": "No id"},
            {"id": "modern", "name": "Not the built-in", "layout": "deck"}
        ]}"""
        val result = ThemePacks.install(context, pack)
        assertEquals(2, result.added)
        val sunset = ClusterThemes.byId("pack-sunset")!!
        assertEquals(ClusterTheme.Layout.ORBIT, sunset.layout)
        assertEquals(Color.rgb(255, 138, 61), sunset.accent)
        assertEquals(Color.argb(160, 16, 16, 24), sunset.surface)
        assertEquals("Modern", ClusterThemes.byId(ClusterThemes.MODERN)!!.name)
        assertTrue(ClusterThemes.byId("pack-modern") != null)
        assertTrue(ClusterThemes.all.none { it.name == "Future" })
        assertEquals(0, ThemePacks.install(context, pack).let { it.added + it.updated })
        // What is saved reads back the same.
        assertEquals(ThemePacks.parse(ThemePacks.serialize(ThemePacks.themes(context))), ThemePacks.themes(context))
        val bitmap = ClusterThemePreview.render(context, sunset, ClusterConnection.ANDROID_AUTO, 480, 180)
        assertEquals(480, bitmap.width)
        bitmap.recycle()
    }

    @Test(expected = IllegalArgumentException::class)
    fun aFileWithoutUsableThemesIsRefused() {
        ThemePacks.install(context, """{"themes": [{"id": "x", "name": "X", "speed": "warp"}]}""")
    }

    @Test fun splitThemesAskForAHalfWidthMapOnAndroidAutoOnly() {
        val split = ClusterThemes.byId("split-test")!!
        assertEquals(.5f to 1f, split.mapWindow)
        assertTrue(split.availableFor(ClusterConnection.ANDROID_AUTO))
        assertTrue(!split.availableFor(ClusterConnection.CARPLAY))
        assertEquals(null, ClusterThemes.byId(ClusterThemes.MODERN)!!.mapWindow)
        // The instruments stay in the left half; the right half is left to the map.
        val (width, height) = 1920 to 720
        val view = ThemedClusterView(context, split, ClusterConnection.ANDROID_AUTO, preview = true)
        view.setSnapshot(GwmVehicleData.Snapshot(54f, "D", 58f, 523f, 19f, SystemClock.elapsedRealtime(), 34868f, 35f, 1800f))
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        view.draw(Canvas(bitmap))
        for (y in 0 until height step 6) for (x in width / 2 + 4 until width step 6)
            assertEquals("split draws over the map at $x,$y", Color.MAGENTA, bitmap.getPixel(x, y))
        assertNotEquals(Color.MAGENTA, bitmap.getPixel(width / 4, height / 2))
        // Draw again, as the car does every frame: the panel must stay solid, not see-through.
        bitmap.eraseColor(Color.MAGENTA)
        view.draw(Canvas(bitmap))
        val panel = bitmap.getPixel((width * .45f).toInt(), (height * .85f).toInt())
        assertTrue("the split panel lets the map show through: ${Integer.toHexString(panel)}", Color.red(panel) < 60)
        bitmap.recycle()
    }

    @Test fun centreStageLeavesTheMiddleToTheMap() {
        for (id in listOf("centre-stage", "co-pilot-stage")) {
            val theme = ClusterThemes.byId(id)!!
            assertEquals(.25f to .75f, theme.mapWindow)
            assertTrue(!theme.availableFor(ClusterConnection.CARPLAY))
            val (width, height) = 1920 to 720
            val view = ThemedClusterView(context, theme, ClusterConnection.ANDROID_AUTO, preview = true)
            view.setSnapshot(GwmVehicleData.Snapshot(72f, "D", 58f, 703f, 19f, SystemClock.elapsedRealtime(), 34116f, -40f))
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (pass in 0 until 2) { bitmap.eraseColor(Color.MAGENTA); view.draw(Canvas(bitmap)) }
            for (y in (height * .15f).toInt() until (height * .85f).toInt() step 6)
                for (x in (width * .3f).toInt() until (width * .7f).toInt() step 6)
                    assertEquals("$id draws over the map at $x,$y", Color.MAGENTA, bitmap.getPixel(x, y))
            assertTrue(Color.red(bitmap.getPixel((width * .02f).toInt(), (height * .2f).toInt())) < 60)
            File("build/reports/gwm-cluster/themes").mkdirs()
            File("build/reports/gwm-cluster/themes/$id-two-frames.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun studioWindowsKeepNavigationPixelsClearAtBothPreviewSizes() {
        val file = sequenceOf(File("../_integration/themes.json"), File("_integration/themes.json")).first { it.isFile }
        val themes = ThemePacks.parse(file.readText()).filter { it.layout in setOf(
            ClusterTheme.Layout.HYBRID_SPORT, ClusterTheme.Layout.VAPORWAVE_STEREO,
            ClusterTheme.Layout.MUSIC_LOUNGE) }
        assertEquals(3, themes.size)
        for (theme in themes) for ((w, h) in listOf(1920 to 720, 960 to 360)) {
            val window = theme.mapWindow!!
            assertTrue(theme.availableFor(ClusterConnection.ANDROID_AUTO))
            assertTrue(!theme.availableFor(ClusterConnection.CARPLAY))
            val view = ThemedClusterView(context, theme, ClusterConnection.ANDROID_AUTO, preview = true)
            view.setSnapshot(GwmVehicleData.Snapshot(54f, "D", 58f, 523f, 19f,
                SystemClock.elapsedRealtime(), 35096f, -35f, 1800f))
            view.setPreviewMusic(ClusterMusicState.Snapshot(CarPlayNowPlaying(title = "A very long song title that must fit",
                artist = "Fleetwood Mac", durationMillis = 200000L, elapsedMillis = 90000L)))
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.MAGENTA)
            view.draw(Canvas(bitmap))
            for (y in (h * .20f).toInt() until (h * .80f).toInt() step 5)
                for (x in (w * (window.first + .01f)).toInt() until (w * (window.second - .01f)).toInt() step 5)
                    assertEquals("${theme.id} overlaps navigation at $x,$y", Color.MAGENTA, bitmap.getPixel(x, y))
            bitmap.recycle()
        }
    }

    @Test fun directionsCardDrawsOnAndroidAutoOnlyWhenSwitchedOn() {
        val (width, height) = 1920 to 720
        fun draw(theme: ClusterTheme): Bitmap {
            val view = ThemedClusterView(context, theme, ClusterConnection.ANDROID_AUTO)
            view.setGuidance(ClusterTurnGuidance(3, 0, 300, "King St"))
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.MAGENTA); view.draw(Canvas(it))
            }
        }
        val mono = ClusterThemes.byId("mono-line")!!
        ClusterThemeStore.setDirectionsCard(context, false)
        draw(mono).let { assertEquals(Color.MAGENTA, it.getPixel(width / 2, (height * .2f).toInt())); it.recycle() }
        ClusterThemeStore.setDirectionsCard(context, true)
        draw(mono).let { assertNotEquals(Color.MAGENTA, it.getPixel(width / 2, (height * .2f).toInt())); it.recycle() }
        // A theme with its own map window puts the card at the top of that window.
        draw(ClusterThemes.byId("split-test")!!).let {
            assertNotEquals(Color.MAGENTA, it.getPixel((width * .75f).toInt(), (height * .2f).toInt())); it.recycle()
        }
        ClusterThemeStore.setDirectionsCard(context, false)
    }

    @Test fun thePreviewRunsThroughEveryTurnAndEnds() {
        assertEquals(null, NavDemo.current())
        assertTrue(NavDemo.steps.size >= 10)
        assertTrue(NavDemo.steps.map { it.icon }.toSet().containsAll(listOf(2, 3, 8, 11, 15)))
        NavDemo.start()
        assertEquals(NavDemo.steps.first(), NavDemo.current())
        assertTrue(NavDemo.active)
        NavDemo.stop()
        assertEquals(null, NavDemo.current())
    }
}
