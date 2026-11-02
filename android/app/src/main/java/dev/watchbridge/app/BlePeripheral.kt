@file:Suppress("MissingPermission", "DEPRECATION")
package dev.watchbridge.app

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.*
import kotlinx.coroutines.launch
import java.util.UUID

class BlePeripheral(private val context:Context,private val engine:BridgeEngine){
    companion object{
        val SERVICE=UUID.fromString("7d7a1001-6f4b-4ef0-9a9d-a98551c2a601")
        val OUT=UUID.fromString("7d7a1002-6f4b-4ef0-9a9d-a98551c2a601")
        val IN=UUID.fromString("7d7a1003-6f4b-4ef0-9a9d-a98551c2a601")
        val CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    private val adapter=context.getSystemService(BluetoothManager::class.java).adapter
    private var server:BluetoothGattServer?=null
    private val outbound=BluetoothGattCharacteristic(OUT,BluetoothGattCharacteristic.PROPERTY_INDICATE,BluetoothGattCharacteristic.PERMISSION_READ)
    private var device:BluetoothDevice?=null
    private var subscribed=false
    private var payload=20
    private val chunks=ArrayDeque<ByteArray>()
    private val incoming=BluetoothFrames()
    private var advertising=false
    private var inFlight=false
    private val advertiseCallback=object:AdvertiseCallback(){override fun onStartFailure(errorCode:Int){engine.status="Bluetooth advertising failed: $errorCode"}}
    private val callback=object:BluetoothGattServerCallback(){
        override fun onServiceAdded(status:Int,service:BluetoothGattService){if(status==BluetoothGatt.GATT_SUCCESS)advertise()else engine.status="Bluetooth service failed: $status"}
        override fun onConnectionStateChange(d:BluetoothDevice,status:Int,newState:Int){synchronized(this@BlePeripheral){
            if(newState==BluetoothProfile.STATE_CONNECTED){if(device!=null&&device!=d)server?.cancelConnection(d)else{device=d;subscribed=false;payload=20;clear();engine.linkReset();engine.status="Watch found · waiting for secure handshake"}}
            else if(device==d){device=null;subscribed=false;clear();engine.linkReset()}
        }}
        override fun onMtuChanged(d:BluetoothDevice,mtu:Int){synchronized(this@BlePeripheral){if(device==d)payload=(mtu-3).coerceIn(20,180)}}
        override fun onDescriptorReadRequest(d:BluetoothDevice,requestId:Int,offset:Int,descriptor:BluetoothGattDescriptor){server?.sendResponse(d,requestId,BluetoothGatt.GATT_SUCCESS,0,if(device==d&&subscribed)BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)}
        override fun onDescriptorWriteRequest(d:BluetoothDevice,requestId:Int,descriptor:BluetoothGattDescriptor,preparedWrite:Boolean,responseNeeded:Boolean,offset:Int,value:ByteArray){
            val valid=d==device&&descriptor.uuid==CCCD&&offset==0&&!preparedWrite&&(value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)||value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE))
            if(valid)synchronized(this@BlePeripheral){subscribed=value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);if(subscribed)engine.transportReady()}
            if(responseNeeded)server?.sendResponse(d,requestId,if(valid)BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,0,null)
        }
        override fun onCharacteristicWriteRequest(d:BluetoothDevice,requestId:Int,c:BluetoothGattCharacteristic,preparedWrite:Boolean,responseNeeded:Boolean,offset:Int,value:ByteArray){
            val valid=d==device&&subscribed&&c.uuid==IN&&!preparedWrite&&offset==0&&value.size<=512
            val frames=if(valid)runCatching{incoming.feed(value)}else null
            if(responseNeeded)server?.sendResponse(d,requestId,if(valid&&frames?.isSuccess==true)BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,0,null)
            if(frames?.isFailure==true){disconnect();return}
            // One ordered consumer avoids reordering hello/proof/data frames on IO threads.
            frames?.getOrNull()?.forEach{frame->if(receiveQueue.trySend(String(frame)).isFailure)disconnect()}
        }
        override fun onNotificationSent(d:BluetoothDevice,status:Int){synchronized(this@BlePeripheral){if(d!=device)return;inFlight=false;if(status!=BluetoothGatt.GATT_SUCCESS)disconnect()else next()}}
    }
    private val receiveQueue=kotlinx.coroutines.channels.Channel<String>(32)
    private var reader:kotlinx.coroutines.Job?=null
    fun start(){
        check(adapter?.isEnabled==true){"Enable Bluetooth"};check(adapter.isMultipleAdvertisementSupported){"BLE advertising unsupported"}
        reader=engine.scope.launch{for(frame in receiveQueue){runCatching{engine.receiveFrame(frame)}.onFailure{engine.status="Bluetooth rejected: ${it.message}";disconnect()}}}
        server=context.getSystemService(BluetoothManager::class.java).openGattServer(context,callback);check(server!=null)
        outbound.addDescriptor(BluetoothGattDescriptor(CCCD,BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        val service=BluetoothGattService(SERVICE,BluetoothGattService.SERVICE_TYPE_PRIMARY);service.addCharacteristic(outbound);service.addCharacteristic(BluetoothGattCharacteristic(IN,BluetoothGattCharacteristic.PROPERTY_WRITE,BluetoothGattCharacteristic.PERMISSION_WRITE));check(server!!.addService(service))
    }
    private fun advertise(){adapter.bluetoothLeAdvertiser?.startAdvertising(AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).setTimeout(0).build(),AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).setIncludeDeviceName(false).build(),advertiseCallback);advertising=true}
    @Synchronized fun send(frame:String):Boolean{
        if(device==null||!subscribed)return false
        val bytes=(frame+"\n").toByteArray();if(bytes.size>350000||chunks.size+(bytes.size+payload-1)/payload>20000)return false
        var offset=0;while(offset<bytes.size){chunks.addLast(bytes.copyOfRange(offset,minOf(offset+payload,bytes.size)));offset+=payload};next();return true
    }
    private fun next(){
        if(inFlight||!subscribed)return
        val d=device?:return;val bytes=chunks.removeFirstOrNull()?:return;inFlight=true
        val accepted=if(Build.VERSION.SDK_INT>=33)server?.notifyCharacteristicChanged(d,outbound,true,bytes)==BluetoothStatusCodes.SUCCESS else{outbound.value=bytes;server?.notifyCharacteristicChanged(d,outbound,true)==true}
        if(!accepted)disconnect()
    }
    private fun clear(){chunks.clear();inFlight=false;incoming.reset();while(receiveQueue.tryReceive().isSuccess){}}
    @Synchronized fun disconnect(){device?.let{server?.cancelConnection(it)};device=null;subscribed=false;clear();engine.linkReset()}
    @Synchronized fun close(){if(advertising)adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback);advertising=false;disconnect();server?.close();server=null;receiveQueue.close();reader?.cancel()}
}
