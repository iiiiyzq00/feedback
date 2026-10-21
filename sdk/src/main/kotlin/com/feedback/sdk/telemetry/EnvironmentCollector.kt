package com.feedback.sdk.telemetry

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs

data class DeviceEnvironment(val deviceModel:String,val networkType:String?,val batteryLevel:Double?,val storageAvailableMb:Int)

object EnvironmentCollector {
    fun collect(context:Context):DeviceEnvironment {
        val battery=(context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf{it in 0..100}?.toDouble()
        val cm=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps=cm.getNetworkCapabilities(cm.activeNetwork)
        val network=when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true -> "WIFI"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true -> "CELLULAR"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)==true -> "ETHERNET"
            else -> null
        }
        val storage=(StatFs(context.filesDir.absolutePath).availableBytes/1024/1024).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return DeviceEnvironment(Build.MODEL.take(100),network,battery,storage)
    }
}
