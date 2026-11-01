package dev.watchbridge.app

import android.app.*
import android.content.*
import android.database.sqlite.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*
import java.net.HttpURLConnection
import java.net.URL

class BridgeApp : Application() {
    lateinit var engine: BridgeEngine
    override fun onCreate() { super.onCreate();engine=BridgeEngine(this) }
}

class QueueDb(context: Context) : SQLiteOpenHelper(context,"bridge.db",null,1) {
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE outbox(id TEXT PRIMARY KEY,body TEXT NOT NULL)");db.execSQL("CREATE TABLE seen(id TEXT PRIMARY KEY,time INTEGER)") }
    override fun onUpgrade(db: SQLiteDatabase,oldVersion:Int,newVersion:Int) = Unit
    @Synchronized fun add(e: JSONObject) { writableDatabase.execSQL("INSERT OR IGNORE INTO outbox VALUES(?,?)",arrayOf(e.getString("id"),e.toString())) }
    @Synchronized fun pending(): List<JSONObject> = readableDatabase.rawQuery("SELECT body FROM outbox ORDER BY rowid LIMIT 50",null).use { c->buildList { while(c.moveToNext())add(JSONObject(c.getString(0))) } }
    @Synchronized fun remove(id:String) { writableDatabase.delete("outbox","id=?",arrayOf(id)) }
    @Synchronized fun seen(id:String)=readableDatabase.rawQuery("SELECT 1 FROM seen WHERE id=?",arrayOf(id)).use { it.moveToFirst() }
    @Synchronized fun mark(id:String) { writableDatabase.execSQL("INSERT OR IGNORE INTO seen VALUES(?,?)",arrayOf<Any>(id,System.currentTimeMillis()));writableDatabase.delete("seen","time<?",arrayOf((System.currentTimeMillis()-8*86400000L).toString())) }
    @Synchronized fun clear() { writableDatabase.execSQL("DELETE FROM outbox");writableDatabase.execSQL("DELETE FROM seen") }
}

class BridgeEngine(val context: Context) {
    val vault=Vault(context)
    val prefs=context.getSharedPreferences("settings",Context.MODE_PRIVATE)
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    val db=QueueDb(context)
    private val inbound=Mutex()
    @Volatile var status="Ready to pair"
    @Volatile var running=false
    @Volatile var active=vault.get("confirmed")=="yes" && vault.get("key")!=null
    @Volatile var comparison=""
    @Volatile var ble: BlePeripheral?=null
    fun allowed(pkg:String)=prefs.getStringSet("allowed",emptySet())!!.contains(pkg)
    fun allow(pkg:String,on:Boolean) { val set=prefs.getStringSet("allowed",emptySet())!!.toMutableSet();if(on)set.add(pkg)else set.remove(pkg);prefs.edit().putStringSet("allowed",set).apply() }
    fun api(path:String,body:JSONObject?=null): JSONObject {
        val base=prefs.getString("url","")!!.trimEnd('/')
        require(base.startsWith("https://") || (BuildConfig.DEBUG && base.startsWith("http://"))) { "Use an HTTPS relay URL" }
        val c=URL(base+path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout=10000;c.readTimeout=10000;c.instanceFollowRedirects=false
            c.setRequestProperty("Content-Type","application/json")
            vault.get("token")?.let { c.setRequestProperty("Authorization","Bearer $it") }
            if(body!=null){ c.requestMethod="POST";c.doOutput=true;c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code=c.responseCode
            val text=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val result=JSONObject(text)
            if(code==401){active=false;vault.put("confirmed",null);ble?.close();context.stopService(Intent(context,BridgeService::class.java))}
            check(code in 200..299) { result.optString("error","Relay error $code") }
            return result
        } finally { c.disconnect() }
    }
    suspend fun createPair(url:String): String=withContext(Dispatchers.IO) {
        check(!active) { "Unpair first" }
        require(url.startsWith("https://") || (BuildConfig.DEBUG && url.startsWith("http://")))
        vault.clear();db.clear();BridgeCrypto.newIdentity(vault)
        prefs.edit().putString("url",url.trimEnd('/')).commit()
        val r=api("/v1/pair/create",JSONObject().put("deviceId",vault.get("id")).put("publicKey",vault.get("public")))
        vault.put("token",r.getString("token"));vault.put("pairCode",r.getString("code"));status="Enter pairing code on your watch";r.getString("code")
    }
    suspend fun refreshPair()=withContext(Dispatchers.IO) {
        if(vault.get("token")==null)return@withContext
        val p=api("/v1/pair")
        if(!p.isNull("peerPublicKey")) {
            val peer=p.getString("peerPublicKey");val pinned=vault.get("peerPublic")
            check(pinned==null || pinned==peer) { "Peer key changed; unpair and retry" }
            check(vault.get("peerId")==null || vault.get("peerId")==p.getString("peerId")) { "Peer identity changed; unpair and retry" }
            val derived=BridgeCrypto.derive(vault,peer)
            vault.put("peerPublic",peer);vault.put("peerId",p.getString("peerId"));vault.put("key",derived.first.b64());comparison=derived.second
        }
        active=p.getBoolean("active") && vault.get("confirmed")=="yes"
        status=if(active)"Paired · start the bridge" else if(comparison.isNotEmpty())"Compare security code on both screens" else "Waiting for watch to join"
    }
    suspend fun approve()=withContext(Dispatchers.IO) {
        check(comparison.isNotEmpty());api("/v1/pair/approve",JSONObject());vault.put("confirmed","yes");refreshPair()
    }
    fun send(type:String,payload:JSONObject) {
        if(!active || vault.get("confirmed")!="yes")return
        val e=BridgeCrypto.encrypt(vault,type,payload);db.add(e);ble?.send(e.toString())
    }
    suspend fun receive(e:JSONObject) = inbound.withLock {
        require(active && vault.get("confirmed")=="yes")
        val data=BridgeCrypto.decrypt(vault,e);val id=e.getString("id")
        if(db.seen(id))return@withLock
        when(e.getString("type")) {
            "reply" -> {
                // Persist before invoking an external PendingIntent: never execute a retry twice.
                db.mark(id)
                val result=PhoneNotifications.execute(data)
                send("reply_result",JSONObject().put("requestId",data.optString("requestId")).put("ok",result.first).put("detail",result.second))
            }
            "health" -> {
                val result=runCatching { HealthImporter(context).import(data.getJSONArray("records")) }
                send("health_result",JSONObject().put("batchId",data.getString("batchId")).put("ok",result.isSuccess).put("detail",result.fold({ "Imported $it records" },{it.message ?: "Health permission or import failed"})))
                status=result.fold({"Health synced · $it records"},{"Health sync needs attention: ${it.message}"})
            }
            "ping" -> status="Watch says: ${data.optString("text").take(80)}"
            else -> error("Unsupported message type")
        }
        db.mark(id)
    }
    suspend fun syncOnce() {
        if(!active)return
        for(e in db.pending()) {
            if(e.getLong("expires")<System.currentTimeMillis()){db.remove(e.getString("id"));continue}
            api("/v1/events",e);db.remove(e.getString("id"))
        }
        val events=api("/v1/events").getJSONArray("events");val ids=JSONArray()
        for(i in 0 until events.length()) {
            val e=events.getJSONObject(i)
            runCatching { receive(e) }.onSuccess { ids.put(e.getString("id")) }.onFailure { status="Message rejected: ${it.message}" }
        }
        if(ids.length()>0)api("/v1/events/ack",JSONObject().put("ids",ids))
    }
    suspend fun revoke()=withContext(Dispatchers.IO) {
        api("/v1/revoke",JSONObject());context.stopService(Intent(context,BridgeService::class.java));ble?.close();vault.clear();db.clear();active=false;comparison="";status="Unpaired"
    }
}

class BridgeService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val engine get()=(application as BridgeApp).engine
    override fun onCreate() {
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("bridge","Watch connection",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        startForeground(1,Notification.Builder(this,"bridge").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("WatchBridge is running").setContentText("Bluetooth and relay sync enabled").setContentIntent(open).setOngoing(true).build())
        engine.running=true
        scope.launch {
            runCatching { engine.refreshPair() }.onFailure { engine.status="Cannot verify pairing: ${it.message}" }
            if(!engine.active){stopSelf();return@launch}
            runCatching { engine.ble=BlePeripheral(this@BridgeService,engine).also { it.start() } }.onFailure { engine.status="Bluetooth unavailable: ${it.message}" }
            while(isActive) {
                runCatching { engine.syncOnce() }.onFailure { engine.status="Relay unavailable; Bluetooth still enabled. ${it.message}" }
                delay(5000)
            }
        }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_NOT_STICKY
    override fun onBind(intent:Intent?)=null
    override fun onDestroy(){scope.cancel();engine.running=false;engine.ble?.close();engine.ble=null;super.onDestroy()}
}
