package com.example.valorantlivetracker.network

import android.util.Log
import com.example.valorantlivetracker.models.MapScore
import org.jsoup.nodes.Document

class MapDetector {
    private val TAG = "MapDetector"

    enum class MatchState {
        NOT_STARTED,
        LIVE,
        FINISHED,
        UNKNOWN
    }

    fun getMatchState(doc: Document): MatchState {

        val liveElement = doc.selectFirst(".match-header-vs-note.mod-live")

        if (liveElement != null) {
            Log.d("MatchState", "Match detected as LIVE")
            return MatchState.LIVE
        }

        val notes = doc.select(".match-header-vs-note")

        for (note in notes) {
            val text = note.text().trim().lowercase()
            Log.d("MatchState", "Header note: $text")

            if (text == "final") {
                Log.d("MatchState", "Match detected as FINISHED")
                return MatchState.FINISHED
            }
        }

        Log.d("MatchState", "Match detected as NOT_STARTED")
        return MatchState.NOT_STARTED
    }

    data class MatchHeaderInfo(
        val teamAMapWins: Int,
        val teamBMapWins: Int,
        val seriesType: String,   // 1, 3, or 5
        val matchState: MatchState
    )

    fun extractMatchHeaderInfo(doc: Document): MatchHeaderInfo {

        val header = doc.selectFirst(".match-header-vs-score")

        // --- Match Score ---
        val scores = header
            ?.select(".js-spoiler span")
            ?.filter { it.text().trim().matches(Regex("\\d+")) }

        val teamA = scores?.getOrNull(0)?.text()?.toIntOrNull() ?: 0
        val teamB = scores?.getOrNull(1)?.text()?.toIntOrNull() ?: 0
        Log.d("MatchHeader", "Series Score: $teamA : $teamB")

        // --- Series Type ---
        var seriesType = "bo1"
        header?.select(".match-header-vs-note")?.forEach {
            val text = it.text().trim().lowercase()
            Log.d("MatchHeader", "Checking note for series type: '$text'")
            if (text.contains("bo3")) seriesType = "bo3"
            if (text.contains("bo5")) seriesType = "bo5"
        }
        Log.d("MatchHeader", "Series Type from HTML: BO$seriesType")

        // --- Match State ---
        val state = when {
            doc.selectFirst(".match-header-vs-note.mod-live") != null -> {
                Log.d("MatchHeader", "Match State: LIVE")
                MatchState.LIVE
            }
            header?.text()?.lowercase()?.contains("final") == true -> {
                Log.d("MatchHeader", "Match State: FINISHED")
                MatchState.FINISHED
            }
            doc.selectFirst(".match-header-vs-note.mod-upcoming") != null -> {
                val timeText = doc.selectFirst(".match-header-vs-note.mod-upcoming")?.text()?.trim()
                Log.d("MatchHeader", "Match State: NOT_STARTED, starts in $timeText")
                MatchState.NOT_STARTED
            }
            else -> {
                Log.d("MatchHeader", "Match State: UNKNOWN")
                MatchState.UNKNOWN
            }
        }

        return MatchHeaderInfo(
            teamAMapWins = teamA,
            teamBMapWins = teamB,
            seriesType = seriesType,
            matchState = state
        )
    }

    fun getMapForNotification(
        maps: List<MapScore>,
        matchInfo: MatchHeaderInfo
    ): MapScore? {
        Log.d("NotificationMap", "Determining map for notification...")
        Log.d("NotificationMap", "Match State: ${matchInfo.matchState}, Score: ${matchInfo.teamAMapWins}:${matchInfo.teamBMapWins}, Series: BO${matchInfo.seriesType}")

        // Calculate how many maps have been "decided" so far
        val totalMapsPlayed = matchInfo.teamAMapWins + matchInfo.teamBMapWins
        Log.d("NotificationMap", "Total maps played: $totalMapsPlayed")

        val mapIndexToShow = when (matchInfo.matchState) {
            MatchState.NOT_STARTED -> 0 // show first map
            MatchState.LIVE -> totalMapsPlayed // current map index is sum of wins
            MatchState.FINISHED -> totalMapsPlayed - 1 // last map index
            else -> 0
        }

        // Safe bounds check
        if (mapIndexToShow !in maps.indices) {
            Log.e("NotificationMap", "Calculated map index $mapIndexToShow out of bounds for maps list size ${maps.size}")
            return null
        }

        val mapToShow = maps[mapIndexToShow]
        Log.d("NotificationMap", "Map to show: ${mapToShow.mapName} at index $mapIndexToShow")
        return mapToShow
    }

    fun getAllMapNames(doc: Document): List<String> {
        val gameTabs = doc.select(".vm-stats-gamesnav-item")
        val mapNames = mutableListOf<String>()
        for (tab in gameTabs) {
            val name = tab.select("div").last()?.text()?.trim() ?: ""
            val cleanName = cleanMapName(name)
            if (cleanName.isNotEmpty() && !cleanName.equals("All Maps", ignoreCase = true) && !cleanName.equals("Overview", ignoreCase = true)) {
                mapNames.add(cleanName)
            }
        }
        return mapNames
    }

    fun getActiveMapIndex(doc: Document): Int {

        return -1;
    }

    fun getActiveMapIndexFromMaps(maps: List<MapScore>): Int {

        if (maps.isEmpty()) {
            Log.d("MapIndex", "No maps found")
            return -1
        }

        maps.forEachIndexed { i, map ->
            Log.d(
                "MapIndex",
                "Map $i -> ${map.mapName} | score ${map.teamAScore}:${map.teamBScore}"
            )
        }

        // Match not started
        if (maps[0].teamAScore < 0 || maps[0].teamBScore < 0) {
            Log.d("MapIndex", "Match not started yet → returning -1")
            return -1
        }

        // Find first map without score → active map
        maps.forEachIndexed { i, map ->
            if (map.teamAScore < 0 || map.teamBScore < 0) {
                Log.d("MapIndex", "Active map detected at index: $i")
                return i
            }
        }

        // If all maps have scores, the last one is either currently live or the final map
        val lastPlayed = maps.indexOfLast { it.teamAScore >= 0 && it.teamBScore >= 0 }

        Log.d("MapIndex", "Active or Last map index: $lastPlayed")

        return lastPlayed
    }

    private fun cleanMapName(name: String): String {
        return name.replace(Regex("^\\d+"), "").split("(")[0].trim().uppercase()
    }
}
