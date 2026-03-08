package com.example.valorantlivetracker.models

data class Team(
    val name: String,
    val logoUrl: String
)

data class MapScore(
    val mapName: String,
    val mapNumber: Int,
    val teamAScore: Int,
    val teamBScore: Int,

    val teamATRounds: Int,
    val teamACTRounds: Int,

    val teamBTRounds: Int,
    val teamBCTRounds: Int,

    val picker: String? = null
)

data class Match(
    val id: String,
    val url: String,
    val teamA: Team,
    val teamB: Team,
    val tournament: String,
    val stage: String,
    val status: String,               // LIVE / NOT_STARTED / FINISHED
    val seriesType: String,           // Bo3 / Bo5
    val teamAMapWins: Int,            // maps won by team A
    val teamBMapWins: Int,            // maps won by team B
    val maps: List<MapScore> = emptyList(),
    val activeMapIndex: Int = -1,     // index in maps list of currently active map
    val notificationMap: MapScore? = null,  // map to show in the notification
)

data class SeriesScore(
    val teamAWins: Int,
    val teamBWins: Int
)
