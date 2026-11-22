package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * OS4 action-button row at the bottom of a dialog: cancel on the left, confirm on the right, splitting the row evenly.
 *
 * @param confirmText Text for the confirm button.
 * @param onCancel Cancel callback.
 * @param onConfirm Confirm callback.
 * @param modifier Root modifier.
 * @param cancelText Text for the cancel button.
 * @param cancelEnabled Whether the cancel button is enabled.
 * @param confirmEnabled Whether the confirm button is enabled.
 * @param destructive Whether confirm is a destructive action (uses the error color).
 */
@Composable
fun MiuixDialogActions(
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    cancelText: String? = null,
    cancelEnabled: Boolean = true,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassDialogButton(
            onClick = onCancel,
            enabled = cancelEnabled,
            role = GlassDialogButtonRole.Normal,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = cancelText ?: stringResource(R.string.action_cancel),
                style = MiuixTheme.textStyles.button,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        GlassDialogButton(
            onClick = onConfirm,
            enabled = confirmEnabled,
            role = if (destructive) GlassDialogButtonRole.Destructive else GlassDialogButtonRole.Primary,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = confirmText,
                style = MiuixTheme.textStyles.button,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
