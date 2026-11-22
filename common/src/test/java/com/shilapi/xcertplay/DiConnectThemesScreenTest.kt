package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
class DiConnectThemesScreenTest {
    private fun texts(activity: DiPlayActivity): List<String> {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        fun walk(v: View): Sequence<View> = sequence { yield(v); if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(walk(v.getChildAt(i))) }
        return walk(root).filterIsInstance<TextView>().map { it.text.toString() }.toList()
    }

    @Test fun homeHasThemesAndRestartAndTheTabsSwitchTheGallery() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            ReflectionHelpers.setField(activity, "page", "home")
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            val home = texts(activity)
            assertTrue(activity.getString(R.string.di_connect_themes) in home)
            assertTrue(activity.getString(R.string.di_connect_restart) in home)

            ReflectionHelpers.setField(activity, "page", "themes")
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            var shown = texts(activity)
            assertTrue(activity.getString(R.string.di_connect_android_auto) in shown)
            assertTrue(activity.getString(R.string.di_connect_carplay) in shown)
            assertTrue(activity.getString(R.string.di_connect_theme_modern) in shown)

            ReflectionHelpers.setField(activity, "themesTab", 1)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            shown = texts(activity)
            assertTrue(activity.getString(R.string.di_connect_theme_glass) in shown)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
