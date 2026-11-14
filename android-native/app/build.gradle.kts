plugins { id("com.android.application"); id("com.google.devtools.ksp") }
android {
    namespace = "vn.vltelecom"
    buildToolsVersion = "36.0.0"
    compileSdk { version = release(36) }
    defaultConfig {
        applicationId = "vn.hptelecom.server.staging"
        minSdk = 26
        targetSdk = 35
        versionCode = 136
        versionName = "4.1.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs.getByName("debug") { storeFile = file("vltelecom3-debug.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
    // Real JSON implementation for local JVM round-trip tests; never packaged in the APK.
    testImplementation(files("test-libs/json-20240205.jar"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
