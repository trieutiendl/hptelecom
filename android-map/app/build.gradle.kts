plugins { id("com.android.application") }
android {
    namespace = "vn.hptelecom.map"
    buildToolsVersion = "35.0.0"
    compileSdk = 35
    defaultConfig {
        applicationId = "vn.hptelecom.map"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
        testInstrumentationRunner = "vn.hptelecom.map.MapSmokeRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    lint { abortOnError = true }
}
dependencies { testImplementation("junit:junit:4.13.2") }
