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
shutil.rmtree(dest/'src/debug',ignore_errors=True)
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
locales=sorted(p.name[7:] for p in (dest/'src/main/res').glob('values-*') if (p/'strings.xml').exists() and '-' not in p.name[7:])
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
    sourceSets.getByName("main").assets.srcDir("src/main/di-connect-notices")
    testOptions { unitTests.isIncludeAndroidResources = true }
}
'''+deps)
notices=dest/'src/main/di-connect-notices'; notices.mkdir(parents=True,exist_ok=True)
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
