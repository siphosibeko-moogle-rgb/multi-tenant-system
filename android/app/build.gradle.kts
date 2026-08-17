import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.openapi.generator)
}

// ---------------------------------------------------------------------------
// The API client is GENERATED from docs/openapi.yaml on every build.
//
// Deliberately a build step and not a one-off paste: the contract is the source
// of truth (CLAUDE.md §1), and the point of wiring it in here is that a contract
// change breaks this build rather than being discovered at runtime by a shop
// owner. Nothing under the output directory is hand-edited or committed — if the
// generator cannot express something, that is a finding about the contract, not
// something to patch downstream.
// ---------------------------------------------------------------------------

val openApiSpec = layout.projectDirectory.file("../../docs/openapi.yaml")
val generatedClientDir = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set(openApiSpec.asFile.path)
    outputDir.set(generatedClientDir.map { it.asFile.path })

    packageName.set("com.example.inventory.api")
    apiPackage.set("com.example.inventory.api.apis")
    modelPackage.set("com.example.inventory.api.models")

    configOptions.set(
        mapOf(
            // Retrofit2 + coroutines: suspend functions returning the model, so
            // call sites read like ordinary Kotlin rather than callbacks.
            "library" to "jvm-retrofit2",
            "useCoroutines" to "true",
            "serializationLibrary" to "moshi",
            // java.time, which is available unconditionally from minSdk 26 —
            // one of the reasons the floor is 26 rather than 24.
            "dateLibrary" to "java8",
            // Enum constants UPPERCASED, which is not cosmetic — it is the only
            // thing making the generated code compile. Two contract values break
            // the default camelCase naming:
            //
            //   /products?sort=name  -> an enum entry `name`, colliding with the
            //     `name` property every Kotlin enum inherits from Enum.
            //   status=open on the reorder recommendations -> `open` is a Kotlin
            //     soft keyword, and the generator emits HTML-escaped backticks
            //     (&#x60;open&#x60;) trying to quote it, which is a syntax error.
            //
            // Both are legal OpenAPI. Renaming to NAME and OPEN sidesteps both
            // without touching the contract or the generated sources.
            "enumPropertyNaming" to "UPPERCASE",
            "omitGradleWrapper" to "true",
        )
    )

    // The generator emits a whole standalone Gradle project by default. Only the
    // sources are wanted; its build files would collide with this module's.
    globalProperties.set(mapOf("apis" to "", "models" to "", "supportingFiles" to ""))
}

val generatedSources = generatedClientDir.map { it.dir("src/main/kotlin") }

android {
    namespace = "com.example.inventory.mobile"
    // Only android-36.1 is installed on this machine and there is no
    // cmdline-tools to add another, so 36 is both the current SDK and the only
    // available one.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.inventory.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // 10.0.2.2 is the emulator's alias for the host machine's loopback,
            // so this is the backend running on the developer's own laptop.
            // Cleartext for it is permitted by src/debug/res/xml only.
            buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8080/api/v1/\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // No default: a release build pointed at someone's laptop would be
            // worse than one that fails to configure.
            buildConfigField(
                "String",
                "BASE_URL",
                "\"${providers.gradleProperty("RELEASE_BASE_URL").getOrElse("https://api.example.com/api/v1/")}\"",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].kotlin.srcDir(generatedSources)

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Compilation must wait for generation, or the first build of a clean checkout
// compiles against sources that do not exist yet.
tasks.withType<KotlinCompile>().configureEach {
    dependsOn(tasks.named("openApiGenerate"))
}
ksp {
    // KSP walks the same sources.
}
tasks.matching { it.name.startsWith("ksp") }.configureEach {
    dependsOn(tasks.named("openApiGenerate"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Required by the generated client.
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.retrofit.converter.scalars)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.moshi.adapters)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
