package com.example.valorantlivetracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.valorantlivetracker.MatchAutoCheckWorker
import com.example.valorantlivetracker.models.UpcomingMatch
import com.example.valorantlivetracker.network.MatchDiscovery
import com.example.valorantlivetracker.ui.theme.ValorantLiveTrackerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) permissions.add(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
        permissions.forEach { requestPermissionLauncher.launch(it) }

        // Schedule the periodic background worker if auto-detect is on
        val prefs = getSharedPreferences("vlr_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("auto_enabled", true)) {
            MatchAutoCheckWorker.schedule(this)
        }

        setContent {
            ValorantLiveTrackerTheme {
                var selectedTab by remember { mutableIntStateOf(1) } // Default to Auto tab
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Text("Manual") }, label = { Text("Manual") })
                            NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Text("Auto") }, label = { Text("Champions") })
                        }
                    }
                ) { innerPadding ->
                    if (selectedTab == 0) MainScreen(Modifier.padding(innerPadding))
                    else AutoMatchScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoMatchScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val discovery = remember { MatchDiscovery() }
    val scope = rememberCoroutineScope()
    
    var matches by remember { mutableStateOf(listOf<UpcomingMatch>()) }
    var isRefreshing by remember { mutableStateOf(false) }

    val prefs = context.getSharedPreferences("vlr_prefs", Context.MODE_PRIVATE)
    var isAutoEnabled by remember { mutableStateOf(prefs.getBoolean("auto_enabled", true)) }

    // Logic to fetch matches
    val fetchMatches: suspend () -> Unit = {
        isRefreshing = true
        Log.d("AutoMatchScreen", "🚀 Starting match discovery...")
        try {
            val fetchedMatches: List<UpcomingMatch> = withContext(Dispatchers.IO) { 
                discovery.getUpcomingChampionsMatches() 
            }
            matches = fetchedMatches
            Log.d("AutoMatchScreen", "✅ Found ${fetchedMatches.size} matches")
        } catch (e: Exception) {
            Log.e("AutoMatchScreen", "💥 Discovery failed", e)
            matches = emptyList()
        } finally {
            isRefreshing = false
        }
    }

    LaunchedEffect(Unit) {
        fetchMatches()
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-Start Live VCT", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Switch(checked = isAutoEnabled, onCheckedChange = {
                isAutoEnabled = it
                prefs.edit().putBoolean("auto_enabled", it).apply()
                if (it) MatchAutoCheckWorker.schedule(context) else MatchAutoCheckWorker.cancel(context)
            })
        }
        Text(if (isAutoEnabled) "Auto-starts live VCT matches" else "Auto-start is off", fontSize = 12.sp)

        Spacer(Modifier.height(16.dp))

        // Pull-to-refresh wrapper
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { scope.launch { fetchMatches() } },
            modifier = Modifier.fillMaxSize()
        ) {
            if (matches.isEmpty() && !isRefreshing) {
                // Empty state with scrollable column to allow pull-to-refresh even when empty
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "No upcoming matches found",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Check your internet or pull down to refresh.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            } else {
                // Optimized LazyColumn with keys for smooth scrolling
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(
                        items = matches,
                        key = { it.matchUrl } // CRITICAL: Use matchUrl as unique key to optimize scrolling and animations
                    ) { match ->
                        MatchCard(match = match, onCardClick = {
                            val intent = Intent(context, MatchService::class.java).apply { putExtra("MATCH_ID", match.matchUrl) }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                        })
                    }
                }
            }
        }
    }
}

/**
 * Extracted MatchCard to reduce recomposition scope and improve performance.
 */
@Composable
fun MatchCard(match: UpcomingMatch, onCardClick: () -> Unit) {
    Card(
        onClick = onCardClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // Tournament header
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                if (match.tournamentLogoUrl.isNotEmpty()) {
                    AsyncImage(
                        model = match.tournamentLogoUrl,
                        contentDescription = "Tournament logo",
                        modifier = Modifier.size(24.dp).padding(end = 8.dp),
                        error = androidx.compose.ui.graphics.painter.ColorPainter(androidx.compose.ui.graphics.Color.Gray)
                    )
                }
                Text(
                    text = match.tournamentName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                
                // Match Date & Time
                Text(
                    text = if (match.matchTime == "LIVE") "LIVE" else match.matchTime,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (match.matchTime == "LIVE") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        
            // Teams row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Team A
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (match.teamALogoUrl.isNotEmpty()) {
                        AsyncImage(
                            model = match.teamALogoUrl,
                            contentDescription = "${match.teamAName} logo",
                            modifier = Modifier.size(32.dp).padding(end = 8.dp),
                            error = androidx.compose.ui.graphics.painter.ColorPainter(androidx.compose.ui.graphics.Color.Gray)
                        )
                    }
                    Text(match.teamAName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
                
                // VS
                Text("VS", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
                
                // Team B
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(match.teamBName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    if (match.teamBLogoUrl.isNotEmpty()) {
                        AsyncImage(
                            model = match.teamBLogoUrl,
                            contentDescription = "${match.teamBName} logo",
                            modifier = Modifier.size(32.dp).padding(start = 8.dp),
                            error = androidx.compose.ui.graphics.painter.ColorPainter(androidx.compose.ui.graphics.Color.Gray)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isTracking by remember { mutableStateOf(false) }
    var matchIdInput by remember { mutableStateOf("626541") }

    Column(modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("Valorant Live Tracker", style = MaterialTheme.typography.headlineMedium)
        Text("v1.5.0 - Unified Auto-Start", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = matchIdInput, onValueChange = { matchIdInput = it }, label = { Text("Match ID") }, modifier = Modifier.width(280.dp))
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            val intent = Intent(context, MatchService::class.java)
            if (!isTracking) {
                intent.putExtra("MATCH_ID", matchIdInput)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                isTracking = true
            } else {
                context.stopService(intent)
                isTracking = false
            }
        }) {
            Text(if (isTracking) "Stop Tracking" else "Start Tracking")
        }
    }
}
