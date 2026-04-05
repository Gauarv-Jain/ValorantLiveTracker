package com.example.valorantlivetracker.network

import android.util.Log
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.*

/**
 * Specialized parser for individual VLR.gg match pages.
 * Handles extracting team logos, tournament info, and precise timing.
 */
class VLRMatchPageParser {
    private val baseUrl = "https://www.vlr.gg"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val TAG = "VLRMatchPageParser"

    /**
     * Holds detailed information extracted from a match page.
     */
    data class MatchDetails(
        val teamALogoUrl: String,
        val teamBLogoUrl: String,
        val tournamentName: String,
        val tournamentLogoUrl: String,
        val isVCT: Boolean,
        val timestamp: Long,
        val formattedTime: String
    )

    /**
     * Scrapes a match page to extract details.
     * @param matchHref The relative URL of the match (e.g., "/123/team-a-vs-team-b")
     * @param etaText Fallback text if the specific time elements are missing
     */
    fun getMatchDetails(matchHref: String, etaText: String = ""): MatchDetails? {
        return try {
            val fullUrl = if (matchHref.startsWith("/")) "$baseUrl$matchHref" else "$baseUrl/$matchHref"
            val matchDoc = Jsoup.connect(fullUrl).userAgent(userAgent).timeout(5000).get()
            
            // 1. EXTRACT TIME AND DATE
            // VLR uses 'moment-tz-convert' class for elements that should show local time.
            val timeElements = matchDoc.select(".match-header-date .moment-tz-convert")
            
            // Extract the text content as seen in a browser (e.g., "Tuesday, April 7")
            val dateText = timeElements.firstOrNull { it.attr("data-moment-format") == "dddd, MMMM D" }?.text()?.trim() ?: ""
            // Extract the time text (e.g., "2:30 PM IST")
            val timeText = timeElements.firstOrNull { it.attr("data-moment-format") == "h:mm A z" }?.text()?.trim() ?: ""
            
            // Combine them for UI display
            val combinedFormattedTime = if (dateText.isNotEmpty() && timeText.isNotEmpty()) {
                "$dateText, $timeText"
            } else {
                etaText // Fallback to ETA (like "LIVE" or "2h 30m") if specific strings aren't found
            }

            // Also parse the machine-readable UTC timestamp for any background logic/sorting
            val rawTimestamp = timeElements.firstOrNull { it.hasAttr("data-utc-ts") }?.attr("data-utc-ts")
            var timestamp = 0L
            if (!rawTimestamp.isNullOrEmpty()) {
                try {
                    // Format: "yyyy-MM-dd HH:mm:ss" in UTC
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    val date = sdf.parse(rawTimestamp)
                    timestamp = date?.time ?: 0L
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse UTC timestamp: $rawTimestamp", e)
                }
            }

            // 2. EXTRACT TEAM LOGOS
            var teamALogo = matchDoc.select(".match-header-vs img").firstOrNull()?.attr("src") ?: ""
            var teamBLogo = matchDoc.select(".match-header-vs img").lastOrNull()?.attr("src") ?: ""
            
            teamALogo = formatUrl(teamALogo)
            teamBLogo = formatUrl(teamBLogo)

            // 3. TOURNAMENT EXTRACTION
            val tournamentLinkElement = matchDoc.select(".match-header-event a").first() ?: 
                                      matchDoc.select("a.match-header-event").first()
            
            val tournamentLink = tournamentLinkElement?.attr("href") ?: ""
            var tournamentName = tournamentLinkElement?.select("div")?.last()?.text()?.trim() ?: tournamentLinkElement?.text()?.trim() ?: "Unknown Tournament"
            
            // Use specific series title if available (e.g., "Group Stage: Week 2")
            val seriesTitle = matchDoc.select(".match-header-event-series").text().trim()
            if (seriesTitle.isNotEmpty()) {
                tournamentName = seriesTitle
            }

            // 4. VCT VERIFICATION (Is this a high-tier Champions Tour match?)
            var tournamentLogo = ""
            var isVCT = tournamentName.contains("Champions Tour", true) || 
                        tournamentName.contains("VCT", true)

            // If not immediately obvious, check the tournament's own page for VCT status
            if (tournamentLink.isNotEmpty()) {
                val tournamentUrl = if (tournamentLink.startsWith("/")) "$baseUrl$tournamentLink" else "$baseUrl/$tournamentLink"
                try {
                    val tourneyDoc = Jsoup.connect(tournamentUrl).userAgent(userAgent).timeout(3000).get()
                    
                    // Update tournament name from its official page title
                    val eventPageTitle = tourneyDoc.select(".event-header h1, .wf-title, .event-name").firstOrNull()?.text()?.trim() ?: ""
                    if (eventPageTitle.isNotEmpty()) tournamentName = eventPageTitle
                    
                    tournamentLogo = formatUrl(tourneyDoc.select(".event-header img, img[alt*='logo'], .event-logo img").firstOrNull()?.attr("src"))

                    // Deep check: Look for VCT keywords in the entire page content
                    val pageContent = tourneyDoc.text()
                    if (tournamentName.contains("Champions Tour", true) || 
                        tournamentName.contains("VCT", true) ||
                        pageContent.contains("Valorant Champions Tour", true)) {
                        isVCT = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not reach event page: $tournamentUrl")
                }
            }

            // 5. FINAL FILTERING
            // We want to skip lower-tier "Challengers" or "Game Changers" matches for this specific tracker
            if (tournamentName.contains("Challengers", ignoreCase = true) || 
                tournamentName.contains("Game Changers", ignoreCase = true)) {
                isVCT = false
            }

            MatchDetails(teamALogo, teamBLogo, tournamentName, tournamentLogo, isVCT, timestamp, combinedFormattedTime)
        } catch (e: Exception) {
            Log.e(TAG, "Scrape failed for match: $matchHref", e)
            null
        }
    }

    /**
     * Ensures image URLs are absolute and use HTTPS.
     */
    private fun formatUrl(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            else -> "$baseUrl$url"
        }
    }
}
