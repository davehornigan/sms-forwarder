package pro.hornigan.smsforwarder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionInfo
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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

@Composable
fun MainScreen(navController: NavController) {
    val context = LocalContext.current
    val sharedPreferences = remember {
        context.getSharedPreferences("sms_forwarder_prefs", Context.MODE_PRIVATE)
    }

    val subscriptionManager = ContextCompat.getSystemService(context, SubscriptionManager::class.java)
    val activeSubscriptions = remember {
        try {
            subscriptionManager?.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    val statistics = remember { mutableStateMapOf<Int, SimStatistics>() }

    fun loadStatistics() {
        activeSubscriptions.forEach { subInfo ->
            val slotIndex = subInfo.simSlotIndex
            val statKeySuffix = "_$slotIndex"
            val totalForwarded = sharedPreferences.getInt("total_forwarded$statKeySuffix", 0)
            val successfulForwards = sharedPreferences.getInt("successful_forwards$statKeySuffix", 0)
            val failedForwards = sharedPreferences.getInt("failed_forwards$statKeySuffix", 0)
            statistics[slotIndex] = SimStatistics(totalForwarded, successfulForwards, failedForwards)
        }
    }

    LaunchedEffect(Unit) {
        loadStatistics()
    }

    var hasSmsPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED)
    }
    var isIgnoringBatteryOptimizations by remember {
        val powerManager = ContextCompat.getSystemService(context, PowerManager::class.java)
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true)
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            loadStatistics()
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasSmsPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
                val powerManager = ContextCompat.getSystemService(context, PowerManager::class.java)
                isIgnoringBatteryOptimizations = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    MainScreenContent(
        navController = navController,
        statistics = statistics,
        activeSubscriptions = activeSubscriptions,
        sharedPreferences = sharedPreferences,
        hasSmsPermission = hasSmsPermission,
        isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
        onOpenAppSettingsClick = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", context.packageName, null)
            context.startActivity(intent)
        },
        onOpenBatterySettingsClick = {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            context.startActivity(intent)
        },
        onSendTestSmsClick = { subscriptionInfo ->
            val slotIndex = subscriptionInfo.simSlotIndex
            val url = sharedPreferences.getString("webhook_url_$slotIndex", "") ?: ""
            if (url.contains("localhost") || url.contains("webhook.site")) {
                val serviceIntent = Intent(context, ForwardingService::class.java).apply {
                    putExtra("sender", "+1234567890")
                    putExtra("body", "This is a test message from SIM slot ${slotIndex + 1}.")
                    putExtra("subscriptionId", subscriptionInfo.subscriptionId)
                    putExtra("test_recipient", "+0987654321")
                    putExtra("is_test", true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                Toast.makeText(context, context.getString(R.string.test_webhook_restriction), Toast.LENGTH_LONG).show()
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenContent(
    navController: NavController,
    statistics: Map<Int, SimStatistics>,
    activeSubscriptions: List<SubscriptionInfo>,
    sharedPreferences: SharedPreferences,
    hasSmsPermission: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    onOpenAppSettingsClick: () -> Unit,
    onOpenBatterySettingsClick: () -> Unit,
    onSendTestSmsClick: (SubscriptionInfo) -> Unit,
    modifier: Modifier = Modifier
) {
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
                .verticalScroll(rememberScrollState())
        ) {
            if (!hasSmsPermission) {
                WarningCard(
                    text = stringResource(id = R.string.restricted_mode_notice),
                    buttonText = stringResource(id = R.string.open_app_settings),
                    onClick = onOpenAppSettingsClick
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            if (!isIgnoringBatteryOptimizations) {
                WarningCard(
                    text = stringResource(id = R.string.restricted_background_notice),
                    buttonText = stringResource(id = R.string.open_battery_settings),
                    onClick = onOpenBatterySettingsClick
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            activeSubscriptions.sortedBy { it.simSlotIndex }.forEach { subInfo ->
                val slotIndex = subInfo.simSlotIndex
                val simName = sharedPreferences.getString("sim_slot_name_$slotIndex", "")
                    ?.takeIf { it.isNotBlank() } ?: stringResource(id = R.string.default_slot_name, slotIndex + 1)
                val stats = statistics[slotIndex] ?: SimStatistics(0, 0, 0)
                val webhookUrl = sharedPreferences.getString("webhook_url_$slotIndex", "") ?: ""

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .testTag("sim_card_$slotIndex"),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = simName, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(end = 16.dp)) {
                                Text(stringResource(id = R.string.webhook_url))
                                Text(stringResource(id = R.string.total_forwarded))
                                Text(stringResource(id = R.string.successful))
                                Text(stringResource(id = R.string.failed))
                            }
                            Column {
                                Text(webhookUrl.ifEmpty { stringResource(id = R.string.url_not_set) })
                                Text(stats.totalForwarded.toString())
                                Text(stats.successfulForwards.toString())
                                Text(stats.failedForwards.toString())
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { onSendTestSmsClick(subInfo) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(id = R.string.send_test_sms))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WarningCard(text: String, buttonText: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(buttonText)
                }
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

    val subscriptionManager = ContextCompat.getSystemService(context, SubscriptionManager::class.java)
    val activeSubscriptions = remember {
        try {
            subscriptionManager?.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    val simSettings = remember {
        mutableStateMapOf<Int, SimSettings>().apply {
            activeSubscriptions.forEach { subInfo ->
                val slotIndex = subInfo.simSlotIndex
                this[slotIndex] = SimSettings(
                    webhookUrl = sharedPreferences.getString("webhook_url_$slotIndex", "") ?: "",
                    userAgent = sharedPreferences.getString("user_agent_$slotIndex", "") ?: "",
                    simName = sharedPreferences.getString("sim_slot_name_$slotIndex", "") ?: ""
                )
            }
        }
    }

    var showEditSimNameDialogForSlot by remember { mutableStateOf<Int?>(null) }

    showEditSimNameDialogForSlot?.let { slotIndex ->
        val setting = simSettings[slotIndex] ?: return@let
        var newName by remember(slotIndex) { mutableStateOf(setting.simName) }

        AlertDialog(
            onDismissRequest = { showEditSimNameDialogForSlot = null },
            title = { Text(stringResource(id = R.string.edit_sim_name_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(id = R.string.sim_slot_name_template, slotIndex + 1)) },
                    placeholder = { Text(stringResource(id = R.string.default_slot_name, slotIndex + 1)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    simSettings[slotIndex] = setting.copy(simName = newName)
                    sharedPreferences.edit(commit = true) {
                        putString("sim_slot_name_$slotIndex", newName)
                    }
                    showEditSimNameDialogForSlot = null
                    Toast.makeText(context, context.getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(id = R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditSimNameDialogForSlot = null }) {
                    Text(stringResource(id = R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(id = R.string.back))
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
                .verticalScroll(rememberScrollState())
                .testTag("settings_screen"),
            verticalArrangement = Arrangement.Top
        ) {
            activeSubscriptions.sortedBy { it.simSlotIndex }.forEach { subInfo ->
                val slotIndex = subInfo.simSlotIndex
                val setting = simSettings[slotIndex] ?: SimSettings()
                val simNamePlaceholder = stringResource(id = R.string.default_slot_name, slotIndex + 1)

                Column(modifier = Modifier.padding(bottom = 24.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = buildAnnotatedString {
                                append(setting.simName.ifBlank { simNamePlaceholder })
                                append(" ")
                                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.secondary)) {
                                    append("(SIM #${slotIndex + 1})")
                                }
                            },
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showEditSimNameDialogForSlot = slotIndex }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.edit_sim_name_dialog_title)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = setting.webhookUrl,
                        onValueChange = { simSettings[slotIndex] = setting.copy(webhookUrl = it) },
                        label = { Text(stringResource(id = R.string.webhook_url)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = setting.userAgent,
                        onValueChange = { simSettings[slotIndex] = setting.copy(userAgent = it) },
                        label = { Text(stringResource(id = R.string.user_agent)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))
            }

            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    try {
                        sharedPreferences.edit(commit = true) {
                            simSettings.forEach { (slotIndex, settings) ->
                                putString("webhook_url_$slotIndex", settings.webhookUrl)
                                putString("user_agent_$slotIndex", settings.userAgent)
                            }
                        }
                        Toast.makeText(context, context.getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.settings_error), Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(id = R.string.back))
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
                modifier = Modifier.fillMaxWidth()
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
                            Text("Message: $message")
                            Text("Timestamp: ${dateFormat.format(Date(timestamp))}")
                        }
                    }
                }
            }
        }
    }
}

data class SimStatistics(
    val totalForwarded: Int,
    val successfulForwards: Int,
    val failedForwards: Int
)

data class SimSettings(
    val webhookUrl: String = "",
    val userAgent: String = "",
    val simName: String = ""
)
