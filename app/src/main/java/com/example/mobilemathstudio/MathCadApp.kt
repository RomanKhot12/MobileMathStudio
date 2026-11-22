package com.example.mobilemathstudio

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.mobilemathstudio.math.CasWorker
import com.example.mobilemathstudio.performance.GlassQuality
import com.example.mobilemathstudio.performance.Motion
import com.example.mobilemathstudio.performance.rememberPerformanceMonitor
import com.example.mobilemathstudio.performance.rememberPerformanceSnapshot
import com.example.mobilemathstudio.persistence.AppStore
import com.example.mobilemathstudio.persistence.PersistedCanvas
import com.example.mobilemathstudio.ui.canvas.TypeCanvasState
import com.example.mobilemathstudio.ui.components.FloatingTopBar
import com.example.mobilemathstudio.ui.components.LatexImportDialog
import com.example.mobilemathstudio.ui.export.ExportScreen
import com.example.mobilemathstudio.ui.glass.liquidGlassBackdropSource
import com.example.mobilemathstudio.ui.glass.rememberLiquidGlassBackdropState
import com.example.mobilemathstudio.ui.keyboard.KeyAction
import com.example.mobilemathstudio.ui.keyboard.MathKeyboard
import com.example.mobilemathstudio.ui.onboarding.OnboardingOverlay
import com.example.mobilemathstudio.ui.screens.CanvasItem
import com.example.mobilemathstudio.ui.screens.CanvasManagerContent
import com.example.mobilemathstudio.ui.screens.SettingsPanel
import com.example.mobilemathstudio.ui.screens.TypeModeWorkspace
import com.example.mobilemathstudio.ui.theme.LocalIsDarkTheme
import com.example.mobilemathstudio.ui.theme.MathCadTheme
import com.example.mobilemathstudio.ui.theme.WinBlack
import com.example.mobilemathstudio.ui.theme.WinCanvas
import com.example.mobilemathstudio.ui.theme.WinDanger
import com.example.mobilemathstudio.ui.theme.WinLightCanvas
import com.example.mobilemathstudio.ui.theme.WinLightDanger
import com.example.mobilemathstudio.ui.theme.WinLightTextPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MathCadApp() {
    val context = LocalContext.current
    val store = remember { AppStore(context) }
    val initial = remember { store.load() }

    var settings by remember { mutableStateOf(initial.settings) }
    var canvases by remember {
        mutableStateOf(
            pinnedFirst(
                initial.canvases.map { it.item }.ifEmpty {
                    listOf(
                        CanvasItem("default", context.getString(R.string.canvas_default_name), "Just now"),
                    )
                },
            ),
        )
    }
    var activeCanvasId by remember { mutableStateOf(initial.activeCanvasId) }
    var showCanvasSheet by remember { mutableStateOf(false) }
    var showLatexImport by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var onboardingDone by remember { mutableStateOf(initial.onboardingDone) }

    val typeCanvas = remember { TypeCanvasState() }
    val canvasCache = remember {
        mutableStateMapOf<String, PersistedCanvas>().also { map ->
            initial.canvases.forEach { map[it.item.id] = it }
        }
    }
    var restored by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!restored) {
            val snap = canvasCache[activeCanvasId] ?: canvasCache.values.firstOrNull()
            if (snap != null) {
                typeCanvas.restore(snap.cells, Offset(snap.panX, snap.panY), snap.zoom)
            }
            restored = true
        }
    }

    LaunchedEffect(Unit) {
        // Let the first frames and the restore settle before loading Symja.
        withFrameNanos { }
        delay(1200)
        CasWorker.warmUpInBackground()
    }

    val performanceMonitor = rememberPerformanceMonitor()
    val performance = rememberPerformanceSnapshot(settings.performanceMode, performanceMonitor)
    typeCanvas.animationsEnabled = performance.animationsEnabled
    val drawerState = rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val lifecycleOwner = LocalLifecycleOwner.current

    fun cacheActive() {
        val meta = canvases.firstOrNull { it.id == activeCanvasId }
            ?: CanvasItem(activeCanvasId, context.getString(R.string.canvas_default_name), "Just now")
        canvasCache[activeCanvasId] = PersistedCanvas(
            item = meta.copy(updatedLabel = "Just now"),
            cells = typeCanvas.snapshotCells(),
            panX = typeCanvas.pan.x,
            panY = typeCanvas.pan.y,
            zoom = typeCanvas.zoom,
        )
    }

    fun persistNow() {
        CrashGuard.run {
            cacheActive()
            store.save(
                settings = settings,
                activeCanvasId = activeCanvasId,
                // The `canvases` list owns name/pin/order; the cache only owns content.
                canvases = canvases.map { item ->
                    canvasCache[item.id]?.copy(item = item)
                        ?: PersistedCanvas(item = item, cells = emptyList())
                },
                onboardingDone = onboardingDone,
            )
        }
    }

    LaunchedEffect(
        settings, activeCanvasId, typeCanvas.cells, typeCanvas.pan,
        typeCanvas.zoom, onboardingDone, canvases,
    ) {
        if (!restored) return@LaunchedEffect
        delay(700)
        persistNow()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_PAUSE) {
                persistNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            persistNow()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(typeCanvas.isKeyboardVisible, typeCanvas.cells) {
        if (typeCanvas.isKeyboardVisible) performanceMonitor.notifyInteraction()
    }

    BackHandler(
        enabled = drawerState.isOpen || typeCanvas.hasFocus ||
            typeCanvas.menuCellId != null || typeCanvas.cells.any { it.systemMenuOpen } ||
            typeCanvas.selection != null ||
            !onboardingDone,
    ) {
        when {
            !onboardingDone -> {
                onboardingDone = true
                store.markOnboardingDone()
            }
            drawerState.isOpen -> scope.launch { drawerState.close() }
            typeCanvas.selection != null -> typeCanvas.clearSelection()
            typeCanvas.menuCellId != null -> typeCanvas.dismissMenu()
            typeCanvas.cells.any { it.systemMenuOpen } -> typeCanvas.dismissAllSystemMenus()
            typeCanvas.hasFocus -> typeCanvas.clearFocus()
        }
    }

    MathCadTheme(
        themeMode = settings.themeMode,
        accentColor = settings.accentColor,
        performance = performance,
        answerLayout = settings.answerLayout,
        decimalPlaces = settings.decimalPlaces,
        language = settings.language,
        liquidGlassEnabled = settings.liquidGlassEnabled,
    ) {
        val dark = LocalIsDarkTheme.current
        val canvasBg = if (dark) WinCanvas else WinLightCanvas
        val backdropState = rememberLiquidGlassBackdropState(opaqueGroundColor = canvasBg)
        // Top bar refracts live canvas pan, zoom, and moving/editing formulas in real time.
        backdropState.redrawObserver = {
            @Suppress("UNUSED_EXPRESSION")
            typeCanvas.pan
            @Suppress("UNUSED_EXPRESSION")
            typeCanvas.zoom
            @Suppress("UNUSED_EXPRESSION")
            typeCanvas.cells
        }
        val captureBackdrop = settings.liquidGlassEnabled && performance.glassQuality != GlassQuality.Scrim

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = Color.Transparent,
                    drawerContentColor = if (dark) Color.White else WinLightTextPrimary,
                ) {
                    SettingsPanel(
                        settings = settings,
                        performance = performance,
                        onSettingsChange = { settings = it },
                    )
                }
            },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (dark) WinBlack else WinLightCanvas),
            ) {
                val density = LocalDensity.current
                val statusTopDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                var topBarHeightDp by remember { mutableStateOf(76.dp) }
                var keyboardHeightDp by remember { mutableStateOf(340.dp) }
                val topBarInsetDp = (topBarHeightDp - statusTopDp).coerceAtLeast(72.dp).value
                val animatedBottomInset by animateFloatAsState(
                    targetValue = if (typeCanvas.isKeyboardVisible) keyboardHeightDp.value else 0f,
                    animationSpec = Motion.spatial(performance.animationsEnabled),
                    label = "canvas-kbd-inset",
                )

                // Live workspace captured as the shared Liquid Glass backdrop (skipped in Scrim).
                // The outer Box spans the full screen so FloatingTopBar and MathKeyboard share
                // one unified backdrop coordinate space, while the inner Box keeps cards clipped
                // cleanly below the system status bar.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(canvasBg)
                        .liquidGlassBackdropSource(
                            state = backdropState,
                            captureEnabled = captureBackdrop,
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .clipToBounds(),
                    ) {
                        TypeModeWorkspace(
                            state = typeCanvas,
                            bottomInsetDp = animatedBottomInset,
                            topInsetDp = topBarInsetDp,
                        )
                    }
                }

                FloatingTopBar(
                    backdropState = backdropState,
                    onOpenSettings = { scope.launch { drawerState.open() } },
                    onOpenCanvasManager = { showCanvasSheet = true },
                    onImportLatex = { showLatexImport = true },
                    onExport = {
                        typeCanvas.clearFocus()
                        showExport = true
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .onGloballyPositioned { coords ->
                            val height = with(density) { coords.size.height.toDp() }
                            if (height > 40.dp) topBarHeightDp = height
                        },
                )

                MathKeyboard(
                    visible = typeCanvas.isKeyboardVisible,
                    nestedSlot = typeCanvas.isInNestedSlot,
                    canUndo = typeCanvas.canUndo,
                    canRedo = typeCanvas.canRedo,
                    onCollapse = { typeCanvas.collapseKeyboard() },
                    backdropState = backdropState,
                    onAction = { action ->
                        performanceMonitor.notifyInteraction()
                        when (action) {
                            is KeyAction.Insert -> typeCanvas.insert(action.token, action.cursorBack)
                            is KeyAction.InsertExponent -> typeCanvas.insertExponent(action.token)
                            KeyAction.InsertFraction -> typeCanvas.insertFraction()
                            KeyAction.InsertPower -> typeCanvas.insertPower()
                            KeyAction.InsertSqrt -> typeCanvas.insertSqrt()
                            KeyAction.InsertNthRoot -> typeCanvas.insertNthRoot()
                            KeyAction.InsertSystem -> scope.launch { typeCanvas.createOrWrapSystem() }
                            KeyAction.ConfirmSlot -> typeCanvas.confirmNestedSlot()
                            KeyAction.Backspace -> typeCanvas.backspace()
                            KeyAction.ClearAll -> typeCanvas.clearAll()
                            KeyAction.ClearCanvas -> showClearConfirm = true
                            KeyAction.Undo -> typeCanvas.undo()
                            KeyAction.Redo -> typeCanvas.redo()
                            KeyAction.CursorLeft -> typeCanvas.moveCursor(-1)
                            KeyAction.CursorRight -> typeCanvas.moveCursor(1)
                            KeyAction.Submit -> scope.launch { typeCanvas.submitFocused() }
                            else -> Unit
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onGloballyPositioned { coords ->
                            val height = with(density) { coords.size.height.toDp() }
                            if (height > 80.dp) keyboardHeightDp = height
                        },
                )

                if (showClearConfirm) {
                    AlertDialog(
                        onDismissRequest = { showClearConfirm = false },
                        title = { Text(stringResource(R.string.clear_canvas_title)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showClearConfirm = false
                                    typeCanvas.clearCanvas()
                                },
                            ) {
                                Text(
                                    text = stringResource(R.string.clear_canvas_confirm),
                                    color = if (dark) WinDanger else WinLightDanger,
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearConfirm = false }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        },
                    )
                }

                if (!onboardingDone) {
                    OnboardingOverlay(
                        onFinished = {
                            onboardingDone = true
                            store.markOnboardingDone()
                        },
                    )
                }
            }
        }

        if (showCanvasSheet) {
            ModalBottomSheet(
                onDismissRequest = { showCanvasSheet = false },
                sheetState = sheetState,
                containerColor = if (dark) Color(0xFF161618) else WinLightCanvas,
                contentColor = if (dark) Color.White else WinLightTextPrimary,
            ) {
                CanvasManagerContent(
                    canvases = canvases,
                    activeId = activeCanvasId,
                    onSelect = { item ->
                        cacheActive()
                        activeCanvasId = item.id
                        val loaded = canvasCache[item.id]
                        typeCanvas.restore(
                            cells = loaded?.cells.orEmpty(),
                            pan = Offset(loaded?.panX ?: 0f, loaded?.panY ?: 0f),
                            zoom = loaded?.zoom ?: 1f,
                        )
                        showCanvasSheet = false
                    },
                    onCreate = { name ->
                        cacheActive()
                        val item = CanvasItem(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            updatedLabel = "Just now",
                        )
                        canvases = pinnedFirst(listOf(item) + canvases)
                        canvasCache[item.id] = PersistedCanvas(item = item, cells = emptyList())
                        activeCanvasId = item.id
                        typeCanvas.restore(emptyList(), Offset.Zero)
                        showCanvasSheet = false
                    },
                    onRename = { item, name ->
                        if (name.isNotEmpty()) {
                            canvases = canvases.map {
                                if (it.id == item.id) it.copy(name = name) else it
                            }
                        }
                    },
                    onDuplicate = { item ->
                        if (item.id == activeCanvasId) cacheActive()
                        val source = canvasCache[item.id]
                        val copy = CanvasItem(
                            id = UUID.randomUUID().toString(),
                            name = context.getString(R.string.canvas_copy_suffix, item.name),
                            updatedLabel = "Just now",
                        )
                        canvasCache[copy.id] = PersistedCanvas(
                            item = copy,
                            cells = source?.cells.orEmpty().map {
                                it.copy(
                                    id = UUID.randomUUID().toString(),
                                    selected = false,
                                    systemMenuOpen = false,
                                )
                            },
                            panX = source?.panX ?: 0f,
                            panY = source?.panY ?: 0f,
                            zoom = source?.zoom ?: 1f,
                        )
                        canvases = pinnedFirst(
                            canvases.flatMap { if (it.id == item.id) listOf(it, copy) else listOf(it) },
                        )
                    },
                    onTogglePin = { item ->
                        canvases = pinnedFirst(
                            canvases.map { if (it.id == item.id) it.copy(pinned = !it.pinned) else it },
                        )
                    },
                    onDelete = { item ->
                        canvasCache.remove(item.id)
                        val remaining = canvases.filterNot { it.id == item.id }
                        if (remaining.isEmpty()) {
                            val fresh = CanvasItem(
                                id = UUID.randomUUID().toString(),
                                name = context.getString(R.string.canvas_default_name),
                                updatedLabel = "Just now",
                            )
                            canvases = listOf(fresh)
                            canvasCache[fresh.id] = PersistedCanvas(item = fresh, cells = emptyList())
                            activeCanvasId = fresh.id
                            typeCanvas.restore(emptyList(), Offset.Zero)
                        } else {
                            canvases = remaining
                            if (item.id == activeCanvasId) {
                                val next = remaining.first()
                                activeCanvasId = next.id
                                val loaded = canvasCache[next.id]
                                typeCanvas.restore(
                                    cells = loaded?.cells.orEmpty(),
                                    pan = Offset(loaded?.panX ?: 0f, loaded?.panY ?: 0f),
                                    zoom = loaded?.zoom ?: 1f,
                                )
                            }
                        }
                    },
                    rootModifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (showLatexImport) {
            LatexImportDialog(
                onDismiss = { showLatexImport = false },
                onImport = { latex ->
                    showLatexImport = false
                    scope.launch { typeCanvas.importLatex(latex) }
                },
            )
        }

        if (showExport) {
            ExportScreen(
                cells = typeCanvas.cells,
                sizeOf = { typeCanvas.sizeOf(it) },
                canvasName = canvases.firstOrNull { it.id == activeCanvasId }?.name
                    ?: context.getString(R.string.canvas_default_name),
                onClose = { showExport = false },
            )
        }
    }
}

/** Stable sort keeping pinned canvases on top without reshuffling the rest. */
private fun pinnedFirst(items: List<CanvasItem>): List<CanvasItem> =
    items.sortedByDescending { it.pinned }
