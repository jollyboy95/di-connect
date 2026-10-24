from pathlib import Path
import shutil,re,xml.etree.ElementTree as ET,json
root=Path('dplay'); src=Path('diauto'); dest=root/'android-auto'
shutil.copytree(src/'app/src',dest/'src',dirs_exist_ok=True)
shutil.copytree(src/'contract',root/'aa-contract',dirs_exist_ok=True)
for name in ['LICENSE','COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt','README.md','CHANGELOG.md']:
    shutil.copy2(src/name,dest/name)
# Resource isolation: Android library resource names are global in the final APK.
names={}
for p in (dest/'src').glob('*/res/**/*'):
    if not p.is_file(): continue
    typ=p.parent.name.split('-')[0]
    if typ!='values': names.setdefault(typ,set()).add(p.name.split('.')[0])
    if p.suffix=='.xml':
        try: tree=ET.parse(p)
        except ET.ParseError: continue
        for el in tree.iter():
            if typ=='values' and 'name' in el.attrib:
                t=el.attrib.get('type',el.tag)
                if t in ('string-array','integer-array'): t='array'
                if t not in ('resources','item','declare-styleable'): names.setdefault(t,set()).add(el.attrib['name'])
            for val in el.attrib.values():
                for n in re.findall(r'@\+id/([\w]+)',val): names.setdefault('id',set()).add(n)
# attrs are unused by code styleables; preserve styleable field structure through XML.
for p in list((dest/'src').rglob('*')):
    if not p.is_file() or p.suffix not in ('.xml','.kt','.java'): continue
    text=p.read_text()
    if p.suffix in ('.kt','.java'):
        if 'import android.R' not in text:
            code_names={typ:{n.replace('.', '_') for n in ns} for typ,ns in names.items()}
            text=re.sub(r'(?<!android\.)\bR\.(\w+)\.(\w+)',lambda m: 'R.'+m[1]+'.aa_'+m[2] if m[2] in code_names.get(m[1],set()) else m[0],text)
    else:
        text=re.sub(r'([@?]\+?)([\w]+)/([\w.]+)',lambda m:m[1]+m[2]+'/aa_'+m[3] if m[3] in names.get(m[2],set()) else m[0],text)
        if p.parent.name.startswith('values'):
            # Preserve layout/formatting while renaming declarations and explicit style parents.
            def decl(m):
                typ=m[1]; attrs=m[2]
                n=re.search(r'\bname="([^"]+)"',attrs)
                t=re.search(r'\btype="([^"]+)"',attrs)
                rt=t[1] if t else typ
                if rt in ('string-array','integer-array'): rt='array'
                if typ=='item' and not t: rt='attr'
                if n and n[1] in names.get(rt,set()): attrs=attrs.replace('name="'+n[1]+'"','name="aa_'+n[1]+'"',1)
                parent=re.search(r'\bparent="([^"]+)"',attrs)
                if parent and parent[1] in names.get('style',set()): attrs=attrs.replace('parent="'+parent[1]+'"','parent="aa_'+parent[1]+'"')
                return '<'+typ+attrs+'>'
            text=re.sub(r'<([\w-]+)([^<>]*?)>',decl,text)
    p.write_text(text)
for p in list((dest/'src').glob('*/res/**/*')):
    if p.is_file() and not p.parent.name.startswith('values'): p.rename(p.with_name('aa_'+p.name))
# One application, using DiAuto's dependency container. No imported second launcher/auto-start.
A='{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android',A[1:-1]); ET.register_namespace('tools','http://schemas.android.com/tools')
p=dest/'src/main/AndroidManifest.xml'; tree=ET.parse(p); app=tree.getroot().find('application')
for key in list(app.attrib):
    if key not in [A+'name']: del app.attrib[key]
app.set(A+'name','com.andrerinas.openheadunit.App')
for el in app:
    n=el.get(A+'name','')
    if n.startswith('.'): el.set(A+'name','com.andrerinas.openheadunit'+n)
    if el.tag=='activity' and n=='.main.MainActivity':
        for child in list(el):
            if child.tag=='meta-data' and child.get(A+'name')=='android.app.shortcuts': el.remove(child)
        for child in list(el):
            if child.tag=='intent-filter': el.remove(child)
        el.set(A+'exported','false')
    if el.tag=='receiver' and n in ['.app.BootCompleteReceiver','.app.AutoStartReceiver','.app.WifiAutoStartReceiver','.connection.CarKeyReceiver','.app.RemoteControlReceiver']:
        el.set(A+'enabled','false')
    if el.tag=='activity' and n in ['.app.UsbAttachedActivity','.main.AutomationActivity']:
        el.set(A+'enabled','false')
    if el.tag=='provider' and n=='androidx.core.content.FileProvider':
        el.set(A+'name','com.andrerinas.openheadunit.DiConnectFileProvider')
        el.set(A+'authorities','${applicationId}.aa.fileprovider')
tree.write(p,encoding='unicode',xml_declaration=True)
# Github VPN service source is part of this library, with no product flavors.
shutil.copytree(dest/'src/github/java',dest/'src/main/java',dirs_exist_ok=True)
shutil.rmtree(dest/'src/github')
shutil.rmtree(dest/'src/playstore',ignore_errors=True)
(dest/'src/debug/AndroidManifest.xml').unlink(missing_ok=True)
p=dest/'src/main/AndroidManifest.xml'; tree=ET.parse(p); app=tree.getroot().find('application')
el=ET.SubElement(app,'service',{A+'name':'com.andrerinas.openheadunit.aap.DummyVpnService',A+'permission':'android.permission.BIND_VPN_SERVICE',A+'exported':'false'})
f=ET.SubElement(el,'intent-filter'); ET.SubElement(f,'action',{A+'name':'android.net.VpnService'})
tree.write(p,encoding='unicode',xml_declaration=True)
# Separate file provider authority so imported navigation/export paths stay distinct.
for p in (dest/'src/main/java').rglob('*'):
    if p.suffix in ('.kt','.java'):
        t=p.read_text().replace(' + ".fileprovider"',' + ".aa.fileprovider"').replace('${context.packageName}.fileprovider','${context.packageName}.aa.fileprovider').replace('${packageName}.fileprovider','${packageName}.aa.fileprovider')
        p.write_text(t)
(dest/'src/main/java/com/andrerinas/openheadunit/DiConnectFileProvider.kt').write_text('package com.andrerinas.openheadunit\nclass DiConnectFileProvider : androidx.core.content.FileProvider()\n')
# AGP 9 provides Kotlin directly. Neither upstream annotation processor is used by its source.
original=(src/'app/build.gradle.kts').read_text(); deps=original[original.index('dependencies {'):]
deps=re.sub(r'    "playstoreImplementation"\([^\n]+\n','',deps).replace('"githubImplementation"','implementation')
deps=re.sub(r'^.*kapt\([^\n]+\n','',deps,flags=re.M).replace('project(":contract")','project(":aa-contract")')
locales=sorted(p.name[7:] for p in (dest/'src/main/res').glob('values-*') if (p/'strings.xml').exists())
(dest/'build.gradle.kts').write_text('''plugins { id("com.android.library") }
android {
    namespace = "com.andrerinas.openheadunit"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 24
        buildConfigField("String", "APPLICATION_ID", "\\\"com.jollyboy95.diconnect.debug\\\"")
        buildConfigField("String", "VERSION_NAME", "\\\"0.1.0-preview\\\"")
        buildConfigField("int", "VERSION_CODE", "1")
        buildConfigField("String", "AVAILABLE_LOCALES", "\\\"'''+','.join(locales)+'''\\\"")
    }
    buildFeatures { buildConfig = true; aidl = true }
    externalNativeBuild { cmake { path("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
'''+deps)
notices=dest/'src/main/assets'; notices.mkdir(parents=True,exist_ok=True)
for n in ['LICENSE','CHANGELOG.md']: shutil.copy2(src/n,notices/n)
(root/'aa-contract/build.gradle.kts').write_text('''plugins { id("com.android.library") }
android {
    namespace = "com.andrerinas.openheadunit.contract"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
''')
p=root/'settings.gradle.kts'; t=p.read_text().replace('rootProject.name = "xcertplay"','rootProject.name = "di-connect"'); t+='\ninclude(":android-auto", ":aa-contract")\n'; t=t.replace('        mavenCentral()\n    }\n}', '        mavenCentral()\n        maven { url = uri("https://jitpack.io") }\n    }\n}'); p.write_text(t)
p=root/'common/build.gradle.kts'; p.write_text(p.read_text().replace('    api(project(":shared"))','    api(project(":shared"))\n    implementation(project(":android-auto"))'))
p=root/'mobile/build.gradle.kts'; t=p.read_text().replace('applicationId = "com.shihab.diplay"','applicationId = "com.jollyboy95.diconnect"').replace('applicationIdSuffix = ".hudtest"','applicationIdSuffix = ".debug"'); p.write_text(t)
p=root/'common/src/main/AndroidManifest.xml'; t=p.read_text().replace('<application>', '<application android:name="com.andrerinas.openheadunit.App">',1); p.write_text(t)
p=root/'mobile/src/main/AndroidManifest.xml'; t=p.read_text().replace('<application','<application\n        android:name="com.andrerinas.openheadunit.App"\n        tools:replace="android:label,android:icon,android:theme,android:localeConfig"',1);p.write_text(t)
# Backend lifetime: CarPlay waits for full Android Auto service cleanup, not a timer.
p=dest/'src/main/java/com/andrerinas/openheadunit/aap/AapService.kt'; t=p.read_text().replace('override fun onCreate() {','override fun onCreate() {\n        com.andrerinas.openheadunit.DiConnectEngine.service = this',1)
t=t.replace('        if (killProcessOnDestroy) {','        com.andrerinas.openheadunit.DiConnectEngine.onStopped()\n        if (false && killProcessOnDestroy) {',1); p.write_text(t)
(dest/'src/main/java/com/andrerinas/openheadunit/DiConnectEngine.kt').write_text('''package com.andrerinas.openheadunit

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.aap.AapService

/** Serializes a switch back to CarPlay after AA has released audio and transports. */
object DiConnectEngine {
    var service: AapService? = null
    private val waiting = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    fun stopThen(completion: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val running = service
        if (running == null) { completion(); return }
        waiting.add(completion)
        running.stopService(Intent(running, AapService::class.java))
    }
    fun onStopped() {
        service = null
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { main.post(it) }
    }
    fun open(activity: Activity) {
        activity.startActivity(Intent(activity, com.andrerinas.openheadunit.main.MainActivity::class.java))
    }
}
''')
p=root/'common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt'; t=p.read_text()
t=t.replace('    private fun connect(wireless: Boolean) {','''    private fun connect(wireless: Boolean) {
        if (com.andrerinas.openheadunit.DiConnectEngine.service != null) {
            com.andrerinas.openheadunit.DiConnectEngine.stopThen {
                if (!isFinishing && !isDestroyed) connect(wireless)
            }
            return
        }''')
needle='        card.addView(connectButton, matchButton(height = if (compact) 54 else 68))'
t=t.replace(needle,needle+'''
        card.addView(homeButton(getString(R.string.di_connect_android_auto), false) {
            CarPlayBackgroundSession.stop {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        com.andrerinas.openheadunit.DiConnectEngine.open(this)
                        finish()
                    }
                }
            }
        }, matchButton(CONTROL_GAP_DP, if (compact) 54 else 68))
'''); p.write_text(t)
# Reuse existing D-Play button geometry and keep connection copy localised.
for p in (root/'common/src/main/res').glob('values*/strings.xml'):
    t=p.read_text(); t=t.replace('</resources>','    <string name="di_connect_android_auto">Android Auto</string>\n</resources>');p.write_text(t)
for p in (root/'common/src/main/res').glob('values*/strings.xml'):
    t=p.read_text(); t=re.sub(r'(<string name="app_name">).*?(</string>)',r'\g<1>Di-Connect\2',t);p.write_text(t)
# Preserve original donor revisions and licenses.
(root/'docs/DI_CONNECT.md').write_text('''# Di-Connect integration preview

D-Play GWM is the CarPlay and UI foundation. Android Auto is imported as an Android library in the same APK, not an external app dependency.

- D-Play source: jollyboy95/d-play-gwm-h7 at 3df998919f075bc6e21efb5e8a314347813647a1.
- Android Auto source: jollyboy95/DiAuto-GWM at f3046721814bff289a78e58f597b45d895efbb95.
- Separate application ID: com.jollyboy95.diconnect (debug suffix .debug).
- Open Android Auto from the existing D-Play home card. CarPlay is stopped before AA opens. Starting CarPlay waits for AA service teardown.
- AA retains its connection and settings screens in this first integration; these have not yet been redesigned to match every D-Play screen.
- Imported AA boot, Bluetooth, Wi-Fi, automation and USB-attach entry points are disabled initially. Connect Android Auto manually through its screen.
- D-Play settings/pairing are not migrated from the separately installed app.
- Resources are prefixed aa_ to prevent collision with CarPlay resources.
- Both source licenses and original notices are retained. Combined distribution must meet the applicable GPL/AGPL obligations.

## Validation

Physical tests are required for wired/wireless phones, calls, microphones, wheel controls, and cluster/HUD outputs. Source import is not evidence of vehicle compatibility.

## CarPlay runtime assets

The upstream source omits runtime identity files. The existing import-from-APK flow is retained. CI can provision the two files from the upstream published DiPlay release into an external build directory; they are not committed as source files.
''')
print('Integrated Android Auto library; resources:',{k:len(v) for k,v in names.items()})
# Qualified app resources remain app resources even in a file that imports android.R.
p=dest/'src/main/java/com/andrerinas/openheadunit/utils/DialogUtils.kt'
p.write_text(p.read_text().replace('openheadunit.R.style.DarkAlertDialog','openheadunit.R.style.aa_DarkAlertDialog').replace('openheadunit.R.string.reset','openheadunit.R.string.aa_reset'))
p=root/'mobile/src/main/res/values/strings.xml'; p.write_text(p.read_text().replace('>DiPlay<','>Di-Connect<'))
# Internal navigation permission follows the combined package in debug and release builds.
p=dest/'src/main/java/com/andrerinas/openheadunit/aap/AapNavigationHelper.kt'
t=p.read_text().replace('com.andrerinas.openheadunit.BuildConfig.APPLICATION_ID + ".permission.NAVIGATION_UPDATE"','context.packageName + ".permission.NAVIGATION_UPDATE"').replace('else NavigationUpdateIntent.BROADCAST_PERMISSION','else context.packageName + ".permission.NAVIGATION_UPDATE"'); p.write_text(t)
p=dest/'src/main/AndroidManifest.xml'; t=p.read_text().replace('android:name="com.andrerinas.openheadunit.permission.NAVIGATION_UPDATE"','android:name="${applicationId}.permission.NAVIGATION_UPDATE"');p.write_text(t)
# Android Auto initialization is deferred so its theme and radio setup do not run on CarPlay launch.
p=dest/'src/main/java/com/andrerinas/openheadunit/App.kt';t=p.read_text();t=t.replace('        instance = this\n','        instance = this\n    }\n\n    private var androidAutoInitialized = false\n\n    fun initializeAndroidAuto() {\n        if (androidAutoInitialized) return\n        androidAutoInitialized = true\n',1);p.write_text(t)
p=dest/'src/main/java/com/andrerinas/openheadunit/DiConnectEngine.kt';t=p.read_text().replace('    fun open(activity: Activity) {','    fun open(activity: Activity) {\n        App.get(activity).initializeAndroidAuto()');p.write_text(t)
p=dest/'src/main/java/com/andrerinas/openheadunit/aap/AapService.kt';t=p.read_text().replace('        com.andrerinas.openheadunit.DiConnectEngine.service = this\n        super.onCreate()','        super.onCreate()\n        App.get(this).initializeAndroidAuto()\n        com.andrerinas.openheadunit.DiConnectEngine.service = this',1);p.write_text(t)
p=dest/'src/main/java/com/andrerinas/openheadunit/main/MainActivity.kt';t=p.read_text().replace('    override fun onCreate(savedInstanceState: Bundle?) {','    override fun onCreate(savedInstanceState: Bundle?) {\n        App.get(this).initializeAndroidAuto()',1);p.write_text(t)
# Present the combined project clearly while retaining the complete upstream overview.
p=root/'README.md'; original=p.read_text(); p.write_text('''# Di-Connect

CarPlay and Android Auto for the GWM/Haval H7, built on D-Play GWM's interface.

This branch is an integration preview. The Android Auto connection engine is included in the same APK, with its existing setup screens. Compilation checks and physical vehicle testing must pass before this is a release.

See [integration notes](docs/DI_CONNECT.md) for donor revisions, build details and current limitations. The original D-Play overview follows below; its upstream download links point to DiPlay, not to a Di-Connect release.

'''+original)
shutil.copy2(root/'LICENSE',root/'docs/licenses/DiPlay-GPL-3.0.txt')
shutil.copy2(src/'LICENSE',root/'LICENSE')

(root/'common/src/test/java/com/shilapi/xcertplay/DiConnectHomeScreenshotsTest.kt').write_text('package com.shilapi.xcertplay\n\nimport android.graphics.Bitmap\nimport android.graphics.Canvas\nimport android.view.View\nimport android.view.ViewGroup\nimport android.widget.TextView\nimport com.shilapi.xcertplay.host.R\nimport java.io.File\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\nimport org.junit.runner.RunWith\nimport org.robolectric.Robolectric\nimport org.robolectric.RobolectricTestRunner\nimport org.robolectric.RuntimeEnvironment\nimport org.robolectric.annotation.Config\nimport org.robolectric.annotation.GraphicsMode\nimport org.robolectric.util.ReflectionHelpers\n\n@RunWith(RobolectricTestRunner::class)\n@GraphicsMode(GraphicsMode.Mode.NATIVE)\nclass DiConnectHomeScreenshotsTest {\n    @Test @Config(sdk = [29], qualifiers = "en-w320dp-h700dp")\n    fun compactHomeAtLargeFont() = capture("compact-en")\n\n    @Test @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")\n    fun fullHomeAtLargeFont() = capture("full-en")\n\n    @Test @Config(sdk = [29], qualifiers = "ar-ldrtl-w320dp-h700dp")\n    fun compactRtlHomeAtLargeFont() = capture("compact-ar")\n\n    private fun capture(name: String) {\n        RuntimeEnvironment.setFontScale(1.5f)\n        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()\n        val activity = controller.get()\n        try {\n            ReflectionHelpers.setField(activity, "page", "home")\n            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")\n            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)\n            val metrics = activity.resources.displayMetrics\n            val width = (activity.resources.configuration.screenWidthDp * metrics.density).toInt()\n            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),\n                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))\n            root.layout(0, 0, width, root.measuredHeight)\n            val aa = descendants(root).filterIsInstance<TextView>().single {\n                it.text.toString() == activity.getString(R.string.di_connect_android_auto)\n            }\n            assertTrue("Android Auto entry must be laid out", aa.width > 0 && aa.height > 0)\n            val layout = aa.layout\n            if (layout != null) for (line in 0 until layout.lineCount) {\n                assertEquals("Android Auto label must fit", 0, layout.getEllipsisCount(line))\n            }\n            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)\n            try {\n                root.draw(Canvas(image))\n                val file = File("build/reports/di-connect-home/$name.png")\n                file.parentFile.mkdirs()\n                file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }\n            } finally { image.recycle() }\n        } finally {\n            controller.pause().stop().destroy()\n            RuntimeEnvironment.setFontScale(1f)\n        }\n    }\n\n    private fun descendants(view: View): Sequence<View> = sequence {\n        yield(view)\n        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))\n    }\n}\n')

p=root/'common/src/main/java/com/shilapi/xcertplay/update/UpdateClient.kt';p.write_text(p.read_text().replace('repos/shihabal3amri/DiPlay/releases','repos/jollyboy95/di-connect/releases'))
# The donor's prebuilt APK belongs to D-Play; avoid presenting it as a Di-Connect build.
for name in ['DiPlay-GWM-H7-arm64-debug.apk','DiPlay-GWM-H7-arm64-debug.apk.sha256']:
    (root/name).unlink(missing_ok=True)
# All preview builds use the same committed debug signing key and application ID.
# CI supplies monotonically increasing seconds since 2026 as gwmRevision.
p=root/'mobile/build.gradle.kts';t=p.read_text().replace('versionName = "0.2.21"','versionName = "0.1.0"').replace('if (file("gwm-debug.keystore").isFile) signingConfig = signingConfigs.getByName("gwmDebug")','check(file("gwm-debug.keystore").isFile) { "Di-Connect update signing key is missing" }\n            signingConfig = signingConfigs.getByName("gwmDebug")').replace('"-hud-test" + (gwmRevision?.let { "-gwm.r$it" } ?: "")','"-preview" + (gwmRevision?.let { ".r$it" } ?: "")');p.write_text(t)
# Keep the normal build workflow with the combined source for subsequent code changes.
shutil.copy2(Path('target/_integration/build.yml'),root/'.github/workflows/build-di-connect.yml')
p=root/'docs/DI_CONNECT.md';p.write_text(p.read_text()+'''\n## Installing updates\n\nCI uses the committed GWM debug signing key, the stable com.jollyboy95.diconnect.debug package, and an increasing version code derived from the build timestamp. Install a newer CI APK over the existing Di-Connect installation to retain settings. The first install is separate from D-Play and DiAuto. Do not replace the debug key or change the package ID between updates. In-app update checking points to Di-Connect; private repository downloads still require GitHub access.\n''')
# The two JNI libraries export different package-qualified methods; both must be packaged.
p=dest/'src/main/cpp/CMakeLists.txt';t=p.read_text().replace('add_library(local_hotspot_radio SHARED','add_library(aa_local_hotspot_radio SHARED').replace('target_compile_options(local_hotspot_radio PRIVATE','target_compile_options(aa_local_hotspot_radio PRIVATE');p.write_text(t)
p=dest/'src/main/java/com/andrerinas/openheadunit/connection/LegacyHotspotRadio.kt';p.write_text(p.read_text().replace('System.loadLibrary("local_hotspot_radio")','System.loadLibrary("aa_local_hotspot_radio")'))
# The exception is only for the documented, public Android debug signing key.
p=root/'.gitignore';p.write_text(p.read_text()+'\n# Fixed public debug key for install-over updates.\n!/mobile/gwm-debug.keystore\n')
# Direct USB launches must use the same teardown gate as the home-screen connection button.
p=root/'common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt';t=p.read_text().replace('    private fun startCarPlay(size: DisplaySize) {','''    private fun startCarPlay(size: DisplaySize) {
        if (com.andrerinas.openheadunit.DiConnectEngine.service != null) {
            com.andrerinas.openheadunit.DiConnectEngine.stopThen {
                if (!isFinishing && !isDestroyed) startCarPlay(size)
            }
            return
        }''',1);p.write_text(t)
