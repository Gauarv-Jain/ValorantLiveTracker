package com.example.valorantlivetracker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.valorantlivetracker.models.UpcomingMatch

class MatchAutoCheckWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("MatchAutoCheckWorker", "Fetching upcoming matches...")
        val discovery = com.example.valorantlivetracker.network.MatchDiscovery()
        
        val upcomingMatches = discovery.getUpcomingChampionsMatches()
        Log.d("MatchAutoCheckWorker", "Found ${upcomingMatches.size} upcoming VCT matches.")
        
        val workManager = WorkManager.getInstance(applicationContext)
        
        // Cancel existing scheduled matches
        workManager.cancelAllWorkByTag("match_start")
        
        val currentTime = System.currentTimeMillis()
        
        for (match in upcomingMatches) {
            if (match.startTime > currentTime) {
                val delay = match.startTime - currentTime
                Log.d("MatchAutoCheckWorker", "Scheduling ${match.matchTitle} at ${java.util.Date(match.startTime)} (delay: ${delay / 1000 / 60} min)")
                
                val inputData = Data.Builder()
                    .putString("MATCH_URL", match.matchUrl)
                    .build()
                
                val request = OneTimeWorkRequestBuilder<MatchStartWorker>()
                    .setInputData(inputData)
                    .setInitialDelay(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .addTag("match_start")
                    .build()
                
                workManager.enqueue(request)
            }
        }

        return Result.success()
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "MatchAutoCheckWork"

        fun schedule(context: Context) {
            Log.d("MatchAutoCheckWorker", "Scheduling daily refresh work...")
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<MatchAutoCheckWorker>(24, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context) {
            Log.d("MatchAutoCheckWorker", "Cancelling all work...")
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }
}
