package com.qingjing.wallpaper_android.install

import org.junit.Assert.*
import org.junit.Test

class TrialDeadlineTest {
    @Test fun expiresAtExactly120SecondsIncludingBackgroundAndSleep() {
        val trial = TrialDeadline.start(7,10_000)
        assertEquals(120L,trial.remainingSeconds(7,10_000))
        assertEquals(1L,trial.remainingSeconds(7,129_999))
        assertEquals(0L,trial.remainingSeconds(7,130_000))
        assertFalse(trial.valid(7,500_000))
    }
    @Test fun processRestoreUsesTheSavedDeadlineWithoutExtendingIt() {
        val trial = TrialDeadline.start(11,20_000)
        val restored = TrialDeadline(trial.boot,trial.started,trial.deadline)
        assertEquals(38L,restored.remainingSeconds(11,102_000))
        assertEquals(trial.deadline,restored.deadline)
    }
    @Test fun rebootClockRollbackAndUnknownBootInvalidateOldTrials() {
        val trial = TrialDeadline.start(2,900_000)
        assertFalse(trial.valid(3,920_000));assertFalse(trial.valid(2,899_999));assertFalse(trial.valid(-1,920_000))
        assertEquals(0L,TrialDeadline.start(-1,0).remainingSeconds(-1,0))
    }
    @Test fun persistedDurationAndOverflowCannotCreateExtendedTrials() {
        try { TrialDeadline(1,0,120_001);fail() } catch(_: IllegalArgumentException) {}
        try { TrialDeadline.start(1,Long.MAX_VALUE-10);fail() } catch(_: ArithmeticException) {}
    }
}
