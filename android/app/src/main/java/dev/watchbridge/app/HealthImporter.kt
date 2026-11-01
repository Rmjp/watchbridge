package dev.watchbridge.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.*
import androidx.health.connect.client.units.Energy
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneOffset

class HealthImporter(private val context:Context) {
    companion object {
        val permissions=setOf(StepsRecord::class,HeartRateRecord::class,ActiveCaloriesBurnedRecord::class,SleepSessionRecord::class,ExerciseSessionRecord::class).map {HealthPermission.getWritePermission(it)}.toSet()
    }
    suspend fun import(items:JSONArray):Int {
        check(HealthConnectClient.getSdkStatus(context)==HealthConnectClient.SDK_AVAILABLE){"Health Connect is unavailable"}
        require(items.length()<=1000){"Health batch too large"}
        val client=HealthConnectClient.getOrCreate(context)
        val records=buildList<Record> {
            for(i in 0 until items.length()) {
                val r=items.getJSONObject(i);val start=Instant.parse(r.getString("start"));val end=Instant.parse(r.getString("end"));require(end.isAfter(start))
                val id=r.getString("id");require(id.length<=200)
                val model=r.optString("device","Unknown HealthKit device")
                val meta=Metadata.autoRecorded(device=Device(manufacturer="Apple",model=model,type=if(model.contains("watch",true))Device.TYPE_WATCH else Device.TYPE_UNKNOWN),clientRecordId="watchbridge:$id",clientRecordVersion=1)
                when(r.getString("type")) {
                    "steps"->add(StepsRecord(start,ZoneOffset.UTC,end,ZoneOffset.UTC,r.getLong("value"),meta))
                    "activeEnergy"->add(ActiveCaloriesBurnedRecord(start,ZoneOffset.UTC,end,ZoneOffset.UTC,Energy.kilocalories(r.getDouble("value")),meta))
                    "heartRate"->{val samples=r.getJSONArray("samples");val values=(0 until samples.length()).map {s->val v=samples.getJSONObject(s);HeartRateRecord.Sample(Instant.parse(v.getString("time")),v.getLong("bpm"))};add(HeartRateRecord(start,ZoneOffset.UTC,end,ZoneOffset.UTC,values,meta))}
                    "sleep"->{
                        val stages=r.getJSONArray("stages");val values=(0 until stages.length()).map {s->val v=stages.getJSONObject(s);val stage=when(v.getString("stage")){"awake"->SleepSessionRecord.STAGE_TYPE_AWAKE;"light"->SleepSessionRecord.STAGE_TYPE_LIGHT;"deep"->SleepSessionRecord.STAGE_TYPE_DEEP;"rem"->SleepSessionRecord.STAGE_TYPE_REM;else->SleepSessionRecord.STAGE_TYPE_SLEEPING};SleepSessionRecord.Stage(Instant.parse(v.getString("start")),Instant.parse(v.getString("end")),stage)}
                        add(SleepSessionRecord(start,ZoneOffset.UTC,end,ZoneOffset.UTC,meta,title="Apple Watch sleep",stages=values))
                    }
                    "workout"->{val type=when(r.optString("activity")){"walking"->ExerciseSessionRecord.EXERCISE_TYPE_WALKING;"running"->ExerciseSessionRecord.EXERCISE_TYPE_RUNNING;"cycling"->ExerciseSessionRecord.EXERCISE_TYPE_BIKING;else->ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT};add(ExerciseSessionRecord(start,ZoneOffset.UTC,end,ZoneOffset.UTC,meta,type,title="Apple Watch workout"))}
                    else->error("Unsupported health type")
                }
            }
        }
        val granted=client.permissionController.getGrantedPermissions()
        check(records.all {HealthPermission.getWritePermission(it::class) in granted}){"Grant the requested Health Connect write permissions"}
        records.chunked(100).forEach { client.insertRecords(it) }
        return records.size
    }
}
