package com.qingjing.wallpaper_android.install

/** elapsedRealtime includes sleep. A saved trial never receives a fresh deadline on resume. */
internal data class TrialDeadline(val boot: Int,val started: Long,val deadline: Long) {
    init { require(started >= 0 && deadline - started == DURATION && deadline > started) }
    fun valid(currentBoot: Int,now: Long) = boot >= 0 && currentBoot == boot && now >= started && now < deadline
    fun remainingSeconds(currentBoot: Int,now: Long): Long = if(valid(currentBoot,now)) (deadline-now+999)/1000 else 0
    companion object {
        const val DURATION = 120_000L
        fun start(boot: Int,now: Long) = TrialDeadline(boot,now,Math.addExact(now,DURATION))
    }
}
