package com.example.valorantlivetracker.network

import android.util.Log
import com.example.valorantlivetracker.models.MapScore
import org.jsoup.nodes.Document

/**
 * Helper class to extract map scores and round-by-round side information from VLR.gg match pages.
 */
class ScoreExtractor {
    private val TAG = "ScoreExtractor"

    /**
     * Parses the match document to get all map scores and current side information.
     */
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
            var picker: String? = null
            var attackingTeam: String? = null

            for (game in games) {

                val header = game.selectFirst(".vm-stats-game-header") ?: continue
                val headerText = header.text()

                if (!headerText.contains(mapName, ignoreCase = true)) continue

                // 1. EXTRACT BASIC SCORES
                val scores = header.select(".score")
                val tScores = header.select(".mod-t")
                val ctScores = header.select(".mod-ct")

                scoreA = scores.getOrNull(0)?.text()?.trim()?.toIntOrNull() ?: -1
                scoreB = scores.getOrNull(1)?.text()?.trim()?.toIntOrNull() ?: -1

                teamAT = tScores.getOrNull(0)?.text()?.toIntOrNull() ?: 0
                teamACT = ctScores.getOrNull(0)?.text()?.toIntOrNull() ?: 0

                teamBT = tScores.getOrNull(1)?.text()?.toIntOrNull() ?: 0
                teamBCT = ctScores.getOrNull(1)?.text()?.toIntOrNull() ?: 0

                // 2. DETECT MAP PICKER (Primary: CSS Class)
                val pickElement = header.selectFirst(".picked")
                picker = when {
                    pickElement == null -> null
                    pickElement.className().contains("mod-1") -> "teamA"
                    pickElement.className().contains("mod-2") -> "teamB"
                    else -> null
                }

                // 3. ROBUST SIDE DETECTION (Looking at individual round data)
                val roundHistory = game.select(".vlr-rounds-row-col")
                if (roundHistory.isNotEmpty()) {
                    attackingTeam = determineAttackingTeamFromHistory(roundHistory, scoreA, scoreB)
                } else {
                    attackingTeam = when {
                        teamAT > teamBT -> "teamA"
                        teamBT > teamAT -> "teamB"
                        else -> null
                    }
                }

                val teams = header.select(".team-name")
                val teamA = teams.getOrNull(0)?.text()?.trim() ?: "Unknown"
                val teamB = teams.getOrNull(1)?.text()?.trim() ?: "Unknown"

                Log.d(TAG, "Map ${i + 1}: $mapName | Score: $scoreA:$scoreB | Picker: $picker | Attacking: $attackingTeam")
                break
            }

            // Fallback: Pick extraction from Match Note if CSS detection failed
            if (picker == null) {
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

    /**
     * Determines which team is currently on Attack by analyzing round history.
     */
    private fun determineAttackingTeamFromHistory(rounds: org.jsoup.select.Elements, scoreA: Int, scoreB: Int): String? {
        try {
            // Find the first round that has a winner to determine starting sides
            val firstCompletedRound = rounds.firstOrNull { 
                it.select(".rnd-sq.mod-win").isNotEmpty() 
            } ?: return null

            val winnerSquare = firstCompletedRound.selectFirst(".rnd-sq.mod-win") ?: return null
            val winnerIndex = firstCompletedRound.select(".rnd-sq").indexOf(winnerSquare) // 0 for teamA, 1 for teamB
            
            // Check if the winner won as Attacker (mod-t) or Defender (mod-ct)
            val winnerWasAttacking = winnerSquare.hasClass("mod-t")
            
            // Determine if Team A started on Attack
            val teamAStartedOnAttack = if (winnerIndex == 0) winnerWasAttacking else !winnerWasAttacking


            val totalRounds = scoreA + scoreB
            val currentRound = totalRounds + 1


            val result = when {
                // Half 1: Rounds 1-12
                currentRound <= 12 -> if (teamAStartedOnAttack) "teamA" else "teamB"
                
                // Half 2: Rounds 13-24 (Sides swapped)
                currentRound <= 24 -> if (teamAStartedOnAttack) "teamB" else "teamA"
                
                // Overtime: Rounds 25+ (Sides swap every 1 round as per instructions)
                else -> {
                    val otRoundIndex = currentRound - 25
                    val isHalf2Side = (otRoundIndex % 2 == 0)
                    if (isHalf2Side) {
                        if (teamAStartedOnAttack) "teamB" else "teamA"
                    } else {
                        if (teamAStartedOnAttack) "teamA" else "teamB"
                    }
                }
            }

            return result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to determine side from history", e)
            return null
        }
    }

    fun isMatchForTeam(text: String, teamName: String): Boolean {
        val t1 = text.lowercase().trim()
        val t2 = teamName.lowercase().trim()
        return t1 == t2 || t1.contains(t2) || t2.contains(t1)
    }
}
