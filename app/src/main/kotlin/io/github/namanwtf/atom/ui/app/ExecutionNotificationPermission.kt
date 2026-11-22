package io.github.namanwtf.atom.ui.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Asked the first time a user starts a background task; denying the notification doesn't block the task and can be set later from the permission page. */
@Composable
internal fun rememberExecutionNotificationRequest(): () -> Unit {
    val context = LocalContext.current
    val preferences = remember(context.applicationContext) {
        context.applicationContext.getSharedPreferences("atom_execution_notifications", Context.MODE_PRIVATE)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("requested", false)
        ) {
            preferences.edit().putBoolean("requested", true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
