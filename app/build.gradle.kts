import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Allekirjoitustiedot luetaan versionhallinnan ulkopuolisesta tiedostosta.
// Jos sitä ei ole, release-käännös jää allekirjoittamatta eikä käännös kaadu —
// näin projektin voi kääntää myös koneella jolla avainta ei ole.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "fi.tyovuorolukija"
    compileSdk = 35

    defaultConfig {
        applicationId = "fi.tyovuorolukija"
        minSdk = 26          // java.time ilman desugarointia
        targetSdk = 35
        // versionCode on koneelle: se ratkaisee onko paketti uudempi kuin asennettu.
        // **Se ei voi koskaan pienentyä** — Android hylkää asennuksen
        // (INSTALL_FAILED_VERSION_DOWNGRADE), ja ainoa kiertotie olisi sovelluksen
        // poisto, joka veisi historian ja kalenterikirjanpidon. Nosta se siis
        // jokaisella jaettavalla käännöksellä.
        //
        // versionName on ihmiselle: se näkyy aloitusnäkymässä ja Tietoa
        // sovelluksesta -näkymässä. Pidä se samassa tahdissa versionCoden kanssa —
        // eri numerot samassa paketissa ("0.2 (3)") näyttävät vain sekavalta.
        versionCode = 5
        versionName = "0.5"
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                // rootProject.file: polku on projektin juuresta, ei app-moduulista.
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
        compose = true
        buildConfig = true // versionName Tietoa sovelluksesta -näkymään
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    implementation(project(":parser"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.exifinterface)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.mlkit.text.recognition)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
