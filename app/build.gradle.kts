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
        versionCode = 116
        versionName = "3.11.15"
    }

    // 固定签名：CI 从 GitHub Secrets 恢复同一 PKCS12，密钥不进入公开仓库；
    // 所有后续构建证书一致，支持覆盖安装升级。
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/focusguard-release.p12")
            storeType = "PKCS12"
            storePassword = System.getenv("FOCUSGUARD_STORE_PASSWORD")
                ?: providers.gradleProperty("focusguardStorePassword").orNull ?: ""
            keyAlias = System.getenv("FOCUSGUARD_KEY_ALIAS")
                ?: providers.gradleProperty("focusguardKeyAlias").orNull ?: ""
            keyPassword = System.getenv("FOCUSGUARD_KEY_PASSWORD")
                ?: providers.gradleProperty("focusguardKeyPassword").orNull ?: ""
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
        // 两版通过 BuildConfig.CHALLENGE_MODE 在编译期确定答题模式（AGP 8 默认关闭该特性）
        buildConfig = true
    }

    // 两个版本：学段版（真题库+选年级）/ 通用版（繁复计算题，不选年级）
    // 学段版沿用原 applicationId（老用户升级无感、已激活的 Dhizuku 授权不失效）；
    // 通用版加后缀，可与学段版同时安装，但需要用户重新在 Dhizuku 中授权本应用。
    flavorDimensions += "edition"
    productFlavors {
        create("edu") {
            dimension = "edition"
            buildConfigField("String", "CHALLENGE_MODE", "\"edu\"")
        }
        create("general") {
            dimension = "edition"
            applicationIdSuffix = ".general"
            buildConfigField("String", "CHALLENGE_MODE", "\"general\"")
        }
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    testOptions {
        // LockState 依赖 android.util / SharedPreferences 接口；测试中用假实现，
        // 未覆盖的 android.* 调用返回默认值而不是抛异常
        unitTests.isReturnDefaultValues = true
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

    // 单元测试（JVM，本地逻辑：锁机计时 / 篡改 / 番茄钟）
    testImplementation("junit:junit:4.13.2")
}
