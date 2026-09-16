package com.qingjing.wallpaper_android.playback

internal data class WallpaperOutcome(val status: String, val message: String, val retainLiveContent: Boolean = false)
internal object WallpaperResults {
    fun static(returnedId: Int, homeId: Int, lockId: Int, target: String): WallpaperOutcome {
        val home = target != "lock"; val lock = target != "home"
        // Android removes lock-only bookkeeping when BOTH shares the new system image.
        if (target == "both" && returnedId > 0 && homeId == returnedId && lockId < 0)
            return WallpaperOutcome("completed","已确认桌面和锁屏共用新画面")
        return if (returnedId > 0 && (!home || homeId == returnedId) && (!lock || lockId == returnedId))
            WallpaperOutcome("completed","已确认系统壁纸设置完成")
        else WallpaperOutcome("unknown","系统设置结果不可确认，请到桌面或锁屏检查")
    }
    fun live(accepted: Boolean, ready: Boolean, home: Boolean, lock: Boolean, lockQueryable: Boolean): WallpaperOutcome = when {
        !accepted -> WallpaperOutcome("cancelled","已退出系统设置，原有壁纸资源保留")
        !ready -> WallpaperOutcome("unknown","系统设置结果不可确认，请重新打开设置并检查")
        !home && !lock && !lockQueryable -> WallpaperOutcome("accepted","系统已接受设置；位置不可读取，请到桌面或锁屏查看",true)
        !home && !lock -> WallpaperOutcome("unknown","系统设置结果不可确认，请到桌面或锁屏检查")
        home && lock -> WallpaperOutcome("completed","系统已确认动态壁纸用于桌面和锁屏；两处共用同一资源",true)
        lock -> WallpaperOutcome("completed","系统已确认动态壁纸用于锁屏",true)
        lockQueryable -> WallpaperOutcome("completed","系统已确认动态壁纸用于桌面",true)
        else -> WallpaperOutcome("completed","系统已确认动态壁纸用于桌面；此系统无法单独确认锁屏",true)
    }
}
