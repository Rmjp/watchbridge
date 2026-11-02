package dev.watchbridge.app

import android.app.*
import android.content.*
import android.database.sqlite.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*
import java.security.SecureRandom
import java.util.UUID

class BridgeApp:Application(){lateinit var engine:BridgeEngine;override fun onCreate(){super.onCreate();engine=BridgeEngine(this)}}

class QueueDb(context:Context):SQLiteOpenHelper(context,"bridge.db",null,1){
    override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE outbox(id TEXT PRIMARY KEY,body TEXT NOT NULL)");db.execSQL("CREATE TABLE seen(id TEXT PRIMARY KEY,time INTEGER)")}
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
    @Synchronized fun add(e:JSONObject){check(count()<1000){"Bluetooth queue full; reconnect your watch"};writableDatabase.execSQL("INSERT OR IGNORE INTO outbox VALUES(?,?)",arrayOf(e.getString("id"),e.toString()))}
    @Synchronized fun pending():List<JSONObject> = readableDatabase.rawQuery("SELECT body FROM outbox ORDER BY rowid LIMIT 50",null).use{c->buildList{while(c.moveToNext())add(JSONObject(c.getString(0)))}}
    @Synchronized fun count():Int=readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox",null).use{it.moveToFirst();it.getInt(0)}
    @Synchronized fun remove(id:String){writableDatabase.delete("outbox","id=?",arrayOf(id))}
    @Synchronized fun seen(id:String)=readableDatabase.rawQuery("SELECT 1 FROM seen WHERE id=?",arrayOf(id)).use{it.moveToFirst()}
    @Synchronized fun mark(id:String){writableDatabase.execSQL("INSERT OR IGNORE INTO seen VALUES(?,?)",arrayOf<Any>(id,System.currentTimeMillis()));writableDatabase.delete("seen","time<?",arrayOf((System.currentTimeMillis()-8*86400000L).toString()))}
    @Synchronized fun clear(){writableDatabase.execSQL("DELETE FROM outbox");writableDatabase.execSQL("DELETE FROM seen")}
}

class BridgeEngine(val context:Context){
    val vault=Vault(context)
    val prefs=context.getSharedPreferences("settings",Context.MODE_PRIVATE)
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    val db=QueueDb(context)
    private val inbound=Mutex()
    private val window=DeliveryWindow()
    @Volatile var status="Bluetooth only · start pairing"
    @Volatile var running=false
    @Volatile var active=vault.get("transport")=="bt2" && vault.get("confirmed")=="yes" && vault.get("peerConfirmed")=="yes"
    @Volatile var connected=false
    @Volatile var comparison=""
    @Volatile var lastTransfer="None yet"
    @Volatile var ble:BlePeripheral?=null
    private var pairingUntil=0L
    private var ownChallenge=""
    private var peerChallenge=""
    private var proofReceived=false
    private var handshakeStarted=0L
    fun allowed(pkg:String)=prefs.getStringSet("allowed",emptySet())!!.contains(pkg)
    fun allow(pkg:String,on:Boolean){val set=prefs.getStringSet("allowed",emptySet())!!.toMutableSet();if(on)set.add(pkg)else set.remove(pkg);prefs.edit().putStringSet("allowed",set).apply()}
    suspend fun createPair()=inbound.withLock{
        ble?.disconnect();vault.clear();db.clear();BridgeCrypto.newIdentity(vault);vault.put("transport","bt2")
        active=false;connected=false;comparison="";window.reset();proofReceived=false
        pairingUntil=System.currentTimeMillis()+300000
        status="Discoverable for 5 minutes · tap Find Galaxy on watch"
        context.startForegroundService(Intent(context,BridgeService::class.java))
    }
    suspend fun forget()=inbound.withLock{
        context.stopService(Intent(context,BridgeService::class.java));ble?.close();ble=null
        vault.clear();db.clear();active=false;connected=false;comparison="";window.reset();pairingUntil=0;proofReceived=false
        status="Pairing forgotten locally · forget on watch too"
    }
    fun linkReset(){connected=false;ownChallenge="";peerChallenge="";proofReceived=false;handshakeStarted=0;window.reset();status=if(active)"Disconnected · waiting for nearby watch" else "Waiting for watch"}
    fun transportReady(){handshakeStarted=System.currentTimeMillis()}
    private fun wire(kind:String)=JSONObject().put("wire","wb-bt2").put("kind",kind)
    private fun transmit(e:JSONObject)=ble?.send(wire("encrypted").put("envelope",e).toString())==true
    private fun transient(type:String,payload:JSONObject){check(transmit(BridgeCrypto.encrypt(vault,type,payload))){"Bluetooth disconnected; retry after reconnect"}}
    private fun proof(){if(ownChallenge.isNotEmpty()&&peerChallenge.isNotEmpty())transient("session",JSONObject().put("echo",peerChallenge).put("challenge",ownChallenge).put("approved",vault.get("confirmed")=="yes"))}
    suspend fun approve()=inbound.withLock{
        check(comparison.isNotEmpty()&&peerChallenge.isNotEmpty()){"Connect the watch first"}
        check(active||System.currentTimeMillis()<pairingUntil){"Pairing expired; start again"}
        vault.put("confirmed","yes");proof();activate()
    }
    private fun activate(){
        active=vault.get("confirmed")=="yes"&&vault.get("peerConfirmed")=="yes"
        connected=active&&proofReceived
        status=if(connected)"Connected · encrypted Bluetooth" else "Compare codes, then confirm on BOTH devices"
    }
    suspend fun receiveFrame(frame:String)=inbound.withLock{
        val root=JSONObject(frame);require(root.getString("wire")=="wb-bt2"){"Update both apps to Bluetooth 0.2"}
        if(root.getString("kind")=="hello"){
            val id=root.getString("id");require(UUID.fromString(id).toString()==id)
            val pub=root.getString("publicKey");val challenge=root.getString("challenge");require(challenge.unb64().size==32)
            check(vault.get("transport")=="bt2"){"Start Bluetooth pairing on phone"}
            val pinned=vault.get("peerId")
            check(pinned==null||(pinned==id&&vault.get("peerPublic")==pub)){"Different watch: forget pairing on both devices"}
            check(active||System.currentTimeMillis()<pairingUntil){"Pairing window expired"}
            check(peerChallenge.isEmpty()){"Repeated hello; reconnect"}
            val derived=BridgeCrypto.derive(vault,pub)
            vault.put("peerId",id);vault.put("peerPublic",pub);vault.put("key",derived.first.b64());comparison=derived.second
            ownChallenge=ByteArray(32).also{SecureRandom().nextBytes(it)}.b64();peerChallenge=challenge;proofReceived=false
            check(ble?.send(wire("hello").put("id",vault.get("id")).put("publicKey",vault.get("public")).put("challenge",ownChallenge).toString())==true)
            proof();status=if(active)"Authenticating Bluetooth…" else "Security code: $comparison · compare with watch"
            return@withLock
        }
        require(root.getString("kind")=="encrypted"&&peerChallenge.isNotEmpty())
        val e=root.getJSONObject("envelope");val data=BridgeCrypto.decrypt(vault,e);val type=e.getString("type")
        if(type=="session"){
            require(BluetoothProof.matches(ownChallenge,peerChallenge,data.getString("echo"),data.getString("challenge"))){"Stale connection proof"}
            proofReceived=true;vault.put("peerConfirmed",if(data.getBoolean("approved"))"yes" else "no");activate();return@withLock
        }
        check(connected&&active){"Confirm matching security codes first"}
        if(type=="ack"){
            if(window.acknowledge(data.getString("id"))){db.remove(data.getString("id"));lastTransfer="Delivered ${java.time.LocalTime.now().withNano(0)}"};return@withLock
        }
        val id=e.getString("id")
        if(!db.seen(id)){
            when(type){
                "reply"->{db.mark(id);val result=PhoneNotifications.execute(data);send("reply_result",JSONObject().put("requestId",data.optString("requestId")).put("ok",result.first).put("detail",result.second))}
                "health"->{val result=runCatching{HealthImporter(context).import(data.getJSONArray("records"))};send("health_result",JSONObject().put("batchId",data.getString("batchId")).put("ok",result.isSuccess).put("detail",result.fold({"Imported $it records"},{it.message?:"Health import failed"})));status=result.fold({"Health synced · $it records"},{"Health sync: ${it.message}"})}
                "ping"->status="Watch says: ${data.optString("text").take(80)}"
                else->error("Unsupported Bluetooth message")
            }
            db.mark(id);lastTransfer="Received ${java.time.LocalTime.now().withNano(0)}"
        }
        transient("ack",JSONObject().put("id",id))
    }
    @Synchronized fun send(type:String,payload:JSONObject){if(active)db.add(BridgeCrypto.encrypt(vault,type,payload))}
    suspend fun tick()=inbound.withLock{
        if(!proofReceived&&handshakeStarted>0&&System.currentTimeMillis()-handshakeStarted>20000){ble?.disconnect();return@withLock}
        if(!active&&pairingUntil>0&&System.currentTimeMillis()>pairingUntil){status="Pairing expired · start again";ble?.disconnect();pairingUntil=0}
        if(!connected)return@withLock
        if(window.timedOut()){status="Transfer stalled · reconnecting to retry";ble?.disconnect();linkReset();return@withLock}
        if(window.id!=null)return@withLock
        for(e in db.pending()){
            if(e.getLong("expires")<=System.currentTimeMillis()){db.remove(e.getString("id"));continue}
            if(window.begin(e.getString("id"),e.toString().toByteArray().size)&&!transmit(e))window.reset()
            break
        }
    }
}

class BridgeService:Service(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val engine get()=(application as BridgeApp).engine
    override fun onCreate(){
        super.onCreate();val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("bridge","Watch Bluetooth connection",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        startForeground(1,Notification.Builder(this,"bridge").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("WatchBridge Bluetooth is running").setContentText("Nearby watch connection · no internet required").setContentIntent(open).setOngoing(true).build())
        engine.running=true
        scope.launch{
            runCatching{engine.ble=BlePeripheral(this@BridgeService,engine).also{it.start()}}.onFailure{engine.status="Bluetooth: ${it.message}";stopSelf();return@launch}
            while(isActive){runCatching{engine.tick()}.onFailure{engine.status="Transfer: ${it.message}"};delay(1000)}
        }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_NOT_STICKY
    override fun onBind(intent:Intent?)=null
    override fun onDestroy(){scope.cancel();engine.running=false;engine.ble?.close();engine.ble=null;engine.linkReset();super.onDestroy()}
}
