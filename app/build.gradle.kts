plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Lit la clé Groq depuis local.properties (jamais dans le code source versionné)
val localPropsFile = rootProject.file("local.properties")
val groqKey: String = localPropsFile.takeIf { it.exists() }
    ?.readLines()
    ?.firstOrNull { it.startsWith("GROQ_API_KEY=") }
    ?.substringAfter("=")
    ?.trim()
    ?: ""

// Clé de signature STABLE : chaque build est signé avec la même clé, ce qui permet
// d'installer les mises à jour PAR-DESSUS l'app sans la désinstaller (réglages gardés).
// Le fichier jarvis-keystore.p12 est déposé à la racine du dépôt.
val stableKeystore = rootProject.file("jarvis-keystore.p12")

android {
    namespace = "com.jarvis.ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.ai"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "2.1"
        buildConfigField("String", "GROQ_API_KEY", "\"$groqKey\"")
    }

    signingConfigs {
        create("stable") {
            if (stableKeystore.exists()) {
                storeFile = stableKeystore
                storePassword = "jarvis123"
                keyAlias = "jarvis"
                keyPassword = "jarvis123"
                storeType = "PKCS12"
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            if (stableKeystore.exists()) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (stableKeystore.exists()) signingConfig = signingConfigs.getByName("stable")
        }
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Mot d'activation "Jarvis" hors-ligne (nécessite le modèle FR dans assets/model-fr)
    implementation("com.alphacephei:vosk-android:0.3.47")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
