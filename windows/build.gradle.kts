import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
    id("org.jetbrains.compose") version "1.9.3"
    id("com.google.devtools.ksp") version "2.2.21-2.0.4"
}

group = "com.vybra.music"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.ui.ExperimentalComposeUiApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

val ffmpegVersion = "7.1.1-1.5.12"
val javacppVersion = "1.5.12"
val room = "2.8.3"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.components.resources)
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.androidx.navigation:navigation-compose:2.9.1")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.9.6")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    implementation("androidx.room:room-runtime:$room")
    ksp("androidx.room:room-compiler:$room")
    implementation("androidx.sqlite:sqlite-bundled:2.6.1")
    implementation("androidx.datastore:datastore-preferences-core:1.1.7")

    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("sh.calvin.reorderable:reorderable:2.5.1")

    // Decodificación de audio (MP3, FLAC, M4A/AAC, OGG, OPUS, WAV, WMA...) y etiquetas.
    implementation("org.bytedeco:javacpp:$javacppVersion")
    implementation("org.bytedeco:javacpp:$javacppVersion:windows-x86_64")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion:windows-x86_64")
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.vybra.music.MainKt"
        jvmArgs += listOf("-Xmx1g", "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "VYBRA"
            packageVersion = "1.0.0"
            description = "Reproductor de música local"
            vendor = "VYBRA"
            copyright = "© 2026 VYBRA"
            modules("java.sql", "java.naming", "jdk.unsupported", "java.desktop", "java.logging", "java.net.http")
            windows {
                iconFile.set(project.file("icons/vybra.ico"))
                menu = true
                menuGroup = "VYBRA"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6f1c2d6e-7a1b-4f0e-9b8e-3c5a7d2e9f41"
            }
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}
