package io.github.namanwtf.atom.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.agent.imagegen.ImageGenToolCatalog
import io.github.namanwtf.atom.agent.model.AgentFileReferencePromptCodec
import io.github.namanwtf.atom.agent.overlay.toolDisplayName
import io.github.namanwtf.atom.data.model.AppearanceUiStyle
import io.github.namanwtf.atom.ui.app.LocalUiStyle
import io.github.namanwtf.atom.ui.components.coui.CouiMenu
import io.github.namanwtf.atom.ui.components.coui.CouiMenuItem
import io.github.namanwtf.atom.ui.components.coui.CouiPageDefaults
import io.github.namanwtf.atom.ui.components.coui.couiButtonFeedback
import io.github.namanwtf.atom.ui.components.coui.couiMenuAnchor
import io.github.namanwtf.atom.ui.components.coui.rememberCouiMenuAnchor
import io.github.namanwtf.atom.ui.markdown.EditorialLineHeightStyle
import io.github.namanwtf.atom.ui.markdown.MarkdownTone
import io.github.namanwtf.atom.ui.markdown.StaticMarkdown
import io.github.namanwtf.atom.ui.markdown.StreamingMarkdown
import io.github.namanwtf.atom.ui.markdown.StreamingMarkdownState
import io.github.namanwtf.atom.ui.markdown.THINKING_LINE_HEIGHT_SP
import io.github.namanwtf.atom.ui.model.AgentChatMessageUi
import io.github.namanwtf.atom.ui.model.AgentMessageUi
import io.github.namanwtf.atom.ui.model.RunTraceMessageUi
import io.github.namanwtf.atom.ui.model.SuggestionChipsMessageUi
import io.github.namanwtf.atom.ui.model.SystemNoticeCode
import io.github.namanwtf.atom.ui.model.SystemNoticeMessageUi
import io.github.namanwtf.atom.ui.model.ThinkingMessageUi
import io.github.namanwtf.atom.ui.model.ToolActivityMessageUi
import io.github.namanwtf.atom.ui.model.ToolSummaryMessageUi
import io.github.namanwtf.atom.ui.model.UserMessageUi
import io.github.namanwtf.atom.ui.voice.SpeechReadAloudButton
import io.github.suqi8.coui.kmp.squircle.SquircleShape as CouiShape
import io.github.suqi8.coui.kmp.theme.COUITheme as CouiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.RichTooltip
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.basic.TooltipDefaults
import top.yukonga.miuix.kmp.basic.rememberTooltipState
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val chatImageBitmapCache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int =
        (value.width * value.height * 4).coerceAtLeast(1)
}

@Composable
internal fun rememberDataUrlBitmap(dataUrl: String): ImageBitmap? =
    rememberChatImageBitmap(source = dataUrl, maxDimensionPx = 1600)

/**
 * Unified chat image decoder:
 * - `data:image/...;base64,...` hits/writes the LRU cache synchronously, keeping existing user previews free of first-frame flicker;
 * - local original files (including AI-generated 1K~4K images) are sample-decoded on an IO thread by [maxDimensionPx], avoiding main-thread jank and OOM.
 */
@Composable
internal fun rememberChatImageBitmap(
    source: String,
    maxDimensionPx: Int = 1600,
): ImageBitmap? {
    val cacheKey = remember(source, maxDimensionPx) { "$maxDimensionPx|$source" }
    val cached = remember(cacheKey) {
        chatImageBitmapCache.get(cacheKey)
            ?: if (source.startsWith("data:", ignoreCase = true)) {
                decodeDataUrlBitmap(source)?.also { chatImageBitmapCache.put(cacheKey, it) }
            } else {
                null
            }
    }
    var asyncBitmap by remember(cacheKey) { mutableStateOf(cached) }
    LaunchedEffect(cacheKey) {
        if (asyncBitmap != null || source.isBlank() || source.startsWith("data:", ignoreCase = true)) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            decodeLocalFileBitmap(source, maxDimensionPx)
        }
        if (decoded != null) {
            chatImageBitmapCache.put(cacheKey, decoded)
            asyncBitmap = decoded
        }
    }
    return asyncBitmap
}

internal fun decodeDataUrlBitmap(dataUrl: String): ImageBitmap? {
    val base64 = dataUrl.substringAfter("base64,", "")
    if (base64.isBlank()) return null
    return runCatching {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

private fun decodeLocalFileBitmap(source: String, maxDimensionPx: Int): ImageBitmap? {
    val file = File(source.removePrefix("file://").trim())
    if (!file.isFile) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        val limit = maxDimensionPx.coerceAtLeast(256)
        while ((w / sample) > limit || (h / sample) > limit) {
            sample *= 2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )?.asImageBitmap()
    }.getOrNull()
}

/**
 * Lightweight feedback while waiting for the first text chunk.
 */
@Composable
fun AITypingIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val delay = index * 150
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = delay, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .graphicsLayer(alpha = alpha)
                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary, CircleShape)
            )
        }
    }
}

/**
 * Only the executing state holds an infinite animation. Historical thinking and tool entries stay static, so in long sessions
 * every completed node doesn't keep producing frame clocks and state updates.
 */
@Composable
internal fun rememberActivePulse(
    active: Boolean,
    label: String,
): Float {
    if (!active) return 1f
    val transition = rememberInfiniteTransition(label = label)
    val alpha by transition.animateFloat(
        initialValue = 0.58f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(820, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "${label}_alpha",
    )
    return alpha
}

@Composable
internal fun ChatMessageItem(
    message: AgentChatMessageUi,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    showBrowserShortcut: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    assistantOverlay: Boolean = false,
    retainedStreamingState: StreamingMarkdownState? = null,
    showCopyAction: Boolean = true,
    showMessageActions: Boolean = false,
    showBranchAction: Boolean = false,
    messageActionsEnabled: Boolean = true,
    isEditing: Boolean = false,
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
    onBranchMessage: (String) -> Unit = {},
    onSelectReplyCandidate: (String, Int) -> Unit = { _, _ -> },
    onEditGeneratedImage: ((String) -> Unit)? = null,
) {
    when (message) {
        is UserMessageUi -> UserMessageBubble(
            message = message,
            assistantOverlay = assistantOverlay,
            actionsEnabled = messageActionsEnabled,
            isEditing = isEditing,
            onEdit = { onEditMessage(message.id) },
            onDelete = { onDeleteMessage(message.id) },
            modifier = modifier,
        )
        is AgentMessageUi -> AgentMessageBlock(
            message = message,
            allowSpeech = true,
            assistantOverlay = assistantOverlay,
            retainedStreamingState = retainedStreamingState,
            showCopyAction = showCopyAction,
            showMessageActions = showMessageActions,
            showBranchAction = showBranchAction,
            onBranch = { onBranchMessage(message.id) },
            messageActionsEnabled = messageActionsEnabled,
            onDelete = { onDeleteMessage(message.id) },
            onRegenerate = { onRegenerateMessage(message.id) },
            onEdit = { onEditMessage(message.id) },
            onSelectCandidate = { onSelectReplyCandidate(message.id, it) },
            modifier = modifier,
        )
        is SystemNoticeMessageUi -> if (message.code == SystemNoticeCode.ContextCompaction) {
            ContextCompactionMarker(message = message, modifier = modifier)
        } else {
            AgentMessageBlock(
                message = AgentMessageUi(
                    id = message.id,
                    content = buildString {
                        append(
                            stringResource(
                                when (message.code) {
                                    SystemNoticeCode.Stopped -> R.string.system_notice_stopped
                                    SystemNoticeCode.EmptyResult -> R.string.system_notice_empty_result
                                    SystemNoticeCode.ContextCompaction -> R.string.context_compaction
                                    SystemNoticeCode.ModelRetry -> R.string.system_notice_model_retry
                                    SystemNoticeCode.RuntimeFailed -> R.string.system_notice_runtime_failed
                                    SystemNoticeCode.Interrupted -> R.string.system_notice_interrupted
                                },
                            ),
                        )
                        message.detail?.takeIf(String::isNotBlank)?.let { detail ->
                            append("\n\n")
                            append(detail)
                        }
                    },
                    renderMarkdown = false,
                ),
                assistantOverlay = false,
                retainedStreamingState = null,
                showCopyAction = showCopyAction,
                showMessageActions = showMessageActions,
                messageActionsEnabled = messageActionsEnabled,
                onDelete = { onDeleteMessage(message.id) },
                onRegenerate = { onRegenerateMessage(message.id) },
                modifier = modifier,
            )
        }
        is ThinkingMessageUi -> ThinkingRow(
            message = message,
            retainedStreamingState = retainedStreamingState,
            modifier = modifier,
            compact = compact,
        )
        is RunTraceMessageUi -> RunTraceRow(message = message, onClick = onRunTraceClick, modifier = modifier)
        is ToolActivityMessageUi -> if (message.toolName == ImageGenToolCatalog.TOOL_NAME) {
            GeneratedImageMessageCard(
                message = message,
                assistantOverlay = assistantOverlay,
                onEditImage = onEditGeneratedImage,
                modifier = modifier,
            )
        } else {
            ToolActivityInline(
                message = message,
                onOpenBrowser = onOpenBrowser,
                showBrowserShortcut = showBrowserShortcut,
                modifier = modifier,
                compact = compact,
            )
        }
        is ToolSummaryMessageUi -> ToolSummaryInline(message = message, modifier = modifier, compact = compact)
        is SuggestionChipsMessageUi -> SuggestionChipsRow(message = message, onSuggestionClick = onSuggestionClick, modifier = modifier)
    }
}

// ── User message: light, attractive bubble ──────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessageBubble(
    message: UserMessageUi,
    assistantOverlay: Boolean,
    actionsEnabled: Boolean,
    isEditing: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val tooltipState = rememberTooltipState(isPersistent = true)
    val haptic = LocalHapticFeedback.current
    var showCouiMenu by remember { mutableStateOf(false) }
    val couiAnchor = rememberCouiMenuAnchor()
    LaunchedEffect(actionsEnabled) {
        if (!actionsEnabled) {
            tooltipState.dismiss()
            showCouiMenu = false
        }
    }
    LaunchedEffect(tooltipState) {
        snapshotFlow { tooltipState.isVisible }.collect { visible ->
            if (visible) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    val visiblePrompt = remember(message.content) {
        AgentFileReferencePromptCodec.parse(message.content)
    }
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val uiStyle = LocalUiStyle.current
    val overlayBubbleColor = when {
        uiStyle == AppearanceUiStyle.COUI && isDark -> Color(0xFF21293A)
        uiStyle == AppearanceUiStyle.COUI -> Color(0xFFEAF0FC)
        isDark -> Color(0xFF37393D)
        else -> Color(0xFFE5E7EA)
    }
    val imagePreviewer = LocalChatImagePreviewer.current
    val hasTextBubble = visiblePrompt.references.isNotEmpty() || visiblePrompt.request.isNotBlank()
    val canCopy = message.content.isNotBlank()
    val bubbleContent: @Composable () -> Unit = {
        if (visiblePrompt.references.isNotEmpty()) {
            SentFileReferenceFlow(
                references = visiblePrompt.references,
                modifier = Modifier.padding(
                    bottom = if (visiblePrompt.request.isNotBlank()) 8.dp else 0.dp,
                ),
            )
        }
        if (visiblePrompt.request.isNotBlank()) {
            SelectionContainer {
                Text(
                    text = visiblePrompt.request,
                    style = MiuixTheme.textStyles.body1.copy(
                        lineHeight = 23.sp,
                        lineHeightStyle = EditorialLineHeightStyle,
                    ),
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
        if (message.isEdited) {
            Text(
                text = stringResource(R.string.ui_edited_c36776),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (assistantOverlay) 16.dp else 20.dp,
                end = if (assistantOverlay) 16.dp else 20.dp,
                top = if (assistantOverlay) 4.dp else 16.dp,
                bottom = if (assistantOverlay) 4.dp else 6.dp,
            ),
        horizontalArrangement = Arrangement.End,
    ) {
        if (uiStyle == AppearanceUiStyle.COUI && !assistantOverlay) {
            val couiDark = CouiTheme.isDark
            val couiBubbleBg = if (couiDark) Color(0xFF202634) else Color(0xFFEEF2FA)
            val couiColors = CouiPageDefaults.themeColors()
            val destructiveColor = CouiTheme.colorScheme.error
            val copyText = stringResource(R.string.ui_copy_4edd1d)
            val editText = stringResource(R.string.ui_edit_a7f814)
            val deleteText = stringResource(R.string.ui_delete_3755f5)
            val menuItems = remember(canCopy, copyText, editText, deleteText, destructiveColor) {
                buildList {
                    if (canCopy) {
                        add(CouiMenuItem(value = 0, text = copyText, icon = Icons.Rounded.ContentCopy))
                    }
                    add(CouiMenuItem(value = 1, text = editText, icon = Icons.Rounded.Edit))
                    add(
                        CouiMenuItem(
                            value = 2,
                            text = deleteText,
                            icon = Icons.Rounded.Delete,
                            iconTint = destructiveColor,
                            dividerBefore = true,
                        ),
                    )
                }
            }
            val openCouiMenu: (() -> Unit)? = if (actionsEnabled) {
                {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    couiAnchor.open { showCouiMenu = true }
                }
            } else {
                null
            }
            Box {
                Column(
                    modifier = Modifier.couiMenuAnchor(couiAnchor),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (message.images.isNotEmpty()) {
                        UserMessageImages(
                            messageId = message.id,
                            images = message.images,
                            assistantOverlay = false,
                            isCoui = true,
                            couiDark = couiDark,
                            highlightBorder = isEditing && !hasTextBubble,
                            highlightColor = couiColors.accent,
                            previewer = imagePreviewer,
                            onLongClick = openCouiMenu,
                        )
                    }
                    if (hasTextBubble) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 316.dp)
                                .clip(UserBubbleShape)
                                .background(couiBubbleBg, UserBubbleShape)
                                .then(
                                    if (isEditing) {
                                        Modifier.border(1.dp, couiColors.accent, UserBubbleShape)
                                    } else {
                                        Modifier
                                    },
                                )
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {},
                                    onLongClick = openCouiMenu,
                                )
                                .padding(horizontal = 16.dp, vertical = 10.5.dp),
                        ) {
                            bubbleContent()
                        }
                    } else if (message.isEdited) {
                        Text(
                            text = stringResource(R.string.ui_edited_c36776),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                CouiMenu(
                    expanded = showCouiMenu && actionsEnabled,
                    onDismissRequest = { showCouiMenu = false },
                    items = menuItems,
                    onItemClick = { item ->
                        showCouiMenu = false
                        when (item.value) {
                            0 -> {
                                @Suppress("DEPRECATION")
                                clipboardManager.setText(AnnotatedString(message.content))
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            }
                            1 -> onEdit()
                            2 -> onDelete()
                        }
                    },
                    anchor = couiAnchor,
                )
            }
        } else {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                    positioning = TooltipAnchorPosition.Below,
                ),
                tooltip = {
                    RichTooltip(insideMargin = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (canCopy) {
                                MessageTooltipAction(
                                    icon = Icons.Rounded.ContentCopy,
                                    label = stringResource(R.string.ui_copy_4edd1d),
                                    onClick = {
                                        @Suppress("DEPRECATION")
                                        clipboardManager.setText(AnnotatedString(message.content))
                                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                        tooltipState.dismiss()
                                    },
                                )
                            }
                            MessageTooltipAction(
                                icon = Icons.Rounded.Edit,
                                label = stringResource(R.string.ui_edit_a7f814),
                                onClick = {
                                    tooltipState.dismiss()
                                    onEdit()
                                },
                            )
                            MessageTooltipAction(
                                icon = Icons.Rounded.Delete,
                                label = stringResource(R.string.ui_delete_3755f5),
                                onClick = {
                                    tooltipState.dismiss()
                                    onDelete()
                                },
                            )
                        }
                    }
                },
                state = tooltipState,
                focusable = true,
                enableUserInput = actionsEnabled,
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (message.images.isNotEmpty()) {
                        UserMessageImages(
                            messageId = message.id,
                            images = message.images,
                            assistantOverlay = assistantOverlay,
                            isCoui = false,
                            couiDark = false,
                            highlightBorder = isEditing && !hasTextBubble,
                            highlightColor = MiuixTheme.colorScheme.primary,
                            previewer = imagePreviewer.takeUnless { assistantOverlay },
                            onLongClick = null,
                        )
                    }
                    if (hasTextBubble) {
                        val bubbleShape = when {
                            assistantOverlay && uiStyle == AppearanceUiStyle.COUI -> OverlayCouiUserBubbleShape
                            assistantOverlay -> OverlayUserBubbleShape
                            else -> UserBubbleShape
                        }
                        Column(
                            modifier = Modifier
                                .widthIn(max = 316.dp)
                                .clip(bubbleShape)
                                .background(
                                    color = if (assistantOverlay) {
                                        overlayBubbleColor
                                    } else {
                                        MiuixTheme.colorScheme.surfaceContainerHigh
                                    },
                                    shape = bubbleShape,
                                )
                                .then(
                                    if (isEditing) {
                                        Modifier.border(
                                            width = 1.dp,
                                            color = MiuixTheme.colorScheme.primary,
                                            shape = bubbleShape,
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .padding(
                                    horizontal = if (assistantOverlay) 14.dp else 16.dp,
                                    vertical = if (assistantOverlay) 9.dp else 10.5.dp,
                                ),
                        ) {
                            bubbleContent()
                        }
                    } else if (message.isEdited) {
                        Text(
                            text = stringResource(R.string.ui_edited_c36776),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

/** In single-line form the left side is a full half-capsule arc, while the bottom-right keeps a crisp 4dp conversation corner. */
private val UserBubbleShape = RoundedCornerShape(
    topStart = 22.dp,
    topEnd = 22.dp,
    bottomEnd = 4.dp,
    bottomStart = 22.dp,
)

private val OverlayUserBubbleShape = RoundedCornerShape(
    topStart = 14.dp,
    topEnd = 14.dp,
    bottomEnd = 4.dp,
    bottomStart = 14.dp,
)

private val OverlayCouiUserBubbleShape = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 20.dp,
    bottomEnd = 5.dp,
    bottomStart = 20.dp,
)

/**
 * User-message image attachments are laid out right-aligned and independent of the text bubble, so a single thumbnail doesn't sit at the top-left of a wide bubble leaving a large blank.
 * Single images size themselves to the original aspect ratio; multiple images form a compact rounded group; tapping opens full-screen preview.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessageImages(
    messageId: String,
    images: List<String>,
    assistantOverlay: Boolean,
    isCoui: Boolean,
    couiDark: Boolean,
    highlightBorder: Boolean,
    highlightColor: Color,
    previewer: ChatImagePreviewer?,
    onLongClick: (() -> Unit)?,
) {
    val outlineColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.55f)
    val borderWidth = if (highlightBorder) 1.dp else 0.5.dp
    val borderColor = if (highlightBorder) highlightColor else outlineColor
    val single = images.size == 1
    val cornerRadius = when {
        assistantOverlay -> 10.dp
        single -> 16.dp
        else -> 14.dp
    }
    // A plain HashMap is read only at tap time or during flight drawing; list-scroll coordinate updates trigger no composition state writes.
    val itemBounds = remember(messageId) { HashMap<Int, Rect>() }

    if (single) {
        val bitmap = rememberDataUrlBitmap(images[0]) ?: return
        val rawRatio = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
        val ratio = rawRatio.coerceIn(0.54f, 1.85f)
        val maxW = when {
            assistantOverlay -> 152.dp
            ratio > 1.15f -> 204.dp
            else -> 176.dp
        }
        val maxH = if (assistantOverlay) 176.dp else 216.dp
        val (cardW, cardH) = if (maxW / ratio <= maxH) {
            maxW to (maxW / ratio)
        } else {
            (maxH * ratio) to maxH
        }
        UserMessageImageCard(
            bitmap = bitmap,
            width = cardW,
            height = cardH,
            cornerRadius = cornerRadius,
            assistantOverlay = assistantOverlay,
            isCoui = isCoui,
            couiDark = couiDark,
            borderWidth = borderWidth,
            borderColor = borderColor,
            isHiddenInViewer = { previewer?.isPresenting(messageId, 0) == true },
            onBoundsChanged = { itemBounds[0] = it },
            onClick = previewer?.let { viewer ->
                {
                    viewer.open(
                        images = images,
                        initialIndex = 0,
                        groupKey = messageId,
                        sourceBoundsProvider = { itemBounds[it] },
                        sourceCornerRadius = cornerRadius,
                    )
                }
            },
            onLongClick = onLongClick,
        )
        return
    }

    val twoUp = images.size == 2
    val cardW = when {
        assistantOverlay && twoUp -> 94.dp
        assistantOverlay -> 80.dp
        twoUp -> 118.dp
        else -> 98.dp
    }
    val cardH = when {
        assistantOverlay && twoUp -> 118.dp
        assistantOverlay -> 100.dp
        twoUp -> 148.dp
        else -> 124.dp
    }
    val rowModifier = if (images.size > 2) {
        Modifier
            .widthIn(max = if (assistantOverlay) 248.dp else 316.dp)
            .horizontalScroll(rememberScrollState())
    } else {
        Modifier
    }
    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        images.forEachIndexed { index, dataUrl ->
            val bitmap = rememberDataUrlBitmap(dataUrl) ?: return@forEachIndexed
            UserMessageImageCard(
                bitmap = bitmap,
                width = cardW,
                height = cardH,
                cornerRadius = cornerRadius,
                assistantOverlay = assistantOverlay,
                isCoui = isCoui,
                couiDark = couiDark,
                borderWidth = borderWidth,
                borderColor = borderColor,
                isHiddenInViewer = { previewer?.isPresenting(messageId, index) == true },
                onBoundsChanged = { itemBounds[index] = it },
                onClick = previewer?.let { viewer ->
                    {
                        viewer.open(
                            images = images,
                            initialIndex = index,
                            groupKey = messageId,
                            sourceBoundsProvider = { itemBounds[it] },
                            sourceCornerRadius = cornerRadius,
                        )
                    }
                },
                onLongClick = onLongClick,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessageImageCard(
    bitmap: ImageBitmap,
    width: Dp,
    height: Dp,
    cornerRadius: Dp,
    assistantOverlay: Boolean,
    isCoui: Boolean,
    couiDark: Boolean,
    borderWidth: Dp,
    borderColor: Color,
    isHiddenInViewer: () -> Boolean,
    onBoundsChanged: (Rect) -> Unit,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val clickModifier = when {
        onLongClick != null || (isCoui && onClick != null) -> Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
        onClick != null -> Modifier.clickable(onClick = onClick)
        else -> Modifier
    }
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .onGloballyPositioned { coords ->
                if (coords.isAttached) onBoundsChanged(coords.boundsInWindow())
            }
            .graphicsLayer {
                alpha = if (isHiddenInViewer()) 0f else 1f
            }
            .then(
                when {
                    assistantOverlay -> {
                        val shape = RoundedCornerShape(cornerRadius)
                        Modifier
                            .clip(shape)
                            .background(MiuixTheme.colorScheme.surfaceContainer, shape)
                            .border(borderWidth, borderColor, shape)
                    }
                    isCoui -> {
                        val shape = CouiShape(cornerRadius, 2.2f)
                        Modifier
                            .then(
                                if (onClick != null) {
                                    Modifier.couiButtonFeedback(couiDark, interactionSource, enabled = true)
                                } else {
                                    Modifier
                                },
                            )
                            .clip(shape)
                            .background(MiuixTheme.colorScheme.surfaceContainer, shape)
                            .border(borderWidth, borderColor, shape)
                    }
                    else -> Modifier.squircleSurface(
                        color = MiuixTheme.colorScheme.surfaceContainer,
                        cornerRadius = cornerRadius,
                    )
                },
            )
            .then(clickModifier),
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(
                if (onClick != null) R.string.chat_view_image else R.string.chat_image_attachment,
            ),
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        if (!assistantOverlay && !isCoui) {
            // squircleBorder is drawn in onDrawBehind; it must be on the foreground layer to edge over the bitmap.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .squircleBorder(width = borderWidth, color = borderColor, cornerRadius = cornerRadius),
            )
        }
    }
}

@Composable
private fun MessageTooltipAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            // The text below already names the action, so the icon isn't read aloud again.
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

// ── Context compaction: lightweight capsule marker in the timeline ─────────────────────────────────

/**
 * Compaction isn't a turn result but a context-maintenance event; a centered capsule marker distinguishes it from assistant body,
 * pulsing the icon while in progress and retaining before/after token info once finished.
 */
@Composable
private fun ContextCompactionMarker(
    message: SystemNoticeMessageUi,
    modifier: Modifier = Modifier,
) {
    val pulseAlpha = rememberActivePulse(active = message.running, label = "compaction_pulse")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(MiuixTheme.colorScheme.surface)
                .border(
                    0.5.dp,
                    MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                    RoundedCornerShape(percent = 50),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Compress,
                contentDescription = null,
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer(alpha = if (message.running) pulseAlpha else 1f),
                tint = if (message.running) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = message.detail?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.context_compaction),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Agent result ───────────────────────────────────────────────────────

@Composable
private fun AgentMessageBlock(
    message: AgentMessageUi,
    allowSpeech: Boolean = false,
    assistantOverlay: Boolean = false,
    retainedStreamingState: StreamingMarkdownState?,
    showCopyAction: Boolean,
    showMessageActions: Boolean,
    showBranchAction: Boolean = false,
    onBranch: () -> Unit = {},
    messageActionsEnabled: Boolean,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit = {},
    onSelectCandidate: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var copied by remember(message.id) { mutableStateOf(false) }
    val keepStreamingMarkdown = remember(message.id) { message.isStreaming }
    DisposableEffect(retainedStreamingState) {
        retainedStreamingState?.attachContent()
        onDispose { retainedStreamingState?.detachContent() }
    }
    var streamingRevealComplete by remember(message.id) {
        mutableStateOf(!keepStreamingMarkdown)
    }
    // The render session is held by the list layer keyed by message.id; when an item scrolls out and is destroyed, sliding back reuses the same
    // session; without an external holder (e.g. nested entries) it falls back to in-composition remember, behaving as before.
    val streamingState = if (keepStreamingMarkdown) {
        retainedStreamingState ?: remember(message.id) { StreamingMarkdownState() }
    } else {
        null
    }
    val completedDocument = (streamingState ?: retainedStreamingState)
        ?.snapshot?.completedDocumentFor(message.content)
    val revealComplete = streamingRevealComplete && !message.isStreaming &&
        (streamingState == null || completedDocument != null)
    LaunchedEffect(retainedStreamingState, revealComplete, message.content) {
        retainedStreamingState?.revealedContent = message.content.takeIf { revealComplete }
    }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (assistantOverlay) 16.dp else 20.dp,
                end = if (assistantOverlay) 16.dp else 20.dp,
                top = 4.dp,
                bottom = 6.dp,
            ),
    ) {
        when {
            message.content.isBlank() && message.isStreaming -> {
                AITypingIndicator(
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            streamingState != null && !revealComplete -> {
                StreamingMarkdown(
                    state = streamingState,
                    content = message.content,
                    isStreaming = message.isStreaming,
                    tone = MarkdownTone.Answer,
                    onRevealCompleteChange = { streamingRevealComplete = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            message.renderMarkdown -> {
                SelectionContainer {
                    StaticMarkdown(
                        content = message.content,
                        tone = MarkdownTone.Answer,
                        parsed = completedDocument,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            message.content.isNotBlank() -> {
                SelectionContainer {
                    Text(
                        text = message.content,
                        style = MiuixTheme.textStyles.body1.copy(
                            lineHeight = 25.sp,
                            lineHeightStyle = EditorialLineHeightStyle,
                        ),
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        message.searchSuggestionsHtml.forEach { html ->
            SearchSuggestionsView(html = html, modifier = Modifier.padding(top = 8.dp))
        }

        // The action row appears only after the answer's reveal completes; fade-in expansion avoids the whole page jumping a line height during bottom-hugging scroll.
        AnimatedVisibility(
            visible = showCopyAction &&
                !message.isStreaming &&
                message.content.isNotBlank() &&
                revealComplete,
            enter = fadeIn(tween(160)) + expandVertically(tween(160), expandFrom = Alignment.Top),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(120), shrinkTowards = Alignment.Top),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Offset the first button's internal horizontal padding so the icon shares the left centerline with the body above and the work-process icons.
                    .offset(x = -AnswerActionCenterOffset)
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                AnswerActionButton(
                    icon = if (copied) ChatActionIcons.Check else ChatActionIcons.Copy,
                    contentDescription = stringResource(
                        if (copied) R.string.copy_copied else R.string.copy_answer,
                    ),
                    onClick = {
                        @Suppress("DEPRECATION")
                        clipboardManager.setText(AnnotatedString(message.content))
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        copied = true
                    },
                    tint = if (copied) MiuixTheme.colorScheme.primary else null,
                )
                if (allowSpeech) SpeechReadAloudButton(message.id, message.content)
                if (showMessageActions) {
                    if (message.characterEditable) {
                        TooltipBox(
                            text = stringResource(R.string.chat_edit_character_reply),
                            enabled = messageActionsEnabled,
                        ) {
                            AnswerActionButton(
                                icon = ChatActionIcons.Edit,
                                contentDescription = stringResource(R.string.chat_edit_character_reply),
                                onClick = onEdit,
                                enabled = messageActionsEnabled,
                            )
                        }
                    }
                    TooltipBox(text = stringResource(R.string.ui_regenerate_2e1905), enabled = messageActionsEnabled) {
                        AnswerActionButton(
                            icon = ChatActionIcons.Refresh,
                            contentDescription = stringResource(R.string.ui_regenerate_reply_84a7d9),
                            onClick = onRegenerate,
                            enabled = messageActionsEnabled,
                        )
                    }
                    TooltipBox(text = stringResource(R.string.ui_delete_3755f5), enabled = messageActionsEnabled) {
                        AnswerActionButton(
                            icon = ChatActionIcons.Trash,
                            contentDescription = stringResource(R.string.ui_delete_this_conversation_3f351b),
                            onClick = onDelete,
                            enabled = messageActionsEnabled,
                        )
                    }
                    if (showBranchAction) {
                        TooltipBox(text = stringResource(R.string.conversation_branch)) {
                            AnswerActionButton(
                                icon = ChatActionIcons.Branch,
                                contentDescription = stringResource(R.string.conversation_branch),
                                onClick = onBranch,
                                enabled = messageActionsEnabled,
                            )
                        }
                    }
                    if (!assistantOverlay && message.usage != null && message.usage.hasPerformanceStats) {
                        TurnStatsPill(
                            usage = message.usage,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    if (message.characterEditable && message.candidateCount > 1) {
                        Spacer(Modifier.weight(1f))
                        CandidateSwitcher(
                            selected = message.selectedCandidate,
                            count = message.candidateCount,
                            enabled = messageActionsEnabled,
                            onSelect = onSelectCandidate,
                        )
                    }
                }
            }
        }
    }
}

/** Corner radius and stroke weight shared by full-width outlined cards like thinking cards and run records, keeping adjacent outlines consistent. */
private val ChatCardCorner = 12.dp
private const val ChatCardOutlineAlpha = 0.5f

/** Compact touch size of answer action buttons (28.dp box + 16.dp icon); the row shifts left 6.dp to align with the body's left edge. */
internal val AnswerActionSize = 28.dp
private val AnswerActionCenterOffset = 6.dp

/** Lightweight actions under the answer: icons are explicitly tinted, so the disabled state must swap in a disabled color, or they'd still look tappable. */
@Composable
internal fun AnswerActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val couiDark = CouiTheme.isDark
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(AnswerActionSize)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isCoui) {
                    Modifier.couiButtonFeedback(couiDark, interactionSource, enabled = enabled)
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp),
            tint = when {
                !enabled -> MiuixTheme.colorScheme.disabledOnSurface
                tint != null -> tint
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.72f)
            },
        )
    }
}

@Composable
private fun CandidateSwitcher(
    selected: Int,
    count: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 2.dp),
    ) {
        AnswerActionButton(
            icon = Icons.Rounded.ChevronLeft,
            contentDescription = stringResource(R.string.chat_previous_candidate),
            onClick = { onSelect(selected - 1) },
            enabled = enabled && selected > 0,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = "${selected + 1}/$count",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 28.dp),
        )
        AnswerActionButton(
            icon = Icons.Rounded.ChevronRight,
            contentDescription = stringResource(R.string.chat_next_candidate),
            onClick = { onSelect(selected + 1) },
            enabled = enabled && selected < count - 1,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

// ── Thinking process ─────────────────────────────────────────────────────────

@Composable
internal fun ThinkingRow(
    message: ThinkingMessageUi,
    retainedStreamingState: StreamingMarkdownState?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    DisposableEffect(retainedStreamingState) {
        retainedStreamingState?.attachContent()
        onDispose { retainedStreamingState?.detachContent() }
    }
    // null means follow the thinking lifecycle; after the user taps, only the manual choice is honored.
    var manualExpanded by rememberSaveable(message.id) { mutableStateOf<Boolean?>(null) }
    val onExpandItem = LocalOnChatItemExpand.current
    val streamingState = retainedStreamingState ?: if (message.isStreaming) {
        remember(message.id) { StreamingMarkdownState() }
    } else {
        null
    }
    val completedDocument = streamingState?.snapshot?.completedDocumentFor(message.content)
    // The expanded state must be derived synchronously in composition: if collapsed one frame late via LaunchedEffect, the frame where thinking ends would
    // already have its tail window lifted while the body is still expanded, flashing one frame at full height before the collapse animation runs from that height.
    val expanded = manualExpanded ?: message.isStreaming
    // In auto mode the height cap extends through the collapse animation, so the exit never first expands then collapses.
    val tailWindow = manualExpanded == null

    val pulseAlpha = rememberActivePulse(
        active = message.isStreaming,
        label = "thinking_pulse",
    )

    // In compact mode it renders inside the work-process timeline; the node badge already carries the icon, so only text and the expand control remain here.
    val containerModifier = if (compact) {
        modifier.fillMaxWidth()
    } else {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .squircleSurface(
                color = MiuixTheme.colorScheme.surface,
                cornerRadius = ChatCardCorner,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = ChatCardOutlineAlpha),
                cornerRadius = ChatCardCorner,
            )
    }

    Column(modifier = containerModifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                    // A tap under the tail window is treated as wanting the full text: stay expanded and lift the height limit.
                    val expanding = (tailWindow && expanded) || !expanded
                    if (expanding) onExpandItem()
                    manualExpanded = expanding
                }
                .then(
                    if (compact) {
                        Modifier.heightIn(min = WorkRowHeight)
                    } else {
                        Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!compact) {
                Icon(
                    imageVector = Icons.Rounded.Lightbulb,
                    contentDescription = null,
                    modifier = Modifier
                        .size(15.dp)
                        .graphicsLayer(alpha = if (message.isStreaming) pulseAlpha else 1f),
                    tint = if (message.isStreaming) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            ShimmerText(
                active = message.isStreaming,
                text = if (message.isStreaming) {
                    stringResource(R.string.reasoning_in_progress)
                } else {
                    message.elapsedSeconds?.takeIf { it > 0 }?.let { seconds ->
                        pluralStringResource(
                            R.plurals.reasoning_completed_seconds,
                            seconds,
                            seconds,
                        )
                    } ?: stringResource(R.string.reasoning_completed)
                },
                style = MiuixTheme.textStyles.body2.copy(fontSize = 13.5.sp),
                color = if (message.isStreaming) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
                    MiuixTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.56f else 0.52f)
                },
                modifier = Modifier.weight(1f, fill = !compact),
            )
            ExpandChevron(
                expanded = expanded,
                contentDescription = stringResource(
                    if (expanded) R.string.reasoning_collapse else R.string.reasoning_expand,
                ),
                modifier = Modifier.padding(start = 4.dp, end = if (compact) 4.dp else 0.dp),
            )
        }

        AnimatedVisibility(
            visible = expanded && message.content.isNotBlank(),
            enter = ExpandDownEnter,
            exit = CollapseUpExit,
        ) {
            Column {
                if (!compact) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .height(0.5.dp)
                            .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
                    )
                }
                val contentModifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (compact) 0.dp else 12.dp,
                        end = if (compact) 4.dp else 12.dp,
                        top = if (compact) 3.dp else 8.dp,
                        bottom = if (compact) 10.dp else 12.dp,
                    )
                if (streamingState != null && (message.isStreaming || completedDocument == null)) {
                    ThinkingTailWindow(
                        enabled = tailWindow,
                        modifier = contentModifier,
                    ) {
                        StreamingMarkdown(
                            state = streamingState,
                            content = message.content,
                            isStreaming = message.isStreaming,
                            tone = MarkdownTone.Thinking,
                        )
                    }
                } else {
                    StaticMarkdown(
                        content = message.content,
                        tone = MarkdownTone.Thinking,
                        parsed = completedDocument,
                        modifier = contentModifier,
                    )
                }
            }
        }
    }
}

/**
 * In-progress thinking reveals only the last few lines: content is bottom-aligned and overflow is clipped upward, with a top fade hinting at earlier content.
 * Once height is capped, increments no longer grow the work-process card, and the list needn't re-hug the bottom on every thinking snapshot.
 */
@Composable
private fun ThinkingTailWindow(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }
    val maxHeight = with(LocalDensity.current) {
        (THINKING_TAIL_WINDOW_LINES * THINKING_LINE_HEIGHT_SP).sp.toDp()
    }
    Box(
        modifier = modifier
            .heightIn(max = maxHeight)
            .clipToBounds()
            // Offscreen compositing covers only this bounded region, so the fade mask acts on the text alpha.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (size.height >= maxHeight.toPx() - 1f) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.35f to Color.Black,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Bottom, unbounded = true),
        ) {
            content()
        }
    }
}

private const val THINKING_TAIL_WINDOW_LINES = 4

// ── Run trace: lightweight entry row ─────────────────────────────────────────────

@Composable
private fun RunTraceRow(
    message: RunTraceMessageUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .squircleSurface(color = MiuixTheme.colorScheme.surface, cornerRadius = ChatCardCorner)
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = ChatCardOutlineAlpha),
                cornerRadius = ChatCardCorner,
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.ui_available_capacity_743337),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
        )
    }
}

// ── Tool summary ──────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolSummaryInline(
    message: ToolSummaryMessageUi,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 10.dp else 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        message.tools.forEach { tool ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surface)
                    .border(
                        0.5.dp,
                        MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = iconForTool(tool),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = toolDisplayName(tool),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

// ── Suggestions ────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionChipsRow(
    message: SuggestionChipsMessageUi,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        message.prompts.forEach { prompt ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surface)
                    .border(
                        0.5.dp,
                        MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSuggestionClick(prompt) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = prompt,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
