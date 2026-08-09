plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.focusguard.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.focusguard.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 52
        versionName = "3.4.0"
    }

    // 固定签名：仓库内置 keystore，任何机器/任何次构建签名都一致，
    // 从而支持覆盖安装升级（此前用随机 debug key 导致"签名不一致"无法升级）
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/focusguard-debug.jks")
            storePassword = "focusguard"
            keyAlias = "focusguard"
            keyPassword = "focusguard"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    
    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    
    // Activity & Lifecycle
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")
    
    // Core
    implementation("androidx.core:core-ktx:1.12.0")

    // Material Components XML 库——themes.xml 中的 Theme.Material3.* 主题依赖它
    implementation("com.google.android.material:material:1.11.0")
    
    // OkHttp for AI API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // DataStore for settings
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // WorkManager：守护看门狗（进程被杀后仍能被系统唤起重启守护服务）
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // AI 对话 Markdown 渲染（借鉴开源 compose-markdown 实现）
    implementation("com.github.jeziellago:compose-markdown:0.5.8")

    // ── Shizuku / Dhizuku 高级权限增强（可选，无授权时自动降级） ──
    // Shizuku：免 Root 以 shell 身份执行命令（自动授权使用情况/电池优化白名单）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Dhizuku：共享 Device Owner 权限 → Lock Task 系统级防退出
    // 2.5.4 与新版 Dhizuku 服务器 AIDL 兼容（2.4 是 2023 年的旧接口）
    implementation("io.github.iamr0s:Dhizuku-API:2.5.4")
    // 反射隐藏 API（构造 Dhizuku 包装后的 DevicePolicyManager）
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
}
