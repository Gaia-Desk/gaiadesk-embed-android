// GaiaDesk embed for Android. Plugin versions only; see embed/ and examples/.
plugins {
    id("com.android.library") version "8.7.3" apply false
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Maven Central (Central Portal) publishing + signing for :embed.
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}
