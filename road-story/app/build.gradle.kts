import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Pleos Playground > My Project 에서 발급받은 값을 local.properties 에 넣어주세요.
// 비어 있으면 TTS/STT 를 온디바이스 모드로 동작시킵니다.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.hdcontest.roadstory"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hdcontest.roadstory"
        // Pleos Playground 배포 앱은 API 28 이상 필요
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "PLEOS_CLIENT_ID", "\"${localProps.getProperty("pleos.clientId", "")}\"")
        buildConfigField("String", "PLEOS_CLIENT_SECRET", "\"${localProps.getProperty("pleos.clientSecret", "")}\"")
        // 공공데이터포털 > 한국관광공사_국문 관광정보 서비스_GW 인증키. 비어 있으면 내장 명소(pois.json)만 쓴다.
        buildConfigField("String", "TOUR_API_KEY", "\"${localProps.getProperty("tourapi.serviceKey", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // 단위 테스트에서 android.jar 의 org.json 스텁 대신 실제 구현을 쓴다
    testImplementation("org.json:json:20240303")
}
