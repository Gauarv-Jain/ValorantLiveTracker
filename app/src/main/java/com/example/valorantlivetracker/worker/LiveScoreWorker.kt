package com.example.valorantlivetracker.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.valorantlivetracker.network.NtfyClient
import com.example.valorantlivetracker.network.VLRScraper
import com.example.valorantlivetracker.models.Match
import kotlinx.coroutines.delay

class LiveScoreWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val scraper = VLRScraper()
    private val ntfyClient = NtfyClient()
    private val TAG = "LiveScoreWorker"

    override suspend fun doWork(): Result {
        Log.d(TAG, "Worker started")
        
        var matchUrl: String? = null
        
        // Loop until a match is found or worker is cancelled
        while (matchUrl == null && !isStopped) {
            matchUrl = scraper.findLiveMatch()
            if (matchUrl == null) {
                Log.d(TAG, "No live Tier 1 match found. Retrying in 30 seconds...")
                delay(30000) // Wait 30 seconds before searching again
            }
        }

        if (isStopped) return Result.success()

        Log.d(TAG, "Found match to monitor: $matchUrl")
        
        var lastScoreA = -1
        var lastScoreB = -1
        var lastMapName = ""
        var lastMapNumber = -1

        while (!isStopped) {
            try {
                val match = scraper.getMatchDetails(matchUrl!!)
                if (match != null && match.maps.isNotEmpty()) {
                    val currentMap = match.maps.last()
                    
                    if (currentMap.teamAScore != lastScoreA || 
                        currentMap.teamBScore != lastScoreB || 
                        currentMap.mapName != lastMapName ||
                        currentMap.mapNumber != lastMapNumber) {
                        
                        val isMatchPoint = currentMap.teamAScore >= 12 || currentMap.teamBScore >= 12
                        val message = "${match.teamA.name} ${currentMap.teamAScore} - ${currentMap.teamBScore} ${match.teamB.name}\n" +
                                      "${currentMap.mapName} (Map ${currentMap.mapNumber}) | Series ${calculateSeriesScore(match)}"
                        
                        Log.d(TAG, "Score updated: $message")
                        ntfyClient.sendNotification(message, isMatchPoint)
                        
                        lastScoreA = currentMap.teamAScore
                        lastScoreB = currentMap.teamBScore
                        lastMapName = currentMap.mapName
                        lastMapNumber = currentMap.mapNumber
                    } else {
                        Log.d(TAG, "No score change for ${match.teamA.name} vs ${match.teamB.name}")
                    }
                } else if (match != null) {
                    Log.d(TAG, "Match found but no maps started yet")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during match poll", e)
            }
            
            delay(5000) // Poll every 5 seconds
        }

        return Result.success()
    }

    private fun calculateSeriesScore(match: Match): String {
        var teamAWins = 0
        var teamBWins = 0
        for (map in match.maps) {
            if (map.teamAScore >= 13) teamAWins++
            if (map.teamBScore >= 13) teamBWins++
            // Handle overtime wins if needed, though VLR scores usually show 13+
        }
        return "$teamAWins-$teamBWins"
    }
}
