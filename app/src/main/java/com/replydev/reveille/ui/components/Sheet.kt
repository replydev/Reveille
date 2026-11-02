package com.replydev.reveille.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.replydev.reveille.R
import com.replydev.reveille.ui.theme.ReveilleTheme
import kotlinx.coroutines.launch

/** The sheet's top edge sits this far below the top of the screen, unless the sheet asks for another gap. */
private val SheetTopGap = 120.dp

/**
 * A full-height Material bottom sheet hosted by a navigation `dialog` destination, so the editor inside keeps its own
 * back-stack entry (ViewModel, SavedStateHandle, process-death restore). [content] gets a `close` that slides the
 * sheet away before [onDismissed] pops the entry; swiping down or Back do the same.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavBottomSheet(onDismissed: () -> Unit, topGap: Dp = SheetTopGap, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    // The destination's own dialog window stays clear: the sheet draws the only scrim.
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { dialogWindow?.setDimAmount(0f) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onDismissed() } }
    val colors = ReveilleTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismissed,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = colors.sheetBg,
        contentColor = colors.onBg,
        scrimColor = Color.Black.copy(alpha = 0.72f),
        tonalElevation = 0.dp,
        contentWindowInsets = { WindowInsets(0) },
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 40.dp, height = 4.dp).background(colors.outlineDashed, RoundedCornerShape(2.dp)))
            }
        },
    ) {
        val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } - topGap - 14.dp
        Column(Modifier.fillMaxWidth().height(height.coerceAtLeast(320.dp))) { content(close) }
    }
}

/** The inside of a [NavBottomSheet]: a title row with [actions] and a Close button, the content, and a snackbar host. */
@Composable
fun SheetScaffold(
    title: String,
    onClose: () -> Unit,
    snackbarHostState: SnackbarHostState,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, Modifier.weight(1f), style = ReveilleTheme.type.titleSheet)
            actions()
            IconCircleButton(R.drawable.ic_close, "Close", onClose)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content(PaddingValues(0.dp))
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}
