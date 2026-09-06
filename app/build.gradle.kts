plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mg4.control"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mg4.control"
        minSdk = 28
        targetSdk = 34
        versionCode = 19
        // Suffixe injecte par la CI beta : -Pmg4.versionSuffix=-beta42 produit "2.6.6-beta42".
        // L'APK installe annonce alors EXACTEMENT ce que dit le tag de la release, sans quoi
        // l'OTA reproposerait la meme mise a jour indefiniment.
        versionName = "2.6.7" + (project.findProperty("mg4.versionSuffix") as String? ?: "")
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
                // v1 (JAR) OBLIGATOIRE : AGP le désactive par défaut dès minSdk >= 24, or sur
                // AAOS 9 le contrôle OTA lit l'archive via getPackageArchiveInfo(), qui ne
                // remonte pas de façon fiable un APK signé v2 sans v1 → signature jugée
                // illisible → mise à jour refusée. Les APK signés à la main (v1) passaient,
                // ceux de la CI (v2 seul) non. On pose donc les trois schémas.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Tests unitaires JVM (pas de véhicule, pas d'émulateur) : Robolectric a besoin des
    // ressources Android pour instancier un Context.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            if (variant.buildType.name == "release") {
                // ⚠️ LE NOM DE L'APK OFFLINE EST FONCTIONNEL, PAS COSMÉTIQUE.
                //
                // L'API GitHub renvoie les assets d'une release triés PAR NOM, et les clients
                // antérieurs à la 2.6.3 téléchargent le PREMIER .apk qu'ils y trouvent, sans
                // regarder lequel. « offline » précédant « online » alphabétiquement, ces
                // versions installaient l'APK offline — qui porte un applicationId distinct
                // (com.mg4.control.offline) et apparaît donc comme une SECONDE application à
                // côté de la leur. Cas réellement constaté d'une 2.6.0 passée à la 2.6.6.
                //
                // Le préfixe « secure » place l'offline après l'online dans ce tri. Ne pas le
                // retirer, et ne jamais donner à l'offline un nom contenant « online » : c'est
                // sur cette sous-chaîne que selectApk distingue les deux depuis la 2.6.3.
                output.outputFileName = when (variant.flavorName) {
                    "offline" -> "MG4Control-secure-${variant.versionName}.apk"
                    else      -> "MG4Control-${variant.flavorName}-${variant.versionName}.apk"
                }
            }
        }
    }
}

dependencies {
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
