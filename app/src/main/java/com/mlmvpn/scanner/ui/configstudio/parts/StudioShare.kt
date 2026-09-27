package com.mlmvpn.scanner.ui.configstudio.parts

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.theme.CardShape
import java.io.File

/**
 * Getting a link off this phone and onto someone else's.
 *
 * Until this existed the only way out of Config Studio was the clipboard, which is fine when the
 * operator is already in the chat they mean to paste into and useless when they are not: a QR is how
 * a link crosses to a phone standing next to them, and the share sheet is how it reaches an app the
 * app itself knows nothing about.
 *
 * Three ways out, and they are not interchangeable:
 *
 *  * [StudioQrCard] — one payload, scanned by the client on the other phone. Bounded: a QR tops out
 *    around 3 KB and a subscription with a dozen configs in it is bigger than that, which is why the
 *    card draws a stated reason rather than a broken square.
 *  * [shareText] — one payload into whatever the person has installed.
 *  * [shareFile] — many payloads, or a payload that wants a name. Written into `cacheDir` and handed
 *    over through the existing `FileProvider`, never as a `file://` URI, which API 24+ refuses.
 *
 * **Nothing here writes to shared storage.** An export is a thing the operator is sending somewhere
 * this second; leaving a file full of live subscription links sitting in Downloads is a copy nobody
 * remembers to delete.
 */
object StudioShare {

    /** Where an export lands. Mirrored by `res/xml/file_paths.xml`; the two must agree. */
    private const val EXPORT_DIR = "studio-export"

    /**
     * Hand one string to the system chooser.
     *
     * [subject] is filled in as well as the body, because a share into mail with no subject arrives
     * as a blank-titled message the recipient has to open to identify.
     */
    fun shareText(context: Context, value: String, subject: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, value)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Write [content] to a file named [fileName] and share it.
     *
     * Returns false when the write or the chooser failed, so the caller can say so rather than
     * appearing to have done something. The directory is wiped first: an export is a snapshot, and
     * yesterday's file sitting beside today's under a name that differs by one character is how the
     * wrong list gets sent.
     */
    fun shareFile(
        context: Context,
        fileName: String,
        content: String,
        mime: String = "text/plain",
        subject: String = fileName,
    ): Boolean = runCatching {
        val dir = File(context.cacheDir, EXPORT_DIR)
        if (dir.isDirectory) dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        val file = File(dir, sanitize(fileName))
        file.writeText(content)
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, subject)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        true
    }.getOrDefault(false)

    /**
     * A file name the filesystem and the receiving app will both accept.
     *
     * Usernames reach this — they are what an export is named after — and a username is allowed to
     * contain characters a path is not.
     */
    private fun sanitize(name: String): String =
        name.map { if (it.isLetterOrDigit() || it in "._- ") it else '_' }.joinToString("").take(80)
            .ifBlank { "export.txt" }
}

/**
 * A payload as a QR code, or a stated reason there is none.
 *
 * Black on white inside the card rather than tinted to the palette: a scanner needs the contrast,
 * and a QR that looks handsome and does not read is worse than no QR. The same choice
 * `NodeQrCard` makes, and made again here rather than shared because that one is welded to the
 * nodes screen's layout.
 */
@Composable
fun StudioQrCard(value: String, tooLongNote: String) {
    val bitmap = remember(value) {
        runCatching {
            val hints = java.util.EnumMap<com.google.zxing.EncodeHintType, Any>(
                com.google.zxing.EncodeHintType::class.java
            )
            hints[com.google.zxing.EncodeHintType.MARGIN] = 1
            val matrix = com.google.zxing.qrcode.QRCodeWriter()
                .encode(value, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512, hints)
            val bmp = android.graphics.Bitmap.createBitmap(
                matrix.width, matrix.height, android.graphics.Bitmap.Config.RGB_565
            )
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    bmp.setPixel(
                        x, y,
                        if (matrix.get(x, y)) android.graphics.Color.BLACK
                        else android.graphics.Color.WHITE
                    )
                }
            }
            bmp.asImageBitmap()
        }.getOrNull()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .frostedGlass(CardShape)
            .padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .size(220.dp)
                    .background(Color.White, RoundedCornerShape(10.dp))
                    .padding(8.dp),
            )
        } else {
            // Not an apology. A QR holds about three kilobytes; a subscription carrying a config
            // per node per port is simply larger than a camera can read, and saying so is more use
            // than a square that scans into nothing.
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    tooLongNote,
                    color = Ios.SecondaryLabel,
                    fontSize = 13.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
