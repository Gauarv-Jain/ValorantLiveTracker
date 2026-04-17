package com.example.valorantlivetracker

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Background worker that checks for upcoming VCT matches and schedules 
 * a MatchStartWorker to fire when the match actually begins.
 */
class MatchAutoCheckWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    private val schedulingHelper = MatchSchedulingHelper()

    override suspend fun doWork(): Result {
        Log.d("MatchAutoCheckWorker", "Fetching upcoming matches...")
        val discovery = com.example.valorantlivetracker.network.MatchDiscovery()
        
        val upcomingMatches = discovery.getUpcomingChampionsMatches()
        Log.d("MatchAutoCheckWorker", "Found ${upcomingMatches.size} upcoming VCT matches.")
        
        val workManager = WorkManager.getInstance(applicationContext)
        
        // 1. Cancel existing scheduled matches to avoid duplicate runs
        // We only cancel 'match_start' tags, not the Post-Match Watchers
        workManager.cancelAllWorkByTag("match_start")
        
        for (match in upcomingMatches) {
            // Case 1: Match is in the future - schedule a worker using the helper to calculate delay
            val delay = schedulingHelper.calculateDelayUntilStart(match.startTime)
            if (delay > 0) {
                Log.d("MatchAutoCheckWorker", "Scheduling ${match.matchTitle} with delay: ${delay / 1000 / 60} min")
                
                val inputData = Data.Builder()
                    .putString("MATCH_URL", match.matchUrl)
                    .build()
                
                val request = OneTimeWorkRequestBuilder<MatchStartWorker>()
                    .setInputData(inputData)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .addTag("match_start")
                    .build()
                
                workManager.enqueue(request)
            } 
            // Case 2: Match is currently LIVE - start the service immediately using the helper's check
            else if (schedulingHelper.shouldStartImmediately(match)) {
                Log.d("MatchAutoCheckWorker", "Match ${match.matchTitle} is LIVE. Starting service immediately.")
                val intent = Intent(applicationContext, MatchService::class.java).apply {
                    putExtra("MATCH_ID", match.matchUrl)
                }
                applicationContext.startForegroundService(intent)
            }
        }

        return Result.success()
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "MatchAutoCheckWork"
        private const val WATCH_WINDOW_TAG = "post_match_watcher"

        fun schedule(context: Context) {
            Log.d("MatchAutoCheckWorker", "Scheduling daily refresh work...")
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // Keep the 24-hour interval as requested
            val request = PeriodicWorkRequestBuilder<MatchAutoCheckWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request
            )
        }

        /**
         * Triggers a sequence of checks after a match ends to catch back-to-back games.
         * Checks at 10, 20, 22, 24, 25, 27, 30, and 35 minutes.
         */
        fun startPostMatchWatchWindow(context: Context) {
            Log.d("MatchAutoCheckWorker", "🚀 Match ended. Starting Post-Match Watch Window...")
            val workManager = WorkManager.getInstance(context)
            
            val checkIntervals = listOf(10, 20, 22, 24, 25, 27, 30, 35)
            
            for (minutes in checkIntervals) {
                val watchRequest = OneTimeWorkRequestBuilder<MatchAutoCheckWorker>()
                    .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
                    .addTag(WATCH_WINDOW_TAG)
                    .build()
                
                workManager.enqueue(watchRequest)
                Log.d("MatchAutoCheckWorker", "   Scheduled check for T+$minutes minutes")
            }
        }

        fun cancel(context: Context) {
            Log.d("MatchAutoCheckWorker", "Cancelling all work...")
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelAllWorkByTag("match_start")
            workManager.cancelAllWorkByTag(WATCH_WINDOW_TAG)
        }
    }
}
