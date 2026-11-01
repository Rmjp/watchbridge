@file:Suppress("MissingPermission", "DEPRECATION")
package dev.watchbridge.app

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

class BlePeripheral(private val context:Context,private val engine:BridgeEngine) {
    companion object {
        val SERVICE=UUID.fromString("7d7a0001-6f4b-4ef0-9a9d-a98551c2a601")
        val OUT=UUID.fromString("7d7a0002-6f4b-4ef0-9a9d-a98551c2a601")
        val IN=UUID.fromString("7d7a0003-6f4b-4ef0-9a9d-a98551c2a601")
        val CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    private val adapter=context.getSystemService(BluetoothManager::class.java).adapter
    private var server:BluetoothGattServer?=null
    private val outbound=BluetoothGattCharacteristic(OUT,BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ)
    private var device:BluetoothDevice?=null
    private val chunks=ArrayDeque<ByteArray>()
    private val incoming=ByteArrayOutputStream()
    private var advertising=false
    private var inFlight=false
    private val advertiserCallback=object:AdvertiseCallback(){override fun onStartFailure(errorCode:Int){engine.status="BLE advertising failed: $errorCode"}}
    private val callback=object:BluetoothGattServerCallback(){
        override fun onServiceAdded(status:Int,service:BluetoothGattService){if(status==BluetoothGatt.GATT_SUCCESS) advertise()}
        override fun onConnectionStateChange(d:BluetoothDevice,status:Int,newState:Int){synchronized(this@BlePeripheral){
            if(newState==BluetoothProfile.STATE_CONNECTED){if(device!=null && device!=d){server?.cancelConnection(d)}else {incoming.reset();engine.status="Watch Bluetooth connected"}}
            else if(device==d){device=null;chunks.clear();inFlight=false;incoming.reset();engine.status="Bluetooth disconnected · relay available"}
        }}
        override fun onCharacteristicReadRequest(d:BluetoothDevice,requestId:Int,offset:Int,c:BluetoothGattCharacteristic){server?.sendResponse(d,requestId,if(offset==0)BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET,0,byteArrayOf())}
        override fun onDescriptorReadRequest(d:BluetoothDevice,requestId:Int,offset:Int,descriptor:BluetoothGattDescriptor){server?.sendResponse(d,requestId,BluetoothGatt.GATT_SUCCESS,0,if(device==d)BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)}
        override fun onDescriptorWriteRequest(d:BluetoothDevice,requestId:Int,descriptor:BluetoothGattDescriptor,preparedWrite:Boolean,responseNeeded:Boolean,offset:Int,value:ByteArray){
            val valid=descriptor.uuid==CCCD && offset==0 && !preparedWrite && (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) || value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE))
            if(valid)synchronized(this@BlePeripheral){device=if(value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))d else null;chunks.clear();incoming.reset()}
            if(responseNeeded)server?.sendResponse(d,requestId,if(valid)BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,0,null)
        }
        override fun onCharacteristicWriteRequest(d:BluetoothDevice,requestId:Int,c:BluetoothGattCharacteristic,preparedWrite:Boolean,responseNeeded:Boolean,offset:Int,value:ByteArray){
            val valid=c.uuid==IN && !preparedWrite && offset==0 && value.size<=512
            if(responseNeeded)server?.sendResponse(d,requestId,if(valid)BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,0,null)
            if(!valid)return
            synchronized(this@BlePeripheral){for(b in value){
                if(b==10.toByte()){
                    val frame=incoming.toByteArray();incoming.reset()
                    if(frame.isNotEmpty())engine.scope.launch{runCatching{engine.receive(JSONObject(String(frame)))}.onFailure{engine.status="BLE message rejected: ${it.message}"}}
                } else if(incoming.size()<350000)incoming.write(b.toInt())else {incoming.reset();server?.cancelConnection(d);return}
            }}
        }
        override fun onNotificationSent(d:BluetoothDevice,status:Int){synchronized(this@BlePeripheral){inFlight=false;if(status!=BluetoothGatt.GATT_SUCCESS){chunks.clear()}else next()}}
    }
    fun start(){
        check(adapter?.isEnabled==true){"Enable Bluetooth"}
        check(adapter.isMultipleAdvertisementSupported){"BLE advertising not supported"}
        server=context.getSystemService(BluetoothManager::class.java).openGattServer(context,callback)
        check(server!=null){"Could not open BLE service"}
        outbound.addDescriptor(BluetoothGattDescriptor(CCCD,BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        val service=BluetoothGattService(SERVICE,BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(outbound);service.addCharacteristic(BluetoothGattCharacteristic(IN,BluetoothGattCharacteristic.PROPERTY_WRITE,BluetoothGattCharacteristic.PERMISSION_WRITE));server!!.addService(service)
    }
    private fun advertise(){
        val advertiser=adapter.bluetoothLeAdvertiser ?: return
        advertiser.startAdvertising(AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).setTimeout(0).build(),AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).setIncludeDeviceName(false).build(),advertiserCallback);advertising=true
    }
    @Synchronized fun send(frame:String){
        if(device==null || chunks.size>20000)return
        val bytes=(frame+"\n").toByteArray();if(bytes.size>350000)return
        bytes.toList().chunked(20).forEach {chunks.addLast(it.toByteArray())}
        next()
    }
    private fun next(){
        if(inFlight)return
        val d=device ?: return
        val c=chunks.removeFirstOrNull() ?: return
        outbound.value=c
        inFlight=true
        if(server?.notifyCharacteristicChanged(d,outbound,false)!=true){inFlight=false;chunks.clear()}
    }
    fun close(){if(advertising)adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiserCallback);advertising=false;server?.close();server=null;device=null;synchronized(this){chunks.clear();incoming.reset()}}
}
