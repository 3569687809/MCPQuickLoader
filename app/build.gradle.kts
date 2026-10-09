import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// release 签名信息从 local.properties 读（那个文件不进版本库，所以密码不会泄露出去）。
// 别人克隆这个项目时没有这个文件，release 就打不出签名包，但编译不受影响。
val keystoreProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("RELEASE_STORE_FILE") != null

android {
    namespace = "com.z1yr.mcpquickloader"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.z1yr.mcpquickloader"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0-Beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = rootProject.file(keystoreProps.getProperty("RELEASE_STORE_FILE"))
                storePassword = keystoreProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 没有签名配置时就不挂，这样没密钥的人也能跑 assembleRelease（只是产出未签名包）
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
