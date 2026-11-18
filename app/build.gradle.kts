plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ci.patrice.iptv"
    compileSdk = 35

    defaultConfig {
        applicationId = "ci.patrice.iptv"
        minSdk = 21
        targetSdk = 35
        versionCode = 10
        versionName = "2.1"

        // Codes d'accès (Supabase). Vides = contrôle désactivé.
        val supabaseUrl = (project.findProperty("iptv.supabaseUrl") as String?).orEmpty()
        val supabaseKey = (project.findProperty("iptv.supabaseKey") as String?).orEmpty()
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")
    }

    buildFeatures { buildConfig = true }

    // Clé de signature FIXE (secrets GitHub) : indispensable pour installer
    // les mises à jour par-dessus l'ancienne version.
    signingConfigs {
        getByName("debug") {
            val ks = System.getenv("KEYSTORE_PATH")
            if (!ks.isNullOrBlank()) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "iptv"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("io.coil-kt:coil:2.7.0")
}
