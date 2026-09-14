package com.qingjing.wallpaper_android.playback

import android.content.Context
import android.os.SystemClock
import com.qingjing.wallpaper_android.install.PackageRuntime
import java.util.UUID

/** System preview has a pending selection. Existing engines keep the committed selection until confirmation. */
internal object LiveSelection {
    private fun prefs(context: Context) = context.getSharedPreferences("qingjing.live-selection",Context.MODE_PRIVATE)
    @Synchronized fun begin(context: Context, id: String): String {
        // The bridge permits one coordinator. A new flow abandons a persisted selection whose
        // coordinator was lost (for example after process death), while retaining committed video.
        pending(context)?.let { finish(context,it.second,false) }
        val token = UUID.randomUUID().toString(); val store = PackageRuntime.store(context)
        store.hold("picker",id)
        try {
            check(prefs(context).edit().putString("id",id).putString("token",token).putLong("created",SystemClock.elapsedRealtime()).remove("ready").commit())
        } catch (error: Exception) { store.hold("picker",null); throw error }
        return token
    }
    @Synchronized fun pending(context: Context): Pair<String,String>? {
        val p = prefs(context); val id = p.getString("id",null); val token = p.getString("token",null)
        val age = SystemClock.elapsedRealtime() - p.getLong("created",-1)
        if (id == null || token == null || age !in 0..600_000 || PackageRuntime.store(context).held("picker") != id) {
            check(p.edit().clear().commit()); PackageRuntime.store(context).hold("picker",null); return null
        }
        return id to token
    }
    @Synchronized fun ready(context: Context, token: String) {
        if (pending(context)?.second == token) check(prefs(context).edit().putString("ready",token).commit())
    }
    @Synchronized fun isReady(context: Context, token: String) = pending(context)?.second == token && prefs(context).getString("ready",null) == token
    @Synchronized fun finish(context: Context, token: String, commit: Boolean, home: Boolean = false, lock: Boolean = false) {
        val pending = pending(context) ?: return
        if (pending.second != token) return
        val store = PackageRuntime.store(context)
        if (commit) {
            // RESULT_OK + verified preview permits retaining content even on Android <=13,
            // where the location API is unavailable. It does not confirm a HOME/LOCK target.
            require(isReady(context,token))
            store.hold("live",pending.first)
            if (home) store.markActive(pending.first,"home")
            if (lock) store.markActive(pending.first,"lock")
        }
        check(prefs(context).edit().clear().commit()); store.hold("picker",null)
    }
    fun current(context: Context): String? = PackageRuntime.store(context).held("live")
}
