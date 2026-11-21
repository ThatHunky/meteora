import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.thathunky.meteora"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.thathunky.meteora"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        // Must stay valid semver: YTMDesktop validates appVersion at pairing.
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The bundled Python is ~15 MB per ABI; the OP7T and current phones are arm64.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal sideload builds: sign with the debug key until a release key exists.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        // youtubedl-android executes libpython.so from nativeLibraryDir, so it must be extracted.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/io.netty.versions.properties")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // libmeteora_core.so, built from rust/meteora-core by cargoBuildArm64 below.
    // A File, not a Provider: AGP 9 refuses providers here. The task dependency is added below.
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("rustJniLibs").get().asFile)
}

// ---- Rust core (rust/meteora-core) for arm64, with the NDK's clang ----
// Plain cargo, not cargo-ndk: cargo-ndk 4.1.2 overrides the bindgen clang arguments below.
val rustDir: File = rootProject.file("rust/meteora-core")
val rustNdkVersion = "30.0.16248370"
val cargoBuildArm64 = tasks.register<Exec>("cargoBuildArm64") {
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.isFile }?.reader()?.use { props.load(it) }
    val sdk = props.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: error("sdk.dir is not set in local.properties")
    val windows = System.getProperty("os.name").startsWith("Windows")
    val host = when {
        windows -> "windows-x86_64"
        System.getProperty("os.name").startsWith("Mac") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val tc = File(sdk, "ndk/$rustNdkVersion/toolchains/llvm/prebuilt/$host").invariantSeparatorsPath
    // The clang version folder (21 in NDK 30), read rather than hard-coded.
    val clangVersion = File(tc, "lib/clang").listFiles()?.singleOrNull { it.isDirectory }?.name
        ?: error("no clang in $tc/lib/clang: is NDK $rustNdkVersion installed?")
    val clang = "$tc/bin/aarch64-linux-android31-clang" + if (windows) ".cmd" else ""
    val cargo = System.getenv("CARGO_HOME")?.let { File(it, "bin/cargo" + if (windows) ".exe" else "") }?.takeIf { it.isFile }?.path ?: "cargo"

    workingDir = rustDir
    // --quiet: a passing `gradlew -q` prints nothing; errors still show.
    commandLine(cargo, "build", "--quiet", "--target", "aarch64-linux-android", "--release")
    environment("LIBCLANG_PATH", "$tc/bin")
    environment("CC_aarch64_linux_android", clang)
    environment("AR_aarch64_linux_android", "$tc/bin/llvm-ar" + if (windows) ".exe" else "")
    environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", clang)
    environment(
        "BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android",
        "--target=aarch64-linux-android31 --sysroot=$tc/sysroot -resource-dir=$tc/lib/clang/$clangVersion",
    )

    inputs.dir(rustDir.resolve("src"))
    inputs.files(rustDir.resolve("Cargo.toml"), rustDir.resolve("Cargo.lock"))
    val built = rustDir.resolve("target/aarch64-linux-android/release/libmeteora_core.so")
    val copied = layout.buildDirectory.file("rustJniLibs/arm64-v8a/libmeteora_core.so")
    outputs.file(copied)
    doLast {
        val dest = copied.get().asFile
        dest.parentFile.mkdirs()
        built.copyTo(dest, overwrite = true)
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach { dependsOn(cargoBuildArm64) }

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.animation.ExperimentalSharedTransitionApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.lifecycle.service)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)

    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource.okhttp)
    // yt-dlp + embedded Python for phone playback (GPL-3.0; see PLAN.md).
    implementation(libs.youtubedl.library)
    implementation(libs.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.viewmodel.compose)

    implementation(libs.okhttp)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.serialization.json)
    implementation(libs.socketio.client) {
        exclude(group = "org.json", module = "json") // provided by Android
    }

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.material.kolor)
    implementation(libs.reorderable)
    implementation(libs.graphics.shapes)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.mockwebserver)
}
