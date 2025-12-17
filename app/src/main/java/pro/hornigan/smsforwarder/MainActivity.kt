package pro.hornigan.smsforwarder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import pro.hornigan.smsforwarder.ui.theme.SMSForwarderTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10
            permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 34) { // UPSIDE_DOWN_CAKE
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())

        setContent {
            SMSForwarderTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(navController = navController)
                    }
                    composable("settings") {
                        SettingsScreen(navController = navController)
                    }
                    composable("error_log") {
                        ErrorLogScreen(navController = navController)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(navController: NavController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sharedPreferences = remember {
        context.getSharedPreferences("sms_forwarder_prefs", Context.MODE_PRIVATE)
    }
    var totalForwarded by remember {
        mutableStateOf(sharedPreferences.getInt("total_forwarded", 0))
    }
    var successfulForwards by remember {
        mutableStateOf(sharedPreferences.getInt("successful_forwards", 0))
    }
    var failedForwards by remember {
        mutableStateOf(sharedPreferences.getInt("failed_forwards", 0))
    }
    var savedUrl by remember {
        mutableStateOf(sharedPreferences.getString("webhook_url", "") ?: "")
    }

    DisposableEffect(Unit) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            when (key) {
                "total_forwarded" -> totalForwarded = prefs.getInt(key, 0)
                "successful_forwards" -> successfulForwards = prefs.getInt(key, 0)
                "failed_forwards" -> failedForwards = prefs.getInt(key, 0)
                "webhook_url" -> savedUrl = prefs.getString(key, "") ?: ""
            }
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.app_name)) },
                actions = {
                    IconButton(onClick = { navController.navigate("error_log") }) {
                        Icon(Icons.Filled.BugReport, contentDescription = stringResource(id = R.string.error_log))
                    }
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(id = R.string.settings))
                    }
                }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(it)
                .padding(16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(id = R.string.current_url))
                    Text(stringResource(id = R.string.total_forwarded))
                    Text(stringResource(id = R.string.successful))
                    Text(stringResource(id = R.string.failed))
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = savedUrl.ifEmpty { stringResource(id = R.string.url_not_set) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(totalForwarded.toString())
                    Text(successfulForwards.toString())
                    Text(failedForwards.toString())
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    val serviceIntent = Intent(context, ForwardingService::class.java).apply {
                        putExtra("sender", "+1234567890")
                        putExtra("body", "This is a test message.")
                        putExtra("subscriptionId", -1) // Explicitly mark as not a real SMS
                        putExtra("test_recipient", "+0987654321")
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(id = R.string.send_test_sms))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sharedPreferences = remember {
        context.getSharedPreferences("sms_forwarder_prefs", Context.MODE_PRIVATE)
    }
    var textFieldValue by remember {
        mutableStateOf(sharedPreferences.getString("webhook_url", "") ?: "")
    }
    var userAgentFieldValue by remember {
        mutableStateOf(sharedPreferences.getString("user_agent", "") ?: "")
    }

    val subscriptionManager = ContextCompat.getSystemService(context, SubscriptionManager::class.java)
    val activeSubscriptions = remember {
        try {
            subscriptionManager?.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    val simNamesState = remember {
        mutableStateMapOf<Int, String>().apply {
            activeSubscriptions.forEach { subInfo ->
                val slotIndex = subInfo.simSlotIndex
                this[slotIndex] = sharedPreferences.getString("sim_slot_name_$slotIndex", "") ?: ""
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(it)
                .padding(16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { textFieldValue = it },
                label = { Text(stringResource(id = R.string.webhook_url)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = userAgentFieldValue,
                onValueChange = { userAgentFieldValue = it },
                label = { Text(stringResource(id = R.string.user_agent)) },
                modifier = Modifier.fillMaxWidth()
            )

            if (activeSubscriptions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))

                Text(stringResource(id = R.string.sim_slot_names))
                Spacer(modifier = Modifier.height(16.dp))

                activeSubscriptions.sortedBy { it.simSlotIndex }.forEach { subInfo ->
                    val slotIndex = subInfo.simSlotIndex
                    OutlinedTextField(
                        value = simNamesState[slotIndex] ?: "",
                        onValueChange = { newValue -> simNamesState[slotIndex] = newValue },
                        label = { Text(stringResource(id = R.string.sim_slot_name_template, slotIndex + 1)) },
                        placeholder = { Text(stringResource(id = R.string.default_slot_name, slotIndex + 1)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    try {
                        sharedPreferences.edit(commit = true) {
                            putString("webhook_url", textFieldValue)
                            putString("user_agent", userAgentFieldValue)
                            simNamesState.forEach { (slotIndex, name) ->
                                putString("sim_slot_name_$slotIndex", name)
                            }
                        }
                        Toast.makeText(context, context.getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.settings_error), Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(id = R.string.save))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorLogScreen(navController: NavController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sharedPreferences = remember {
        context.getSharedPreferences("sms_forwarder_prefs", Context.MODE_PRIVATE)
    }
    var errorLogs by remember {
        mutableStateOf(sharedPreferences.getStringSet("error_logs", emptySet()) ?: emptySet())
    }

    val sortedLogs = remember(errorLogs) {
        errorLogs.mapNotNull { log ->
            val parts = log.split('|', limit = 2)
            if (parts.size == 2) {
                Pair(parts[0].toLong(), parts[1])
            } else {
                null
            }
        }.sortedByDescending { it.first }
    }

    val dateFormat = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.error_log)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(it)
                .padding(16.dp)
        ) {
            Button(
                onClick = {
                    sharedPreferences.edit {
                        remove("error_logs")
                    }
                    errorLogs = emptySet()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(id = R.string.clear_log))
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (sortedLogs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(id = R.string.no_errors))
                }
            } else {
                LazyColumn {
                    items(sortedLogs) { (timestamp, message) ->
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(dateFormat.format(Date(timestamp)))
                            Text(message)
                        }
                    }
                }
            }
        }
    }
}
