import com.android.build.gradle.internal.tasks.CompileArtProfileTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // Target declarations - add or remove as needed below. These define
    // which platforms this KMP module supports.
    // See: https://kotlinlang.org/docs/multiplatform-discover-project.html#targets
    android {
        namespace = "com.maxrave.spotify"
        compileSdk = 37
        minSdk = 26
    }

    iosArm64()
    iosSimulatorArm64()

    jvm {
    }

    // Source set declarations.
    // Declaring a target automatically creates a source set with the same name. By default, the
    // Kotlin Gradle Plugin creates additional source sets that depend on each other, since it is
    // common to share sources between related targets.
    // See: https://kotlinlang.org/docs/multiplatform-hierarchy.html
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                // Add KMP dependencies here
                implementation(projects.ktorExt)
                implementation(libs.ktor.client.encoding)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.protobuf)
                implementation(libs.ktor.client.logging)
                implementation(libs.kotlin.test)
                implementation(projects.common)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        androidMain {
            dependencies {
                // JVM-only artifacts, kept on the Android/JVM classpath exactly as before; iOS has no
                // build of them.
                implementation(libs.kotlin.reflect)
                implementation(libs.common)
                implementation(libs.logging)
                implementation(libs.kotlin.onetimepassword)
            }
        }

        iosMain {
            dependencies {
                // Add iOS-specific dependencies here. This a source set created by Kotlin Gradle
                // Plugin (KGP) that each specific iOS target (e.g., iosX64) depends on as
                // part of KMP’s default source set hierarchy. Note that this source set depends
                // on common by default and will correctly pull the iOS artifacts of any
                // KMP dependencies declared in commonMain.
                // HMAC-SHA1 for the TOTP (SpotifyTotp.ios.kt), in place of kotlin-onetimepassword.
                implementation(libs.okio)
            }
        }

        jvmMain {
            dependencies {
                // JVM-only artifacts, kept on the Android/JVM classpath exactly as before; iOS has no
                // build of them.
                implementation(libs.kotlin.reflect)
                implementation(libs.common)
                implementation(libs.logging)
                implementation(libs.kotlin.onetimepassword)
            }
        }
    }
}

tasks.withType<CompileArtProfileTask> {
    enabled = false
}