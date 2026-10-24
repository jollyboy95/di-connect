from pathlib import Path
import shutil,re,sys,xml.etree.ElementTree as ET,json
root=Path('dplay'); src=Path('diauto'); dest=root/'android-auto'

# Targeted edits must fail loudly when the donor source moves. A silent no-op would still compile
# and quietly drop a CarPlay/Android Auto safety step (USB handover, teardown, hotspot).
def fail(message):
    sys.exit('integrate.py: '+message+'\nThe pinned donor source no longer matches this step; update the step, not the check.')

def first_line(text):
    return (text.strip().splitlines() or [''])[0][:90]

def edit(path,*pairs):
    """Apply (old, new[, expected_count]) replacements to one file, checking each match count."""
    p=Path(path); t=p.read_text()
    for pair in pairs:
        old,new=pair[0],pair[1]; expected=pair[2] if len(pair)>2 else 1
        found=t.count(old)
        if found!=expected: fail(f'{p}: expected {expected} match(es) of {first_line(old)!r}, found {found}')
        t=t.replace(old,new)
    p.write_text(t)

def edit_re(path,pattern,repl,expected=1,flags=0):
    p=Path(path); t,found=re.subn(pattern,repl,p.read_text(),flags=flags)
    if found!=expected: fail(f'{p}: expected {expected} match(es) of /{pattern}/, found {found}')
    p.write_text(t)

def require(condition,message):
    if not condition: fail(message)

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
require(len(names.get('string',()))>100 and len(names.get('layout',()))>10,f'Android Auto resource scan found too little to prefix: { {k:len(v) for k,v in names.items()} }')
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
seen=set()
for el in app:
    n=el.get(A+'name','')
    if n.startswith('.'): el.set(A+'name','com.andrerinas.openheadunit'+n)
    if el.tag=='activity' and n=='.main.MainActivity':
        seen.add(n)
        for child in list(el):
            if child.tag=='meta-data' and child.get(A+'name')=='android.app.shortcuts': el.remove(child)
        for child in list(el):
            if child.tag=='intent-filter': el.remove(child)
        el.set(A+'exported','false')
    if el.tag=='receiver' and n in ['.app.BootCompleteReceiver','.app.AutoStartReceiver','.app.WifiAutoStartReceiver','.connection.CarKeyReceiver','.app.RemoteControlReceiver']:
        seen.add(n); el.set(A+'enabled','false')
    if el.tag=='activity' and n in ['.app.UsbAttachedActivity','.main.AutomationActivity']:
        seen.add(n); el.set(A+'enabled','false')
    if el.tag=='provider' and n=='androidx.core.content.FileProvider':
        seen.add(n)
        el.set(A+'name','com.andrerinas.openheadunit.DiConnectFileProvider')
        el.set(A+'authorities','${applicationId}.aa.fileprovider')
# Every launcher, auto-start and USB-attach entry point must be found and disabled, or AA could
# start underneath a running CarPlay session.
missing={'.main.MainActivity','.app.BootCompleteReceiver','.app.AutoStartReceiver','.app.WifiAutoStartReceiver','.connection.CarKeyReceiver','.app.RemoteControlReceiver','.app.UsbAttachedActivity','.main.AutomationActivity','androidx.core.content.FileProvider'}-seen
require(not missing,f'Android Auto manifest entries not found: {sorted(missing)}')
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
renamed=0
for p in (dest/'src/main/java').rglob('*'):
    if p.suffix in ('.kt','.java'):
        t=p.read_text(); renamed+=t.count('${context.packageName}.fileprovider')
        p.write_text(t.replace('${context.packageName}.fileprovider','${context.packageName}.aa.fileprovider'))
require(renamed==2,f'expected 2 Android Auto FileProvider authorities to rename, found {renamed}')
leftover=[str(p) for p in (dest/'src/main/java').rglob('*.kt') if re.search(r'\.fileprovider"',p.read_text().replace('.aa.fileprovider"',''))]
require(not leftover,f'Android Auto FileProvider authority left unrenamed in {leftover}')
(dest/'src/main/java/com/andrerinas/openheadunit/DiConnectFileProvider.kt').write_text('package com.andrerinas.openheadunit\nclass DiConnectFileProvider : androidx.core.content.FileProvider()\n')
# AGP 9 provides Kotlin directly. Neither upstream annotation processor is used by its source.
original=(src/'app/build.gradle.kts').read_text(); deps=original[original.index('dependencies {'):]
require('"githubImplementation"' in deps and 'project(":contract")' in deps,'Android Auto dependency block no longer has githubImplementation/contract entries')
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
edit(root/'settings.gradle.kts',
    ('rootProject.name = "xcertplay"','rootProject.name = "di-connect"'),
    ('        mavenCentral()\n    }\n}','        mavenCentral()\n        maven { url = uri("https://jitpack.io") }\n    }\n}'))
p=root/'settings.gradle.kts'; p.write_text(p.read_text()+'\ninclude(":android-auto", ":aa-contract")\n')
edit(root/'common/build.gradle.kts',('    api(project(":shared"))','    api(project(":shared"))\n    implementation(project(":android-auto"))'))
edit(root/'mobile/build.gradle.kts',
    ('applicationId = "com.shihab.diplay"','applicationId = "com.jollyboy95.diconnect"'),
    ('applicationIdSuffix = ".hudtest"','applicationIdSuffix = ".debug"'))
edit(root/'common/src/main/AndroidManifest.xml',('<application>','<application android:name="com.andrerinas.openheadunit.App">'))
edit(root/'mobile/src/main/AndroidManifest.xml',('<application','<application\n        android:name="com.andrerinas.openheadunit.App"\n        tools:replace="android:label,android:icon,android:theme,android:localeConfig"'))
# Backend lifetime: CarPlay waits for full Android Auto service cleanup, bounded by a timeout.
AAP=dest/'src/main/java/com/andrerinas/openheadunit/aap/AapService.kt'
edit(AAP,
    ('override fun onCreate() {','override fun onCreate() {\n        com.andrerinas.openheadunit.DiConnectEngine.service = this'),
    ('        if (killProcessOnDestroy) {','        com.andrerinas.openheadunit.DiConnectEngine.onStopped()\n        if (false && killProcessOnDestroy) {'))
(dest/'src/main/java/com/andrerinas/openheadunit/StopGate.kt').write_text('''package com.andrerinas.openheadunit

import android.os.Handler

/**
 * Runs work once a running backend has stopped, but never waits longer than [timeoutMs].
 *
 * All calls must be made on [handler]'s thread. After a timeout the stuck backend is
 * abandoned: [needsStop] stays false for it, so a retry cannot wait on it again, while
 * [stopping] stays true until it really stops (or another backend replaces it).
 */
internal class StopGate<T : Any>(
    private val handler: Handler,
    private val timeoutMs: Long,
    private val requestStop: (T) -> Unit,
    private val onTimeout: (T) -> Unit = {},
) {
    var current: T? = null
        set(value) {
            if (value != null && value !== field) { abandoned = null; stopping = false }
            field = value
        }

    /** True from a stop request until the backend has actually stopped. */
    var stopping = false
        private set

    private var abandoned: T? = null
    private val waiting = mutableListOf<() -> Unit>()
    private val timeout = Runnable {
        current?.let { stuck -> abandoned = stuck; onTimeout(stuck) }
        release()
    }

    /** Whether new work must wait for the running backend to stop first. */
    fun needsStop(): Boolean = current.let { it != null && it !== abandoned }

    fun stopThen(completion: () -> Unit) {
        val running = current
        if (running == null || running === abandoned) { completion(); return }
        waiting.add(completion)
        if (stopping && waiting.size > 1) return // A stop is already under way.
        stopping = true
        handler.postDelayed(timeout, timeoutMs)
        requestStop(running)
    }

    fun onStopped() {
        current = null
        abandoned = null
        stopping = false
        release()
    }

    private fun release() {
        handler.removeCallbacks(timeout)
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { handler.post(it) }
    }
}
''')
(dest/'src/main/java/com/andrerinas/openheadunit/DiConnectEngine.kt').write_text('''package com.andrerinas.openheadunit

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.andrerinas.openheadunit.aap.AapService

/**
 * Serializes a switch back to CarPlay after AA has released audio and transports.
 *
 * The wait is bounded by [STOP_TIMEOUT_MS] so a stuck Android Auto shutdown cannot leave the
 * CarPlay button doing nothing. While the switch is under way AA leaves the hotspot up.
 */
object DiConnectEngine {
    private const val TAG = "DiConnectEngine"
    const val STOP_TIMEOUT_MS = 10_000L

    private val gate = StopGate<AapService>(
        Handler(Looper.getMainLooper()),
        STOP_TIMEOUT_MS,
        requestStop = { it.stopService(Intent(it, AapService::class.java)) },
        onTimeout = { Log.w(TAG, "Android Auto did not stop within $STOP_TIMEOUT_MS ms; starting CarPlay anyway") },
    )

    var service: AapService?
        get() = gate.current
        set(value) { gate.current = value }

    /** True while Android Auto is stopping so CarPlay can take over. */
    val handoverInProgress: Boolean get() = gate.stopping

    /** Whether CarPlay must wait for Android Auto before it starts. */
    fun needsStop(): Boolean = gate.needsStop()

    fun stopThen(completion: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        gate.stopThen(completion)
    }

    fun onStopped() = gate.onStopped()

    fun open(activity: Activity) {
        activity.startActivity(Intent(activity, com.andrerinas.openheadunit.main.MainActivity::class.java))
    }
}
''')
(dest/'src/test/java/com/andrerinas/openheadunit/StopGateTest.kt').write_text('''package com.andrerinas.openheadunit

import android.os.Handler
import android.os.Looper
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

// Same Robolectric setup as LocaleHelperTest: no network stack, so Conscrypt stays off.
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33], manifest = Config.NONE)
class StopGateTest {
    private class Backend
    private val stops = mutableListOf<Backend>()
    private val timeouts = mutableListOf<Backend>()
    private val gate = StopGate<Backend>(Handler(Looper.getMainLooper()), 10_000L,
        requestStop = { stops += it }, onTimeout = { timeouts += it })
    private val looper get() = shadowOf(Looper.getMainLooper())

    @Test fun runsAtOnceWhenNothingIsRunning() {
        var ran = false
        gate.stopThen { ran = true }
        assertTrue(ran)
        assertFalse(gate.stopping)
    }

    @Test fun waitsForTheBackendToStop() {
        gate.current = Backend()
        var ran = 0
        gate.stopThen { ran++ }
        looper.idleFor(Duration.ofSeconds(5))
        assertEquals(0, ran)
        assertTrue(gate.stopping)
        gate.onStopped()
        looper.idle()
        assertEquals(1, ran)
        assertFalse(gate.stopping)
        assertFalse(gate.needsStop())
    }

    @Test fun repeatedRequestsShareOneStop() {
        gate.current = Backend()
        var ran = 0
        gate.stopThen { ran++ }
        gate.stopThen { ran++ }
        assertEquals(1, stops.size)
        gate.onStopped()
        looper.idle()
        assertEquals(2, ran)
    }

    @Test fun timeoutRunsTheWorkAndDoesNotWaitOnTheStuckBackendAgain() {
        val stuck = Backend()
        gate.current = stuck
        var ran = 0
        gate.stopThen { ran++ }
        looper.idleFor(Duration.ofMillis(10_001))
        assertEquals(1, ran)
        assertEquals(listOf(stuck), timeouts)
        assertFalse("A retry must not wait on the stuck backend", gate.needsStop())
        assertTrue("Handover stays active until the backend really stops", gate.stopping)
        gate.stopThen { ran++ }
        assertEquals(2, ran)
        assertEquals(1, stops.size)
        gate.onStopped()
        assertFalse(gate.stopping)
    }

    @Test fun aNewBackendIsWaitedOnEvenAfterAnEarlierTimeout() {
        gate.current = Backend()
        gate.stopThen {}
        looper.idleFor(Duration.ofMillis(10_001))
        gate.current = Backend()
        assertTrue(gate.needsStop())
        assertFalse(gate.stopping)
    }
}
''')
D=root/'common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt'
edit(D,('    private fun connect(wireless: Boolean) {','''    private fun connect(wireless: Boolean) {
        if (com.andrerinas.openheadunit.DiConnectEngine.needsStop()) {
            com.andrerinas.openheadunit.DiConnectEngine.stopThen {
                if (!isFinishing && !isDestroyed) connect(wireless)
            }
            return
        }'''))
needle='        card.addView(connectButton, matchButton(height = if (compact) 54 else 68))'
edit(D,(needle,needle+'''
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
'''))
# Reuse existing D-Play button geometry and keep connection copy localised.
STRINGS=sorted((root/'common/src/main/res').glob('values*/strings.xml'))
require(root/'common/src/main/res/values/strings.xml' in STRINGS,'D-Play base strings.xml not found')
for p in STRINGS:
    edit(p,('</resources>','    <string name="di_connect_android_auto">Android Auto</string>\n</resources>'))
for p in STRINGS:
    edit_re(p,r'(<string name="app_name">).*?(</string>)',r'\g<1>Di-Connect\2',expected=1 if p.parent.name=='values' else p.read_text().count('<string name="app_name">'))
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
edit(dest/'src/main/java/com/andrerinas/openheadunit/utils/DialogUtils.kt',
    ('openheadunit.R.style.DarkAlertDialog','openheadunit.R.style.aa_DarkAlertDialog'),
    ('openheadunit.R.string.reset','openheadunit.R.string.aa_reset'))
edit(root/'mobile/src/main/res/values/strings.xml',('>DiPlay<','>Di-Connect<'))
# Internal navigation permission follows the combined package in debug and release builds.
edit(dest/'src/main/java/com/andrerinas/openheadunit/aap/AapNavigationHelper.kt',
    ('com.andrerinas.openheadunit.BuildConfig.APPLICATION_ID + ".permission.NAVIGATION_UPDATE"','context.packageName + ".permission.NAVIGATION_UPDATE"'),
    ('else NavigationUpdateIntent.BROADCAST_PERMISSION','else context.packageName + ".permission.NAVIGATION_UPDATE"'))
edit(dest/'src/main/AndroidManifest.xml',('android:name="com.andrerinas.openheadunit.permission.NAVIGATION_UPDATE"','android:name="${applicationId}.permission.NAVIGATION_UPDATE"'))
# Android Auto initialization is deferred so its theme and radio setup do not run on CarPlay launch.
edit(dest/'src/main/java/com/andrerinas/openheadunit/App.kt',('        instance = this\n','        instance = this\n    }\n\n    private var androidAutoInitialized = false\n\n    fun initializeAndroidAuto() {\n        if (androidAutoInitialized) return\n        androidAutoInitialized = true\n'))
edit(dest/'src/main/java/com/andrerinas/openheadunit/DiConnectEngine.kt',('    fun open(activity: Activity) {','    fun open(activity: Activity) {\n        App.get(activity).initializeAndroidAuto()'))
edit(AAP,('        com.andrerinas.openheadunit.DiConnectEngine.service = this\n        super.onCreate()','        super.onCreate()\n        App.get(this).initializeAndroidAuto()\n        com.andrerinas.openheadunit.DiConnectEngine.service = this'))
edit(dest/'src/main/java/com/andrerinas/openheadunit/main/MainActivity.kt',('    override fun onCreate(savedInstanceState: Bundle?) {','    override fun onCreate(savedInstanceState: Bundle?) {\n        App.get(this).initializeAndroidAuto()'))
# Present the combined project clearly while retaining the complete upstream overview.
p=root/'README.md'; original=p.read_text(); p.write_text('''# Di-Connect

CarPlay and Android Auto for the GWM/Haval H7, built on D-Play GWM's interface.

This branch is an integration preview. The Android Auto connection engine is included in the same APK, with its existing setup screens. Compilation checks and physical vehicle testing must pass before this is a release.

See [integration notes](docs/DI_CONNECT.md) for donor revisions, build details and current limitations. The original D-Play overview follows below; its upstream download links point to DiPlay, not to a Di-Connect release.

'''+original)
shutil.copy2(root/'LICENSE',root/'docs/licenses/DiPlay-GPL-3.0.txt')
shutil.copy2(src/'LICENSE',root/'LICENSE')

(root/'common/src/test/java/com/shilapi/xcertplay/DiConnectHomeScreenshotsTest.kt').write_text('package com.shilapi.xcertplay\n\nimport android.graphics.Bitmap\nimport android.graphics.Canvas\nimport android.view.View\nimport android.view.ViewGroup\nimport android.widget.TextView\nimport com.shilapi.xcertplay.host.R\nimport java.io.File\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\nimport org.junit.runner.RunWith\nimport org.robolectric.Robolectric\nimport org.robolectric.RobolectricTestRunner\nimport org.robolectric.RuntimeEnvironment\nimport org.robolectric.annotation.Config\nimport org.robolectric.annotation.GraphicsMode\nimport org.robolectric.util.ReflectionHelpers\n\n@RunWith(RobolectricTestRunner::class)\n@GraphicsMode(GraphicsMode.Mode.NATIVE)\nclass DiConnectHomeScreenshotsTest {\n    @Test @Config(sdk = [29], qualifiers = "en-w320dp-h700dp")\n    fun compactHomeAtLargeFont() = capture("compact-en")\n\n    @Test @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")\n    fun fullHomeAtLargeFont() = capture("full-en")\n\n    @Test @Config(sdk = [29], qualifiers = "ar-ldrtl-w320dp-h700dp")\n    fun compactRtlHomeAtLargeFont() = capture("compact-ar")\n\n    private fun capture(name: String) {\n        RuntimeEnvironment.setFontScale(1.5f)\n        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()\n        val activity = controller.get()\n        try {\n            ReflectionHelpers.setField(activity, "page", "home")\n            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")\n            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)\n            val metrics = activity.resources.displayMetrics\n            val width = (activity.resources.configuration.screenWidthDp * metrics.density).toInt()\n            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),\n                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))\n            root.layout(0, 0, width, root.measuredHeight)\n            val aa = descendants(root).filterIsInstance<TextView>().single {\n                it.text.toString() == activity.getString(R.string.di_connect_android_auto)\n            }\n            assertTrue("Android Auto entry must be laid out", aa.width > 0 && aa.height > 0)\n            val layout = aa.layout\n            if (layout != null) for (line in 0 until layout.lineCount) {\n                assertEquals("Android Auto label must fit", 0, layout.getEllipsisCount(line))\n            }\n            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)\n            try {\n                root.draw(Canvas(image))\n                val file = File("build/reports/di-connect-home/$name.png")\n                file.parentFile.mkdirs()\n                file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }\n            } finally { image.recycle() }\n        } finally {\n            controller.pause().stop().destroy()\n            RuntimeEnvironment.setFontScale(1f)\n        }\n    }\n\n    private fun descendants(view: View): Sequence<View> = sequence {\n        yield(view)\n        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))\n    }\n}\n')

edit(root/'common/src/main/java/com/shilapi/xcertplay/update/UpdateClient.kt',('repos/shihabal3amri/DiPlay/releases','repos/jollyboy95/di-connect/releases'))
# The donor's prebuilt APK belongs to D-Play; avoid presenting it as a Di-Connect build.
for name in ['DiPlay-GWM-H7-arm64-debug.apk','DiPlay-GWM-H7-arm64-debug.apk.sha256']:
    (root/name).unlink(missing_ok=True)
# All preview builds use the same committed debug signing key and application ID.
# CI supplies monotonically increasing seconds since 2026 as gwmRevision.
edit(root/'mobile/build.gradle.kts',
    ('versionName = "0.2.21"','versionName = "0.1.0"'),
    ('if (file("gwm-debug.keystore").isFile) signingConfig = signingConfigs.getByName("gwmDebug")','check(file("gwm-debug.keystore").isFile) { "Di-Connect update signing key is missing" }\n            signingConfig = signingConfigs.getByName("gwmDebug")'),
    ('"-hud-test" + (gwmRevision?.let { "-gwm.r$it" } ?: "")','"-preview" + (gwmRevision?.let { ".r$it" } ?: "")'))
# Keep the normal build workflow with the combined source for subsequent code changes.
shutil.copy2(Path('target/_integration/build.yml'),root/'.github/workflows/build-di-connect.yml')
p=root/'docs/DI_CONNECT.md';p.write_text(p.read_text()+'''\n## Installing updates\n\nCI uses the committed GWM debug signing key, the stable com.jollyboy95.diconnect.debug package, and an increasing version code derived from the build timestamp. Install a newer CI APK over the existing Di-Connect installation to retain settings. The first install is separate from D-Play and DiAuto. Do not replace the debug key or change the package ID between updates. In-app update checking points to Di-Connect; private repository downloads still require GitHub access.\n''')
# The two JNI libraries export different package-qualified methods; both must be packaged.
edit(dest/'src/main/cpp/CMakeLists.txt',
    ('add_library(local_hotspot_radio SHARED','add_library(aa_local_hotspot_radio SHARED'),
    ('target_compile_options(local_hotspot_radio PRIVATE','target_compile_options(aa_local_hotspot_radio PRIVATE'))
edit(dest/'src/main/java/com/andrerinas/openheadunit/connection/LegacyHotspotRadio.kt',('System.loadLibrary("local_hotspot_radio")','System.loadLibrary("aa_local_hotspot_radio")'))
# The committed GWM development key is deliberately shared so preview APKs update in place.
# It is not the Android SDK's stock debug key and must never sign a public production release.
p=root/'.gitignore';p.write_text(p.read_text()+'\n# Deliberately committed GWM development key for install-over preview updates (not for production releases).\n!/mobile/gwm-debug.keystore\n')
# Direct USB launches must use the same teardown gate as the home-screen connection button.
edit(root/'common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt',('    private fun startCarPlay(size: DisplaySize) {','''    private fun startCarPlay(size: DisplaySize) {
        if (com.andrerinas.openheadunit.DiConnectEngine.needsStop()) {
            com.andrerinas.openheadunit.DiConnectEngine.stopThen {
                if (!isFinishing && !isDestroyed) startCarPlay(size)
            }
            return
        }'''))
# Brand the shared shell without changing upstream credits or internal identifiers.
for p in STRINGS:
    edit_re(p,r'(<string name="diplay">).*?(</string>)',r'\g<1>Di-Connect\2',expected=1 if p.parent.name=='values' else p.read_text().count('<string name="diplay">'))
needle='            setPadding(if (compact) dp(8) else dp(12), 0, 0, 0)'
edit(D,(needle,needle+'\n            // In compact headers the app icon carries the identity; reserve the flexible gap.\n            if (compact) visibility = View.INVISIBLE'))
# The mobile combined app starts at the shared home; each protocol owns its setup flow.
edit(root/'mobile/src/main/AndroidManifest.xml',('        <!-- Based on the split-screen metadata','        <meta-data android:name="di_connect_entry_point" android:value="true" />\n\n        <!-- Based on the split-screen metadata'))
edit(D,
    ('?: if (setupError == null && SetupGuide.shouldOpenOnLaunch','?: if (diConnectEntryEnabled()) "home" else if (setupError == null && SetupGuide.shouldOpenOnLaunch'),
    ('''            if (CarPlayBackgroundSession.hasSession()) openProjection()
            else connect(true)''','''            if (CarPlayBackgroundSession.hasSession()) openProjection()
            else if (diConnectEntryEnabled() && !SetupGuide.seen(this)) { page = "setup"; render() }
            else connect(true)'''),
    ('    private fun home(content: LinearLayout) {','''    private fun diConnectEntryEnabled(): Boolean = runCatching {
        packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            .metaData?.getBoolean("di_connect_entry_point", false) == true
    }.getOrDefault(false)

    private fun home(content: LinearLayout) {'''))
# Android Auto's "kill on disconnect" closed every task in the app. In Di-Connect that includes
# CarPlay, the D-Play home and the cluster window, so only AA's own screens are closed: the
# ACTION_FINISH_ACTIVITIES broadcast just above already does that, and the service is stopped.
edit(dest/'src/main/java/com/andrerinas/openheadunit/connection/CommManager.kt',('''                // Finish all tasks (API 21+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    activityManager.appTasks.forEach { it.finishAndRemoveTask() }
                }
''','''                // Di-Connect: CarPlay, the home screen and the cluster window share this app's
                // tasks. The ACTION_FINISH_ACTIVITIES broadcast above closes the Android Auto
                // screens, so the shared tasks are left running.
''',2))
# AA turns its auto-enabled hotspot off when its service stops. While handing over to CarPlay the
# hotspot is left up so a wireless CarPlay start does not lose the network it is about to use.
edit(AAP,('        if (App.provide(this).settings.autoEnableHotspot &&','        if (!com.andrerinas.openheadunit.DiConnectEngine.handoverInProgress &&\n            App.provide(this).settings.autoEnableHotspot &&'))
p=root/'docs/DI_CONNECT.md';p.write_text(p.read_text()+'''\n## Switching between CarPlay and Android Auto\n\n- Starting CarPlay waits for the Android Auto service to finish stopping, for at most 10 seconds. After that CarPlay starts anyway and the log notes the timeout.\n- While Android Auto stops for CarPlay, it leaves its auto-enabled hotspot on so wireless CarPlay can use it.\n- Android Auto's "kill on disconnect" setting closes only the Android Auto screens and service. It no longer closes CarPlay, the home screen or the cluster window.\n''')
