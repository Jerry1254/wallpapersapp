package com.qingjing.wallpaper_android.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperOutcomeTest {
    @Test fun staticConfirmsEachRequestedTargetAgainstActualSystemIds() {
        assertEquals("completed",WallpaperResults.static(12,12,4,"home").status)
        assertEquals("completed",WallpaperResults.static(12,4,12,"lock").status)
        assertEquals("completed",WallpaperResults.static(12,12,12,"both").status)
        assertEquals("completed",WallpaperResults.static(12,12,-1,"both").status)
        assertEquals("unknown",WallpaperResults.static(12,4,-1,"both").status)
        assertEquals("unknown",WallpaperResults.static(12,12,4,"both").status)
        assertEquals("unknown",WallpaperResults.static(0,0,0,"home").status)
        assertEquals("unknown",WallpaperResults.static(12,4,4,"home").status)
    }
    @Test fun pickerCancellationCannotSucceedEvenWhenOwnServiceWasAlreadyActive() {
        assertEquals("cancelled",WallpaperResults.live(false,true,true,true,true).status)
        assertEquals(false,WallpaperResults.live(false,true,true,true,true).retainLiveContent)
        assertEquals("unknown",WallpaperResults.live(true,false,true,true,true).status)
        assertEquals(false,WallpaperResults.live(true,false,true,true,true).retainLiveContent)
        assertEquals("unknown",WallpaperResults.live(true,true,false,false,true).status)
        assertEquals(false,WallpaperResults.live(true,true,false,false,true).retainLiveContent)
        assertEquals("accepted",WallpaperResults.live(true,true,false,false,false).status)
        assertEquals(true,WallpaperResults.live(true,true,false,false,false).retainLiveContent)
    }
    @Test fun confirmedLiveResultDescribesActualLocationAndOlderOsLimit() {
        assertEquals("系统已确认动态壁纸用于桌面和锁屏；两处共用同一资源",WallpaperResults.live(true,true,true,true,true).message)
        assertEquals("系统已确认动态壁纸用于锁屏",WallpaperResults.live(true,true,false,true,true).message)
        assertEquals("系统已确认动态壁纸用于桌面；此系统无法单独确认锁屏",WallpaperResults.live(true,true,true,false,false).message)
    }
}
