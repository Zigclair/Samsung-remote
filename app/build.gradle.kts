plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.example.frameremote"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.frameremote"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
}
dependencies { implementation("com.squareup.okhttp3:okhttp:4.12.0") }
