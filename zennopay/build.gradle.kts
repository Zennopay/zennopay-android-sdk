plugins {
    id("com.android.library") version "8.5.0"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    `maven-publish`
}

android {
    namespace = "com.zennopay.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
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
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
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
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.zennopay"
            artifactId = "sdk"
            version = "0.2.0"

            afterEvaluate {
                from(components["release"])
            }
        }
    }

    // Publish target. Credentials/URL are supplied by CI as -P properties or
    // via env vars; NO secrets here.
    // For GitHub Packages set:
    //   mavenRepoUrl=https://maven.pkg.github.com/<org>/<repo>
    //   mavenUsername=<github user>   mavenPassword=<PAT with write:packages>
    // For Maven Central use the OSSRH URL + Sonatype credentials instead.
    repositories {
        maven {
            name = "ZennopayMaven"
            url = uri(
                (project.findProperty("mavenRepoUrl") as String?)
                    ?: System.getenv("MAVEN_REPO_URL")
                    ?: "https://maven.pkg.github.com/Zennopay/zennopay-android-sdk"
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
