package com.example.valorantlivetracker.network

import org.jsoup.nodes.Document

/**
 * Specialized parser for the VLR.gg /matches list page.
 * Extracts raw match item information before deep-scraping each match.
 */
class VLRMatchListParser {

    /**
     * Holds basic info extracted from a single row on the /matches page.
     */
    data class RawMatchItem(
        val href: String,
        val eventName: String,
        val etaText: String
    )

    /**
     * Parses the /matches page HTML and returns a list of raw match items.
     * @param doc The Jsoup Document of the /matches page.
     */
    fun parseMatchList(doc: Document): List<RawMatchItem> {
        val matchItems = mutableListOf<RawMatchItem>()
        
        // Find all match cards using the .match-item class
        val cards = doc.select(".match-item")
        
        for (card in cards) {
            val href = card.attr("href")
            if (href.isEmpty()) continue
            
            // Extract the tournament name label on the list page
            val eventName = card.select(".match-item-event").text().trim()
            
            // Extract the time until start or "LIVE" status
            val etaText = card.select(".match-item-eta").text().trim()
            
            matchItems.add(RawMatchItem(href, eventName, etaText))
        }
        
        return matchItems
    }
}
