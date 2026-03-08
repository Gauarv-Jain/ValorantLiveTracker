package com.example.valorantlivetracker

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.valorantlivetracker.ui.theme.ValorantLiveTrackerTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
        }
        
        permissions.forEach { permission ->
            requestPermissionLauncher.launch(permission)
        }

        setContent {
            ValorantLiveTrackerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isTracking by remember { mutableStateOf(false) }
    var matchIdInput by remember { mutableStateOf("626538") }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Valorant Live Tracker", style = MaterialTheme.typography.headlineMedium)
        Text(text = "v1.4.0 - Active Map Sync", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
        
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = matchIdInput,
            onValueChange = { matchIdInput = it },
            label = { Text("Match ID (from VLR.gg URL)") },
            modifier = Modifier.width(280.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))
        
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
            Text(if (isTracking) "Stop Live Notification" else "Start Live Notification")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isTracking) "Live notification is active!" else "Status: Idle",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
