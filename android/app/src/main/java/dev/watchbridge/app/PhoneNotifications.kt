package dev.watchbridge.app

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.service.notification.*
import android.os.Bundle
import org.json.*
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class PhoneNotifications:NotificationListenerService() {
    private val engine get()=(application as BridgeApp).engine
    companion object {
        private data class Action(val action:Notification.Action,val key:String,val expires:Long)
        private val actions=ConcurrentHashMap<String,Action>()
        @Volatile private var instance:PhoneNotifications?=null
        fun execute(data:JSONObject):Pair<Boolean,String> {
            val id=data.optString("actionId");val a=actions.remove(id) ?: return false to "Action expired; open the source app"
            val service=instance ?: return false to "Notification access disconnected"
            if(a.key!=data.optString("key") || a.expires<System.currentTimeMillis() || !service.engine.running)return false to "Action expired"
            return runCatching {
                val intent=Intent()
                val inputs=a.action.remoteInputs?.filter {it.allowFreeFormInput}?.toTypedArray()
                if(!inputs.isNullOrEmpty()) {
                    val text=data.optString("text").take(1000);require(text.isNotBlank())
                    val results=Bundle();inputs.forEach{results.putCharSequence(it.resultKey,text)}
                    RemoteInput.addResultsToIntent(inputs,intent,results)
                }
                a.action.actionIntent.send(service,0,intent)
                true to "Sent to the source app"
            }.getOrElse {false to (it.message ?: "Action failed")}
        }
    }
    override fun onListenerConnected(){instance=this}
    override fun onListenerDisconnected(){instance=null;actions.clear()}
    override fun onNotificationPosted(sbn:StatusBarNotification){
        if(!engine.running || !engine.active || sbn.packageName==packageName || !engine.allowed(sbn.packageName))return
        val n=sbn.notification
        if(n.flags and Notification.FLAG_GROUP_SUMMARY !=0 || n.flags and Notification.FLAG_ONGOING_EVENT !=0)return
        val extras=n.extras
        val title=extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(200) ?: ""
        val body=(extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.take(2000) ?: ""
        if(title.isBlank() && body.isBlank())return
        val now=System.currentTimeMillis();actions.entries.removeIf {it.value.expires<now || it.value.key==sbn.key}
        val list=JSONArray()
        n.actions?.take(3)?.forEach {a->
            val reply=a.remoteInputs?.any{it.allowFreeFormInput}==true
            // The first version exposes text replies only, not arbitrary destructive actions.
            if(reply){val id=java.util.UUID.randomUUID().toString();actions[id]=Action(a,sbn.key,now+600000);list.put(JSONObject().put("id",id).put("title",a.title.toString()).put("reply",true))}
        }
        val app=runCatching{packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName,0)).toString()}.getOrDefault(sbn.packageName)
        engine.scope.launch {
            runCatching{engine.send("notification",JSONObject().put("key",sbn.key).put("app",app).put("title",title).put("body",body).put("posted",sbn.postTime).put("actions",list))}.onFailure{engine.status="Notification queue: ${it.message}"}
        }
    }
    override fun onNotificationRemoved(sbn:StatusBarNotification){
        actions.entries.removeIf{it.value.key==sbn.key}
        if(engine.running && engine.allowed(sbn.packageName))engine.scope.launch {runCatching{engine.send("remove",JSONObject().put("key",sbn.key))}.onFailure{engine.status="Notification queue: ${it.message}"}}
    }
}
