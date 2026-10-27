plugins { id("com.android.application") }
android {
    namespace = "dev.mylive2d.web"
    compileSdk = 35
    defaultConfig { applicationId = "dev.mylive2d.web"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation("com.google.androidbrowserhelper:androidbrowserhelper:2.2.0") }
