package com.mbv.viberdbviewer

import android.content.ClipData
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mbv.viberdbviewer.model.MessageKind
import kotlinx.coroutines.launch

const val CHAT_SEARCH_FIELD_TAG = "chat_search_field"

@Composable
fun SearchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    testTag: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        singleLine = true,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        trailingIcon = {
            if (value.isNotEmpty()) {
                TextButton(onClick = { onValueChange("") }) { Text("✕") }
            }
        },
    )
}

@Composable
fun CenteredContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        content()
    }
}

@Composable
fun CenteredProgress(modifier: Modifier = Modifier) {
    CenteredContent(modifier) { CircularProgressIndicator() }
}

@Composable
fun CenteredMessage(
    message: String,
    modifier: Modifier = Modifier,
) {
    CenteredContent(modifier) { Text(message) }
}

@Composable
fun CenteredProgressWithText(
    text: String,
    modifier: Modifier = Modifier,
) {
    CenteredContent(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(text)
        }
    }
}

@Composable
fun DeletedAwareText(
    text: String,
    kind: MessageKind,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Text(
        text = text,
        modifier = modifier,
        style = deletedAwareTextStyle(kind, MaterialTheme.typography.bodyMedium),
        color = deletedAwareTextColor(kind),
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
fun DeletedAwareText(
    text: AnnotatedString,
    kind: MessageKind,
    modifier: Modifier = Modifier,
) {
    if (kind == MessageKind.DELETED) {
        Text(
            text = text,
            modifier = modifier,
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(text = text, modifier = modifier)
    }
}

@Composable
private fun deletedAwareTextStyle(
    kind: MessageKind,
    normal: TextStyle,
): TextStyle =
    if (kind == MessageKind.DELETED) {
        MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic)
    } else {
        normal
    }

@Composable
private fun deletedAwareTextColor(kind: MessageKind): Color =
    if (kind == MessageKind.DELETED) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        Color.Unspecified
    }

@Composable
fun Modifier.copyOnLongClick(
    text: () -> String,
    onClick: () -> Unit = {},
): Modifier {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    return combinedClickable(
        onClick = onClick,
        onLongClick = {
            coroutineScope.launch {
                clipboard.setClipEntry(
                    ClipData.newPlainText(null, text()).toClipEntry(),
                )
            }
        },
    )
}
