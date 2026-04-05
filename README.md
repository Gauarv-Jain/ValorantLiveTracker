# Valorant Live Tracker

An Android application that provides real-time, round-by-round score notifications for Valorant Champions Tour (VCT) matches by scraping data from VLR.gg.

## 📂 Project Structure & Logic Flow

### 1. Discovery & Scraping Layer (`com.example.valorantlivetracker.network`)
The discovery process is decoupled into three specialized parts:
*   **`MatchDiscovery.kt`**: The **Orchestrator**. It coordinates the whole process—fetching the main page, filtering tournaments, and triggering deep scrapes.
*   **`VLRMatchListParser.kt`**: The **List Scanner**. Its only job is to find match cards on the main `/matches` page and extract raw links, tournament labels, and ETA/Live status.
*   **`VLRMatchPageParser.kt`**: The **Detail Scraper**. It visits a specific match URL to extract precise data:
    *   **Exact Time**: Scrapes the date ("Tuesday, April 7") and time ("2:30 PM IST") text directly from the HTML.
    *   **Verification**: Performs a "deep check" on the tournament page to confirm it's an official VCT event.
    *   **Logos**: Grabs team and tournament logos.
*   **`VLRScraper.kt`**: The **Live Engine**. Once a match is being tracked, this class fetches updates every few seconds.
*   **`ScoreExtractor.kt`**: The **Data Cruncher**. Parses the complex round tables to determine current scores and attacking/defending sides.

### 2. Automation & UI
*   **`MainActivity.kt`**: UI with two tabs: **Champions** (Auto-discovered VCT matches) and **Manual** (Match ID input).
*   **`MatchAutoCheckWorker.kt`**: Background task that runs every 15 minutes to automatically start the tracker if a VCT match goes LIVE.
*   **`MatchService.kt`**: Foreground service that maintains the live score notification.

---

## 🏃 Dry Run: What happens when you open the app?

1.  **UI Trigger**: The `MainActivity` loads the "Champions" tab. A `LaunchedEffect` calls `MatchDiscovery.getUpcomingChampionsMatches()`.
2.  **Step A (List Scan)**: `MatchDiscovery` fetches `vlr.gg/matches`. It hands the HTML to `VLRMatchListParser`.
3.  **Step B (Parsing List)**: `VLRMatchListParser` finds ~50 matches. It returns a list of `RawMatchItem` objects containing URLs and Tournament names.
4.  **Step C (Filtering)**: `MatchDiscovery` loops through these. It sees "Challengers" in a tournament name and immediately **skips** it (saving time and data).
5.  **Step D (Deep Scrape)**: For a "VCT" match, `MatchDiscovery` tells `VLRMatchPageParser` to visit that match's specific URL.
6.  **Step E (Extracting Details)**: `VLRMatchPageParser` loads the match page, finds the **"2:30 PM IST"** text, and confirms the tournament is VCT.
7.  **Result**: The UI displays a clean list of verified VCT matches with their exact start times.

---

## 🛠️ Fix Checklist: Which file should I check?

| If this happens... | Check this file |
| :--- | :--- |
| **A match is missing from the list** | `VLRMatchListParser.kt` (Is the `.match-item` CSS class still correct?) |
| **Wrong Date or Time text shown** | `VLRMatchPageParser.kt` (Check `.match-header-date .moment-tz-convert` selectors) |
| **"Challengers" matches are showing up** | `MatchDiscovery.kt` (Check the `shouldSkipTournament` filter) |
| **Scores aren't updating live** | `ScoreExtractor.kt` (Check the `.vlr-rounds` table parsing logic) |
| **Team Logos aren't showing** | `VLRMatchPageParser.kt` (Check `.match-header-vs img` selectors) |
| **App isn't auto-starting on live games** | `MatchAutoCheckWorker.kt` |
