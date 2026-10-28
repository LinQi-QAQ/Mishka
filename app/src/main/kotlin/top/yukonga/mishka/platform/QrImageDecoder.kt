package top.yukonga.mishka.platform

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * 从相册图片识别 QR 码内容（相机扫码走 Quickie，这里补图片路径）。
 * 解码在本进程内用 ML Kit bundled 模型完成，不依赖 GMS 动态下发。
 */
object QrImageDecoder {

    fun decode(context: Context, uri: Uri, onResult: (String?) -> Unit) {
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (_: Exception) {
            onResult(null)
            return
        }
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
        scanner.process(image)
            .addOnSuccessListener { codes ->
                onResult(codes.firstOrNull()?.rawValue)
            }
            .addOnFailureListener { onResult(null) }
    }
}
