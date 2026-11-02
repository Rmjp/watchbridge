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
    private lateinit var security:TextView
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
        column.addView(text("Bluetooth only · prototype 0.2",14f).apply{setPadding(0,0,0,dp(24))})
        card("Connection") {
            status=text(engine.status);addView(status)
            addView(text("No Wi-Fi, relay or PC required. Keep WatchBridge open on the watch for the first test; screen-off delivery still needs validation.",14f))
            button("Grant Bluetooth permissions") {permissionRequest.launch(buildList{add(Manifest.permission.BLUETOOTH_CONNECT);add(Manifest.permission.BLUETOOTH_ADVERTISE);if(Build.VERSION.SDK_INT>=33)add(Manifest.permission.POST_NOTIFICATIONS)}.toTypedArray())}
            button("Start bridge") {
                if(!engine.active)show("Use Start new Bluetooth pairing first")
                else if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=android.content.pm.PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)show("Grant Bluetooth permissions first")
                else runCatching{startForegroundService(Intent(this@MainActivity,BridgeService::class.java))}.onFailure{show(it.message ?: "Could not start bridge")}
            }
            button("Pause bridge") {stopService(Intent(this@MainActivity,BridgeService::class.java));engine.status="Paused";renderStatus()}
        }
        card("Pair your Apple Watch") {
            addView(text("Start pairing here, then tap Find Galaxy on the watch. Check the SAME security code on both screens before confirming. Pairing stays open for five minutes.",14f))
            security=text("",22f);addView(security)
            button("Start new Bluetooth pairing") {
                if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=android.content.pm.PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)show("Grant Bluetooth permissions first")
                else android.app.AlertDialog.Builder(this@MainActivity).setTitle("Start fresh Bluetooth pairing?").setMessage("This clears this app's previous pairing and pending transfers. Health Connect records remain. Forget the old link on the watch too.").setNegativeButton("Cancel",null).setPositiveButton("Start"){_,_->work{engine.createPair()}}.show()
            }
            button("Security codes match — confirm") {work{engine.approve()}}
            button("Forget pairing") {android.app.AlertDialog.Builder(this@MainActivity).setTitle("Forget this device link?").setMessage("Local keys and queued messages will be removed. Forget pairing on the watch too.").setNegativeButton("Cancel",null).setPositiveButton("Forget"){_,_->work{engine.forget()}}.show()}
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
        card("Privacy") {addView(text("Data travels only over encrypted Bluetooth between the paired devices. No account, server or internet connection is used. Pair only after both security codes match.",14f))}
        lifecycleScope.launch{while(isActive){renderStatus();delay(1000)}}
    }
    private fun renderStatus(){
        status.text=engine.status+(if(engine.running)"\nBridge running" else "\nBridge paused")+"\nPending: ${engine.db.count()} · Last: ${engine.lastTransfer}"
        security.text=if(engine.comparison.isBlank())"Waiting for watch discovery" else "Security code: ${engine.comparison}\nCompare with watch before confirming."
    }
    private fun chooseApps(){
        val apps=packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0).map{it.activityInfo.packageName to it.loadLabel(packageManager).toString()}.distinctBy{it.first}.filter{it.first!=packageName}.sortedBy{it.second.lowercase()}
        android.app.AlertDialog.Builder(this).setTitle("Forward notifications from")
            .setMultiChoiceItems(apps.map{it.second}.toTypedArray(),apps.map{engine.allowed(it.first)}.toBooleanArray()){_,which,checked->engine.allow(apps[which].first,checked)}.setPositiveButton("Done",null).show()
    }
}
