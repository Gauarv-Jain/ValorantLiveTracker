package com.example.valorantlivetracker.network

import android.util.Log
import com.example.valorantlivetracker.models.UpcomingMatch
import org.jsoup.Jsoup

/**
 * Orchestrates the discovery of upcoming VCT matches.
 * It uses specialized parsers for list-level and page-level data extraction.
 */
class MatchDiscovery {
    private val baseUrl = "https://www.vlr.gg"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val TAG = "MatchDiscovery"
    
    // Decoupled Parsers
    private val matchListParser = VLRMatchListParser()
    private val matchPageParser = VLRMatchPageParser()

    /**
     * Main discovery loop.
     * 1. Fetches the list of all matches.
     * 2. Filters out irrelevant tournaments (Challengers, etc.).
     * 3. Performs deep-scraping on valid match candidates.
     */
    fun getUpcomingChampionsMatches(onMatchFound: ((UpcomingMatch) -> Unit)? = null): List<UpcomingMatch> {
        val matches = mutableListOf<UpcomingMatch>()
        val matchListUrl = "$baseUrl/matches"
        
        Log.d(TAG, "🚀 STARTING MATCH DISCOVERY")

        try {
            // Fetch the main matches page
            val doc = Jsoup.connect(matchListUrl).userAgent(userAgent).timeout(10000).get()
            
            // Step 1: Get the list of raw match items from the main page
            val rawItems = matchListParser.parseMatchList(doc)
            Log.d(TAG, "🎯 Found ${rawItems.size} raw match items on the list page")

            var processedCount = 0
            for (item in rawItems) {
                // Step 2: Early Filtering
                // We skip tournaments that we know aren't top-tier VCT early to save network calls.
                if (shouldSkipTournament(item.eventName)) {
                    Log.d(TAG, "⏭️ Skipping ${item.eventName} (Early Filter)")
                    continue
                }

                Log.d(TAG, "⚽ Processing match ${++processedCount} | ETA: ${item.etaText}")

                // Step 3: Extract basic team names from the URL slug
                val teamNames = parseTeamsFromSlug(item.href)
                if (teamNames == null) continue

                // Step 4: Deep Scrape
                // Visit the actual match page to get logos and verified tournament info
                Log.d(TAG, "🌐 Deep-scraping details for: ${teamNames.first} vs ${teamNames.second}")
                val details = matchPageParser.getMatchDetails(item.href, item.etaText) ?: continue

                // Step 5: Final Validation & Object Creation
                if (details.isVCT) {
                    val match = UpcomingMatch(
                        matchTitle = "${teamNames.first} vs ${teamNames.second}",
                        matchUrl = if (item.href.startsWith("/")) "$baseUrl${item.href}" else "$baseUrl/${item.href}",
                        startTime = details.timestamp,
                        teamAName = teamNames.first,
                        teamBName = teamNames.second,
                        teamALogoUrl = details.teamALogoUrl,
                        teamBLogoUrl = details.teamBLogoUrl,
                        tournamentName = details.tournamentName,
                        tournamentLogoUrl = details.tournamentLogoUrl,
                        matchTime = details.formattedTime
                    )
                    matches.add(match)
                    onMatchFound?.invoke(match)
                    Log.d(TAG, "✅ ADDED: ${match.matchTitle}")
                }
            }
            Log.d(TAG, "🎉 DISCOVERY COMPLETE! Found ${matches.size} matches.")
        } catch (e: Exception) {
            Log.e(TAG, "💥 DISCOVERY FAILED", e)
        }
        return matches
    }

    /**
     * Filters out non-VCT tournaments.
     */
    private fun shouldSkipTournament(name: String): Boolean {
        return name.contains("Challengers", ignoreCase = true) || 
               name.contains("Game Changers", ignoreCase = true)
    }

    /**
     * Helper to extract team names from a VLR URL slug.
     * e.g., "/642927/team-a-vs-team-b" -> Pair("Team A", "Team B")
     */
    private fun parseTeamsFromSlug(href: String): Pair<String, String>? {
        return try {
            val slug = href.split("/").last()
            val parts = slug.split("-vs-")
            if (parts.size < 2) return null
            
            val teamA = parts[0].replace("-", " ").capitalizeWords()
            val teamB = parts[1].split("-")
                .takeWhile { !it.matches(Regex("\\d{4}")) } // Stop if we hit a year/id
                .joinToString(" ").replace("-", " ").capitalizeWords()
            
            Pair(teamA, teamB)
        } catch (e: Exception) {
            null
        }
    }

    private fun String.capitalizeWords() = split(" ").joinToString(" ") { 
        it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() }
    }
}
