package com.mbv.viberdbviewer

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch

val LocalCopySnackbarHostState = staticCompositionLocalOf<SnackbarHostState?> { null }

@Composable
fun CopySnackbarHost(hostState: SnackbarHostState) {
    SnackbarHost(hostState) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
fun Modifier.copyOnLongClick(
    text: () -> String,
    onClick: () -> Unit = {},
): Modifier {
    val clipboard = LocalClipboard.current
    val snackbarHostState = LocalCopySnackbarHostState.current
    val copiedMessage = stringResource(R.string.copied_to_clipboard)
    val coroutineScope = rememberCoroutineScope()
    return combinedClickable(
        onClick = onClick,
        onLongClick = {
            coroutineScope.launch {
                clipboard.setClipEntry(
                    ClipData.newPlainText(null, text()).toClipEntry(),
                )
                if (snackbarHostState != null && shouldShowCopySnackbar()) {
                    snackbarHostState.showSnackbar(copiedMessage)
                }
            }
        },
    )
}

private fun shouldShowCopySnackbar(): Boolean {
    val isOnePlus =
        Build.MANUFACTURER.equals("oneplus", ignoreCase = true) ||
            Build.BRAND.equals("oneplus", ignoreCase = true)
    return isOnePlus || Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2
}
