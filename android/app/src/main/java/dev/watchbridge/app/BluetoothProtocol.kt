package dev.watchbridge.app

import java.io.ByteArrayOutputStream

class BluetoothFrames(private val limit:Int=350000) {
    private val partial=ByteArrayOutputStream()
    @Synchronized fun reset()=partial.reset()
    @Synchronized fun feed(bytes:ByteArray):List<ByteArray> {
        val frames=mutableListOf<ByteArray>()
        for(b in bytes)if(b==10.toByte()) {if(partial.size()>0)frames.add(partial.toByteArray());partial.reset()}
        else {if(partial.size()>=limit){partial.reset();error("Bluetooth frame exceeds limit")};partial.write(b.toInt())}
        return frames
    }
}

class DeliveryWindow(private val now:()->Long=System::currentTimeMillis) {
    var id:String?=null;private set
    private var deadline=0L
    @Synchronized fun begin(messageId:String,bytes:Int):Boolean {
        if(id!=null)return false
        id=messageId;deadline=now()+30000L+bytes.coerceAtMost(350000)*10L;return true
    }
    @Synchronized fun acknowledge(messageId:String):Boolean {if(id!=messageId)return false;reset();return true}
    @Synchronized fun timedOut()=id!=null && now()>deadline
    @Synchronized fun reset(){id=null;deadline=0}
}

object BluetoothProof {
    fun matches(own:String,peer:String,echo:String,challenge:String)=own.isNotEmpty()&&peer.isNotEmpty()&&own==echo&&peer==challenge
}
