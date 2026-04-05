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
        return null
    }

    fun getMatchDetails(url: String): Match? {
        Log.d(TAG, "Scraping details from: $url")
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        return try {
            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return null
            val doc = Jsoup.parse(html)

            val teamNames = doc.select(".match-header-vs .wf-title-med, .match-header-vs .wf-title, .wf-title-med")
            if (teamNames.size < 2) {
                Log.e(TAG, "Could not find full team names in HTML")
                return null
            }

            val teamAName = teamNames[0].text().trim()
            val teamBName = teamNames[1].text().trim()
            
            // Extract short names from the vlr-rounds row columns as specified
            // Logic: look for the .team div inside the first columns of the rounds table
            val teamAShort = doc.select(".vlr-rounds .vlr-rounds-row-col .team").firstOrNull()?.ownText()?.trim() ?: ""
            val teamBShort = doc.select(".vlr-rounds .vlr-rounds-row-col .team").getOrNull(1)?.ownText()?.trim() ?: ""

            Log.d(TAG, "Extracted short names: Team A: '$teamAShort', Team B: '$teamBShort'")

            val teamA = Team(
                name = teamAName,
                shortName = teamAShort,
                logoUrl = fixUrl(doc.select(".match-header-vs img").firstOrNull()?.attr("src") ?: "")
            )
            val teamB = Team(
                name = teamBName,
                shortName = teamBShort,
                logoUrl = fixUrl(doc.select(".match-header-vs img").lastOrNull()?.attr("src") ?: "")
            )

            val tournament = doc.select(".match-header-event-series, .match-header-event").firstOrNull()?.text() ?: "VCT"
            val stage = doc.select(".match-header-event-name").text()
            val note = doc.select(".match-header-note").text().lowercase()

            val matchHeaderInfo = mapDetector.extractMatchHeaderInfo(doc)
            val allMapNames = mapDetector.getAllMapNames(doc)
            val maps = scoreExtractor.getAllMapScores(doc, allMapNames, note, teamAName, teamBName)
            val activeMapIndex = mapDetector.getActiveMapIndexFromMaps(maps)
            val notificationMap = mapDetector.getMapForNotification(maps, matchHeaderInfo)

            // Infer series type from stage if not detected from HTML
            var seriesType = matchHeaderInfo.seriesType
            if (seriesType == "bo1") {
                seriesType = inferSeriesTypeFromStage(stage)
                Log.d(TAG, "Inferred series type from stage '$stage': $seriesType")
            } else {
                Log.d(TAG, "Series type detected from HTML: $seriesType")
            }

            Match(
                id = url.split("/").getOrNull(4) ?: "live",
                url = url,
                teamA = teamA,
                teamB = teamB,
                tournament = tournament,
                stage = stage,
                status = matchHeaderInfo.matchState.name,
                seriesType = seriesType,
                teamAMapWins = matchHeaderInfo.teamAMapWins,
                teamBMapWins = matchHeaderInfo.teamBMapWins,
                maps = maps,
                activeMapIndex = activeMapIndex,
                notificationMap = notificationMap
            )
        } catch (e: Exception) {
            Log.e(TAG, "Scrape error", e)
            null
        }
    }

    private fun inferSeriesTypeFromStage(stage: String): String {
        val stageLower = stage.lowercase()
        
        // Playoffs, semifinals, finals are typically BO5
        if (stageLower.contains("playoffs") || 
            stageLower.contains("semifinals") || 
            stageLower.contains("semi-finals") ||
            stageLower.contains("finals") ||
            stageLower.contains("upper") ||
            stageLower.contains("lower") ||
            stageLower.contains("grand final")) {
            return "bo5"
        }
        
        // Group stages, Swiss stages are typically BO3
        if (stageLower.contains("group") || 
            stageLower.contains("swiss") ||
            stageLower.contains("week") ||
            stageLower.contains("stage")) {
            return "bo3"
        }
        
        // Default to BO3 for VCT
        return "bo3"
    }

    private fun fixUrl(url: String): String {
        if (url.isEmpty()) return ""
        return if (url.startsWith("//")) "https:$url" else if (url.startsWith("/")) "$baseUrl$url" else url
    }
}
