plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val releaseStoreFile = System.getenv("ETA_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("ETA_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("ETA_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ETA_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// CI passes the commit hash for non-release builds, appended as +<sha> per SemVer build metadata without affecting version ordering; local and release builds do not pass it
val buildMetadata = providers.gradleProperty("eta.buildMetadata").orNull?.takeIf { it.isNotBlank() }
require(buildMetadata == null || buildMetadata.matches(Regex("[0-9A-Za-z.-]+"))) {
    "eta.buildMetadata must contain only letters, digits, dots, and hyphens"
}
val buildMetadataSuffix = buildMetadata?.let { "+$it" }.orEmpty()

// Fork update channel: override with -Peta.updateRepo=owner/repo (defaults to upstream)
val updateRepo = providers.gradleProperty("eta.updateRepo").getOrElse("Mangi-11/Eta")

android {
    namespace = "io.github.mangi.eta"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "io.github.namanwtf.atom"
        minSdk = 34
        targetSdk = 37
        // versionCode rule: yyyyMMdd + a two-digit sequence number for the day (starting at 01), incremented manually together with versionName on release.
        versionCode = 2026111302
        versionName = "3.4.4"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")

        ndk {
            // Filter native libraries from both local and dependency AARs to ensure the APK contains ARM64 only.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // The version name carries the build type marker so the settings page and APK filename can distinguish Debug builds
            versionNameSuffix = "-debug" + buildMetadataSuffix
            isMinifyEnabled = false
            isPseudoLocalesEnabled = true
        }
        release {
            versionNameSuffix = buildMetadataSuffix
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        localeFilters += listOf("en")
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libproot_exec.so", "**/libproot_loader.so", "**/libeta_pty.so")
        }
        resources {
            // Merge the Xposed module declaration to keep the module entry valid after release shrinking
            merges += "META-INF/xposed/*"
            // Exclude only the signature/version metadata that causes packaging conflicts, to avoid clobbering Compose resources
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // versionCode uses date encoding and already-published values can only increase; the cap of 2100000000 is enough to cover through 2099.
        disable += "HighAppVersionCode"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric needs access to internal APIs on newer JDKs; these args only affect the test JVM.
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                "--enable-native-access=ALL-UNNAMED",
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        // The filename uses only versionName; both the build type marker and CI build metadata are carried by versionNameSuffix
        variant.outputs.forEach { output ->
            output.outputFileName.set(output.versionName.map { "Atom-v$it.apk" })
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    implementation(libs.jsoup)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    compileOnly(libs.libxposed.api)
    // UI-side RemotePreferences write bridge: submits configuration to the LSPosed database via XposedService;
    // The Hook side reads the configuration cache held by the current process via XposedInterface.getRemotePreferences.
    implementation(libs.libxposed.service)
    implementation(libs.dexkit)
    constraints {
        // Keep the window size and Android compatibility component versions shared by both UIs in the old dependency graph.
        implementation(libs.androidx.compose.window.size)
        implementation(libs.androidx.core.ktx)
    }
    implementation(libs.coui.ui)
    implementation(libs.coui.preference)
    implementation(libs.coui.icons)
    implementation(libs.coui.blur)
    implementation(libs.coui.squircle)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.glass)
    implementation(libs.miuix.glass.icons)
    implementation(libs.miuix.material)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.material.icons.extended)
    implementation(libs.androidx.navigationevent)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    // Use only the GFM parser; the chat rendering layer is built in-house under ui/markdown, freezing per block and wiring up character-by-character reveal.
    implementation(libs.intellij.markdown)
    implementation(libs.ratex.android)
    implementation(libs.hidden.api.bypass)

    // DataStore: Provider / Model structured JSON and key-value pairs such as the currently selected ID
    implementation(libs.datastore.preferences)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // OkHttp: replaces HttpURLConnection and supports SSE
    implementation(libs.okhttp)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.okhttp.sse)

    // Kotlinx Serialization: JSON for provider settings and runtime configuration
    implementation(libs.kotlinx.serialization.json)

    // Coroutines: declared explicitly to avoid an indeterminate transitive dependency version
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
}
