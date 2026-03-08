package com.example.valorantlivetracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.valorantlivetracker.models.Match
import com.example.valorantlivetracker.network.VLRScraper
import kotlinx.coroutines.*

class MatchService : Service() {

    private val scraper = VLRScraper()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private val TAG = "MatchService"

    private val CHANNEL_ID = "vlr_live_scores"
    private val NOTIFICATION_ID = 101

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rawInput = intent?.getStringExtra("MATCH_ID")?.trim() ?: ""
        
        val matchId = if (rawInput.contains("vlr.gg/")) {
            rawInput.split("/").find { it.all { c -> c.isDigit() } && it.isNotEmpty() } ?: rawInput
        } else {
            rawInput
        }
        
        Log.d(TAG, "Service starting. Raw: $rawInput | Clean ID: $matchId")
        
        startForeground(NOTIFICATION_ID, createInitialNotification("Initializing..."))
        startTracking(matchId)
        return START_STICKY
    }

    private fun startTracking(cleanId: String?) {
        job?.cancel()
        job = serviceScope.launch {
            var matchUrl: String? = when {
                cleanId.isNullOrBlank() -> null
                cleanId.startsWith("http") -> cleanId
                else -> "https://www.vlr.gg/$cleanId"
            }
            
            if (matchUrl == null) {
                updateStatus("Searching for LIVE VCT matches...")
                while (matchUrl == null && isActive) {
                    matchUrl = scraper.findLiveMatch()
                    if (matchUrl == null) {
                        Log.d(TAG, "No match found yet, retrying in 15s...")
                        delay(15000)
                    }
                }
            }

            if (matchUrl != null) {
                Log.d(TAG, "Success! Monitoring: $matchUrl")
                updateStatus("Connecting to Match...")
                monitorMatch(matchUrl)
            }
        }
    }

    private suspend fun monitorMatch(url: String) {
        var lastScoreA = -1
        var lastScoreB = -1
        var lastMapName = ""

        while (serviceScope.isActive) {
            try {
                val match = scraper.getMatchDetails(url)
                if (match != null) {
                    val currentMap = match.notificationMap ?: match.maps.lastOrNull()
                    
                    if (currentMap != null) {
                        if (currentMap.teamAScore != lastScoreA ||
                            currentMap.teamBScore != lastScoreB || 
                            currentMap.mapName != lastMapName || 
                            lastScoreA == -1) {
                            
                            Log.d(TAG, "Pushing update: ${match.teamA.name} vs ${match.teamB.name}")
                            updateNotification(match)

                            lastScoreA = currentMap.teamAScore
                            lastScoreB = currentMap.teamBScore
                            lastMapName = currentMap.mapName
                        }
                    } else {
                        updateStatus("Match Found: ${match.teamA.name} vs ${match.teamB.name}\nWaiting for Map 1 to start...")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Monitor loop error", e)
            }
            delay(5000)
        }
    }

    private fun updateStatus(status: String) {
        val notification = createInitialNotification(status)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(match: Match) {
        val currentMap = match.notificationMap ?: match.maps.lastOrNull() ?: return
        val remoteViews = RemoteViews(packageName, R.layout.notification_match)

        // Team names
        remoteViews.setTextViewText(R.id.tvTeamA, match.teamA.name)
        remoteViews.setTextViewText(R.id.tvTeamB, match.teamB.name)

        // Map score
        remoteViews.setTextViewText(
            R.id.tvScoreTeamA,
            "${currentMap.teamAScore}"
        )
        remoteViews.setTextViewText(
            R.id.tvScoreTeamB,
            "${currentMap.teamBScore}"
        )

        // CT / T rounds (example format: 3/2)
        remoteViews.setTextViewText(
            R.id.tvTeamACT,
            "${currentMap.teamACTRounds}"
        )
        remoteViews.setTextViewText(
            R.id.tvTeamAT,
            "${currentMap.teamATRounds}"
        )
        remoteViews.setTextViewText(
            R.id.tvTeamBCT,
            "${currentMap.teamBCTRounds} "
        )
        remoteViews.setTextViewText(
            R.id.tvTeamBT,
            "${currentMap.teamBTRounds}"
        )

        // Tournament & Map info
        remoteViews.setTextViewText(R.id.tvTournament, match.tournament.substringBefore(":").trim())
        remoteViews.setTextViewText(R.id.tvMapName, currentMap.mapName.uppercase())
        remoteViews.setTextViewText(R.id.tvStatus, match.status)
        remoteViews.setTextViewText(
            R.id.tvMapInfo,
            "Map ${currentMap.mapNumber} | Series ${match.teamAMapWins}-${match.teamBMapWins}"
        )

        // Arrows for map picker
        remoteViews.setViewVisibility(R.id.tvArrowA, if (currentMap.picker == "teamA") View.VISIBLE else View.GONE)
        remoteViews.setViewVisibility(R.id.tvArrowB, if (currentMap.picker == "teamB") View.VISIBLE else View.GONE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setCustomContentView(remoteViews)
            .setCustomBigContentView(remoteViews)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)

        // Load logos
        loadLogo(match.teamA.logoUrl, remoteViews, R.id.ivTeamA)
        loadLogo(match.teamB.logoUrl, remoteViews, R.id.ivTeamB)
    }

    private fun loadLogo(url: String, remoteViews: RemoteViews, viewId: Int) {
        if (url.isEmpty()) return
        Glide.with(this)
            .asBitmap()
            .load(url)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    remoteViews.setImageViewBitmap(viewId, resource)
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val notification = NotificationCompat.Builder(this@MatchService, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setCustomContentView(remoteViews)
                        .setCustomBigContentView(remoteViews)
                        .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .build()
                    notificationManager.notify(NOTIFICATION_ID, notification)
                }
                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    private fun createInitialNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Valorant Live Tracker")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Live Scores", NotificationManager.IMPORTANCE_HIGH)
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        serviceScope.cancel()
    }
}
