package com.hdcontest.roadstory

import android.app.Application

class YeoginEodiApp : Application() {

    /** 화면과 포그라운드 서비스가 함께 쓰는 가이드 엔진 (프로세스 단위 1개) */
    val engine: GuideEngine by lazy { GuideEngine(this) }
}
