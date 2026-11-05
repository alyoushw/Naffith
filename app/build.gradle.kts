plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// محرك كلمة التنبيه «يا نفّذ» (sherpa-onnx، يشتغل بدون إنترنت). الملف كبير (~50MB)
// فما نحطه في المستودع؛ ينزل تلقائياً أول مرة من صفحة إصدارات المشروع.
val sherpaVersion = "2.0.1"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")
if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    val tmp = File(sherpaAar.path + ".part")
    val url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar")
    logger.lifecycle("Downloading $url")
    url.toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    tmp.renameTo(sherpaAar)
}

android {
    namespace = "com.naffith.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.naffith.app"
        minSdk = 24
        targetSdk = 35
        // رقم البناء في GitHub Actions يكبر مع كل بناء، عشان كل نسخة تحدّث اللي قبلها.
        val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"

        // جوالات 64-bit بس (كل الجوالات الحديثة)، يصغّر حجم التطبيق للنص.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        // نضغط مكتبات المحرك داخل الـ APK (بدونها الحجم يتضاعف).
        jniLibs.useLegacyPackaging = true
    }

    signingConfigs {
        // مفتاح debug ثابت في المستودع: بدونه كل بناء على GitHub يتوقّع بمفتاح جديد
        // والجوال يرفض التحديث فوق النسخة القديمة.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // الواجهة عربية بالكامل، والترجمة الإنجليزية تخص اسم التطبيق فقط.
        disable += "MissingTranslation"
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation(files(sherpaAar))

    testImplementation("junit:junit:4.13.2")
}
