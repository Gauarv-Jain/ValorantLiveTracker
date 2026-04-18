# AI Agent Guidelines for Valorant Live Tracker

This document outlines the coding standards, architectural patterns, and specific instructions that AI agents must follow when contributing to this project.

## 🏗️ Architectural Principles

### 1. Decoupling & Logic Separation
*   **Rule**: Never put multiple responsibilities in a single file.
*   **Implementation**:
    *   Separate **List Scanning** (e.g., `VLRMatchListParser.kt`) from **Detail Scraping** (e.g., `VLRMatchPageParser.kt`).
    *   Keep **Orchestration** logic (e.g., `MatchDiscovery.kt`) separate from raw parsing logic.
    *   Move **Timing and Scheduling** math into helper classes (e.g., `MatchSchedulingHelper.kt`) rather than keeping them inside Workers or Services.
    *   Extract complex data extraction (like round history) into specialized "Extractor" or "Detector" classes.

### 2. Task-Specific Small Files
*   Prefer creating a new specialized file over adding complexity to an existing one. Files should be small and focused on a single task.

## 📝 Coding Standards

### 1. Mandatory Commenting
*   **Javadocs**: Every major class and public function must have a Javadoc-style comment explaining its purpose, parameters, and return value.
*   **Inline Comments**: Use comments to explain "why" a specific logic exists, especially for complex scraping/regex operations.
*   **Step-by-Step**: For core logic loops (like match discovery), provide numbered comments explaining the flow.

### 2. Scraping Integrity
*   **Time Extraction**: Always prioritize direct text extraction from the HTML (e.g., "2:30 PM IST") for display purposes, as browser-level localizations on the source site are more reliable for users.
*   **Timezone Handling**: When parsing timestamps for backend logic, explicitly handle timezones. Assume VLR.gg backend data-utc-ts is in `America/New_York` unless proven otherwise.
*   **Filtering**: Perform "Early Skips" on the list page to save network resources (e.g., filtering out "Challengers" or "Game Changers" before deep-scraping).

## ⚙️ Background Automation Rules

### 1. Refresh Cycles
*   **Worker Interval**: Keep the `MatchAutoCheckWorker` on a **24-hour periodic interval** unless explicitly asked to change it.
*   **WorkManager**: Always cancel existing work by tag (`match_start`) before rescheduling to prevent duplicate notifications.

## 📚 Maintenance Documentation
*   Maintain the **README.md** with a "Dry Run" section explaining the current code flow and a "Fix Checklist" mapping specific bugs to the responsible files.

## 🔓 UNLOCKING WORKFLOW
Before modifying any file listed in the **LOCKED FILES** section, the following steps **MUST** be followed:
1.  **Request Permission**: The AI must explicitly ask the user for permission to unlock a specific file.
2.  **Provide Reason**: The AI must provide a clear prompt explaining why the file needs to be unlocked and what changes are planned.
3.  **Update Status**: Only after the user grants permission, the AI must update the file's status in this `agents.md` file from "LOCKED" to "UNLOCKED".
4.  **Execute Task**: Only then can the AI proceed to modify the file.

## 🔒 LOCKED FILES (DO NOT MODIFY)
The following files are verified and working. **Do not modify these files unless the unlocking workflow above is strictly followed.**

### Core Logic & Network
*   [LOCKED] `app/src/main/java/com/example/valorantlivetracker/network/VLRMatchListParser.kt`: Specialized parser for the VLR.gg `/matches` list page. Extracts raw match item information.
*   [LOCKED] `app/src/main/java/com/example/valorantlivetracker/network/VLRMatchPageParser.kt`: Specialized parser for individual VLR.gg match pages. Handles team logos, tournament info, and precise text-based timing.
*   [LOCKED] `app/src/main/java/com/example/valorantlivetracker/network/MatchDiscovery.kt`: Orchestrates the discovery of upcoming VCT matches using the list and page parsers.
*   [LOCKED] `app/src/main/java/com/example/valorantlivetracker/network/VLRScraper.kt`: The core engine that fetches live match details, scores, and status updates.
*   [LOCKED] `app/src/main/java/com/example/valorantlivetracker/network/ScoreExtractor.kt`: Parses complex round-by-round tables on VLR to determine scores and side info.
*   [LOCKED] `app/src/main/java/com/example/valorantlivetracker/network/MapDetector.kt`: Logic for identifying the current active map and series progress.

### Background & Scheduling
*   [LOCKED] `app/src/main/java/com/example/valorantlivetracker/MatchAutoCheckWorker.kt`: Periodic worker that refreshes the schedule and handles immediate starts for live matches.
*   [LOCKED] `app/src/main/java/com/example/valorantlivetracker/MatchSchedulingHelper.kt`: Contains calculations for scheduling delays and start condition checks.
*   [LOCKED] `app/src/main/java/com/example/valorantlivetracker/MatchStartWorker.kt`: One-time worker that launches the tracker service at the scheduled match start time.
*   [LOCKED] `app/src/main/java/com/example/valorantlivetracker/MatchBootReceiver.kt`: Reschedules the periodic auto-check worker after a device reboot.

### UI & Service
*   [LOCKED] `app/src/main/java/com/example/valorantlivetracker/MainActivity.kt`: Main UI entry point with tabbed navigation and manual/auto tracking controls.
*   [UNLOCKED] `app/src/main/java/com/example/valorantlivetracker/MatchService.kt`: Foreground service that maintains the live scrape loop and updates the score notification.

### Models
*   [LOCKED] `app/src/main/java/com/example/valorantlivetracker/models/Models.kt`: Centralized data models for Teams, Matches, Scores, and Upcoming Matches.

## 🛠️ Tooling & Libraries
*   **Scraping**: Jsoup for HTML parsing.
*   **Images**: Coil for Compose UI, Glide for RemoteViews (Notifications).
*   **Background**: WorkManager for periodic and one-time tasks.
