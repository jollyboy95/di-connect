plugins { id("com.android.library") }
android {
    namespace = "com.andrerinas.openheadunit"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 24
        buildConfigField("String", "APPLICATION_ID", "\"com.jollyboy95.diconnect.debug\"")
        buildConfigField("String", "VERSION_NAME", "\"0.1.0-preview\"")
        buildConfigField("int", "VERSION_CODE", "1")
        buildConfigField("String", "AVAILABLE_LOCALES", "\"ar,cs,de,es,es-rES,hu,it,ja,ka,ko,nl,pl,pt-rBR,ro,ru,tr,uk,vi,zh-rCN,zh-rTW\"")
    }
    buildFeatures { buildConfig = true; aidl = true }
    externalNativeBuild { cmake { path("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint {
        abortOnError = false
        disable += "PackagedPrivateKey"
        // CI for the GWM H7 port (-PgwmLintFast): the full pass overruns a hosted runner, so it
        // runs only the correctness checks that matter when porting code to an Android 9 head unit.
        if (project.hasProperty("gwmLintFast")) {
            checkOnly += setOf(
                "NewApi", "InlinedApi", "WrongConstant", "ResourceType", "MissingPermission",
                "WrongThread", "Range", "StringFormatInvalid", "StringFormatMatches",
                "StringFormatCount", "MissingDefaultResource", "MissingClass", "Instantiatable",
                "DuplicateIds", "UnspecifiedRegisterReceiverFlag", "WrongViewCast",
            )
        }
    }
}
dependencies {
    implementation(project(":gwm-adapter"))
    // Conscrypt (Flavor specific: 2.6.1 for Playstore 16KB alignment; 2.5.3 for Github minSdk 16)
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    implementation("com.google.protobuf:protobuf-java:3.25.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.media:media:1.6.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.startup:startup-runtime:1.1.1")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    // ViewModel and LiveData
    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")

    // KTX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0")
    implementation(project(":aa-contract"))

    // Multidex
    implementation("androidx.multidex:multidex:2.0.1")

    // Navigation Component
    implementation("androidx.navigation:navigation-fragment-ktx:2.3.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.3.5")

    // DexMaker for runtime subclassing (Hotspot Fix)
    implementation("com.linkedin.dexmaker:dexmaker:2.28.3")

    // Glide for image/GIF loading (custom loading screen)
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // ZXing for QR Code generation
    implementation("com.google.zxing:core:3.5.3")

    // Shizuku for root / shell access
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
}
