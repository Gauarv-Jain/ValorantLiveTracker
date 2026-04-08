package com.example.valorantlivetracker

import com.example.valorantlivetracker.models.UpcomingMatch

/**
 * Helper class to handle timing and scheduling logic for matches.
 * Decouples the calculation of delays and start conditions from the Worker.
 */
class MatchSchedulingHelper {

    /**
     * Calculates the delay in milliseconds until a match is scheduled to start.
     * @param startTime The Unix timestamp (ms) of the match start.
     * @return The delay in ms, or 0 if the match has already started.
     */
    fun calculateDelayUntilStart(startTime: Long): Long {
        val currentTime = System.currentTimeMillis()
        
        // If the match is more than 5 minutes in the future, we schedule it.
        // We use a small buffer to avoid "immediate" starts for matches that are about to begin.
        return if (startTime > (currentTime + 300000)) { 
            startTime - currentTime
        } else {
            0L
        }
    }

    /**
     * Determines if a match should be tracked immediately.
     * A match starts immediately only if it is explicitly marked as "LIVE" 
     * or if the current time has passed the start time.
     */
    fun shouldStartImmediately(match: UpcomingMatch): Boolean {
        val currentTime = System.currentTimeMillis()
        return match.matchTime == "LIVE" || (match.startTime > 0 && currentTime >= match.startTime)
    }
}
