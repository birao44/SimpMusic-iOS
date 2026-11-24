import com.android.build.gradle.internal.tasks.CompileArtProfileTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "com.my.kizzy"
        compileSdk = 37
        minSdk = 26
    }

    iosArm64()
    iosSimulatorArm64()

    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                // Add KMP dependencies here
                implementation(projects.common)
                implementation(projects.domain)
                implementation(projects.ktorExt)
                implementation(libs.ktor.client.encoding)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.protobuf)
                implementation(libs.ktor.client.logging)
                implementation(libs.kotlin.test)
                implementation(libs.okio)
            }
        }

        androidMain {
            dependencies {
                // JVM-only artifacts, kept on the Android/JVM classpath exactly as before; iOS has no
                // build of them.
                implementation(libs.kotlin.reflect)
                implementation(libs.common)
                implementation(libs.logging)
            }
        }

        iosMain {
            dependencies {}
        }

        jvmMain {
            dependencies {
                // JVM-only artifacts, kept on the Android/JVM classpath exactly as before; iOS has no
                // build of them.
                implementation(libs.kotlin.reflect)
                implementation(libs.common)
                implementation(libs.logging)
            }
        }
    }
}

tasks.withType<CompileArtProfileTask> {
    enabled = false
}