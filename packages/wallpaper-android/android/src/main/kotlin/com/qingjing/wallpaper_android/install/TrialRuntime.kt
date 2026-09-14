package com.qingjing.wallpaper_android.install

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** Independent ephemeral store: neither live services nor the formal installer can look up these bytes. */
internal object TrialRuntime {
    internal data class Session(val id: String,val installed: String,val type: String,val boot: Int,val created: Long,val deadline: TrialDeadline?)
    private var instance: AtomicPackageStore? = null
    private var session: Session? = null
    private var loaded = false
    private var viewing: String? = null
    fun boot(context: Context) = Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1)
    @Synchronized fun store(context: Context): AtomicPackageStore = instance ?: AtomicPackageStore(File(context.filesDir,"wallpaper-app-preview-v1")).also { instance = it }
    private fun prefs(context: Context) = context.getSharedPreferences("qingjing.app-preview",Context.MODE_PRIVATE)
    @Synchronized private fun load(context: Context) {
        if(loaded) return
        loaded = true
        try {
            val raw = prefs(context).getString("session",null)
            if(raw != null) {
                require(raw.length <= 512)
                val json = JSONObject(raw)
                val id = json.getString("id"); require(UUID.fromString(id).toString() == id)
                val type = json.getString("type"); require(type in setOf("STATIC_IMAGE","VIDEO","LAYER_PARALLAX"))
                val installed = json.getString("installed"); require(store(context).directory(installed).isDirectory)
                val trialBoot = json.getInt("boot"); val created = json.getLong("created")
                val deadline = if(json.has("started")) TrialDeadline(trialBoot,json.getLong("started"),json.getLong("deadline")) else null
                val now = SystemClock.elapsedRealtime()
                require(trialBoot >= 0 && trialBoot == boot(context) && now >= created)
                require(deadline?.valid(boot(context),now) ?: (now-created < 300_000))
                session = Session(id,installed,type,trialBoot,created,deadline)
                store(context).hold("trial",installed)
            }
        } catch(_: Exception) { session = null }
        if(session == null) { prefs(context).edit().clear().commit(); cleanup(context) }
    }
    private fun persist(context: Context,current: Session) {
        val json = JSONObject().put("id",current.id).put("installed",current.installed).put("type",current.type).put("boot",current.boot).put("created",current.created)
        current.deadline?.let { json.put("started",it.started).put("deadline",it.deadline) }
        check(prefs(context).edit().putString("session",json.toString()).commit())
    }
    @Synchronized fun prepare(context: Context) {
        load(context); check(viewing == null)
        session = null; check(prefs(context).edit().clear().commit()); store(context).hold("trial",null); store(context).clearUnused()
    }
    @Synchronized fun installed(context: Context,installed: String,type: String): String {
        load(context); check(viewing == null)
        val current = Session(UUID.randomUUID().toString(),installed,type,boot(context),SystemClock.elapsedRealtime(),null)
        require(current.boot >= 0)
        store(context).hold("trial",installed); persist(context,current); session = current
        return current.id
    }
    @Synchronized fun current(context: Context,id: String? = null): Session? {
        load(context)
        val current = session ?: return null
        if(id != null && current.id != id) return null
        val now = SystemClock.elapsedRealtime()
        if(current.boot != boot(context) || now < current.created || (current.deadline?.valid(boot(context),now) ?: (now-current.created < 300_000)).not()) {
            finish(context,current.id); return null
        }
        return current
    }
    @Synchronized fun open(context: Context,id: String): Session? {
        val current = current(context,id) ?: return null
        check(viewing == null || viewing == id); viewing = id
        return current
    }
    @Synchronized fun rendered(context: Context,id: String): TrialDeadline? {
        val current = current(context,id) ?: return null
        val deadline = current.deadline ?: TrialDeadline.start(current.boot,SystemClock.elapsedRealtime())
        if(current.deadline == null) { val started = current.copy(deadline = deadline); persist(context,started); session = started }
        return deadline
    }
    @Synchronized fun finish(context: Context,id: String) {
        load(context)
        if(session?.id != id) return
        session = null; prefs(context).edit().clear().commit(); cleanup(context)
    }
    @Synchronized fun closed(context: Context,id: String) { if(viewing == id) viewing = null; cleanup(context) }
    @Synchronized fun cleanup(context: Context) {
        if(loaded && session == null) try { store(context).hold("trial",null);store(context).clearUnused() } catch(_: Exception) {
            // Keep a failed deletion for the next recovery/prepare attempt; never crash a renderer or expose its path.
        }
    }
    @Synchronized fun recover(context: Context): Map<String,Any>? {
        if(viewing != null) return null
        val current = current(context) ?: return null
        return mapOf("trialId" to current.id,"resourceType" to current.type,"remainingSeconds" to (current.deadline?.remainingSeconds(boot(context),SystemClock.elapsedRealtime()) ?: 120L))
    }
}
