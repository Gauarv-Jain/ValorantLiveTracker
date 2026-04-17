package com.example.valorantlivetracker.network

import android.util.Log
import org.jsoup.select.Elements

/**
 * Specialized component to determine which team is attacking based on round history.
 * Handles half-time swaps and overtime side changes.
 */
class VLRSideDetector {
    private val TAG = "VLRSideDetector"

    /**
     * Determines which team is currently on Attack by analyzing round history.
     * 
     * @param rounds Jsoup Elements containing the round squares (.vlr-rounds-row-col)
     * @param scoreA Current score for Team A
     * @param scoreB Current score for Team B
     * @return "teamA", "teamB", or null if undetermined
     */
    fun determineAttackingTeam(rounds: Elements, scoreA: Int, scoreB: Int): String? {
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
            
            Log.d(TAG, "Side Detection: First Round winner at index $winnerIndex. Was Attacker (mod-t): $winnerWasAttacking. Team A started on Attack: $teamAStartedOnAttack")

            val totalRounds = scoreA + scoreB
            val currentRound = totalRounds + 1
            
            Log.d(TAG, "Side Detection: Total rounds played: $totalRounds. Current round being tracked: $currentRound")

            val result = when {
                // Half 1: Rounds 1-12
                currentRound <= 12 -> if (teamAStartedOnAttack) "teamA" else "teamB"
                
                // Half 2: Rounds 13-24 (Sides swapped)
                currentRound <= 24 -> if (teamAStartedOnAttack) "teamB" else "teamA"
                
                // Overtime: Rounds 25+ (Sides swap every 1 round)
                else -> {
                    val otRoundIndex = currentRound - 25
                    // Even index (0, 2, 4...) -> Half 2 side. Odd index (1, 3...) -> Swap back to Half 1 side.
                    val isHalf2Side = (otRoundIndex % 2 == 0)
                    if (isHalf2Side) {
                        if (teamAStartedOnAttack) "teamA" else "teamB"
                    } else {
                        if (teamAStartedOnAttack) "teamB" else "teamA"
                    }
                }
            }
            
            Log.d(TAG, "Side Detection: Resulting attacking team: $result")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to determine side from history", e)
            return null
        }
    }
}
