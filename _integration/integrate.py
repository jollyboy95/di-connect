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
# DiAuto-GWM's third-party notices (including the D-Play GWM code it ports) and its H7 notes.
(dest/'docs').mkdir(exist_ok=True)
for name in ['THIRD_PARTY.md','GWM_H7.md']:
    shutil.copy2(src/'docs'/name,dest/'docs'/name)
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
# DiAuto-GWM's own lint policy, carried over verbatim: abortOnError off, and with -PgwmLintFast only
# the correctness checks it chose because the full pass overruns a hosted runner.
li=original.index('    lint {'); depth=0
for i in range(li,len(original)):
    if original[i]=='{': depth+=1
    elif original[i]=='}':
        depth-=1
        if depth==0: lint_block=original[li:i+1]; break
require('abortOnError = false' in lint_block and 'gwmLintFast' in lint_block,'DiAuto-GWM lint block no longer has abortOnError=false and the gwmLintFast check set')
# Android Auto uses D-Play's GWM adapter module instead of its own copy of the same org.dplay.gwm
# classes; two copies in one APK are duplicate classes.
dup=dest/'src/main/java/org/dplay/gwm'
require(sorted(p.name for p in dup.iterdir())==['GwmAdapterConfig.java','GwmDisplayAdapter.java','GwmDisplayTestView.java'],
    f'DiAuto-GWM org.dplay.gwm copies changed: {sorted(p.name for p in dup.iterdir())}')
shutil.rmtree(dest/'src/main/java/org')
deps=deps.replace('dependencies {','dependencies {\n    implementation(project(":gwm-adapter"))',1)
# DiAuto-GWM builds the GWM test view from its own package, so D-Play's must be public too.
edit(root/'gwm-adapter/src/main/java/org/dplay/gwm/GwmDisplayTestView.java',
    ('final class GwmDisplayTestView extends View {','public final class GwmDisplayTestView extends View {'),
    ('    GwmDisplayTestView(Context context) {','    public GwmDisplayTestView(Context context) {'))
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
'''+lint_block+'''
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
edit(root/'common/src/main/AndroidManifest.xml',('<application>','<application android:name="com.andrerinas.openheadunit.App" tools:ignore="MissingClass">'))
edit(root/'mobile/src/main/AndroidManifest.xml',('<application','<application\n        android:name="com.andrerinas.openheadunit.App"\n        tools:ignore="MissingClass"\n        tools:replace="android:label,android:icon,android:theme,android:localeConfig"'))
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
        onTimeout = {
            Log.w(TAG, "Android Auto did not stop within $STOP_TIMEOUT_MS ms; starting CarPlay anyway")
            clearClusterWindows("Android Auto stop timed out; CarPlay taking over")
        },
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

    fun onStopped() {
        clearClusterWindows("Android Auto service stopped")
        gate.onStopped()
    }

    /** Android Auto's GWM cluster and HUD windows must be gone before CarPlay draws there. */
    private fun clearClusterWindows(reason: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            com.andrerinas.openheadunit.gwm.GwmOutputs.stop(reason)
        }
    }

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
# Android Auto is the main protocol: its button comes first and is primary; CarPlay is the backup.
edit(D,(needle,'''        androidAutoButton = homeButton(getString(R.string.di_connect_connect_android_auto), true) { connectAndroidAuto() }
        card.addView(androidAutoButton, matchButton(height = if (compact) 54 else 68))
        card.addView(connectButton, matchButton(CONTROL_GAP_DP, if (compact) 54 else 68))'''),
    ('        connectButton = homeButton(getString(R.string.connect_phone), true) {','        connectButton = homeButton(getString(R.string.connect_phone), false) {'),
    ('        homeCardTitle = label(getString(R.string.wireless_carplay), 12, ACCENT, true)','        homeCardTitle = label(getString(R.string.di_connect_home_title), 12, ACCENT, true)'))
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
- Android Auto source: jollyboy95/DiAuto-GWM at 4febfe1568faab5bf6899575b31e1c708298759f (gwm-h7-hud-fit, the r16 DiAuto GWM build), including its GWM/Haval H7 port. It uses D-Play's gwm-adapter module rather than its own copy.
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

(root/'common/src/test/java/com/shilapi/xcertplay/DiConnectHomeScreenshotsTest.kt').write_text('package com.shilapi.xcertplay\n\nimport android.graphics.Bitmap\nimport android.graphics.Canvas\nimport android.view.View\nimport android.view.ViewGroup\nimport android.widget.TextView\nimport com.shilapi.xcertplay.host.R\nimport java.io.File\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\nimport org.junit.runner.RunWith\nimport org.robolectric.Robolectric\nimport org.robolectric.RobolectricTestRunner\nimport org.robolectric.RuntimeEnvironment\nimport org.robolectric.annotation.Config\nimport org.robolectric.annotation.GraphicsMode\nimport org.robolectric.util.ReflectionHelpers\n\n@RunWith(RobolectricTestRunner::class)\n@GraphicsMode(GraphicsMode.Mode.NATIVE)\nclass DiConnectHomeScreenshotsTest {\n    @Test @Config(sdk = [29], qualifiers = "en-w320dp-h700dp")\n    fun compactHomeAtLargeFont() = capture("compact-en")\n\n    @Test @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")\n    fun fullHomeAtLargeFont() = capture("full-en")\n\n    @Test @Config(sdk = [29], qualifiers = "ar-ldrtl-w320dp-h700dp")\n    fun compactRtlHomeAtLargeFont() = capture("compact-ar")\n\n    private fun capture(name: String) {\n        RuntimeEnvironment.setFontScale(1.5f)\n        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()\n        val activity = controller.get()\n        try {\n            ReflectionHelpers.setField(activity, "page", "home")\n            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")\n            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)\n            val metrics = activity.resources.displayMetrics\n            val width = (activity.resources.configuration.screenWidthDp * metrics.density).toInt()\n            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),\n                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))\n            root.layout(0, 0, width, root.measuredHeight)\n            val aa = descendants(root).filterIsInstance<TextView>().single {\n                it.text.toString() == activity.getString(R.string.di_connect_connect_android_auto)\n            }\n            assertTrue("Android Auto entry must be laid out", aa.width > 0 && aa.height > 0)\n            val layout = aa.layout\n            if (layout != null) for (line in 0 until layout.lineCount) {\n                assertEquals("Android Auto label must fit", 0, layout.getEllipsisCount(line))\n            }\n            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)\n            try {\n                root.draw(Canvas(image))\n                val file = File("build/reports/di-connect-home/$name.png")\n                file.parentFile.mkdirs()\n                file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }\n            } finally { image.recycle() }\n        } finally {\n            controller.pause().stop().destroy()\n            RuntimeEnvironment.setFontScale(1f)\n        }\n    }\n\n    private fun descendants(view: View): Sequence<View> = sequence {\n        yield(view)\n        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))\n    }\n}\n')

# Updates come from the public APK-only releases repository; the source repository stays private, and a
# private repository's releases cannot be read by the car without a stored GitHub credential.
edit(root/'common/src/main/java/com/shilapi/xcertplay/update/UpdateClient.kt',('repos/shihabal3amri/DiPlay/releases','repos/jollyboy95/di-connect-releases/releases'))
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

# Di-Connect identity: an original mark (two linked rings, one per connection type), not either
# platform's logo. The launcher gets an adaptive icon with a themed-icon layer; the app header and
# home card get a round badge. CarPlay's own connecting screen keeps the CarPlay icon.
RINGS = {
    'blue': 'M45,38 A16,16 0 1 1 45,70 A16,16 0 1 1 45,38',
    'green': 'M63,38 A16,16 0 1 1 63,70 A16,16 0 1 1 63,38',
    # Blue passes over green inside this disc, so the rings read as linked.
    'over': 'M46.5,40.77 A7.5,7.5 0 1 1 61.5,40.77 A7.5,7.5 0 1 1 46.5,40.77 Z',
}
def rings_xml(blue,green,indent='    '):
    s=f'{indent}<path android:pathData="{{p}}" android:strokeColor="{{c}}" android:strokeWidth="7.5" android:fillColor="#00000000"/>\n'
    return (s.format(p=RINGS['blue'],c=blue)+s.format(p=RINGS['green'],c=green)+
        f'{indent}<group>\n{indent}    <clip-path android:pathData="{RINGS["over"]}"/>\n'+
        s.format(p=RINGS['blue'],c=blue).replace(indent,indent+'    ',1)+f'{indent}</group>\n')
RES=root/'common/src/main/res'
VEC='<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="108dp" android:height="108dp"\n    android:viewportWidth="108" android:viewportHeight="108">\n'
(RES/'drawable/ic_di_connect_foreground.xml').write_text(VEC+rings_xml('#FF3D8BFF','#FF2FD07F')+'</vector>\n')
(RES/'drawable/ic_di_connect_monochrome.xml').write_text(VEC+rings_xml('#FF000000','#FF000000')+'</vector>\n')
(RES/'drawable/ic_di_connect_background.xml').write_text('''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="38" android:centerY="32"
                android:gradientRadius="97" android:startColor="#FF1B2A44" android:endColor="#FF0E1726"/>
        </aapt:attr>
    </path>
</vector>
''')
(RES/'drawable-anydpi-v26').mkdir(exist_ok=True)
(RES/'drawable-anydpi-v26/ic_di_connect.xml').write_text('''<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_di_connect_background"/>
    <foreground android:drawable="@drawable/ic_di_connect_foreground"/>
    <monochrome android:drawable="@drawable/ic_di_connect_monochrome"/>
</adaptive-icon>
''')
BADGE=VEC+'    <path android:pathData="M54,0 A54,54 0 1 1 54,108 A54,54 0 1 1 54,0 Z" android:fillColor="#FF13203A"/>\n    <group android:pivotX="54" android:pivotY="54" android:scaleX="1.3" android:scaleY="1.3">\n'+rings_xml('#FF3D8BFF','#FF2FD07F','        ')+'    </group>\n</vector>\n'
(RES/'drawable/ic_di_connect.xml').write_text(BADGE)        # launcher fallback before Android 8
(RES/'drawable/ic_di_connect_badge.xml').write_text(BADGE)  # in-app logo on every version
edit(root/'mobile/src/main/AndroidManifest.xml',
    ('android:icon="@drawable/ic_carplay"','android:icon="@drawable/ic_di_connect"'),
    ('android:roundIcon="@drawable/ic_carplay"','android:roundIcon="@drawable/ic_di_connect"'))
edit(D,
    ('''            setImageResource(R.drawable.ic_carplay)
            contentDescription = getString(R.string.carplay)
        }, LinearLayout.LayoutParams(logoSize, logoSize))''','''            setImageResource(R.drawable.ic_di_connect_badge)
            contentDescription = getString(R.string.diplay)
        }, LinearLayout.LayoutParams(logoSize, logoSize))'''),
    ('''            setImageResource(R.drawable.ic_carplay)
            contentDescription = getString(R.string.carplay_icon)''','''            setImageResource(R.drawable.ic_di_connect_badge)
            contentDescription = getString(R.string.diplay)'''))

# One set of GWM settings. Android Auto's GWM cluster/HUD settings and DiAuto's cluster-map switch
# read and write D-Play's own settings (the same file, in the same process), so the cluster, HUD and
# music card are set once for both protocols. Settings only Android Auto has (HUD arrow area, lower
# strip) stay in Android Auto's store, and its directions card gets its own Di-Connect key, off by
# default because Google Maps draws a card into the cluster video that cannot be switched off.
PERSIST=root/'common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt'
SHARED_KEYS={'CLUSTER_MAP':'cluster_map_enabled','GWM_CLUSTER_OVERLAY_ENABLED':'gwm_cluster_overlay_enabled',
    'GWM_CLUSTER_DISPLAY_ID':'gwm_cluster_display_id','GWM_FORCED':'gwm_forced','GWM_CLUSTER_STYLE':'gwm_cluster_style',
    'GWM_MUSIC_CARD':'gwm_music_card','GWM_HUD_ENABLED':'gwm_hud_enabled','GWM_HUD_DISPLAY_ID':'gwm_hud_display_id',
    'GWM_HUD_ANCHOR':'gwm_hud_anchor'}
persist=PERSIST.read_text()
require('private const val PREFS = "xcertplay_airplay"' in persist,'D-Play settings file name changed')
for const,key in SHARED_KEYS.items():
    require(f'KEY_{const} = "{key}"' in persist,f'D-Play setting KEY_{const} is no longer "{key}"')
(dest/'src/main/java/com/andrerinas/openheadunit/DiConnectSettings.kt').write_text('''package com.andrerinas.openheadunit

import android.content.Context
import android.content.SharedPreferences

/**
 * D-Play's settings store, shared with Android Auto so both protocols are set up in one place.
 * The keys are D-Play's own (AirPlayPersistence); the integration script fails if they change.
 */
internal object DiConnectSettings {
    private const val PREFS = "xcertplay_airplay"
'''+''.join(f'    const val {c} = "{k}"\n' for c,k in SHARED_KEYS.items())+'''    /** Android Auto only: Di-Connect's directions card. Off: Google Maps draws its own card. */
    const val AA_NAV_CARD = "di_connect_aa_nav_card"
    const val DEFAULT_CLUSTER_DISPLAY_ID = 2
    const val DEFAULT_HUD_DISPLAY_ID = 3

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun bool(context: Context, key: String, default: Boolean) = prefs(context).getBoolean(key, default)
    fun setBool(context: Context, key: String, value: Boolean) = prefs(context).edit().putBoolean(key, value).apply()
    fun int(context: Context, key: String, default: Int) = prefs(context).getInt(key, default)
    fun setInt(context: Context, key: String, value: Int) = prefs(context).edit().putInt(key, value).apply()

    /** A display id above the main screen's, or [default]. */
    fun displayId(context: Context, key: String, default: Int) =
        int(context, key, -1).takeIf { it > android.view.Display.DEFAULT_DISPLAY } ?: default
}
''')
G=dest/'src/main/java/com/andrerinas/openheadunit/gwm/GwmSettings.kt'
S_='com.andrerinas.openheadunit.DiConnectSettings'
edit(G,
    ('''    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
''','''    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    // Di-Connect: the settings shared with CarPlay live in D-Play's store (DiConnectSettings).
    private val app: Context = context.applicationContext
'''),
    ('''        get() = prefs.getBoolean("forced", false)
        set(value) = prefs.edit().putBoolean("forced", value).apply()''',
     f'''        get() = {S_}.bool(app, {S_}.GWM_FORCED, false)
        set(value) = {S_}.setBool(app, {S_}.GWM_FORCED, value)'''),
    ('''        get() = prefs.getBoolean("cluster-enabled", false)
        set(value) = prefs.edit().putBoolean("cluster-enabled", value).apply()''',
     f'''        get() = {S_}.bool(app, {S_}.CLUSTER_MAP, false) && {S_}.bool(app, {S_}.GWM_CLUSTER_OVERLAY_ENABLED, false)
        set(value) {{
            {S_}.setBool(app, {S_}.GWM_CLUSTER_OVERLAY_ENABLED, value)
            if (value) {S_}.setBool(app, {S_}.CLUSTER_MAP, true)
        }}'''),
    ('''        get() = prefs.getInt("cluster-display-id", DEFAULT_CLUSTER_DISPLAY_ID)
        set(value) = prefs.edit().putInt("cluster-display-id", value).apply()''',
     f'''        get() = {S_}.displayId(app, {S_}.GWM_CLUSTER_DISPLAY_ID, DEFAULT_CLUSTER_DISPLAY_ID)
        set(value) = {S_}.setInt(app, {S_}.GWM_CLUSTER_DISPLAY_ID, value)'''),
    ('''        get() = prefs.getBoolean("styled-layer", true)
        set(value) = prefs.edit().putBoolean("styled-layer", value).apply()''',
     f'''        get() = {S_}.bool(app, {S_}.GWM_CLUSTER_STYLE, true)
        set(value) = {S_}.setBool(app, {S_}.GWM_CLUSTER_STYLE, value)'''),
    ('''        get() = prefs.getBoolean("nav-card", false)
        set(value) = prefs.edit().putBoolean("nav-card", value).apply()''',
     f'''        get() = {S_}.bool(app, {S_}.AA_NAV_CARD, false)
        set(value) = {S_}.setBool(app, {S_}.AA_NAV_CARD, value)'''),
    ('''        get() = prefs.getBoolean("music-card", true)
        set(value) = prefs.edit().putBoolean("music-card", value).apply()''',
     f'''        get() = {S_}.bool(app, {S_}.GWM_MUSIC_CARD, true)
        set(value) = {S_}.setBool(app, {S_}.GWM_MUSIC_CARD, value)'''),
    ('''        get() = prefs.getBoolean("hud-enabled", false)
        set(value) = prefs.edit().putBoolean("hud-enabled", value).apply()''',
     f'''        get() = {S_}.bool(app, {S_}.GWM_HUD_ENABLED, false)
        set(value) = {S_}.setBool(app, {S_}.GWM_HUD_ENABLED, value)'''),
    ('''        get() = prefs.getInt("hud-display-id", DEFAULT_HUD_DISPLAY_ID)
        set(value) = prefs.edit().putInt("hud-display-id", value).apply()''',
     f'''        get() = {S_}.displayId(app, {S_}.GWM_HUD_DISPLAY_ID, DEFAULT_HUD_DISPLAY_ID)
        set(value) = {S_}.setInt(app, {S_}.GWM_HUD_DISPLAY_ID, value)'''),
    ('''        get() = prefs.getInt("hud-anchor", 0)
        set(value) = prefs.edit().putInt("hud-anchor", value).apply()''',
     f'''        get() = {S_}.int(app, {S_}.GWM_HUD_ANCHOR, 0).coerceIn(0, 2)
        set(value) = {S_}.setInt(app, {S_}.GWM_HUD_ANCHOR, value.coerceIn(0, 2))'''))
edit(dest/'src/main/java/com/andrerinas/openheadunit/utils/Settings.kt',
    ('''        get() = prefs.getBoolean("cluster-map-enabled", false)
        set(value) { prefs.edit().putBoolean("cluster-map-enabled", value).apply() }''',
     '''        // Di-Connect: one cluster-map switch for CarPlay and Android Auto, kept in D-Play's store.
        get() = com.andrerinas.openheadunit.DiConnectSettings.bool(context, com.andrerinas.openheadunit.DiConnectSettings.CLUSTER_MAP, false)
        set(value) { com.andrerinas.openheadunit.DiConnectSettings.setBool(context, com.andrerinas.openheadunit.DiConnectSettings.CLUSTER_MAP, value) }'''))
# D-Play settings: Android Auto's directions card, beside the GWM music card. CarPlay is unchanged.
edit(PERSIST,('''    fun loadGwmMusicCard(context: Context): Boolean =''','''    /** Di-Connect: Android Auto's own directions card on the GWM cluster (Google Maps draws one too). */
    fun loadAndroidAutoNavCard(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DI_CONNECT_AA_NAV_CARD, false)

    fun saveAndroidAutoNavCard(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DI_CONNECT_AA_NAV_CARD, enabled).apply()
    }

    fun loadGwmMusicCard(context: Context): Boolean ='''),
    ('''    private const val KEY_GWM_MUSIC_CARD = "gwm_music_card"''','''    private const val KEY_GWM_MUSIC_CARD = "gwm_music_card"
    /** Shared with Android Auto (DiConnectSettings.AA_NAV_CARD). */
    private const val KEY_DI_CONNECT_AA_NAV_CARD = "di_connect_aa_nav_card"'''))
edit(D,('''            toggle(card, getString(R.string.settings_gwm_music_card), getString(R.string.settings_gwm_music_card_description),
                AirPlayPersistence.loadGwmMusicCard(this)) { AirPlayPersistence.saveGwmMusicCard(this, it) }''','''            toggle(card, getString(R.string.settings_gwm_music_card), getString(R.string.settings_gwm_music_card_description),
                AirPlayPersistence.loadGwmMusicCard(this)) { AirPlayPersistence.saveGwmMusicCard(this, it) }
            toggle(card, getString(R.string.settings_di_connect_aa_nav_card), getString(R.string.settings_di_connect_aa_nav_card_description),
                AirPlayPersistence.loadAndroidAutoNavCard(this)) { AirPlayPersistence.saveAndroidAutoNavCard(this, it) }'''))
AA_NAV_CARD_TEXT={
    'values':('Directions card in Android Auto',"Google Maps already shows its own directions card on the cluster. Turn on to add Di-Connect\\'s card as well. CarPlay always shows it. Applies the next time Android Auto connects."),
    'values-ar':('بطاقة الاتجاهات في Android Auto','تعرض خرائط Google بطاقة الاتجاهات الخاصة بها على لوحة العدادات. شغّل هذا الخيار لإضافة بطاقة Di-Connect أيضًا. تعرضها CarPlay دائمًا. يُطبَّق عند اتصال Android Auto في المرة القادمة.'),
    'values-es':('Tarjeta de indicaciones en Android Auto','Google Maps ya muestra su propia tarjeta de indicaciones en el cuadro de instrumentos. Actívalo para añadir también la tarjeta de Di-Connect. CarPlay siempre la muestra. Se aplica la próxima vez que se conecte Android Auto.'),
    'values-it':('Scheda indicazioni in Android Auto',"Google Maps mostra già la propria scheda di indicazioni sul quadro strumenti. Attiva per aggiungere anche la scheda di Di-Connect. CarPlay la mostra sempre. Si applica alla prossima connessione di Android Auto."),
    'values-ja':('Android Auto の案内カード','Google マップはメーターに独自の案内カードを表示します。オンにすると Di-Connect のカードも表示します。CarPlay では常に表示されます。次に Android Auto が接続したときに適用されます。'),
    'values-ru':('Карточка маршрута в Android Auto','Google Карты уже показывают свою карточку маршрута на приборной панели. Включите, чтобы добавить карточку Di-Connect. В CarPlay она показывается всегда. Применяется при следующем подключении Android Auto.'),
    'values-uk':('Картка маршруту в Android Auto','Google Карти вже показують власну картку маршруту на приладовій панелі. Увімкніть, щоб додати картку Di-Connect. У CarPlay вона показується завжди. Застосовується під час наступного підключення Android Auto.'),
    'values-zh-rCN':('Android Auto 中的导航卡片','Google 地图已在仪表盘上显示自己的导航卡片。开启后会同时显示 Di-Connect 的卡片。CarPlay 始终显示。下次连接 Android Auto 时生效。'),
    'values-zh-rTW':('Android Auto 中的導航卡片','Google 地圖已在儀表板上顯示自己的導航卡片。開啟後會同時顯示 Di-Connect 的卡片。CarPlay 一律顯示。下次連接 Android Auto 時生效。'),
}
require(sorted(AA_NAV_CARD_TEXT)==sorted(p.parent.name for p in STRINGS),f'D-Play locales changed: {sorted(p.parent.name for p in STRINGS)}')
for p in STRINGS:
    title,description=AA_NAV_CARD_TEXT[p.parent.name]
    edit(p,('</resources>',f'    <string name="settings_di_connect_aa_nav_card">{title}</string>\n    <string name="settings_di_connect_aa_nav_card_description">{description}</string>\n</resources>'))
(dest/'src/test/java/com/andrerinas/openheadunit/gwm/DiConnectSharedSettingsTest.kt').write_text('''package com.andrerinas.openheadunit.gwm

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Android Auto's GWM settings are D-Play's: what the D-Play settings screen saves is what AA uses. */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], manifest = Config.NONE)
class DiConnectSharedSettingsTest {
    private lateinit var context: Context
    private val dplay get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun clear() {
        context = RuntimeEnvironment.getApplication()
        dplay.edit().clear().commit()
        context.getSharedPreferences(GwmSettings.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun defaultsMatchDPlayAndKeepAndroidAutosDirectionsCardOff() {
        val gwm = GwmSettings(context)
        assertFalse(gwm.clusterEnabled)
        assertFalse(gwm.navCard)
        assertTrue(gwm.musicCard)
        assertTrue(gwm.styledLayer)
        assertEquals(2, gwm.clusterDisplayId)
        assertEquals(3, gwm.hudDisplayId)
    }

    @Test fun androidAutoReadsWhatDPlaySaved() {
        dplay.edit().putBoolean("cluster_map_enabled", true).putBoolean("gwm_cluster_overlay_enabled", true)
            .putBoolean("gwm_music_card", false).putBoolean("gwm_hud_enabled", true)
            .putInt("gwm_hud_display_id", 4).putInt("gwm_hud_anchor", 2).commit()
        val gwm = GwmSettings(context)
        assertTrue(gwm.clusterEnabled)
        assertFalse(gwm.musicCard)
        assertTrue(gwm.hudEnabled)
        assertEquals(4, gwm.hudDisplayId)
        assertEquals(2, gwm.hudAnchor)
        assertTrue(com.andrerinas.openheadunit.utils.Settings(context).clusterMapEnabled)
    }

    @Test fun theClusterNeedsBothDPlaySwitchesAndAndroidAutoSetsBoth() {
        dplay.edit().putBoolean("gwm_cluster_overlay_enabled", true).commit()
        val gwm = GwmSettings(context)
        assertFalse("The cluster map switch is off", gwm.clusterEnabled)
        gwm.clusterEnabled = true
        assertTrue(dplay.getBoolean("cluster_map_enabled", false))
        assertTrue(gwm.clusterEnabled)
    }

    @Test fun theDirectionsCardIsAndroidAutoOnly() {
        GwmSettings(context).navCard = true
        assertTrue(dplay.getBoolean("di_connect_aa_nav_card", false))
    }
}
''')

# Android Auto first, from D-Play's own screens. The home button, the "connect when Di-Connect
# opens" choice and the Android Auto settings card drive Android Auto without DiAuto's screens;
# DiAuto's full settings stay reachable as "Advanced Android Auto settings".
(dest/'src/main/java/com/andrerinas/openheadunit/DiConnectAndroidAuto.kt').write_text('''package com.andrerinas.openheadunit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.WirelessScopePolicy
import com.andrerinas.openheadunit.connection.UsbDeviceCompat
import com.andrerinas.openheadunit.utils.Settings

/**
 * Connects Android Auto from Di-Connect's home screen, as DiAuto's own home buttons do: an Android
 * phone on USB connects by cable (DiAuto's USB check, which also asks for USB access and switches
 * the phone to accessory mode); otherwise native wireless Android Auto to the chosen paired phone.
 * Android Auto opens its projection screen by itself once the phone has connected.
 */
object DiConnectAndroidAuto {
    enum class Route { OPENED, USB, WIRELESS, NEEDS_PHONE }

    private const val PHONE_ADDRESS = "di_connect_aa_phone_address"
    private const val PHONE_NAME = "di_connect_aa_phone_name"
    private const val OPENS_WITH_CARPLAY = "di_connect_opens_with_carplay"

    fun phoneAddress(context: Context): String? = prefs(context).getString(PHONE_ADDRESS, null)
    fun phoneName(context: Context): String? = prefs(context).getString(PHONE_NAME, null)
    fun savePhone(context: Context, address: String, name: String) =
        prefs(context).edit().putString(PHONE_ADDRESS, address).putString(PHONE_NAME, name).apply()

    /** "Connect when Di-Connect opens" starts Android Auto unless CarPlay is chosen. */
    fun opensWithCarPlay(context: Context): Boolean = prefs(context).getBoolean(OPENS_WITH_CARPLAY, false)
    fun saveOpensWithCarPlay(context: Context, carPlay: Boolean) =
        prefs(context).edit().putBoolean(OPENS_WITH_CARPLAY, carPlay).apply()

    /** Without starting Android Auto: only a running Android Auto service can be connected. */
    fun isConnected(context: Context): Boolean =
        DiConnectEngine.service != null && App.provide(context).commManager.isConnected

    fun connect(activity: Activity): Route {
        App.get(activity).initializeAndroidAuto()
        val app = App.provide(activity)
        if (app.commManager.isConnected) {
            activity.startActivity(AapProjectionActivity.intent(activity).putExtra(AapProjectionActivity.EXTRA_FOCUS, true))
            return Route.OPENED
        }
        val usb = activity.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usb?.deviceList?.values?.any { UsbDeviceCompat.isAndroidDevice(it) } == true) {
            start(activity, AapService.ACTION_CHECK_USB)
            return Route.USB
        }
        val address = phoneAddress(activity) ?: return Route.NEEDS_PHONE
        // As DiAuto's Wireless button: native wireless Android Auto, with Wi-Fi among the connection
        // types so the USB-only gate does not keep the route down.
        app.settings.wifiConnectionMode = 3
        if (!WirelessScopePolicy.allowsWireless(app.settings.connectionModes)) {
            app.settings.connectionModes = app.settings.connectionModes + Settings.ConnectionMode.WIFI
        }
        start(activity, AapService.ACTION_NATIVE_AA_POKE) { putExtra(AapService.EXTRA_MAC, address) }
        return Route.WIRELESS
    }

    /** DiAuto's full settings, for anything Di-Connect's own settings do not cover. */
    fun openAdvancedSettings(activity: Activity) {
        App.get(activity).initializeAndroidAuto()
        activity.startActivity(Intent(activity, com.andrerinas.openheadunit.main.SettingsActivity::class.java))
    }

    private fun start(context: Context, action: String, extras: Intent.() -> Unit = {}) {
        ContextCompat.startForegroundService(context, Intent(context, AapService::class.java).apply {
            this.action = action
            extras()
        })
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
}
''')
AA_='com.andrerinas.openheadunit.DiConnectAndroidAuto'
edit(D,
    ('    private var connectButton: Button? = null\n','    private var connectButton: Button? = null\n    private var androidAutoButton: Button? = null\n'),
    ('connectButton = null; disconnectButton = null','connectButton = null; androidAutoButton = null; disconnectButton = null'),
    ('    private val bluetoothAutoConnectPermission = registerForActivityResult(',f'''    private val androidAutoBluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {{ granted ->
        if (granted) chooseAndroidAutoPhone(connectAfter = true)
        else permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
    }}
    private val bluetoothAutoConnectPermission = registerForActivityResult('''),
    ('    private fun diConnectEntryEnabled(): Boolean = runCatching {',f'''    /** Di-Connect's main action: CarPlay stops first, then Android Auto connects by cable or wirelessly. */
    private fun connectAndroidAuto(choosePhoneIfNeeded: Boolean = true) {{
        CarPlayBackgroundSession.stop {{
            runOnUiThread {{
                if (isFinishing || isDestroyed) return@runOnUiThread
                when ({AA_}.connect(this)) {{
                    {AA_}.Route.NEEDS_PHONE -> {{ if (choosePhoneIfNeeded) chooseAndroidAutoPhone(connectAfter = true) }}
                    {AA_}.Route.OPENED -> {{ }}
                    else -> {{ status?.text = getString(R.string.di_connect_aa_connecting) }}
                }}
            }}
        }}
    }}

    /** The paired Android phone for wireless Android Auto, in the same dialog style as the iPhone choice. */
    private fun chooseAndroidAutoPhone(connectAfter: Boolean) {{
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {{
            androidAutoBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }}
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        val devices = runCatching {{ adapter?.takeIf {{ it.isEnabled }}?.bondedDevices?.sortedBy {{ it.name ?: "" }} }}.getOrNull().orEmpty()
        if (devices.isEmpty()) {{
            appDialogBuilder().setTitle(getString(R.string.di_connect_choose_android_phone))
                .setMessage(getString(R.string.di_connect_pair_android_phone))
                .setPositiveButton(getString(R.string.open_bluetooth)) {{ _, _ -> openBluetoothSettings() }}
                .setNegativeButton(getString(R.string.got_it), null).show(); return
        }}
        appDialogBuilder().setTitle(getString(R.string.di_connect_choose_android_phone))
            .setItems(devices.map {{ device ->
                val name = device.name ?: getString(R.string.paired_device)
                if (devices.count {{ it.name == device.name }} > 1) "$name · ${{device.address.takeLast(5)}}" else name
            }}.toTypedArray()) {{ _, index ->
                val device = devices[index]
                {AA_}.savePhone(this, device.address, device.name ?: getString(R.string.paired_device))
                render()
                if (connectAfter) connectAndroidAuto(choosePhoneIfNeeded = false)
            }}.setNeutralButton(getString(R.string.pair_another)) {{ _, _ -> openBluetoothSettings() }}
            .setNegativeButton(getString(R.string.cancel), null).show()
    }}

    private fun diConnectEntryEnabled(): Boolean = runCatching {{'''),
    # Home status speaks for Android Auto while CarPlay is idle; CarPlay's own states still show.
    ('        homeCardTitle?.text = getString(if (wired) R.string.home_usb_carplay else R.string.wireless_carplay)',
     '        homeCardTitle?.text = if (running) getString(if (wired) R.string.home_usb_carplay else R.string.wireless_carplay)\n            else getString(R.string.di_connect_home_title)'),
    ('''            setupError != null -> getString(R.string.setup_needs_attention)
            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            running -> getString(R.string.connecting_to_your_iphone)
            DiPlayPreferences.phoneAddress(this) != null -> "${getString(R.string.status_ready_for_prefix)}${DiPlayPreferences.phoneName(this)}"
            else -> getString(R.string.ready_when_you_are)''',f'''            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            running -> getString(R.string.connecting_to_your_iphone)
            {AA_}.isConnected(this) -> getString(R.string.di_connect_aa_connected)
            {AA_}.phoneName(this) != null -> "${{getString(R.string.status_ready_for_prefix)}}${{{AA_}.phoneName(this)}}"
            else -> getString(R.string.ready_when_you_are)'''),
    ('        connectButton?.isEnabled = setupError == null\n',f'''        connectButton?.isEnabled = setupError == null
        androidAutoButton?.text = getString(if ({AA_}.isConnected(this)) R.string.di_connect_open_android_auto
            else R.string.di_connect_connect_android_auto)
'''),
    # "Connect when Di-Connect opens" connects Android Auto unless CarPlay is chosen.
    ('''            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null && page != "setup") {
                handler.post { connect(DiPlayPreferences.autoConnectWireless(this)) }
            }''',f'''            val carPlayFirst = {AA_}.opensWithCarPlay(this)
            if ((setupError == null || !carPlayFirst) && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null && page != "setup") {{
                handler.post {{
                    if (carPlayFirst) connect(DiPlayPreferences.autoConnectWireless(this))
                    else connectAndroidAuto(choosePhoneIfNeeded = false)
                }}
            }}'''),
    ('            toggle(card, getString(R.string.connect_when_diplay_opens), getString(R.string.default_connection_description), DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }\n',
     f'''            toggle(card, getString(R.string.connect_when_diplay_opens), getString(R.string.default_connection_description), DiPlayPreferences.autoConnect(this)) {{ DiPlayPreferences.saveAutoConnect(this, it) }}
            choice(card, getString(R.string.settings_di_connect_open_with), listOf(getString(R.string.di_connect_android_auto), getString(R.string.carplay)),
                if ({AA_}.opensWithCarPlay(this)) 1 else 0, reconnects = false) {{ index -> {AA_}.saveOpensWithCarPlay(this, index == 1) }}
'''),
    # Settings: an Android Auto card first in Connection.
    ('    CARPLAY_CONTROLS,\n    WHEEL_KEYS,\n    CONNECTION_SETUP,\n','    CARPLAY_CONTROLS,\n    WHEEL_KEYS,\n    ANDROID_AUTO,\n    CONNECTION_SETUP,\n'),
    ('        SettingsCategory.CONNECTION to setOf(\n            SettingsSection.CONNECTION_SETUP,','        SettingsCategory.CONNECTION to setOf(\n            SettingsSection.ANDROID_AUTO,\n            SettingsSection.CONNECTION_SETUP,'),
    ('        filteredSection(content, SettingsSection.CONNECTION_SETUP,',f'''        filteredSection(content, SettingsSection.ANDROID_AUTO,
            getString(R.string.settings_di_connect_android_auto), R.drawable.ic_dp_connection) {{ card ->
            card.addView(label(getString(R.string.settings_di_connect_android_auto_description), 16, MUTED))
            val phone = {AA_}.phoneName(this) ?: getString(R.string.settings_di_connect_aa_phone_none)
            card.addView(button("${{getString(R.string.settings_di_connect_aa_phone)}} · $phone", false) {{
                chooseAndroidAutoPhone(connectAfter = false)
            }}, matchButton(12, 60))
            card.addView(button(getString(R.string.settings_di_connect_aa_advanced), false) {{
                {AA_}.openAdvancedSettings(this)
            }}, matchButton(12, 60))
        }}
        filteredSection(content, SettingsSection.CONNECTION_SETUP,'''))
AA_STRINGS={
 'di_connect_home_title':['ANDROID AUTO · CARPLAY']*9,
 'di_connect_connect_android_auto':['Connect Android Auto','اتصال Android Auto','Conectar Android Auto','Connetti Android Auto','Android Auto に接続','Подключить Android Auto','Підключити Android Auto','连接 Android Auto','連接 Android Auto'],
 'di_connect_open_android_auto':['Open Android Auto','فتح Android Auto','Abrir Android Auto','Apri Android Auto','Android Auto を開く','Открыть Android Auto','Відкрити Android Auto','打开 Android Auto','開啟 Android Auto'],
 'di_connect_aa_connecting':['Connecting Android Auto…','جارٍ اتصال Android Auto…','Conectando Android Auto…','Connessione ad Android Auto…','Android Auto に接続中…','Подключение Android Auto…','Підключення Android Auto…','正在连接 Android Auto…','正在連接 Android Auto…'],
 'di_connect_aa_connected':['Android Auto connected','Android Auto متصل','Android Auto conectado','Android Auto connesso','Android Auto に接続済み','Android Auto подключён','Android Auto підключено','Android Auto 已连接','Android Auto 已連接'],
 'di_connect_choose_android_phone':['Choose your Android phone','اختر هاتف Android','Elige tu teléfono Android','Scegli il tuo telefono Android','Android スマートフォンを選択','Выберите телефон Android','Виберіть телефон Android','选择你的 Android 手机','選擇你的 Android 手機'],
 'di_connect_pair_android_phone':["Turn on the car\\'s Bluetooth and pair your Android phone first, then choose it here.",'شغّل البلوتوث في السيارة واقرن هاتف Android أولًا، ثم اختره هنا.','Activa el Bluetooth del coche y empareja primero tu teléfono Android; después elígelo aquí.',"Attiva il Bluetooth dell\\'auto e abbina prima il tuo telefono Android, poi sceglilo qui.",'車の Bluetooth をオンにして Android スマートフォンをペアリングしてから、ここで選択してください。','Включите Bluetooth автомобиля и сначала создайте пару с телефоном Android, затем выберите его здесь.','Увімкніть Bluetooth автомобіля й спершу створіть пару з телефоном Android, потім виберіть його тут.','请先打开车机蓝牙并配对你的 Android 手机，然后在这里选择它。','請先開啟車機藍牙並配對你的 Android 手機，然後在這裡選擇它。'],
 'settings_di_connect_android_auto':['Android Auto']*9,
 'settings_di_connect_android_auto_description':['Connect Android Auto from the home screen. A phone plugged in by USB connects by cable; otherwise the chosen phone connects wirelessly.','اتصل بـ Android Auto من الشاشة الرئيسية. يتصل الهاتف الموصول عبر USB بالكابل؛ وإلا يتصل الهاتف المختار لاسلكيًا.','Conecta Android Auto desde la pantalla de inicio. Un teléfono conectado por USB se conecta por cable; si no, el teléfono elegido se conecta de forma inalámbrica.',"Connetti Android Auto dalla schermata iniziale. Un telefono collegato via USB si connette via cavo; altrimenti il telefono scelto si connette in wireless.",'ホーム画面から Android Auto に接続します。USB で接続したスマートフォンはケーブルで、それ以外は選択したスマートフォンがワイヤレスで接続します。','Подключайте Android Auto с главного экрана. Телефон, подключённый по USB, работает через кабель; иначе выбранный телефон подключается без проводов.',"Підключайте Android Auto з головного екрана. Телефон, під\\'єднаний через USB, працює через кабель; інакше вибраний телефон підключається бездротово.",'从主屏幕连接 Android Auto。通过 USB 插入的手机使用数据线连接；否则所选手机以无线方式连接。','從主畫面連接 Android Auto。透過 USB 插入的手機使用傳輸線連接；否則所選手機以無線方式連接。'],
 'settings_di_connect_aa_phone':['Wireless phone','الهاتف اللاسلكي','Teléfono inalámbrico','Telefono wireless','ワイヤレスのスマートフォン','Телефон для беспроводного подключения','Телефон для бездротового підключення','无线连接手机','無線連接手機'],
 'settings_di_connect_aa_phone_none':['Not chosen','لم يُختر','Sin elegir','Non scelto','未選択','Не выбран','Не вибрано','未选择','未選擇'],
 'settings_di_connect_aa_advanced':['Advanced Android Auto settings','إعدادات Android Auto المتقدمة','Ajustes avanzados de Android Auto','Impostazioni avanzate di Android Auto','Android Auto の詳細設定','Дополнительные настройки Android Auto','Додаткові налаштування Android Auto','Android Auto 高级设置','Android Auto 進階設定'],
 'settings_di_connect_open_with':['Connect on open with','الاتصال عند الفتح باستخدام','Conectar al abrir con',"Connetti all\\'apertura con",'起動時に接続する方式','Подключаться при запуске через','Підключатися під час запуску через','打开时连接','開啟時連接'],
}
LOCALE_ORDER=['values','values-ar','values-es','values-it','values-ja','values-ru','values-uk','values-zh-rCN','values-zh-rTW']
require(sorted(LOCALE_ORDER)==sorted(p.parent.name for p in STRINGS),'D-Play locales changed')
for p in STRINGS:
    i=LOCALE_ORDER.index(p.parent.name)
    edit(p,('</resources>',''.join(f'    <string name="{k}">{v[i]}</string>\n' for k,v in AA_STRINGS.items())+'</resources>'))

# D-Play's look in Android Auto: the GWM cluster layer and HUD are D-Play's own views, as in CarPlay.
# DiAuto-GWM keeps its working window and map-video plumbing; only the drawn layers change. The views
# are created by D-Play's side through DiConnectViews, so the Android Auto library does not depend on
# D-Play's code. DiAuto's cluster codes are the AMap broadcast codes D-Play's views draw.
(dest/'src/main/java/com/andrerinas/openheadunit/DiConnectViews.kt').write_text('''package com.andrerinas.openheadunit

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import com.andrerinas.openheadunit.gwm.GwmMusicState
import com.andrerinas.openheadunit.hud.BydGuidance

/** Lets Di-Connect's D-Play side draw Android Auto's GWM cluster layer and HUD with its own views. */
object DiConnectViews {
    /** Next turn in the AMap broadcast codes D-Play's views draw (DiAuto's cluster codes). */
    data class Guidance(
        val icon: Int,
        val roundaboutExit: Int,
        val distanceMeters: Int,
        val road: String,
        val remainingMeters: Int,
        val remainingSeconds: Int,
    )

    data class Music(
        val title: String?,
        val artist: String?,
        val album: String?,
        val artwork: Bitmap?,
        val durationMillis: Long?,
        val positionMillis: Long?,
        val playing: Boolean,
    )

    interface Layer {
        val view: View
        fun setGuidance(guidance: Guidance?)
        fun release()
    }

    /** Set by Di-Connect's D-Play side at start-up; while null, DiAuto's own views are used. */
    @Volatile var cluster: ((Context) -> Layer)? = null
    /** [anchor]: 0 left, 1 centre, 2 right, as both apps store it. */
    @Volatile var hud: ((Context, Int) -> Layer)? = null

    fun observeMusic(observer: (Music) -> Unit): () -> Unit = GwmMusicState.observe { s ->
        observer(Music(s.title, s.artist, s.album, s.artwork, s.durationMillis, s.positionNow(), s.playing))
    }

    internal fun from(guidance: BydGuidance?): Guidance? = guidance?.let {
        Guidance(it.clusterIcon, it.roundaboutExit, it.distanceMeters, it.road, it.remainingMeters, it.remainingSeconds)
    }
}
''')
O=dest/'src/main/java/com/andrerinas/openheadunit/gwm/GwmOutputs.kt'
V_='com.andrerinas.openheadunit.DiConnectViews'
edit(O,
    ('''    private var layer: GwmClusterLayerView? = null
''','''    private var layer: GwmClusterLayerView? = null
    // Di-Connect: D-Play's cluster layer and HUD, used instead of DiAuto's when D-Play provides them.
    private var dplayLayer: com.andrerinas.openheadunit.DiConnectViews.Layer? = null
    private var dplayLayerNavCard = false
    private var dplayHud: com.andrerinas.openheadunit.DiConnectViews.Layer? = null
'''),
    ('''            layer?.setGuidance(next)
            hudView?.setGuidance(next)
''',f'''            layer?.setGuidance(next)
            hudView?.setGuidance(next)
            val mapped = {V_}.from(next)
            // Google Maps draws its own card into the cluster video; Di-Connect's is opt-in in Android Auto.
            dplayLayer?.setGuidance(if (dplayLayerNavCard) mapped else null)
            dplayHud?.setGuidance(mapped)
'''),
    ('''        if (clusterRoot != null || hudView != null) handler.post(guidanceTick)
    }
''','''        if (clusterRoot != null || hudView != null || dplayHud != null) handler.post(guidanceTick)
    }
'''),
    ('''        if (gwm.styledLayer) {
            val view = GwmClusterLayerView(displayContext, gwm)
            root.addView(view, FrameLayout.LayoutParams(-1, -1))
            layer = view
        }''',f'''        if (gwm.styledLayer) {{
            val dplay = {V_}.cluster?.invoke(displayContext)
            if (dplay != null) {{
                root.addView(dplay.view, FrameLayout.LayoutParams(-1, -1))
                dplayLayer = dplay
                dplayLayerNavCard = gwm.navCard
            }} else {{
                val view = GwmClusterLayerView(displayContext, gwm)
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                layer = view
            }}
        }}'''),
    ('''        } catch (error: RuntimeException) {
            layer = null
            clusterStatus =''','''        } catch (error: RuntimeException) {
            layer = null
            dplayLayer?.release()
            dplayLayer = null
            clusterStatus ='''),
    ('        if (gwm.styledLayer && gwm.vehicleStrip) GwmVehicleData.addListener(app, vehicleListener)',
     "        // D-Play's layer reads the car's values itself.\n        if (layer != null && gwm.vehicleStrip) GwmVehicleData.addListener(app, vehicleListener)"),
    ('''        clusterManager = null
        layer = null
''','''        clusterManager = null
        layer = null
        dplayLayer?.release()
        dplayLayer = null
'''),
    ('        if (hudView != null && hudDisplayId == target) {','        if ((hudView != null || dplayHud != null) && hudDisplayId == target) {'),
    ('''        val view = GwmHeadUpView(displayContext).apply { setAnchor(anchor); setArea(gwm.hudArea) }
        try {
            windows.addView(view, overlayParams(opaque = false))
        } catch (error: RuntimeException) {''',f'''        val dplay = {V_}.hud?.invoke(displayContext, gwm.hudAnchor)
        val view: android.view.View = dplay?.view
            ?: GwmHeadUpView(displayContext).apply {{ setAnchor(anchor); setArea(gwm.hudArea) }}
        try {{
            windows.addView(view, overlayParams(opaque = false))
        }} catch (error: RuntimeException) {{
            dplay?.release()'''),
    ('''        hudManager = windows
        hudView = view
''','''        hudManager = windows
        if (dplay != null) dplayHud = dplay else hudView = view as GwmHeadUpView
'''),
    ('''        val view = hudView
        val windows = hudManager
        hudView = null
''','''        val view: android.view.View? = hudView ?: dplayHud?.view
        val windows = hudManager
        hudView = null
        dplayHud?.release()
        dplayHud = null
'''))
(root/'common/src/main/java/com/shilapi/xcertplay/DiConnectPlayViews.kt').write_text('''package com.shilapi.xcertplay

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.view.View
import com.andrerinas.openheadunit.DiConnectViews
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/**
 * Di-Connect: Android Auto's GWM cluster layer and HUD are D-Play's own views, fed by D-Play's car
 * values and Android Auto's turn guidance and now playing, so they look and behave as in CarPlay.
 */
internal object DiConnectPlayViews {
    fun install(app: Context) {
        DiConnectViews.cluster = { displayContext -> ClusterLayer(displayContext, app) }
        DiConnectViews.hud = { displayContext, anchor -> HudLayer(displayContext, anchor) }
    }

    fun guidance(next: DiConnectViews.Guidance?): ClusterTurnGuidance? = next?.takeIf { it.icon != 0 }?.let {
        ClusterTurnGuidance(
            icon = it.icon,
            roundaboutExit = it.roundaboutExit,
            distanceMeters = it.distanceMeters,
            road = it.road,
            arrivalEpochSeconds = it.remainingSeconds.takeIf { s -> s >= 0 }
                ?.let { s -> System.currentTimeMillis() / 1000 + s },
            remainingSeconds = it.remainingSeconds.takeIf { s -> s >= 0 }?.toLong(),
            remainingMeters = it.remainingMeters.takeIf { m -> m >= 0 }?.toLong(),
        )
    }

    private class ClusterLayer(displayContext: Context, private val app: Context) : DiConnectViews.Layer {
        private val hud = GwmClusterHudView(displayContext)
        private val vehicle: (GwmVehicleData.Snapshot?) -> Unit = { snapshot -> hud.post { hud.setSnapshot(snapshot) } }
        private val stopMusic = DiConnectViews.observeMusic { music ->
            ClusterMusicState.publish(CarPlayNowPlaying(
                title = music.title, album = music.album, artist = music.artist,
                durationMillis = music.durationMillis, elapsedMillis = music.positionMillis, playing = music.playing,
            ), music.artwork)
        }
        override val view: View get() = hud

        init { GwmVehicleData.addListener(app, vehicle) }

        override fun setGuidance(guidance: DiConnectViews.Guidance?) = hud.setGuidance(guidance(guidance))

        override fun release() {
            GwmVehicleData.removeListener(vehicle)
            stopMusic()
            ClusterMusicState.publish(CarPlayNowPlaying(), null)
        }
    }

    private class HudLayer(displayContext: Context, anchor: Int) : DiConnectViews.Layer {
        private val hud = GwmHeadUpView(displayContext).apply {
            setAnchor(GwmHeadUpView.Anchor.entries.getOrElse(anchor) { GwmHeadUpView.Anchor.CENTRE })
        }
        override val view: View get() = hud
        override fun setGuidance(guidance: DiConnectViews.Guidance?) = hud.setGuidance(guidance(guidance))
        override fun release() = Unit
    }
}

/** Registers [DiConnectPlayViews] when the app process starts, before any Android Auto session. */
internal class DiConnectViewsProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let(DiConnectPlayViews::install)
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
''')
edit(root/'common/src/main/AndroidManifest.xml',('    </application>','''        <!-- Di-Connect: D-Play's cluster layer and HUD for Android Auto (DiConnectPlayViews). -->
        <provider
            android:name="com.shilapi.xcertplay.DiConnectViewsProvider"
            android:authorities="${applicationId}.di-connect-views"
            android:exported="false" />
    </application>'''))

# D-Play's launch test covers CarPlay's transport on auto-connect; Di-Connect opens with Android Auto
# unless CarPlay is chosen, so the test chooses CarPlay first. Its CarPlay assertions are unchanged.
edit(root/'common/src/test/java/com/shilapi/xcertplay/DefaultConnectionModeTest.kt',
    ('''        DiPlayPreferences.saveAutoConnect(app, true)
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")''','''        DiPlayPreferences.saveAutoConnect(app, true)
        com.andrerinas.openheadunit.DiConnectAndroidAuto.saveOpensWithCarPlay(app, true)
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")'''))

# CarPlay's settings swipe in Android Auto: D-Play's finger count (three by default) swiped down opens
# Di-Connect's settings while Android Auto stays connected, as it does over CarPlay. DiAuto's
# two-finger edge swipes are unchanged. The Di-Connect icon then returns to Android Auto.
require('const val SETTINGS_SWIPE_DISTANCE_DP = 72' in (root/'common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt').read_text()
    and 'KEY_SETTINGS_GESTURE_FINGERS = "settings_gesture_fingers"' in persist,'D-Play settings swipe constants changed')
(dest/'src/main/java/com/andrerinas/openheadunit/DiConnectSettingsSwipe.kt').write_text('''package com.andrerinas.openheadunit

import android.app.Activity
import android.content.Intent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * D-Play's CarPlay settings swipe, for Android Auto: the chosen number of fingers (D-Play's setting,
 * three by default, 0 = off) swiped down opens Di-Connect's settings while Android Auto stays
 * connected. Same distance and direction rule as D-Play's CarPlay screen.
 */
class DiConnectSettingsSwipe(private val activity: Activity) {
    enum class Result { PASS, STARTED, CONSUMED }

    private var sequence = false
    private var tracking = false
    private var startX = 0f
    private var startY = 0f

    fun onTouch(event: MotionEvent): Result {
        val fingers = DiConnectSettings.int(activity, SETTINGS_GESTURE_FINGERS, 3).let { if (it == 0) 0 else it.coerceIn(2, 4) }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sequence = false; tracking = false }
            MotionEvent.ACTION_POINTER_DOWN -> if (fingers > 0 && event.pointerCount == fingers && !sequence) {
                sequence = true
                tracking = true
                startX = centroid(event, horizontal = true)
                startY = centroid(event, horizontal = false)
                return Result.STARTED
            }
        }
        if (!sequence) return Result.PASS
        when {
            event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL -> {
                sequence = false; tracking = false
            }
            event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.pointerCount != fingers -> tracking = false
            tracking && event.actionMasked == MotionEvent.ACTION_MOVE -> {
                val dx = abs(centroid(event, horizontal = true) - startX)
                val dy = centroid(event, horizontal = false) - startY
                val distance = SWIPE_DISTANCE_DP * activity.resources.displayMetrics.density
                if (abs(dy) >= distance && abs(dy) >= dx * SWIPE_DIRECTION_RATIO) {
                    tracking = false
                    if (dy > 0) openSettings()
                }
            }
        }
        return Result.CONSUMED
    }

    private fun openSettings() {
        activity.startActivity(Intent().setClassName(activity, DI_CONNECT_HOME)
            .putExtra("page", "settings").addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun centroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (i in 0 until event.pointerCount) total += if (horizontal) event.getX(i) else event.getY(i)
        return total / event.pointerCount
    }

    private companion object {
        const val SETTINGS_GESTURE_FINGERS = "settings_gesture_fingers"
        const val SWIPE_DISTANCE_DP = 72
        const val SWIPE_DIRECTION_RATIO = 1.15f
        const val DI_CONNECT_HOME = "com.shilapi.xcertplay.DiPlayActivity"
    }
}
''')
edit(dest/'src/main/java/com/andrerinas/openheadunit/aap/AapProjectionActivity.kt',
    ('''    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
''','''    // Di-Connect: CarPlay's settings swipe opens Di-Connect's settings without leaving Android Auto.
    private val diConnectSettingsSwipe by lazy { com.andrerinas.openheadunit.DiConnectSettingsSwipe(this) }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (diConnectSettingsSwipe.onTouch(ev)) {
            com.andrerinas.openheadunit.DiConnectSettingsSwipe.Result.STARTED -> {
                // Release what the first fingers pressed in Android Auto, as D-Play does for CarPlay.
                val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                return true
            }
            com.andrerinas.openheadunit.DiConnectSettingsSwipe.Result.CONSUMED -> return true
            com.andrerinas.openheadunit.DiConnectSettingsSwipe.Result.PASS -> Unit
        }
'''))
edit(D,('''        if (isLauncherIntent(intent) && CarPlayBackgroundSession.hasSession()) {
            page = "home"; render(); openProjection(); return
        }''',f'''        if (isLauncherIntent(intent) && CarPlayBackgroundSession.hasSession()) {{
            page = "home"; render(); openProjection(); return
        }}
        // Di-Connect: the icon returns to a connected Android Auto the same way.
        if (isLauncherIntent(intent) && {AA_}.isConnected(this)) {{
            page = "home"; render(); {AA_}.connect(this); return
        }}'''))

# One name: Di-Connect, in D-Play's and Android Auto's text, the car name the phone shows and Android
# Auto's local hotspot. Credits, licence notes and the "Import from a DiPlay APK" option keep the
# real names, and URLs are untouched.
KEEP_NAMES={'receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay','settings_auth_import_apk','settings_auth_import_description'}
def rename(folder,old,keep=()):
    total=0
    for f in sorted(folder.glob('values*/strings.xml')):
        text=f.read_text(); count=[0]
        def element(m):
            if m[2] in keep or 'translatable="false"' in m[1]: return m[0]
            body,n=re.subn(rf'(?<![/\w]){old}(?![/\w])','Di-Connect',m[3]); count[0]+=n
            return m[1]+body+m[4]
        f.write_text(re.sub(r'(<string name="([^"]+)"[^>]*>)(.*?)(</string>)',element,text,flags=re.S)); total+=count[0]
    return total
require(rename(root/'common/src/main/res','DiPlay',KEEP_NAMES)>50,'Expected D-Play text naming DiPlay to rename')
require(rename(dest/'src/main/res','DiAuto')>10,'Expected Android Auto text naming DiAuto to rename')
require('DiPlay APK' in (root/'common/src/main/res/values/strings.xml').read_text(),'The DiPlay APK import text must keep its name')
edit(dest/'src/main/java/com/andrerinas/openheadunit/utils/Settings.kt',
    ('prefs.getString("vehicle-display-name", "DiAuto")!!','prefs.getString("vehicle-display-name", "Di-Connect")!!'))
edit(dest/'src/main/java/com/andrerinas/openheadunit/connection/LocalHotspotCredentialsProvider.kt',
    ('"DiAuto-${UUID.randomUUID().toString().take(6)}"','"Di-Connect-${UUID.randomUUID().toString().take(6)}"'))

# Hybrid battery current for the Android Auto battery-flow bar. Verified live on the H7 (Oct 2026):
# GWM_BMS_PackCurr through car.ev_info.cur_charge_current, amps, positive while the battery drives
# the car (+188 A pulling up a hill) and negative while regen or the engine charges it (-105 A).
# Motor power (car.ev_info.motor_power) only ever answered the -1001 sentinel, so it is not used.
vehicle=root/'common/src/main/java/com/shilapi/xcertplay/GwmVehicleData.kt'
edit(vehicle,
    ('    const val ODOMETER = "car.basic.odometer"\n',
     '    const val ODOMETER = "car.basic.odometer"\n'
     '    /** Hybrid battery pack current in amps; positive = discharging, negative = charging. */\n'
     '    const val BATTERY_CURRENT = "car.ev_info.cur_charge_current"\n'
     '    /** The adapter\'s "unavailable" answer; never a real reading. */\n'
     '    private const val UNAVAILABLE = -1001f\n'),
    ('arrayOf(SPEED, GEAR_STATUS, CURRENT_GEAR, FUEL_PERCENT, RANGE_KM, OUTSIDE_TEMP, TOTAL_ODOMETER, ODOMETER)',
     'arrayOf(SPEED, GEAR_STATUS, CURRENT_GEAR, FUEL_PERCENT, RANGE_KM, OUTSIDE_TEMP, TOTAL_ODOMETER, ODOMETER, BATTERY_CURRENT)'),
    ('        val odometerKm: Float? = null,\n    ) {',
     '        val odometerKm: Float? = null,\n        val batteryCurrentA: Float? = null,\n    ) {'),
    ('            ?.takeIf { it in 0f..2_000_000f },\n    )',
     '            ?.takeIf { it in 0f..2_000_000f },\n'
     '        batteryCurrentA = number(values[BATTERY_CURRENT])?.takeIf { it != UNAVAILABLE && it in -1000f..1000f },\n    )'),
    ('range=${snapshot.rangeKm} km outside=${snapshot.outsideTempC} °C odo=${snapshot.odometerKm} km")',
     'range=${snapshot.rangeKm} km outside=${snapshot.outsideTempC} °C odo=${snapshot.odometerKm} km ")\n'
     '            append("battery=${snapshot.batteryCurrentA} A")'))
(root/'common/src/test/java/com/shilapi/xcertplay/GwmBatteryCurrentTest.kt').write_text("""package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GwmBatteryCurrentTest {
    private fun current(raw: String) =
        GwmVehicleData.snapshotFrom(mapOf(GwmVehicleData.BATTERY_CURRENT to raw), 0L).batteryCurrentA

    @Test fun readsSignedAmpsAsLogged() {
        assertEquals(188.0f, current("188.0"))
        assertEquals(-104.8f, current("-104.8"))
        assertEquals(2.2f, current("2.2"))
    }

    @Test fun theUnavailableSentinelIsNotAReading() {
        assertNull(current("-1001"))
        assertNull(current(""))
        assertNull(current("5000"))
    }
}
""")

# Car hotspot robustness (Oct 2026). With the H7's AP band on "Both", Android bridges the two radios
# as wifi_br0 and the name heuristic picked eth0.11; a stale manual override then waited forever for
# an interface that no longer existed. Prefer the interface Android itself reports as tethering,
# ignore an override that names a missing interface, and say plainly when the band is "Both".
softap=dest/'src/main/java/com/andrerinas/openheadunit/connection/SoftApCredentialsProvider.kt'
edit(softap,
    ('    @Volatile private var reportedNoInterface = false\n',
     '    @Volatile private var reportedNoInterface = false\n'
     '    @Volatile private var reportedMissingOverride = false\n'
     '    @Volatile private var reportedDualBand = false\n'),
    ('        reportedNoInterface = false\n        resolveJob = scope.launch(',
     '        reportedNoInterface = false\n        reportedMissingOverride = false\n        reportedDualBand = false\n        resolveJob = scope.launch('),
    ("""            val match = candidates.firstOrNull { it.name.equals(named, ignoreCase = true) }
            val picked = SoftApNetworkPolicy.pickApInterface(candidates, namedInterface = named)
            when {
                match == null ->
                    AppLog.w("SoftApCredentials: Waiting for interface '$named'. Present: ${candidates.joinToString { it.name }}.")
                picked == null ->
                    AppLog.w("SoftApCredentials: Waiting for interface '$named' to be up with a private IPv4 address.")
                else -> {
                    AppLog.i("SoftApCredentials: Using '${picked.name}' (${picked.siteLocalIpv4}) as named in settings.")
                    return ChosenInterface(picked, namedByUser = true)
                }
            }
            return null
        }
""","""            val match = candidates.firstOrNull { it.name.equals(named, ignoreCase = true) }
            if (match != null) {
                val picked = SoftApNetworkPolicy.pickApInterface(candidates, namedInterface = named)
                if (picked == null) {
                    AppLog.w("SoftApCredentials: Waiting for interface '$named' to be up with a private IPv4 address.")
                    return null
                }
                AppLog.i("SoftApCredentials: Using '${picked.name}' (${picked.siteLocalIpv4}) as named in settings.")
                return ChosenInterface(picked, namedByUser = true)
            }
            // A name that does not exist at all is a stale setting (wifi_br0 after the band changed),
            // not a hotspot that is still starting. Waiting for it can never succeed.
            if (!reportedMissingOverride) {
                reportedMissingOverride = true
                AppLog.w("SoftApCredentials: Hotspot network interface '$named' does not exist on this device " +
                    "(present: ${candidates.joinToString { it.name }}). Ignoring it and finding the hotspot automatically.")
                showToastOnce("Hotspot network interface '$named' not found - finding the car hotspot automatically. " +
                    "Clear it in Android Auto settings.")
            }
        }

        tetheredAccessPoint(candidates)?.let { tethered ->
            warnIfDualBand(tethered.name)
            AppLog.i("SoftApCredentials: Using '${tethered.name}' (${tethered.siteLocalIpv4}), the interface Android reports as tethering.")
            return ChosenInterface(tethered, namedByUser = false)
        }
"""),
    ("""    /** Resolves the rest of the credentials for [iface] and hands them over. */""",
     """    /**
     * The access point Android itself reports as tethering, read from the sticky tether broadcast
     * (the same evidence the CarPlay side uses). Name heuristics cannot see a bridge like wifi_br0.
     */
    private fun tetheredAccessPoint(candidates: List<ApInterfaceCandidate>): ApInterfaceCandidate? {
        val tethered = try {
            context.registerReceiver(null, IntentFilter("android.net.conn.TETHER_STATE_CHANGED"))
                ?.getStringArrayListExtra("tetherArray").orEmpty()
        } catch (e: Exception) {
            emptyList<String>()
        }
        return tethered.asSequence()
            .filterNot { name -> TETHER_NOT_WIFI.any { name.startsWith(it) } }
            .mapNotNull { name -> candidates.firstOrNull { it.name == name } }
            .firstOrNull { it.isUp && !it.isLoopback && it.siteLocalIpv4 != null }
    }

    /**
     * A bridge over two or more radios is the car's "Both" band setting: one SSID per band behind
     * one address. The phone is told one name and one radio address and does not join reliably,
     * so ask for a single band instead of failing silently.
     */
    private fun warnIfDualBand(name: String) {
        if (reportedDualBand) return
        val radios = try {
            val bridge = java.io.File("/sys/class/net/$name/brif")
            if (!bridge.isDirectory) return
            bridge.list().orEmpty().count { member ->
                java.io.File("/sys/class/net/$member/wireless").exists() || member.startsWith("wlan")
            }
        } catch (e: Exception) {
            return
        }
        if (radios < 2) return
        reportedDualBand = true
        AppLog.w("SoftApCredentials: '$name' bridges $radios radios - the car hotspot band is set to Both. " +
            "Android Auto needs one band: set AP Band to 2.4 GHz or 5 GHz in the car's hotspot settings.")
        showToastOnce("Car hotspot is on both bands. Set AP Band to 2.4 GHz or 5 GHz in the car's hotspot settings for Android Auto.")
    }

    private fun showToastOnce(message: String) {
        Handler(Looper.getMainLooper()).post {
            ToastUtils.showToast(context, message, Toast.LENGTH_LONG, force = true)
        }
    }

    /** Resolves the rest of the credentials for [iface] and hands them over. */"""),
    ('\n    companion object {\n',
     '\n    companion object {\n'
     '        /** Tethering that is not the car hotspot: USB, Bluetooth PAN and Ethernet sharing. */\n'
     '        private val TETHER_NOT_WIFI = listOf("rndis", "usb", "ncm", "bt-pan", "bnep")\n'))

# USB Android Auto for any Android phone (Oct 2026). Di-Connect disables DiAuto's UsbAttachedActivity
# so Android Auto cannot start underneath CarPlay, which leaves the service's attach fallback as the
# only USB path, and that fallback only accepted Google's vendor ID. A Samsung S26 Ultra (04e8)
# was logged as "Trying from service..." and then nothing. Accept one Android phone of any vendor.
edit(AAP,
    ("""            val googleDevices = nonAccessoryDevices.filter { it.vendorId == 0x18D1 }
            if (googleDevices.size == 1) {
                AppLog.i("Fallback: force=true and found single Google normal-mode device ${UsbDeviceCompat(googleDevices[0]).uniqueName}. Switching to accessory mode.")
                performSingleUsbConnect(googleDevices[0])
            }""",
     """            // isAndroidDevice already excludes the CarPlay iPhone, hubs and the MFi chip.
            if (nonAccessoryDevices.size == 1) {
                AppLog.i("Fallback: force=true and found one Android phone in normal mode ${UsbDeviceCompat(nonAccessoryDevices[0]).uniqueName}. Switching to accessory mode.")
                performSingleUsbConnect(nonAccessoryDevices[0])
            } else {
                AppLog.i("USB attach: ${nonAccessoryDevices.size} Android devices in normal mode; not choosing one automatically.")
            }"""))

# HUD window and CarPlay roundabout (Oct 2026). The parked grid on the H7 windscreen shows the
# visible window ending about halfway through column C (~25 %), not at 30 %: the right of "300 m"
# and of column C were cut. Row 3's top edge (40 %) is right on the boundary, which clipped the top
# line on CarPlay. Keep a margin inside 42-78 % x 0-25 % for both the CarPlay and Android Auto HUDs.
# CarPlay roundabouts used one fixed right-hand roundabout picture; show the same neutral numbered
# roundabout as Android Auto instead, so it never points the wrong way.
cphud=root/'common/src/main/java/com/shilapi/xcertplay/GwmHeadUpView.kt'
glyph_block='''        val res = clusterTurnGlyphRes(next.icon)
        if (res != glyphRes) {
            glyph = ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(arrowTint) }
            glyphRes = res
        }
'''
edit(cphud,
    (glyph_block, '        glyph = glyphFor(next)\n', 3),
    ('''        const val NAV_WINDOW_RIGHT = 0.30f
        const val NAV_WINDOW_TOP = 0.40f
        const val NAV_WINDOW_BOTTOM = 0.80f''','''        // Measured on the windscreen with the parked grid: visible to ~25 % across, 40-80 % down.
        const val NAV_WINDOW_RIGHT = 0.25f
        const val NAV_WINDOW_TOP = 0.42f
        const val NAV_WINDOW_BOTTOM = 0.78f'''),
    ('    private var glyphRes = 0\n','''    private var glyphRes = 0
    private val roundabout = HudRoundaboutGlyph(arrowTint)

    /** Roundabouts get the neutral numbered roundabout; everything else the usual turn arrow. */
    private fun glyphFor(next: ClusterTurnGuidance): Drawable? {
        if (next.icon in HudRoundaboutGlyph.ICONS) {
            roundabout.exitNumber = next.roundaboutExit
            glyphRes = 0
            return roundabout
        }
        val res = clusterTurnGlyphRes(next.icon)
        if (res != glyphRes) {
            glyph = ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(arrowTint) }
            glyphRes = res
        }
        return glyph
    }
'''))
require(cphud.read_text().index('private val arrowTint') < cphud.read_text().index('HudRoundaboutGlyph(arrowTint)'),
    'GwmHeadUpView: arrowTint must be declared before the roundabout glyph')
(root/'common/src/main/java/com/shilapi/xcertplay/HudRoundaboutGlyph.kt').write_text('''package com.shilapi.xcertplay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * A roundabout with its exit number and no direction: the ring, the approach and the number.
 * Used where the source gives a roundabout code but no reliable picture, so the HUD never shows
 * a turn in the wrong direction, as one fixed roundabout picture for every exit can.
 */
internal class HudRoundaboutGlyph(tint: Int) : Drawable() {
    var exitNumber = 0
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    override fun draw(canvas: Canvas) {
        val box = RectF(bounds)
        val size = minOf(box.width(), box.height())
        val cx = box.centerX()
        val cy = box.top + size * .40f
        val radius = size * .25f
        ink.strokeWidth = size * .075f
        canvas.drawCircle(cx, cy, radius, ink)
        canvas.drawLine(cx, cy + radius, cx, box.top + size * .88f, ink)
        if (exitNumber in 1..10) {
            number.textSize = size * .30f
            canvas.drawText(exitNumber.toString(), cx, cy - (number.ascent() + number.descent()) / 2f, number)
        }
    }

    override fun setAlpha(alpha: Int) { ink.alpha = alpha; number.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { ink.colorFilter = colorFilter }
    @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** AMap roundabout codes as mapped by clusterTurnGlyphRes. */
        val ICONS = setOf(11, 12, 17, 18)
    }
}
''')

(root/'common/src/test/java/com/shilapi/xcertplay/GwmCarPlayHudWindowTest.kt').write_text("""package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GwmCarPlayHudWindowTest {
    private fun render(guidance: ClusterTurnGuidance): Bitmap {
        val view = GwmHeadUpView(RuntimeEnvironment.getApplication())
        view.setAnchor(GwmHeadUpView.Anchor.LEFT)
        view.setGuidance(guidance)
        view.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 480, 240)
        return Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.TRANSPARENT)
            view.draw(Canvas(it))
        }
    }

    @Test fun carPlayGuidanceStaysInsideTheVisibleWindscreenWindow() {
        val arrival = System.currentTimeMillis() / 1000L + 600L
        val bitmap = render(ClusterTurnGuidance(3, 0, 300, "Next road", arrivalEpochSeconds = arrival))
        var drawn = 0
        for (y in 0 until 240) for (x in 0 until 480) {
            val alpha = Color.alpha(bitmap.getPixel(x, y))
            if (x >= 120 || y < 100 || y >= 188) assertEquals("Outside HUD window $x,$y", 0, alpha)
            if (alpha > 0) drawn++
        }
        assertTrue("guidance must draw", drawn > 200)
    }

    @Test fun roundaboutExitsAreNumberedNotOneFixedPicture() {
        val first = render(ClusterTurnGuidance(11, 1, 300, ""))
        val second = render(ClusterTurnGuidance(11, 2, 300, ""))
        var changed = 0
        for (y in 0 until 240) for (x in 0 until 480) if (first.getPixel(x, y) != second.getPixel(x, y)) changed++
        assertTrue("exit 1 and exit 2 must differ", changed > 0)
    }
}
""")

# Android Auto-only luxury cluster skin. Preserve the donor CarPlay renderer byte-for-byte.
skin = Path('target/_integration/GwmAndroidAutoClusterView.kt')
require(skin.is_file(), 'Missing Android Auto cluster skin')
(root/'common/src/main/java/com/shilapi/xcertplay/GwmAndroidAutoClusterView.kt').write_text(skin.read_text())
edit(root/'common/src/main/java/com/shilapi/xcertplay/DiConnectPlayViews.kt',
    ('private val hud = GwmClusterHudView(displayContext)',
     'private val hud = GwmAndroidAutoClusterView(displayContext)'))

# Actual renderer regression tests and AA-only HUD, retained across donor regeneration.
for name, folder in [('GwmAndroidAutoClusterRenderTest.kt', 'common/src/test/java/com/shilapi/xcertplay'),
                     ('GwmAndroidAutoHeadUpView.kt', 'common/src/main/java/com/shilapi/xcertplay')]:
    source = Path('target/_integration')/name
    require(source.is_file(), 'Missing '+name)
    (root/folder/name).write_text(source.read_text())
for patch in json.loads(Path('target/_integration/android-auto-hud-patches.json').read_text()):
    edit(root/patch['path'], (patch['old'], patch['new'], patch['count']))
# The app is renamed Di-Connect; the consent test must expect its actual user-facing name.
edit(root/'common/src/test/java/com/shilapi/xcertplay/CarPlayVpnConsentTest.kt',
    ('message.contains("Return to DiPlay")', 'message.contains("Return to Di-Connect")'))

# Route metadata and HUD aperture regression coverage.
(root/'common/src/test/java/com/shilapi/xcertplay/GwmAndroidAutoHudRenderTest.kt').write_text(Path('target/_integration/GwmAndroidAutoHudRenderTest.kt').read_text())
(root/'android-auto/src/test/java/com/andrerinas/openheadunit/hud/BydNavigationMapperTest.kt').write_text(Path('target/_integration/BydNavigationMapperTest.kt').read_text())


# Keep roundabout images, route-level arrival retention and the debug launcher label fixes.
for patch in json.loads(Path('target/_integration/roundabout-arrival-patches.json').read_text()):
    edit(root/patch['path'], (patch['old'], patch['new'], patch['count']))


# Parked Android Auto HUD grid/sample tests and diagnostic data-presence snapshot.
for patch in json.loads(Path('target/_integration/hud-test-pill-patches.json').read_text()):
    edit(root/patch['path'], (patch['old'], patch['new'], patch['count']))


# Android 9 report export needs no DocumentsUI; the phone/QR report includes AA guidance logs.
for patch in json.loads(Path('target/_integration/report-export-patches.json').read_text()):
    edit(root/patch['path'], (patch['old'], patch['new'], patch['count']))
(root/'android-auto/src/test/java/com/andrerinas/openheadunit/utils/PrivateDiagnosticExportTest.kt').write_text(
    Path('target/_integration/PrivateDiagnosticExportTest.kt').read_text())


# ---- Built-in updater for Di-Connect preview builds ------------------------------------------------
# Preview builds share versionName 0.1.0 and differ by the CI revision (".r<revision>", also in the
# versionCode). A newer revision of the same version is an update.
edit(root/'common/src/main/java/com/shilapi/xcertplay/update/UpdateVersion.kt',
    ("""    private val TRIPLE = Regex("(\\\\d+)\\\\.(\\\\d+)\\\\.(\\\\d+)")
""","""    private val TRIPLE = Regex("(\\\\d+)\\\\.(\\\\d+)\\\\.(\\\\d+)")
    /** Di-Connect preview revision, as in 0.1.0-preview.r25625761. */
    private val REVISION = Regex("[.-]r(\\\\d+)\\\\b")

    internal fun revision(value: String): Long? = REVISION.find(value)?.groupValues?.get(1)?.toLongOrNull()
"""),
    ("""        return target.third > current.third
""","""        if (target.third != current.third) return target.third > current.third
        // Same version: a later Di-Connect preview revision is newer; without revisions it is the same.
        val remoteRevision = revision(remote) ?: return false
        val installedRevision = revision(installed) ?: return false
        return remoteRevision > installedRevision
"""))
(root/'common/src/main/java/com/shilapi/xcertplay/update/UpdateSignature.kt').write_text("""package com.shilapi.xcertplay.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * A downloaded update is installed only when it is this app (same package) signed by this app's own
 * key, so an update can never replace Di-Connect with a different app or a differently signed build,
 * and Android keeps the settings when it installs over the top.
 */
internal object UpdateSignature {
    fun matchesInstalled(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk) ?: return false
        if (archive.packageName != context.packageName) return false
        val installed = installedInfo(pm, context.packageName) ?: return false
        return sameSigners(signers(archive), signers(installed))
    }

    /** SHA-256 digests of each signing certificate; equal non-empty sets mean the same key. */
    internal fun sameSigners(update: Set<String>, installed: Set<String>): Boolean =
        update.isNotEmpty() && update == installed

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return pm.getPackageArchiveInfo(apk.path, flags)?.takeIf { signers(it).isNotEmpty() }
            ?: pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, packageName: String): PackageInfo? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        pm.getPackageInfo(packageName, flags)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val certificates = if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            val signing = info.signingInfo!!
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else info.signatures
        return certificates.orEmpty().mapNotNull { it?.toByteArray() }.map(::sha256).toSet()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
""")
edit(D,("""                if (!UpdateChecksums.matches(expected, release.apkName, actual)) {
                    throw IOException("Checksum mismatch for ${release.apkName}")
                }
""","""                if (!UpdateChecksums.matches(expected, release.apkName, actual)) {
                    throw IOException("Checksum mismatch for ${release.apkName}")
                }
                // Same app, same signing key: anything else is refused before the installer opens.
                if (!com.shilapi.xcertplay.update.UpdateSignature.matchesInstalled(applicationContext, apkFile)) {
                    throw IOException(getString(R.string.di_connect_update_wrong_signer))
                }
"""))
UPDATE_SIGNER_TEXT={
 'values':"This download isn\\'t a Di-Connect build signed with your key, so it wasn\\'t installed.",
 'values-ar':'هذا التنزيل ليس إصدارًا من Di-Connect موقّعًا بمفتاحك، لذلك لم يُثبَّت.',
 'values-es':'Esta descarga no es una versión de Di-Connect firmada con tu clave, así que no se instaló.',
 'values-it':"Questo download non è una build di Di-Connect firmata con la tua chiave, quindi non è stato installato.",
 'values-ja':'このダウンロードはあなたの鍵で署名された Di-Connect ではないため、インストールしませんでした。',
 'values-ru':'Эта загрузка не является сборкой Di-Connect, подписанной вашим ключом, поэтому она не установлена.',
 'values-uk':'Це завантаження не є збіркою Di-Connect, підписаною вашим ключем, тому його не встановлено.',
 'values-zh-rCN':'此下载不是用你的密钥签名的 Di-Connect 版本，因此未安装。',
 'values-zh-rTW':'此下載不是用你的金鑰簽署的 Di-Connect 版本，因此未安裝。',
}
require(sorted(UPDATE_SIGNER_TEXT)==sorted(p.parent.name for p in STRINGS),'D-Play locales changed')
for p in STRINGS:
    edit(p,('</resources>',f'    <string name="di_connect_update_wrong_signer">{UPDATE_SIGNER_TEXT[p.parent.name]}</string>\n</resources>'))
(root/'common/src/test/java/com/shilapi/xcertplay/update/DiConnectUpdateTest.kt').write_text("""package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiConnectUpdateTest {
    @Test fun aLaterPreviewRevisionIsAnUpdate() {
        assertTrue(UpdateVersion.isNewer("v0.1.0-preview.r25700000", "0.1.0-preview.r25625761"))
    }

    @Test fun theSameOrAnEarlierRevisionIsNot() {
        assertFalse(UpdateVersion.isNewer("v0.1.0-preview.r25625761", "0.1.0-preview.r25625761"))
        assertFalse(UpdateVersion.isNewer("v0.1.0-preview.r25600000", "0.1.0-preview.r25625761"))
    }

    @Test fun aHigherVersionStillWinsWhateverTheRevision() {
        assertTrue(UpdateVersion.isNewer("v0.2.0-preview.r1", "0.1.0-preview.r25625761"))
        assertFalse(UpdateVersion.isNewer("v0.1.0-preview.r99999999", "0.2.0-preview.r1"))
    }

    @Test fun releaseCandidatesAreNotRevisions() {
        assertEquals(null, UpdateVersion.revision("0.2.14-rc.1"))
        assertFalse(UpdateVersion.isNewer("0.2.14-rc.1", "0.2.14"))
    }

    @Test fun onlyTheSameSigningKeyMatches() {
        assertTrue(UpdateSignature.sameSigners(setOf("aa"), setOf("aa")))
        assertFalse(UpdateSignature.sameSigners(setOf("bb"), setOf("aa")))
        assertFalse(UpdateSignature.sameSigners(emptySet(), emptySet()))
    }
}
""")
