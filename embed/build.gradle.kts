// net.gaiadesk:embed-android — the AAR: the Kotlin API (MIT) and the prebuilt
// libgaiadesk_embed.so for arm64-v8a, armeabi-v7a and x86_64 (GaiaDesk Native
// Binary Licence, LICENSE-BINARY). The .so files are not in this repository:
// `scripts/fetch-binary.sh <version>` downloads a release's, or a GaiaDesk
// build puts its own in src/main/jniLibs.
// No dependencies besides the Android SDK.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

group = "net.gaiadesk"
version = "0.1.0"

android {
    namespace = "net.gaiadesk.embed"
    compileSdk = 35

    defaultConfig {
        // 8.0: PixelCopy of a window, MediaCodec's request-sync from the NDK.
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // The real org.json on the JVM test classpath (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// `./gradlew :embed:publishToMavenLocal` (or a repository below) publishes
// net.gaiadesk:embed-android. Configured, not run by any script here.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "net.gaiadesk"
            artifactId = "embed-android"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("GaiaDesk embed for Android")
                description.set("A \"Get help\" button for Android apps: share the app's own screen with your support team in the GaiaDesk console, with consent, masks and an always-visible indicator.")
                url.set("https://github.com/Gaia-Desk/gaiadesk-embed-android")
                licenses {
                    license {
                        name.set("MIT (Kotlin sources)")
                        url.set("https://github.com/Gaia-Desk/gaiadesk-embed-android/blob/main/LICENSE")
                    }
                    license {
                        name.set("GaiaDesk Native Binary Licence (libgaiadesk_embed.so)")
                        url.set("https://github.com/Gaia-Desk/gaiadesk-embed-android/blob/main/LICENSE-BINARY")
                    }
                }
                developers {
                    developer {
                        name.set("GaiaDesk")
                        email.set("noreply@gaiadesk.net")
                    }
                }
                scm {
                    url.set("https://github.com/Gaia-Desk/gaiadesk-embed-android")
                }
            }
        }
    }
    repositories {
        // Set GAIADESK_MAVEN_URL (+ _USER/_PASSWORD) to publish somewhere real.
        val url = System.getenv("GAIADESK_MAVEN_URL")
        if (url != null) {
            maven {
                name = "gaiadesk"
                setUrl(url)
                credentials {
                    username = System.getenv("GAIADESK_MAVEN_USER")
                    password = System.getenv("GAIADESK_MAVEN_PASSWORD")
                }
            }
        }
    }
}
