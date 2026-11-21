import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 内置 Kotlin 支持，不需要 org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Release 签名。
 *
 * 口令放在项目根的 `keystore.properties`（**不在版本库、不要外传**）。
 * 文件不存在时回落到 debug 签名 —— 保证 clone 下来的人也能 `assembleRelease`
 * 跑通流程，而不是卡在一个"缺密钥"的报错上。
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists()

android {
    namespace = "com.simplechat.app"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.simplechat.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 83
        versionName = "2.4.1"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    /**
     * proot 以 `jniLibs/<abi>/libproot*.so` 的形式随 APK 分发。
     *
     * 这是 Android 10 起 W^X 限制下的唯一可行路径：应用**不能**执行自己私有目录
     * 里的文件（`execve` 直接 EACCES），但 `nativeLibraryDir` 里的东西是可以的。
     * 代价是必须 `extractNativeLibs=true` 且对 jniLibs 用 legacy 打包 ——
     * 默认的"不落地、直接从 APK 映射"模式下，这些文件根本不会出现在磁盘上。
     *
     * 里面是**两套**成对的 proot + loader：
     * - `libproot5.so` / `libproot5loader.so`：Android 原生（bionic PIE）构建，
     *   首选。与系统库同源，最不容易被内核 / SELinux 挑刺。
     * - `libproot.so` / `libprootloader.so`：上游静态构建，后备。
     * loader 必须与 proot 同版本配对，混用会得到"退出码 255、一个字都不打印"。
     *
     * `keepDebugSymbols` 是给打包工具看的：它们不是 ARM 平台库，让 AGP 去 strip
     * 只会得到一个损坏的 ELF（本地已经手工 strip 过）。
     */
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 「关于」页直接读 BuildConfig.VERSION_NAME，避免手写版本号跟 build.gradle 漂移
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
        jniLibs {
            // 见上面那段说明：proot 必须落盘到 nativeLibraryDir 才可执行
            useLegacyPackaging = true
            keepDebugSymbols += setOf(
                "**/libproot.so",
                "**/libprootloader.so",
                "**/libproot5.so",
                "**/libproot5loader.so",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)
    implementation(libs.highlights)
    implementation(libs.latex.renderer)
    implementation(libs.latex.renderer.fonts)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.jsch)
    // 解开 Ubuntu rootfs（tar.gz）。自己写 tar 解析要处理 GNU/PAX 长名、符号链接、
    // 稀疏文件，出错就是"装到一半的文件系统"，不值得省这 600 KB。
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}




