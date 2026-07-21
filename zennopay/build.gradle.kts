import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.android.library") version "8.5.0"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    // Sonatype Central Portal publishing + PGP signing.
    id("com.vanniktech.maven.publish") version "0.30.0"
}

android {
    namespace = "com.zennopay.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // Compose compiler compatible with Kotlin 1.9.24.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
        // DEBUG-only screen gallery (mock state; compiled out of release).
        getByName("debug") {
            java.srcDirs("src/debug/kotlin")
        }
        getByName("androidTest") {
            java.srcDirs("src/androidTest/kotlin")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    // NOTE: the publication variant (sources + javadoc jars) is registered by
    // the com.vanniktech.maven.publish plugin via AndroidSingleVariantLibrary
    // below, so there is intentionally no android { publishing { } } block here.
}

dependencies {
    // Kotlin coroutines — the whole flow is suspend-based (REST + refresh hook).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // AndroidX Activity + Compose. The native checkout is a full-screen
    // ComponentActivity hosting a Compose NavHost.
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")

    // Jetpack Compose (BOM-managed).
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime")

    // CameraX — native camera capture for the QR scanner.
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ML Kit Barcode Scanning — on-device QR decode (bundled model, no Play deps).
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // DataStore (Preferences) — durable idempotency-key persistence for confirm.
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // Compose semantics-tree tests. Local tests run under Robolectric (no
    // emulator needed for the TalkBack/a11y assertions); the same suite style
    // is also compiled for the on-device (connected) pass. The pinned Compose
    // (1.6.x via BOM 2024.06.00) has no `enableAccessibilityChecks()` /
    // ATF integration yet, so semantics properties are asserted directly.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
}

mavenPublishing {
    // Maven COORDINATE only. The reverse-DNS namespace for the company domain
    // zennopay.in is `in.zennopay` (NOT com.zennopay). This is deliberately
    // decoupled from the Kotlin source package and the Android `namespace`,
    // which both stay `com.zennopay.sdk` — the Gradle groupId and the Kotlin
    // package are independent and are allowed to differ. Partners depend on
    //   implementation("in.zennopay:sdk:0.7.0")
    // but still `import com.zennopay.sdk.*`.
    coordinates("in.zennopay", "sdk", "0.7.0")

    // Register the release variant with sources + (empty) javadoc jars, both
    // of which Sonatype Central requires for validation.
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        ),
    )

    // Sonatype Central Portal is the primary (and default) target.
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    // Sign every publication with the in-memory PGP key supplied by CI via
    // ORG_GRADLE_PROJECT_signingInMemoryKey / *KeyPassword. NO secrets here.
    signAllPublications()

    pom {
        name.set("Zennopay Android SDK")
        description.set(
            "Android SDK for Zennopay: your app's users scan a local merchant QR code abroad and pay it from their wallet balance.",
        )
        url.set("https://github.com/Zennopay/zennopay-android-sdk")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("zennopay")
                name.set("Zennopay")
                url.set("https://zennopay.in")
            }
        }
        scm {
            url.set("https://github.com/Zennopay/zennopay-android-sdk")
            connection.set("scm:git:git://github.com/Zennopay/zennopay-android-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/Zennopay/zennopay-android-sdk.git")
        }
    }
}

// Optional fallback repository: GitHub Packages. Central is primary; this
// target is only used when the GitHubPackages tasks are invoked explicitly
// (e.g. ./gradlew publishAllPublicationsToGitHubPackagesRepository) with a
// PAT. Credentials come from CI props/env — NO secrets here.
publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri(
                (project.findProperty("mavenRepoUrl") as String?)
                    ?: System.getenv("MAVEN_REPO_URL")
                    ?: "https://maven.pkg.github.com/Zennopay/zennopay-android-sdk",
            )
            credentials {
                username = (project.findProperty("mavenUsername") as String?)
                    ?: System.getenv("MAVEN_USERNAME")
                password = (project.findProperty("mavenPassword") as String?)
                    ?: System.getenv("MAVEN_PASSWORD")
            }
        }
    }
}
