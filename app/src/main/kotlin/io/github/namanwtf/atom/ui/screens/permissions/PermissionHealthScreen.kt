package io.github.namanwtf.atom.ui.screens.permissions

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.data.model.AppearanceUiStyle
import io.github.namanwtf.atom.ui.app.LocalUiStyle
import io.github.namanwtf.atom.ui.components.MiuixScaffoldPage
import io.github.namanwtf.atom.ui.components.color
import io.github.namanwtf.atom.ui.components.coui.CouiPageBottomSpacer
import io.github.namanwtf.atom.ui.components.coui.CouiPreference
import io.github.namanwtf.atom.ui.components.coui.CouiPreferenceGroup
import io.github.namanwtf.atom.ui.components.coui.CouiScaffoldPage
import io.github.namanwtf.atom.ui.components.label
import io.github.namanwtf.atom.ui.model.PermissionHealthAction
import io.github.namanwtf.atom.ui.model.PermissionHealthItemUi
import io.github.namanwtf.atom.ui.model.PermissionHealthUiState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PermissionHealthScreen(
    state: PermissionHealthUiState,
    onAction: (PermissionHealthAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = stringResource(R.string.ui_permission_health_3048bb),
                onBack = { onAction(PermissionHealthAction.NavigateBack) },
                modifier = modifier,
            ) {
                CouiPreferenceGroup(title = stringResource(R.string.ui_permissions_and_status_35f368)) {
                    state.items.forEach { item ->
                        item(key = item.id) {
                            PermissionItemRow(
                                item = item,
                                onActionClick = { onAction(PermissionHealthAction.OpenItemAction(item.id)) },
                            )
                        }
                    }
                }
                CouiPageBottomSpacer()
            }
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.ui_permission_health_3048bb),
                onBack = { onAction(PermissionHealthAction.NavigateBack) },
                modifier = modifier,
            ) {
                item(key = "card") {
                    SmallTitle(stringResource(R.string.ui_permissions_and_status_35f368))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        state.items.forEach { item ->
                            PermissionItemRow(
                                item = item,
                                onActionClick = { onAction(PermissionHealthAction.OpenItemAction(item.id)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionItemRow(
    item: PermissionHealthItemUi,
    onActionClick: () -> Unit,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiPreference(
                title = item.title,
                summary = item.summary.takeIf { it.isNotBlank() },
                value = item.status.label(),
                valueColor = item.status.color(),
                onClick = onActionClick,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            ArrowPreference(
                title = item.title,
                summary = item.summary.takeIf { it.isNotBlank() },
                endActions = {
                    Text(
                        text = item.status.label(),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = item.status.color(),
                    )
                },
                onClick = onActionClick,
            )
        }
    }
}
