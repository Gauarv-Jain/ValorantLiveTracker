package com.example.valorantlivetracker.network

import android.util.Log
import com.example.valorantlivetracker.models.MapScore
import org.jsoup.nodes.Document

class ScoreExtractor {
    private val TAG = "ScoreExtractor"

    fun getAllMapScores(
        doc: Document,
        mapNames: List<String>,
        note: String,
        teamAName: String,
        teamBName: String
    ): List<MapScore> {

        val games = doc.select(".vm-stats-container .vm-stats-game")
        Log.d(TAG, "Found vm-stats-game blocks: ${games.size}")

        val maps = mutableListOf<MapScore>()

        for ((i, mapName) in mapNames.withIndex()) {

            var scoreA = -1
            var scoreB = -1
            var teamAT = 0
            var teamACT = 0
            var teamBT = 0
            var teamBCT = 0

            for (game in games) {

                val header = game.selectFirst(".vm-stats-game-header") ?: continue
                val headerText = header.text()

                if (!headerText.contains(mapName, ignoreCase = true)) continue

                val scores = header.select(".score")
                val tScores = header.select(".mod-t")
                val ctScores = header.select(".mod-ct")

                scoreA = scores.getOrNull(0)?.text()?.trim()?.toIntOrNull() ?: -1
                scoreB = scores.getOrNull(1)?.text()?.trim()?.toIntOrNull() ?: -1

                teamAT = tScores.getOrNull(0)?.text()?.toIntOrNull() ?: 0
                teamACT = ctScores.getOrNull(0)?.text()?.toIntOrNull() ?: 0

                teamBT = tScores.getOrNull(1)?.text()?.toIntOrNull() ?: 0
                teamBCT = ctScores.getOrNull(1)?.text()?.toIntOrNull() ?: 0

                val teams = header.select(".team-name")
                val teamA = teams.getOrNull(0)?.text()?.trim() ?: "Unknown"
                val teamB = teams.getOrNull(1)?.text()?.trim() ?: "Unknown"

                val pickElement = header.selectFirst(".picked")

                val pickedBy = when {
                    pickElement == null -> "DECIDER"
                    pickElement.className().contains("mod-1") -> teamA
                    pickElement.className().contains("mod-2") -> teamB
                    else -> "UNKNOWN"
                }

                Log.d(TAG, "Teams: $teamA vs $teamB")
                Log.d(TAG, "Map ${i + 1}: $mapName | Score: $scoreA:$scoreB | Picked by: $pickedBy")
                Log.d(TAG, "Sides -> $teamA T:$teamAT CT:$teamACT | $teamB T:$teamBT CT:$teamBCT")

                break
            }

            var picker: String? = null
            val mapNameLower = mapName.lowercase()

            if (note.contains("pick $mapNameLower") || note.contains("picked $mapNameLower")) {
                val segments = note.split(";", ",")
                for (segment in segments) {
                    if (segment.contains(mapNameLower) && segment.contains("pick")) {
                        val teamPart = segment.substringBefore("pick").trim()
                        if (isMatchForTeam(teamPart, teamAName)) picker = "teamA"
                        else if (isMatchForTeam(teamPart, teamBName)) picker = "teamB"
                    }
                }
            }

            val attackingTeam = when {
                teamAT > teamBT -> "teamA"
                teamBT > teamAT -> "teamB"
                else -> null
            }

            maps.add(
                MapScore(
                    mapName = mapName,
                    mapNumber = i + 1,
                    teamAScore = scoreA,
                    teamBScore = scoreB,
                    teamATRounds = teamAT,
                    teamACTRounds = teamACT,
                    teamBTRounds = teamBT,
                    teamBCTRounds = teamBCT,
                    picker = picker,
                    attackingTeam = attackingTeam
                )
            )
        }

        return maps
    }

    fun isMatchForTeam(text: String, teamName: String): Boolean {
        val t1 = text.lowercase().trim()
        val t2 = teamName.lowercase().trim()

        return t1 == t2 || t1.contains(t2) || t2.contains(t1)
    }
}
