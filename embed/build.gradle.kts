// net.gaiadesk:embed-android — the AAR: the Kotlin API (MIT) and the prebuilt
// libgaiadesk_embed.so for arm64-v8a, armeabi-v7a and x86_64 (GaiaDesk Native
// Binary Licence, LICENSE-BINARY). The .so files are not in this repository:
// `scripts/fetch-binary.sh <version>` downloads a release's, or a GaiaDesk
// build puts its own in src/main/jniLibs.
// No dependencies besides the Android SDK.
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // The real org.json on the JVM test classpath (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Publishing (configured, never run by CI): the release AAR + sources jar +
// javadoc jar (AGP's Dokka) + POM, signed when a key is given.
//
//   scripts/release.sh <version> [jniLibs.zip]   tests, then publishes into
//       embed/build/staging-repo and gathers dist/ (the GitHub release assets)
//   ./gradlew :embed:publishAllPublicationsToMavenCentralRepository
//       uploads to the Central Portal (central.sonatype.com) as a deployment
//       you then Publish there (automaticRelease = false)
//   ./gradlew :embed:publishToMavenLocal         ~/.m2, for local wrappers
//
// Credentials, as Gradle properties or ORG_GRADLE_PROJECT_<name> env vars:
//   mavenCentralUsername / mavenCentralPassword   a Central Portal user token
//   signingInMemoryKey                            ASCII-armoured private key (gpg --export-secret-keys --armor <id>)
//   signingInMemoryKeyId / signingInMemoryKeyPassword
// Without a signing key nothing is signed (local and staging builds); Maven
// Central refuses unsigned uploads, and release.sh says so.
val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
    providers.gradleProperty("signing.keyId").isPresent

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
    if (hasSigningKey) {
        signAllPublications()
    }
    coordinates("net.gaiadesk", "embed-android", project.version.toString())
    pom {
        name.set("GaiaDesk embed for Android")
        description.set("A \"Get help\" button for Android apps: share the app's own screen with your support team in the GaiaDesk console, with consent, masks and an always-visible indicator.")
        inceptionYear.set("2026")
        url.set("https://github.com/Gaia-Desk/gaiadesk-embed-android")
        licenses {
            license {
                name.set("MIT (Kotlin sources)")
                url.set("https://github.com/Gaia-Desk/gaiadesk-embed-android/blob/main/LICENSE")
                distribution.set("repo")
            }
            license {
                name.set("GaiaDesk Native Binary Licence (libgaiadesk_embed.so)")
                url.set("https://github.com/Gaia-Desk/gaiadesk-embed-android/blob/main/LICENSE-BINARY")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("gaiadesk")
                name.set("GaiaDesk")
                email.set("noreply@gaiadesk.net")
                url.set("https://gaiadesk.net")
            }
        }
        scm {
            url.set("https://github.com/Gaia-Desk/gaiadesk-embed-android")
            connection.set("scm:git:https://github.com/Gaia-Desk/gaiadesk-embed-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/Gaia-Desk/gaiadesk-embed-android.git")
        }
    }
}

publishing {
    repositories {
        // scripts/release.sh: the publication as a Maven layout under build/
        // (AAR, sources, javadoc, POM, Gradle module, checksums, signatures).
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-repo"))
        }
        // Set GAIADESK_MAVEN_URL (+ _USER/_PASSWORD) to publish to another repository.
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
