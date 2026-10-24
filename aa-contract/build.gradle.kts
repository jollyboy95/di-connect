plugins { id("com.android.library") }
android {
    namespace = "com.andrerinas.openheadunit.contract"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
