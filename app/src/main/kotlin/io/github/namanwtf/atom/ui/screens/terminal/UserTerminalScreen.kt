package io.github.namanwtf.atom.ui.screens.terminal

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.agent.terminal.isLinux
import io.github.namanwtf.atom.data.model.AppearanceUiStyle
import io.github.namanwtf.atom.ui.app.LocalUiStyle
import io.github.namanwtf.atom.ui.app.TerminalBlockUi
import io.github.namanwtf.atom.ui.app.UserTerminalStore
import io.github.namanwtf.atom.ui.components.AtomMonospace
import io.github.namanwtf.atom.ui.components.AtomTopLayer
import io.github.namanwtf.atom.ui.components.ansiPlainText
import io.github.namanwtf.atom.ui.components.ansiToAnnotatedString
import io.github.namanwtf.atom.ui.components.coui.CouiButton
import io.github.namanwtf.atom.ui.components.coui.CouiButtonRole
import io.github.namanwtf.atom.ui.components.coui.CouiMenu
import io.github.namanwtf.atom.ui.components.coui.CouiMenuAnchor
import io.github.namanwtf.atom.ui.components.coui.CouiMenuItem
import io.github.namanwtf.atom.ui.components.coui.couiMenuAnchor
import io.github.namanwtf.atom.ui.components.coui.rememberCouiMenuAnchor
import io.github.namanwtf.atom.ui.components.currentGlassOverlayBackdrop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Copy
import top.yukonga.miuix.kmp.icon.glass.Reply
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * User manual terminal: block-based output (command, output, exit code) for humans; separate from the task model used by AI tool calls.
 * Sessions are held by [UserTerminalStore]; after leaving the page, running commands keep going in the resident session.
 */
@Composable
internal fun UserTerminalScreen(
    store: UserTerminalStore,
    onOpenEnvironment: () -> Unit,
    onOpenConsole: (() -> Unit)? = null,
) {
    val state by store.uiState.collectAsState()
    val input = remember { TextFieldState() }
    var showTasks by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Refresh the Linux ready state after returning from the install page so the guide page yields to the terminal; refresh daemon task state too.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                store.refreshLinuxReady()
                store.refreshDaemonTasks()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val showLinuxGuide = state.environment.isLinux && !state.linuxReady

    fun submit() {
        val command = input.text.toString().trim()
        if (command.isEmpty()) return
        // While running, input goes to the foreground process stdin; otherwise it runs as a new command.
        if (state.running) {
            store.sendInput(command)
        } else {
            store.send(command)
        }
        input.clearText()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        TerminalToolbar(
            environment = state.environment,
            linuxEnvironment = state.linuxEnvironment,
            onSwitchEnvironment = store::switchEnvironment,
            sessionCount = state.sessions.size,
            daemonRunning = state.daemonTasks.any { it.running },
            onOpenSessions = { showSessions = true },
            onOpenTasks = {
                store.refreshDaemonTasks()
                showTasks = true
            },
            modeTarget = if (onOpenConsole != null) TerminalModeTarget.CONSOLE else null,
            onSwitchMode = { onOpenConsole?.invoke() },
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                showLinuxGuide -> LinuxGuide(onOpenEnvironment = onOpenEnvironment)
                state.blocks.isEmpty() -> EmptyHint(state.failMessage)
                else -> BlockList(
                    blocks = state.blocks,
                    onReinput = { input.setTextAndPlaceCursorAtEnd(it) },
                )
            }
        }
        if (!showLinuxGuide) {
            InputPanel(
                input = input,
                cwd = state.cwd,
                running = state.running,
                onSubmit = ::submit,
                onStop = store::stop,
            )
        }
    }

    DaemonTasksDialog(
        show = showTasks,
        tasks = state.daemonTasks,
        onDismiss = { showTasks = false },
        onStop = store::stopDaemonTask,
        onLoadLogs = store::daemonLogs,
    )

    SessionListDialog(
        show = showSessions,
        rows = state.sessions.map { session ->
            SessionDialogRow(
                id = session.id,
                environment = session.environment,
                subtitle = session.cwd,
                active = session.id == state.activeSessionId,
                running = session.running,
                alive = session.alive,
            )
        },
        onDismiss = { showSessions = false },
        onSelect = store::switchSession,
        onRestart = store::restartSession,
        onClose = store::closeSession,
        onNew = store::newSession,
    )
}

@Composable
private fun BlockList(
    blocks: List<TerminalBlockUi>,
    onReinput: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: Int.MAX_VALUE
            lastVisible >= layoutInfo.totalItemsCount - 1
        }
    }
    // Output growth only follows when the user is already at the bottom; scrolling up through history is not interrupted.
    LaunchedEffect(blocks.size, blocks.lastOrNull()?.output?.length) {
        if (atBottom && blocks.isNotEmpty()) {
            listState.scrollToItem(blocks.lastIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(items = blocks, key = { it.id }) { block ->
            if (block.isSystem) {
                SystemBlock(block)
            } else {
                CommandBlock(block = block, onReinput = onReinput)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommandBlock(
    block: TerminalBlockUi,
    onReinput: (String) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var blockBounds by remember { mutableStateOf(Rect.Zero) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val colors = MiuixTheme.colorScheme
    val exitCode = block.exitCode
    val failed = exitCode != null && exitCode != 0
    val anchor = rememberCouiMenuAnchor()
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { blockBounds = it.boundsInWindow() }
                .couiMenuAnchor(anchor)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = { anchor.open { showMenu = true } },
                ),
        ) {
            if (block.cwdAtStart.isNotEmpty()) {
                Text(
                    text = block.cwdAtStart,
                    style = MiuixTheme.textStyles.footnote2.copy(fontFamily = AtomMonospace),
                    color = colors.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = blockHeader(block.command, promptColor = if (failed) colors.error else colors.primary),
                style = MiuixTheme.textStyles.body2.copy(fontFamily = AtomMonospace),
                color = colors.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (block.output.isNotEmpty()) {
                val parsedOutput = remember(block.output) { ansiToAnnotatedString(block.output) }
                Text(
                    text = parsedOutput,
                    style = MiuixTheme.textStyles.footnote1.copy(fontFamily = AtomMonospace),
                    color = colors.onSurface.copy(alpha = 0.86f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            BlockFooter(block = block, failed = failed)
        }
        BlockMenu(
            show = showMenu,
            hasOutput = block.output.isNotEmpty(),
            anchor = anchor,
            anchorBounds = blockBounds,
            onDismiss = { showMenu = false },
            onCopyCommand = { scope.launch { clipboard.setPlainText(block.command) } },
            onCopyOutput = { scope.launch { clipboard.setPlainText(ansiPlainText(block.output)) } },
            onReinput = { onReinput(block.command) },
        )
    }
}

@Composable
private fun BlockFooter(block: TerminalBlockUi, failed: Boolean) {
    val colors = MiuixTheme.colorScheme
    val labels = buildList {
        if (failed) add(stringResource(R.string.terminal_exit_code, block.exitCode ?: 0) to colors.error)
        if (block.truncated) add(stringResource(R.string.terminal_output_truncated) to colors.onSurfaceVariantSummary)
    }
    if (!block.running && labels.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        if (block.running) {
            InfiniteProgressIndicator(size = 12.dp)
            Text(
                text = stringResource(R.string.terminal_running),
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
            )
        }
        labels.forEach { (text, color) ->
            Text(text = text, style = MiuixTheme.textStyles.footnote2, color = color)
        }
    }
}

@Composable
private fun blockHeader(command: String, promptColor: Color): AnnotatedString =
    remember(command, promptColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = promptColor, fontWeight = FontWeight.Bold)) { append("❯ ") }
            append(command)
        }
    }

private suspend fun Clipboard.setPlainText(text: String) {
    setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
}

@Composable
private fun BlockMenu(
    show: Boolean,
    hasOutput: Boolean,
    anchor: CouiMenuAnchor,
    anchorBounds: Rect,
    onDismiss: () -> Unit,
    onCopyCommand: () -> Unit,
    onCopyOutput: () -> Unit,
    onReinput: () -> Unit,
) {
    val copyCommandText = stringResource(R.string.terminal_copy_command)
    val copyOutputText = stringResource(R.string.terminal_copy_output)
    val reinputText = stringResource(R.string.terminal_reinput)
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val menuItems = remember(copyCommandText, copyOutputText, reinputText, hasOutput) {
                listOf(
                    CouiMenuItem(value = 0, text = copyCommandText),
                    CouiMenuItem(value = 1, text = copyOutputText, enabled = hasOutput),
                    CouiMenuItem(value = 2, text = reinputText),
                )
            }
            CouiMenu(
                expanded = show,
                onDismissRequest = onDismiss,
                items = menuItems,
                onItemClick = { item ->
                    onDismiss()
                    when (item.value) {
                        0 -> onCopyCommand()
                        1 -> onCopyOutput()
                        2 -> onReinput()
                    }
                },
                anchor = anchor,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            val overlayBackdrop = currentGlassOverlayBackdrop()
            AtomTopLayer {
                GlassPopup(
                    show = show,
                    onDismissRequest = onDismiss,
                    anchorBounds = anchorBounds,
                    backdrop = overlayBackdrop,
                ) {
                    GlassPopupItem(
                        text = copyCommandText,
                        icon = MiuixIcons.Glass.Copy,
                        onClick = {
                            onDismiss()
                            onCopyCommand()
                        },
                    )
                    GlassPopupItem(
                        text = copyOutputText,
                        icon = MiuixIcons.Glass.Copy,
                        enabled = hasOutput,
                        onClick = {
                            onDismiss()
                            onCopyOutput()
                        },
                    )
                    GlassPopupItem(
                        text = reinputText,
                        icon = MiuixIcons.Glass.Reply,
                        onClick = {
                            onDismiss()
                            onReinput()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemBlock(block: TerminalBlockUi) {
    Text(
        text = block.output,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Bottom input area: current directory on top, command input below. Enter executes directly;
 * while running, an empty input box turns the trailing button into stop; with content it sends to the foreground process stdin.
 */
@Composable
private fun InputPanel(
    input: TextFieldState,
    cwd: String,
    running: Boolean,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val canSend = input.text.isNotBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
    ) {
        if (cwd.isNotEmpty()) {
            Text(
                text = cwd,
                style = MiuixTheme.textStyles.footnote2.copy(fontFamily = AtomMonospace),
                color = colors.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
            )
        }
        TextField(
            state = input,
            lineLimits = TextFieldLineLimits.SingleLine,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(
                if (running) R.string.terminal_input_running_hint else R.string.terminal_input_hint,
            ),
            useLabelAsPlaceholder = true,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Send,
                autoCorrectEnabled = false,
            ),
            onKeyboardAction = { onSubmit() },
            textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = AtomMonospace),
            trailingIcon = {
                val stopMode = running && !canSend
                IconButton(
                    onClick = if (stopMode) onStop else onSubmit,
                    enabled = stopMode || canSend,
                    minWidth = 36.dp,
                    minHeight = 36.dp,
                    backgroundColor = when {
                        stopMode -> colors.error.copy(alpha = 0.12f)
                        canSend -> colors.primary
                        else -> Color.Transparent
                    },
                    cornerRadius = 18.dp,
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Icon(
                        imageVector = if (stopMode) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                        contentDescription = stringResource(
                            if (stopMode) R.string.terminal_stop else R.string.terminal_send,
                        ),
                        modifier = Modifier.size(18.dp),
                        tint = when {
                            stopMode -> colors.error
                            canSend -> colors.onPrimary
                            else -> colors.onSurfaceVariantSummary.copy(alpha = 0.5f)
                        },
                    )
                }
            },
        )
    }
}

@Composable
private fun LinuxGuide(onOpenEnvironment: () -> Unit) {
    TerminalPlaceholder(
        icon = Icons.Rounded.Terminal,
        message = stringResource(R.string.terminal_linux_not_ready),
        actionText = stringResource(R.string.terminal_open_environment),
        onAction = onOpenEnvironment,
    )
}

@Composable
private fun EmptyHint(message: String? = null) {
    TerminalPlaceholder(
        icon = Icons.Rounded.Terminal,
        message = message ?: stringResource(R.string.terminal_empty_hint),
    )
}

/** Centered placeholder for the terminal area: empty state, environment-not-ready, and disconnected sessions share the same layering and spacing. */
@Composable
internal fun TerminalPlaceholder(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionText: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .squircleBackground(colors.surfaceContainer, 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = colors.onSurfaceVariantSummary,
            )
        }
        Text(
            text = message,
            style = MiuixTheme.textStyles.footnote1,
            color = colors.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )
        if (actionText != null && onAction != null) {
            if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
                CouiButton(
                    text = actionText,
                    onClick = onAction,
                    role = CouiButtonRole.Primary,
                    modifier = Modifier.padding(top = 18.dp),
                )
            } else {
                TextButton(
                    text = actionText,
                    onClick = onAction,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.padding(top = 18.dp),
                )
            }
        }
        if (secondaryActionText != null && onSecondaryAction != null) {
            if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
                CouiButton(
                    text = secondaryActionText,
                    onClick = onSecondaryAction,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                TextButton(
                    text = secondaryActionText,
                    onClick = onSecondaryAction,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
