package com.hdcontest.ecocoach

import android.app.Application

class EcoCoachApp : Application() {

    /** 화면 회전·재생성과 무관하게 주행을 이어서 기록하도록 프로세스 단위로 1개만 둔다. */
    val engine: TripEngine by lazy { TripEngine(this) }
}
