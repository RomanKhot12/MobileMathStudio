package com.example.mobilemathstudio.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

enum class ExportFormat { Png, Jpg, Pdf }

/** Writes rendered pages into the public Downloads folder. */
object ExportWriter {

    private const val TAG = "ExportWriter"

    /** A4 at 72 dpi — the standard PostScript point size. */
    private const val A4_WIDTH_PT = 595
    private const val A4_HEIGHT_PT = 842

    suspend fun write(
        context: Context,
        pages: List<Bitmap>,
        format: ExportFormat,
        baseName: String,
    ): String? = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) return@withContext null
        val safeName = baseName.replace(Regex("""[^\w\-. ]"""), "_").trim().ifEmpty { "canvas" }
        val fileName = "$safeName.${format.extension()}"
        runCatching {
            openDownloadStream(context, fileName, format.mime())?.use { out ->
                when (format) {
                    ExportFormat.Pdf -> writePdf(pages, out)
                    ExportFormat.Png -> pages.first().compress(Bitmap.CompressFormat.PNG, 100, out)
                    ExportFormat.Jpg -> pages.first().compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                out.flush()
            } ?: return@runCatching null
            fileName
        }.onFailure { Log.e(TAG, "Export to ${format.extension()} failed", it) }.getOrNull()
    }

    private fun writePdf(pages: List<Bitmap>, out: OutputStream) {
        val document = PdfDocument()
        // Copies live until writeTo has rendered every page.
        val copies = mutableListOf<Bitmap>()
        try {
            pages.forEachIndexed { index, bitmap ->
                val drawable = bitmap.forSoftwareCanvas() ?: return@forEachIndexed
                if (drawable !== bitmap) copies += drawable
                val info = PdfDocument.PageInfo
                    .Builder(A4_WIDTH_PT, A4_HEIGHT_PT, index + 1)
                    .create()
                val page = document.startPage(info)
                page.canvas.drawBitmap(
                    drawable,
                    null,
                    Rect(0, 0, A4_WIDTH_PT, A4_HEIGHT_PT),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
                )
                document.finishPage(page)
            }
            document.writeTo(out)
        } finally {
            document.close()
            copies.forEach { it.recycle() }
        }
    }

    /**
     * A [PdfDocument] page is backed by a software canvas, which refuses to draw
     * the hardware bitmaps that `GraphicsLayer.toImageBitmap()` produces — that
     * is why PNG and JPEG (which go through `compress`) worked while PDF did not.
     */
    private fun Bitmap.forSoftwareCanvas(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val needsCopy = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            config == Bitmap.Config.HARDWARE
        if (!needsCopy) return this
        return copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun openDownloadStream(context: Context, fileName: String, mime: String): OutputStream? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val stream = resolver.openOutputStream(uri)
        if (stream == null) {
            resolver.delete(uri, null, null)
            return null
        }
        return object : OutputStream() {
            override fun write(b: Int) = stream.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = stream.write(b, off, len)
            override fun flush() = stream.flush()
            override fun close() {
                stream.close()
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        }
    }

    private fun ExportFormat.extension() = when (this) {
        ExportFormat.Png -> "png"
        ExportFormat.Jpg -> "jpg"
        ExportFormat.Pdf -> "pdf"
    }

    private fun ExportFormat.mime() = when (this) {
        ExportFormat.Png -> "image/png"
        ExportFormat.Jpg -> "image/jpeg"
        ExportFormat.Pdf -> "application/pdf"
    }
}
