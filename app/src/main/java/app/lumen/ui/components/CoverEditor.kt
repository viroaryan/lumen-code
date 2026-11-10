// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import app.lumen.ui.components.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.lumen.Graph
import app.lumen.data.Covers
import app.lumen.ui.LocalApp
import app.lumen.ui.theme.LIcons
import app.lumen.ui.theme.Lumen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Change or remove a list's cover picture: pick any photo (the system photo picker, so Lumen needs no
 * storage permission), then move and zoom it inside a square frame.
 */
@Composable
fun CoverEditor(key: String, hasCover: Boolean, open: Boolean, onClose: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    var cropping by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) cropping = uri }
    val c = Lumen.colors

    if (open) {
        ModalBottomSheet(onDismissRequest = onClose, containerColor = c.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                Text("Cover picture", style = Lumen.type.title, color = c.text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                SheetItem(LIcons.Edit, if (hasCover) "Choose a different picture" else "Choose a picture") {
                    onClose()
                    runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        .onFailure { app.toast("No photo picker on this phone") }
                }
                if (hasCover) {
                    SheetItem(LIcons.Trash, "Remove picture") {
                        onClose()
                        Covers.delete(context, Graph.store.removeCover(key))
                        app.toast("Cover removed")
                    }
                }
            }
        }
    }

    val scope = rememberCoroutineScope()
    cropping?.let { uri ->
        CoverCropDialog(uri, onCancel = { cropping = null }) { square ->
            cropping = null
            scope.launch {
                val path = withContext(Dispatchers.IO) { Covers.save(context, key, square) }
                if (path == null) app.toast("Couldn't save that picture")
                else {
                    val old = Graph.store.setCover(key, path)
                    withContext(Dispatchers.IO) { Covers.delete(context, old) }
                    app.toast("Cover updated")
                }
            }
        }
    }
}

@Composable
private fun SheetItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(onClick).padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = Lumen.colors.text)
        Spacer(Modifier.width(18.dp))
        Text(text, style = Lumen.type.body, color = Lumen.colors.text)
    }
}

/**
 * Full-screen square cropper. Pinch to zoom, drag to move, rotate in quarter turns. The picture always
 * fills the frame, so the saved cover never has empty edges. [onDone] runs off the main thread's
 * critical path and receives a [Covers.SIZE]-pixel square.
 */
@Composable
private fun CoverCropDialog(uri: Uri, onCancel: () -> Unit, onDone: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var frameSide by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(uri) {
        val b = withContext(Dispatchers.IO) { Covers.load(context, uri) }
        if (b == null) failed = true else source = b
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0F)).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                LIconButton(LIcons.Close, onCancel, tint = Color.White, contentDescription = "Cancel")
                Text("Move and zoom", style = Lumen.type.bodyStrong, color = Color.White, modifier = Modifier.weight(1f))
                Text(
                    if (saving) "Saving…" else "Save", style = Lumen.type.label, color = Color(0xFF15174D),
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White)
                        .pressable({
                            val b = source ?: return@pressable
                            if (saving) return@pressable
                            saving = true
                            scope.launch {
                                val square = withContext(Dispatchers.Default) { cropSquare(b, zoom, pan, frameSide) }
                                if (square != null) onDone(square) else { saving = false; onCancel() }
                            }
                        })
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val density = LocalDensity.current
                val sidePx = with(density) { (minOf(maxWidth, maxHeight) - 32.dp).toPx() }
                androidx.compose.runtime.SideEffect { frameSide = sidePx }
                val bitmap = source
                when {
                    failed -> Text("Couldn't open that picture", style = Lumen.type.body, color = Color.White)
                    bitmap == null -> CircularProgressIndicator(color = Color.White)
                    else -> {
                        val image = remember(bitmap) { bitmap.asImageBitmap() }
                        val side = with(density) { sidePx.toDp() }
                        Canvas(
                            Modifier.size(side)
                                .pointerInput(bitmap) {
                                    detectTransformGestures { _, panChange, zoomChange, _ ->
                                        val z = (zoom * zoomChange).coerceIn(1f, 6f)
                                        zoom = z
                                        pan = clampPan(pan + panChange, bitmap, z, sidePx)
                                    }
                                },
                        ) {
                            val g = geometry(bitmap, zoom, pan, sidePx)
                            clipRect {
                                drawImage(
                                    image,
                                    dstOffset = IntOffset(g.left.toInt(), g.top.toInt()),
                                    dstSize = IntSize(g.width.toInt(), g.height.toInt()),
                                )
                            }
                            // Rule-of-thirds guides and the frame.
                            val third = size.width / 3f
                            val line = Color.White.copy(alpha = 0.35f)
                            for (i in 1..2) {
                                drawLine(line, Offset(third * i, 0f), Offset(third * i, size.height), strokeWidth = 1.5f)
                                drawLine(line, Offset(0f, third * i), Offset(size.width, third * i), strokeWidth = 1.5f)
                            }
                            drawRect(Color.White, style = Stroke(width = 3f))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                LButton("Rotate", {
                    val b = source ?: return@LButton
                    scope.launch {
                        // A new bitmap; the old one is left to the garbage collector, never recycled while on screen.
                        val r = withContext(Dispatchers.Default) {
                            Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(90f) }, true)
                        }
                        source = r; zoom = 1f; pan = Offset.Zero
                    }
                }, style = ButtonStyle.Secondary)
                Spacer(Modifier.width(12.dp))
                LButton("Reset", { zoom = 1f; pan = Offset.Zero }, style = ButtonStyle.Secondary)
            }
            Text(
                "Pinch to zoom · drag to move", style = Lumen.type.small, color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp),
            )
        }
    }
}

private class Geometry(val left: Float, val top: Float, val width: Float, val height: Float, val scale: Float)

/** Where the picture sits inside a frame of [side] pixels: it always covers the frame completely. */
private fun geometry(b: Bitmap, zoom: Float, pan: Offset, side: Float): Geometry {
    val scale = maxOf(side / b.width, side / b.height) * zoom
    val w = b.width * scale
    val h = b.height * scale
    return Geometry((side - w) / 2f + pan.x, (side - h) / 2f + pan.y, w, h, scale)
}

private fun clampPan(pan: Offset, b: Bitmap, zoom: Float, side: Float): Offset {
    val scale = maxOf(side / b.width, side / b.height) * zoom
    val maxX = ((b.width * scale - side) / 2f).coerceAtLeast(0f)
    val maxY = ((b.height * scale - side) / 2f).coerceAtLeast(0f)
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}

/** Cuts the framed square out of the original picture at full quality. */
private fun cropSquare(b: Bitmap, zoom: Float, pan: Offset, side: Float): Bitmap? = runCatching {
    val g = geometry(b, zoom, clampPan(pan, b, zoom, side), side)
    val srcLeft = (-g.left / g.scale).coerceIn(0f, b.width.toFloat())
    val srcTop = (-g.top / g.scale).coerceIn(0f, b.height.toFloat())
    val srcSide = (side / g.scale).coerceAtMost(minOf(b.width - srcLeft, b.height - srcTop))
    val out = Bitmap.createBitmap(Covers.SIZE, Covers.SIZE, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(out).drawBitmap(
        b,
        android.graphics.Rect(srcLeft.toInt(), srcTop.toInt(), (srcLeft + srcSide).toInt(), (srcTop + srcSide).toInt()),
        android.graphics.Rect(0, 0, Covers.SIZE, Covers.SIZE),
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG),
    )
    out
}.getOrNull()
