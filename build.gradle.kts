plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

/*
 * Käännöshakemisto voidaan siirtää projektikansion ulkopuolelle asettamalla
 * `buildDirRoot` esimerkiksi tiedostoon ~/.gradle/gradle.properties.
 *
 * Tarpeen kun projekti sijaitsee pilvisynkronoidussa kansiossa: synkronointi
 * pitää käännöksen väliaikaistiedostoja auki, jolloin Gradle kaatuu satunnaisesti
 * virheeseen "Could not delete ...". Kansion merkitseminen synkronoinnista
 * ohitettavaksi ei riittänyt.
 */
val buildDirRoot: String? = providers.gradleProperty("buildDirRoot").orNull
if (buildDirRoot != null) {
    allprojects {
        val name = project.path.replace(':', '_').trim('_').ifEmpty { "root" }
        layout.buildDirectory.set(File(buildDirRoot, name))
    }
}
