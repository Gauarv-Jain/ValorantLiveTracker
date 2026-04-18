package com.example.valorantlivetracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that can track multiple live Valorant matches simultaneously.
 * Each match gets its own ongoing notification.
 */
class MatchService : Service() {

    private val scraper = VLRScraper()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Concurrent map to track multiple active match jobs by their unique URL
    private val activeJobs = ConcurrentHashMap<String, Job>()
    
    // Cache for team logos to avoid re-downloading and flickering
    private val logoCache = ConcurrentHashMap<String, Bitmap>()
    
    private val TAG = "MatchService"
    private val CHANNEL_ID = "vlr_live_scores"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate called")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rawInput = intent?.getStringExtra("MATCH_ID")?.trim() ?: ""
        Log.d(TAG, "onStartCommand received MATCH_ID: '$rawInput'")

        if (rawInput.isEmpty()) {
            Log.w(TAG, "Empty MATCH_ID received. Active jobs: ${activeJobs.size}")
            if (activeJobs.isEmpty()) {
                Log.d(TAG, "No active jobs, stopping self")
                stopSelf()
            }
            return START_NOT_STICKY
        }

        // Standardize the VLR URL
        val matchUrl = when {
            rawInput.startsWith("http") -> rawInput
            rawInput.all { it.isDigit() } -> "https://www.vlr.gg/$rawInput"
            rawInput.startsWith("/") -> "https://www.vlr.gg$rawInput"
            else -> "https://www.vlr.gg/$rawInput"
        }
        Log.d(TAG, "Standardized URL: $matchUrl")

        // Unique Notification ID derived from the match URL/ID
        val notificationId = generateNotificationId(matchUrl)
        Log.d(TAG, "Generated Notification ID: $notificationId")

        // Only start a new job if we aren't already tracking this match
        if (!activeJobs.containsKey(matchUrl)) {
            Log.d(TAG, "Starting tracker for new match: $matchUrl (ID: $notificationId)")
            
            // CRITICAL: Ensure the service stays in foreground with a valid initial notification
            try {
                val initialNotification = createInitialNotification("Connecting to match...", matchUrl)
                startForeground(notificationId, initialNotification)
                Log.d(TAG, "startForeground successful for ID $notificationId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to startForeground for ID $notificationId", e)
            }

            val job = serviceScope.launch {
                monitorMatch(matchUrl, notificationId)
            }
            activeJobs[matchUrl] = job
        } else {
            Log.d(TAG, "Already tracking match: $matchUrl")
        }

        return START_STICKY
    }

    private suspend fun monitorMatch(url: String, notificationId: Int) {
        Log.d(TAG, "Monitoring started for $url (ID: $notificationId)")
        var lastScoreA = -1
        var lastScoreB = -1
        var lastMapName = ""
        var matchWasLive = false

        while (serviceScope.isActive) {
            try {
                Log.d(TAG, "Fetching match details for $url...")
                val match = scraper.getMatchDetails(url)
                if (match != null) {
                    Log.d(TAG, "Match data fetched: ${match.teamA.name} vs ${match.teamB.name} | Status: ${match.status}")
                    val isLive = "LIVE".equals(match.status, ignoreCase = true)
                    val isFinished = "FINISHED".equals(match.status, ignoreCase = true)
                    
                    if (isLive) matchWasLive = true

                    // Tournament Handoff Logic
                    if (matchWasLive && isFinished) {
                        Log.d(TAG, "Match finished: $url. Triggering watch window.")
                        MatchAutoCheckWorker.startPostMatchWatchWindow(applicationContext)
                        updateNotification(match, notificationId)
                        delay(5000)
                        
                        // Clean up this specific match
                        activeJobs.remove(url)
                        Log.d(TAG, "Removed job for $url. Remaining jobs: ${activeJobs.size}")
                        if (activeJobs.isEmpty()) {
                            Log.d(TAG, "All matches finished, stopping service")
                            stopSelf()
                        }
                        return
                    }

                    val currentMap = match.notificationMap ?: match.maps.lastOrNull()
                    if (currentMap != null) {
                        Log.d(TAG, "Updating notification for ${match.teamA.shortName} vs ${match.teamB.shortName} | Score: ${currentMap.teamAScore}-${currentMap.teamBScore}")
                        if (currentMap.teamAScore != lastScoreA ||
                            currentMap.teamBScore != lastScoreB ||
                            currentMap.mapName != lastMapName ||
                            lastScoreA == -1) {

                            updateNotification(match, notificationId)

                            lastScoreA = currentMap.teamAScore
                            lastScoreB = currentMap.teamBScore
                            lastMapName = currentMap.mapName
                        }
                    } else {
                        Log.d(TAG, "No map data yet for $url, updating status")
                        updateStatus(notificationId, url, "Match Found: ${match.teamA.name} vs ${match.teamB.name}", "Waiting for map...")
                    }
                } else {
                    Log.w(TAG, "Scraper returned null for $url")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Monitor loop error for $url", e)
            }
            delay(10000)
        }
        Log.d(TAG, "Monitoring loop ended for $url")
    }

    private fun updateStatus(id: Int, url: String, title: String, text: String) {
        Log.d(TAG, "updateStatus called for ID $id: $title - $text")
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(createMatchIntent(url))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
    }

    private fun createMatchIntent(url: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return PendingIntent.getActivity(this, generateNotificationId(url), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun updateNotification(match: Match, id: Int) {
        val currentMap = match.notificationMap ?: match.maps.lastOrNull() ?: return
        Log.d(TAG, "Building full notification for ID $id")
        val remoteViews = RemoteViews(packageName, R.layout.notification_match)

        remoteViews.setTextViewText(R.id.tvTeamA, match.teamA.shortName)
        remoteViews.setTextViewText(R.id.tvTeamB, match.teamB.shortName)
        
        val attackingTeam = currentMap.attackingTeam
        remoteViews.setViewVisibility(R.id.ivSpikeA, if (attackingTeam == "teamA") View.VISIBLE else View.GONE)
        remoteViews.setViewVisibility(R.id.ivSpikeB, if (attackingTeam == "teamB") View.VISIBLE else View.GONE)
        if (attackingTeam == "teamA") remoteViews.setImageViewResource(R.id.ivSpikeA,
            R.drawable.croped_no_bg1
        )
        if (attackingTeam == "teamB") remoteViews.setImageViewResource(R.id.ivSpikeB,
            R.drawable.croped_no_bg1
        )
//        if (attackingTeam == "teamA") remoteViews.setImageViewResource(R.id.ivSpikeA, R.drawable.ic_spike)
//        if (attackingTeam == "teamB") remoteViews.setImageViewResource(R.id.ivSpikeB, R.drawable.ic_spike)
        
        remoteViews.setTextViewText(R.id.tvScoreTeamA, "${currentMap.teamAScore}")
        remoteViews.setTextViewText(R.id.tvScoreTeamB, "${currentMap.teamBScore}")
        remoteViews.setTextViewText(R.id.tvTeamACT, "${currentMap.teamACTRounds}")
        remoteViews.setTextViewText(R.id.tvTeamAT, "${currentMap.teamATRounds}")
        remoteViews.setTextViewText(R.id.tvTeamBCT, "${currentMap.teamBCTRounds} ")
        remoteViews.setTextViewText(R.id.tvTeamBT, "${currentMap.teamBTRounds}")
        remoteViews.setTextViewText(R.id.tvMapName, currentMap.mapName.uppercase())

        remoteViews.setTextViewText(R.id.tvStatus, match.status)
        if ("LIVE".equals(match.status, ignoreCase = true)) {
            remoteViews.setTextColor(R.id.tvStatus, Color.parseColor("#FF4655"))
        } else {
            remoteViews.setTextColor(R.id.tvStatus, Color.WHITE)
        }

        val seriesInfo = "Map ${currentMap.mapNumber} | Series ${match.teamAMapWins}-${match.teamBMapWins}"
        val mapWinnerInfo = getMapWinnerInfo(match)
        val finalMapInfo = if (mapWinnerInfo.isNotEmpty()) "$seriesInfo ⋅ $mapWinnerInfo" else seriesInfo
        remoteViews.setTextViewText(R.id.tvMapInfo, finalMapInfo)

        remoteViews.setViewVisibility(R.id.tvArrowA, if (currentMap.picker == "teamA") View.VISIBLE else View.GONE)
        remoteViews.setViewVisibility(R.id.tvArrowB, if (currentMap.picker == "teamB") View.VISIBLE else View.GONE)

        // Apply cached logos immediately if available
        val bitmapA = logoCache[match.teamA.logoUrl]
        if (bitmapA != null) {
            remoteViews.setImageViewBitmap(R.id.ivTeamA, bitmapA)
        } else {
            remoteViews.setImageViewResource(R.id.ivTeamA, android.R.drawable.ic_menu_gallery)
            loadLogo(match.teamA.logoUrl, match, currentMap, R.id.ivTeamA, id)
        }

        val bitmapB = logoCache[match.teamB.logoUrl]
        if (bitmapB != null) {
            remoteViews.setImageViewBitmap(R.id.ivTeamB, bitmapB)
        } else {
            remoteViews.setImageViewResource(R.id.ivTeamB, android.R.drawable.ic_menu_gallery)
            loadLogo(match.teamB.logoUrl, match, currentMap, R.id.ivTeamB, id)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${match.teamA.shortName} vs ${match.teamB.shortName}")
            .setContentText("${currentMap.teamAScore} - ${currentMap.teamBScore} | ${currentMap.mapName}")
            .setCustomContentView(remoteViews)
            .setCustomBigContentView(remoteViews)
            .setContentIntent(createMatchIntent(match.url))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(id, notification)
    }

    private fun getMapWinnerInfo(match: Match): String {
        val winnerInfo = StringBuilder()
        val currentMap = match.notificationMap ?: match.maps.lastOrNull()
        val currentIndex = match.maps.indexOf(currentMap)
        val limit = if (currentIndex != -1) currentIndex else match.maps.size
        
        for (i in 0 until limit) {
            val map = match.maps[i]
            if (map.teamAScore > map.teamBScore) {
                winnerInfo.append("${map.mapName}: ${match.teamA.shortName} ⋅ ")
            } else if (map.teamBScore > map.teamAScore) {
                winnerInfo.append("${map.mapName}: ${match.teamB.shortName} ⋅ ")
            }
        }
        return winnerInfo.toString().removeSuffix(" ⋅ ")
    }

    private fun loadLogo(url: String, match: Match, currentMap: com.example.valorantlivetracker.models.MapScore, viewId: Int, id: Int) {
        if (url.isEmpty()) return
        
        serviceScope.launch(Dispatchers.Main) {
            Glide.with(this@MatchService)
                .asBitmap()
                .load(url)
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        logoCache[url] = resource
                        updateNotification(match, id)
                    }
                    override fun onLoadCleared(placeholder: Drawable?) {}
                })
        }
    }

    private fun createInitialNotification(text: String, url: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Valorant Live Tracker")
            .setContentText(text)
            .setContentIntent(createMatchIntent(url))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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

    private fun generateNotificationId(url: String): Int {
        // Safe notification ID generation
        val idString = url.split("/").filter { it.isNotEmpty() }.find { it.all { char -> char.isDigit() } }
        val id = idString?.toIntOrNull() ?: (url.hashCode() and 0x7FFFFFFF)
        // Ensure ID is not 0 (standard requirement for notifications)
        return if (id == 0) 1 else id
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy called. Cancelling ${activeJobs.size} jobs.")
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        logoCache.clear()
        serviceScope.cancel()
    }
}
