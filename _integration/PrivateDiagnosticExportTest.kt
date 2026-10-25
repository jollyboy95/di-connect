package com.andrerinas.openheadunit.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PrivateDiagnosticExportTest {
    @Test fun android9ReportWritesWithoutExternalStorageOrDocumentPicker() {
        val app = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val context: Context = app
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        val report = "HUD arrival present=false\nUnicode: → 300 m · 17:45\n"
        val file = DiagnosticExportStore.writePrivateReport(context, "aa-hud-test.txt", report)
        assertTrue(file.isFile)
        assertTrue(file.canonicalPath.startsWith(context.cacheDir.canonicalPath + "/"))
        assertEquals(report, file.readText(Charsets.UTF_8))
        file.delete()
    }
}
