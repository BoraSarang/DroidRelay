plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

import java.util.Properties

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

// HTTPS 자체서명 키스토어 비밀번호 — tls.properties (없으면 빌드 실패, 시크릿 하드코딩 금지)
val tlsPropsFile = rootProject.file("tls.properties")
val tlsKeystorePassword: String =
    if (tlsPropsFile.exists()) {
        Properties().apply { tlsPropsFile.inputStream().use { load(it) } }
            .getProperty("tlsKeystorePassword", "")
            .also { require(it.isNotEmpty()) { "tls.properties에 tlsKeystorePassword가 비어 있음" } }
    } else {
        throw GradleException("tls.properties 없음 — apps/android/tls.properties에 tlsKeystorePassword 기록 필요")
    }

android {
    namespace = "com.borasarang.droidrelay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.borasarang.droidrelay"
        minSdk = 26
        targetSdk = 36
        // 버전 단일 진실: root gradle.properties (변경은 scripts/bump-version.sh 로만)
        versionCode = (project.findProperty("versionCode") as String).toInt()
        versionName = project.findProperty("versionName") as String
        buildConfigField("String", "TLS_KEYSTORE_PASSWORD", "\"$tlsKeystorePassword\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes +=
                setOf(
                    "META-INF/INDEX.LIST",
                    "META-INF/io.netty.versions.properties",
                    "META-INF/native-image/**",
                    "META-INF/versions/9/**",
                )
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation("io.ktor:ktor-io:3.5.2")
    implementation(libs.zxing.core)
    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4j.android.arm64)
    implementation(libs.ffmpeg.kit.https)
    implementation(libs.smart.exception.java)

    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    testImplementation(libs.junit)
}
