package dev.agentdeck.companion.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * The decode half of QR pairing: one camera frame's luminance in, the raw pairing string out.
 *
 * `com.google.zxing:core` is the decoder the retired `com.journeyapps:zxing-android-embedded`
 * wrapper wrapped, so what pairing trusts to *read* a code is unchanged — only the camera around
 * it moved to CameraX. This file is deliberately free of Android types: it is the half that can
 * be exercised without a device, and `ui/QrScanner.kt` is the half that cannot.
 *
 * Not thread-safe — [MultiFormatReader] carries state between calls. One instance per analyzer,
 * and the analyzer runs on a single-threaded executor.
 */
class QrDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                // Pairing is one still code held up to an IDE window, not a scan gun over a
                // conveyor: the extra passes are worth more here than the frames they cost.
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    /**
     * [luma] is a tightly packed 8-bit greyscale plane of exactly `width * height` bytes.
     *
     * Null for every frame holding no readable code — the overwhelmingly common case at frame
     * rate, and the reason nothing on this path logs.
     */
    fun decode(luma: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || luma.size < width * height) return null
        // Frames arrive in the sensor's orientation, so an upright phone shows the decoder a
        // code lying on its side. Nothing is rotated: QR detection is rotation-invariant by
        // construction — three finder patterns fix the transform before a module is read.
        val source = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            null
        } catch (_: Exception) {
            // A checksum or format failure is a code seen through motion blur, which carries
            // the same instruction as no code at all: look at the next frame.
            null
        } finally {
            reader.reset()
        }
    }
}
