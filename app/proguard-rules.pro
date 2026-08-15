# ML Kit lataa tekstintunnistusmallin refleksiivisesti.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_bundled_common.** { *; }
-dontwarn com.google.mlkit.**

# Room luo toteutukset käännösaikana; entiteettien kentät luetaan nimillä.
-keep class fi.tyovuorolukija.data.** { *; }

# Parserin data-luokat ovat pelkkää dataa, mutta pidetään nimet luettavina
# jotta lokit ja mahdolliset kaatumisraportit ovat tulkittavissa.
-keepnames class fi.tyovuorolukija.parser.** { *; }
