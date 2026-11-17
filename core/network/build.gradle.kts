plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.agentic.core.network"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.all {
            val envKey = System.getenv("GEMINI_API_KEY") ?: System.getenv("GOOGLE_API_KEY")
            val sysKey = System.getProperty("GEMINI_API_KEY") ?: System.getProperty("GOOGLE_API_KEY")
            val projKey = (project.findProperty("gemini.api.key") ?: project.findProperty("GEMINI_API_KEY") ?: project.findProperty("google.api.key")) as? String
            val resolvedKey = envKey ?: sysKey ?: projKey
            if (!resolvedKey.isNullOrBlank()) {
                it.systemProperty("GEMINI_API_KEY", resolvedKey)
            }
            val testModel = System.getenv("GEMINI_TEST_MODEL") ?: System.getProperty("GEMINI_TEST_MODEL")
            if (!testModel.isNullOrBlank()) {
                it.systemProperty("GEMINI_TEST_MODEL", testModel)
            }
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":model-provider"))
    implementation(project(":agent-runtime"))
    implementation(project(":tools:spi"))
    implementation(project(":tools:local"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
