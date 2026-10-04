package com.ayuemin.disputeai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun BackgroundWorkSettings() {
    val context = LocalContext.current
    var batteryOptimizationDisabled by remember {
        mutableStateOf(isIgnoringBatteryOptimizations(context))
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryOptimizationDisabled = isIgnoringBatteryOptimizations(context)
    }

    fun launchSettings(primary: Intent, fallback: Intent? = null) {
        val intent = when {
            primary.resolveActivity(context.packageManager) != null -> primary
            fallback?.resolveActivity(context.packageManager) != null -> fallback
            else -> null
        }
        if (intent != null) {
            settingsLauncher.launch(intent)
        } else {
            Toast.makeText(
                context,
                "Системные настройки недоступны на этом устройстве",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Работа в фоне", style = MaterialTheme.typography.titleSmall)
        Text(
            if (batteryOptimizationDisabled) {
                "Оптимизация батареи для DisputeAI отключена."
            } else {
                "Чтобы длинная дискуссия не прерывалась при выключенном экране, можно исключить DisputeAI из оптимизации батареи."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedButton(
            onClick = {
                if (batteryOptimizationDisabled) {
                    launchSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } else {
                    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    launchSettings(
                        request,
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (batteryOptimizationDisabled) {
                    "Настройки оптимизации батареи"
                } else {
                    "Отключить оптимизацию батареи"
                }
            )
        }

        OutlinedButton(
            onClick = {
                val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                launchSettings(appDetails, Intent(Settings.ACTION_APPLICATION_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Настройки фоновой работы")
        }

        Text(
            "Если оболочка телефона предлагает режимы «Без ограничений», «Разрешить работу в фоне» или похожие — выберите разрешающий режим. Названия отличаются у разных производителей.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}
