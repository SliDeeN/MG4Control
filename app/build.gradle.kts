plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mg4.control"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mg4.control"
        minSdk = 28
        targetSdk = 34
        versionCode = 16
        versionName = "2.6.4"
    }

    // Signature avec la clé plateforme de la ROM (requise par sharedUserId=android.uid.system).
    // Secrets lus depuis l'environnement (CI) ou gradle.properties local — JAMAIS commités.
    val keystorePath = System.getenv("MG4_KEYSTORE") ?: (project.findProperty("mg4.keystore") as String?)
    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("platform") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("MG4_KEYSTORE_PASSWORD") ?: (project.findProperty("mg4.keystore.password") as String?)
                keyAlias = System.getenv("MG4_KEY_ALIAS") ?: (project.findProperty("mg4.key.alias") as String?) ?: "platform"
                keyPassword = System.getenv("MG4_KEY_PASSWORD") ?: (project.findProperty("mg4.key.password") as String?)
            }
        }
    }

    flavorDimensions += "dist"
    productFlavors {
        create("online") {
            dimension = "dist"
            buildConfigField("boolean", "OFFLINE", "false")
        }
        create("offline") {
            dimension = "dist"
            applicationIdSuffix = ".offline"
            versionNameSuffix = "-offline"
            buildConfigField("boolean", "OFFLINE", "true")
        }
    }

    buildTypes {
        release {
            // [T-909] R8 activé : shrink + obfuscation. Le code est très réflexif —
            // proguard-rules.pro liste les cibles à conserver. Toute release DOIT passer
            // le test manuel sur véhicule (Katman1/2/3, HVAC, ADAS/AEB/ELK, allumage, OTA)
            // avant diffusion.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("platform")?.let { signingConfig = it }
        }
        debug {
            signingConfigs.findByName("platform")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }



    buildFeatures {
        viewBinding = true
        buildConfig = true
        // ITaskerBridge : contrat IPC partagé avec MG4Tasker (même package AIDL des deux côtés).
        aidl = true
    }


    // Tests unitaires JVM (pas de véhicule, pas d'émulateur) : Robolectric a besoin des
    // ressources Android pour instancier un Context.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    // AGP 9 removed the legacy applicationVariants output API. Release APK naming is
    // handled by the release workflow (renames app-<flavor>-release.apk on collect).
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Shared vehicle layer (git submodule ./MG4Hardware, subproject :mg4hardware).
    implementation(project(":mg4hardware"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.gson)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.viewpager2)

    // QR code (génération dans le dialog Infos) — flavor online uniquement.
    // Le flavor offline n'embarque PAS ZXing (réduction de surface, pas de dépendance superflue).
    "onlineImplementation"("com.google.zxing:core:3.5.3")

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
