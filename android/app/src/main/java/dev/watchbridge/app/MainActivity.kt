package dev.watchbridge.app

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class MainActivity:ComponentActivity() {
    private val engine get()=(application as BridgeApp).engine
    private lateinit var column:LinearLayout
    private lateinit var status:TextView
    private lateinit var code:TextView
    private lateinit var security:TextView
    private lateinit var url:EditText
    private val permissionRequest=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){show("Permissions updated")}
    private val healthRequest=registerForActivityResult(PermissionController.createRequestPermissionResultContract()){show("Health permissions updated")}
    private val ink=Color.rgb(231,239,244)
    private fun dp(n:Int)=(resources.displayMetrics.density*n).toInt()
    private fun text(value:String,size:Float=16f)=TextView(this).apply{text=value;textSize=size;setTextColor(ink);setPadding(0,dp(5),0,dp(5))}
    private fun card(title:String,block:LinearLayout.()->Unit){
        column.addView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(14),dp(18),dp(16));background=GradientDrawable().apply{setColor(Color.rgb(23,38,51));cornerRadius=dp(18).toFloat()};addView(text(title,20f).apply{setTypeface(null,Typeface.BOLD)});block()},LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(16)})
    }
    private fun LinearLayout.button(label:String,action:()->Unit){addView(Button(this@MainActivity).apply{text=label;isAllCaps=false;setOnClickListener{action()}},LinearLayout.LayoutParams(-1,dp(52)).apply{topMargin=dp(8)})}
    private fun work(block:suspend()->Unit){lifecycleScope.launch{runCatching{block()}.onFailure{show(it.message ?: "Action failed")};renderStatus()}}
    private fun show(message:String){Toast.makeText(this,message,Toast.LENGTH_LONG).show()}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=Color.rgb(10,20,29);window.navigationBarColor=Color.rgb(10,20,29)
        val scroll=ScrollView(this).apply{setBackgroundColor(Color.rgb(10,20,29));isFillViewport=true}
        scroll.setOnApplyWindowInsetsListener{view,insets->val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());view.setPadding(0,bars.top,0,bars.bottom);insets}
        column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(24),dp(22),dp(36))};scroll.addView(column);setContentView(scroll)
        column.addView(text("WATCHBRIDGE",13f).apply{setTextColor(Color.rgb(75,216,195));letterSpacing=.18f})
        column.addView(text("Your phone.\nYour watch. Connected.",32f).apply{setTypeface(null,Typeface.BOLD)})
        column.addView(text("Personal companion · prototype 0.1",14f).apply{setPadding(0,0,0,dp(24))})
        card("Connection") {
            status=text(engine.status);addView(status)
            addView(text("The bridge runs while enabled. Keep the watch app open for the first test; background delivery is not yet validated.",14f))
            button("Grant Bluetooth permissions") {permissionRequest.launch(buildList{add(Manifest.permission.BLUETOOTH_CONNECT);add(Manifest.permission.BLUETOOTH_ADVERTISE);add(Manifest.permission.BLUETOOTH_SCAN);if(Build.VERSION.SDK_INT>=33)add(Manifest.permission.POST_NOTIFICATIONS)}.toTypedArray())}
            button("Start bridge") {
                if(!engine.active)show("Confirm pairing on both devices first")
                else if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=android.content.pm.PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)show("Grant Bluetooth permissions first")
                else runCatching{startForegroundService(Intent(this@MainActivity,BridgeService::class.java))}.onFailure{show(it.message ?: "Could not start bridge")}
            }
            button("Pause bridge") {stopService(Intent(this@MainActivity,BridgeService::class.java));engine.status="Paused";renderStatus()}
        }
        card("Pair your Apple Watch") {
            addView(text("Use the same HTTPS relay address on both devices. A debug build also permits a local HTTP relay.",14f))
            url=EditText(this@MainActivity).apply{setTextColor(ink);setHintTextColor(Color.GRAY);hint="https://your-relay.example";setSingleLine();setText(engine.prefs.getString("url",""))};addView(url)
            code=text("",26f);addView(code);security=text("",18f);addView(security)
            button("Create pairing code") {work{val c=engine.createPair(url.text.toString().trim());code.text="Pairing code: $c"}}
            button("Security codes match — confirm") {work{engine.approve()}}
            button("Check connection") {work{engine.refreshPair()}}
            button("Unpair and revoke") {android.app.AlertDialog.Builder(this@MainActivity).setTitle("Revoke this device link?").setMessage("Both relay tokens will stop working. Local keys and queued messages will be removed.").setNegativeButton("Cancel",null).setPositiveButton("Unpair"){_,_->work{engine.revoke()}}.show()}
        }
        card("Notifications") {
            addView(text("Choose which apps may forward notification text. Replies work only when the source notification provides a text-reply action.",14f))
            button("Enable notification access") {startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}
            button("Choose apps") {chooseApps()}
            button("Send a connection test") {engine.scope.launch{engine.send("ping",org.json.JSONObject().put("text","Hello from Galaxy"))};show("Test queued if paired")}
        }
        card("Health") {
            addView(text("Import watch steps, heart rate, active calories, sleep, and workouts into Health Connect. Start sync on the watch. Import requires explicit permission; source edits/deletions are not handled in this version.",14f))
            button("Allow Health Connect import") {healthRequest.launch(HealthImporter.permissions)}
        }
        card("Privacy") {addView(text("Payloads are encrypted between your devices. The relay sees device IDs, timing and encrypted sizes. Pair only after the security codes match. Health and notification content are excluded from app diagnostics.",14f))}
        lifecycleScope.launch{while(isActive){if(!engine.running && engine.vault.get("token")!=null)runCatching{engine.refreshPair()}.onFailure{engine.status=it.message ?: "Connection unavailable"};renderStatus();delay(5000)}}
    }
    private fun renderStatus(){
        status.text=engine.status+if(engine.running)"\nBridge running" else "\nBridge paused"
        code.text=engine.vault.get("pairCode")?.let{"Pairing code: $it"} ?: "Create a pairing session"
        security.text=if(engine.comparison.isBlank())"Waiting for watch" else "Security code: ${engine.comparison}\nCompare this code with your watch before confirming."
    }
    private fun chooseApps(){
        val apps=packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0).map{it.activityInfo.packageName to it.loadLabel(packageManager).toString()}.distinctBy{it.first}.filter{it.first!=packageName}.sortedBy{it.second.lowercase()}
        android.app.AlertDialog.Builder(this).setTitle("Forward notifications from")
            .setMultiChoiceItems(apps.map{it.second}.toTypedArray(),apps.map{engine.allowed(it.first)}.toBooleanArray()){_,which,checked->engine.allow(apps[which].first,checked)}.setPositiveButton("Done",null).show()
    }
}
