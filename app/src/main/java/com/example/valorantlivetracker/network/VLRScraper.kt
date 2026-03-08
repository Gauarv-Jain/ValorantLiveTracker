package com.example.valorantlivetracker.network

import android.util.Log
import com.example.valorantlivetracker.models.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class VLRScraper {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .build()
    private val baseUrl = "https://www.vlr.gg"
    private val TAG = "VLRScraper"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val mapDetector = MapDetector()
    private val scoreExtractor = ScoreExtractor()

    fun findLiveMatch(): String? {
        Log.d(TAG, "Searching for live matches...")
        val request = Request.Builder()
            .url("$baseUrl/matches")
            .header("User-Agent", userAgent)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return null
            val doc = Jsoup.parse(html)
            
            val matchLinks = doc.select("a.match-item, a.m-item, a.wf-module-item")
            for (link in matchLinks) {
                val href = link.attr("href")
                if (!href.startsWith("/match/")) continue

                val statusText = link.select(".ml-status, .m-item-result, .match-item-eta").text()
                if (statusText.contains("LIVE", ignoreCase = true) || statusText.contains(":")) {
                    val matchUrl = baseUrl + href
                    if (isTier1Match(matchUrl)) return matchUrl
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error finding live match", e)
            null
        }
    }

    private fun isTier1Match(url: String): Boolean {
        return try {
            val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return false
            
            val keywords = listOf("Champions Tour", "Tier 1", "Masters", "Champions", "VCT")
            for (k in keywords) {
                if (html.contains(k, ignoreCase = true)) return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    fun getMatchDetails(url: String): Match? {
        Log.d(TAG, "Scraping details: $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        return try {
            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return null
            
            // Console log the HTML summary for debugging
            Log.d(TAG, "HTML Length: ${html.length}")
            if (html.length > 500) {
                Log.d(TAG, "HTML Snippet (Header): ${html.take(500)}")
            }

            val doc = Jsoup.parse(html)

            val teamNames = doc.select(".match-header-vs .wf-title-med, .match-header-vs .wf-title, .wf-title-med")
            if (teamNames.size < 2) {
                Log.e(TAG, "Could not find team names in HTML")
                return null
            }
            
            val teamAName = teamNames[0].text().trim()
            val teamBName = teamNames[1].text().trim()

            val teamA = Team(name = teamAName, logoUrl = fixUrl(doc.select(".match-header-vs img").firstOrNull()?.attr("src") ?: ""))
            val teamB = Team(name = teamBName, logoUrl = fixUrl(doc.select(".match-header-vs img").lastOrNull()?.attr("src") ?: ""))

            val tournament = doc.select(".match-header-event-series, .match-header-event").firstOrNull()?.text() ?: "VCT"
            val stage = doc.select(".match-header-event-name").text()
            val note = doc.select(".match-header-note").text().lowercase()

            // --- Match State (live/upcoming/final) & Series info ---
            val matchHeaderInfo = mapDetector.extractMatchHeaderInfo(doc)
            Log.d(TAG, "Match state: $matchHeaderInfo")  // e.g., state=LIVE, teamAMapWins=0, teamBMapWins=1, seriesType=3

            // --- Map Names ---
            val allMapNames = mapDetector.getAllMapNames(doc)
            Log.d(TAG, "All Maps found: ${allMapNames.joinToString()}")

            // --- Map Scores & Side Info ---
            val maps = scoreExtractor.getAllMapScores(
                doc,
                allMapNames,
                note,
                teamAName,
                teamBName
            )

            // --- Determine Active Map / Notification Map ---
            val activeMapIndex = mapDetector.getActiveMapIndexFromMaps(maps)
            val notificationMap = mapDetector.getMapForNotification(maps, matchHeaderInfo)


            Match(
                id = url.split("/").getOrNull(4) ?: "live",
                url = url,
                teamA = teamA,
                teamB = teamB,
                tournament = tournament,
                stage = stage,
                status = matchHeaderInfo.matchState.name, // LIVE / NOT_STARTED / FINISHED
                seriesType = matchHeaderInfo.seriesType,  // Bo3 / Bo5
                teamAMapWins = matchHeaderInfo.teamAMapWins,
                teamBMapWins = matchHeaderInfo.teamBMapWins,
                maps = maps,                     // full map list with scores & side info
                activeMapIndex = activeMapIndex, // index of currently active map in list
                notificationMap = notificationMap,
                // map that should be displayed in the notification
            )
        } catch (e: Exception) {
            Log.e(TAG, "Scrape error", e)
            null
        }
    }

    private fun isMatchForTeam(part: String, fullName: String): Boolean {
        val p = part.lowercase().trim()
        val f = fullName.lowercase().trim()
        if (p.isEmpty()) return false
        if (f.contains(p) || p.contains(f)) return true
        val abbreviation = fullName.split(" ").filter { it.isNotEmpty() }.map { it[0] }.joinToString("").lowercase()
        if (abbreviation == p) return true
        if (fullName.split(" ").any { it.lowercase() == p }) return true
        return false
    }

    private fun fixUrl(url: String): String {
        if (url.isEmpty()) return ""
        return if (url.startsWith("//")) "https:$url" else if (url.startsWith("/")) "$baseUrl$url" else url
    }
}
