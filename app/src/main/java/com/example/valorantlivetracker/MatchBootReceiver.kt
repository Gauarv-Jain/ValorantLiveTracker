package com.example.valorantlivetracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.valorantlivetracker.MatchAutoCheckWorker

class MatchBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("MatchBootReceiver", "Boot completed. Scheduling auto-check worker.")
            MatchAutoCheckWorker.schedule(context)
        }
    }
}
