import org.jetbrains.kotlin.gradle.dsl.JvmTarget
// Imported explicitly because inside the android {} block `java` resolves to
// the java plugin accessor, not the java.* package, so java.util.Properties
// would fail to compile.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.edward.escalationloot"; compileSdk = 35
    defaultConfig { applicationId = "com.edward.escalationloot"; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

    // Strip build-tooling files that ship inside kotlin-stdlib's JAR.
    //
    // kotlin-tooling-metadata.json describes the IDE that compiled the
    // standard library; DebugProbesKt.bin is a coroutines debug facility. Both
    // are dead weight in a shipped app, and neither is ever read at runtime.
    // They do not affect Play Protect either way, but a public APK should not
    // carry IDE bookkeeping.
    packaging {
        resources {
            // META-INF/*.kotlin_module is deliberately NOT excluded: it is
            // consumed by kotlin-reflect at runtime, and losing it turns into a
            // NoClassDefFoundError rather than a clean failure.
            excludes += setOf(
                "kotlin-tooling-metadata.json",
                "**/DebugProbesKt.bin",
                "DebugProbesKt.bin"
            )
        }
    }

    // Release, not debug, so the package is not android:debuggable. Xiaomi/MIUI's
    // security scan blocks debuggable APKs with "App blocked to protect your
    // device" and refuses to install them outright.
    //
    // The signing key is NOT in this repository on purpose. It stays on the
    // maintainer's machine only, so nobody else can build an "update" to this
    // app that Android would accept as genuinely ours. Configure it with either
    // of these, both of which are gitignored:
    //
    //   keystore.properties  (storeFile / storePassword / keyAlias / keyPassword)
    //   or the environment:  ESCALATION_KEYSTORE, ESCALATION_KEYSTORE_PASSWORD,
    //                        ESCALATION_KEY_ALIAS, ESCALATION_KEY_PASSWORD
    //
    // Without a key the release build still works, just unsigned, so a fresh
    // clone can compile and run the app without any secrets.
    signingConfigs {
        val props = Properties().apply {
            val f = rootProject.file("keystore.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        fun secret(env: String, key: String): String? =
            System.getenv(env)?.takeIf { it.isNotBlank() }
                ?: props.getProperty(key)?.takeIf { it.isNotBlank() }

        val storePath = secret("ESCALATION_KEYSTORE", "storeFile")
        create("release") {
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = secret("ESCALATION_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = secret("ESCALATION_KEY_ALIAS", "keyAlias")
                keyPassword = secret("ESCALATION_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isDebuggable = false
            // Only sign when a key is actually configured. Otherwise Gradle
            // produces a valid but unsigned APK instead of failing the build,
            // which is what lets a fresh clone build without any secrets.
            signingConfigs.findByName("release")?.takeIf { it.storeFile != null }?.let {
                signingConfig = it
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") {
            isDebuggable = true
        }
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // Parses ProtoTrack's target-loot.json, which stays available when the
    // PHP page returns 500.
    implementation("com.google.code.gson:gson:2.11.0")
}
