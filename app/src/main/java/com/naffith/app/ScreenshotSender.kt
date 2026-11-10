package com.naffith.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.widget.Toast
import com.naffith.app.core.Contact
import com.naffith.app.core.PhoneNumbers

/**
 * «صور الشاشة وارسلها لفلان»: ياخذ لقطة للشاشة (عن طريق خدمة «مرسال: الإرسال والتحكم»)،
 * ويفتح واتساب على محادثة الشخص والصورة جاهزة، ويضغط إرسال.
 */
object ScreenshotSender {

    fun captureAndSend(context: Context, contact: Contact) {
        val app = context.applicationContext
        val service = WhatsAppSendService.instance
        if (service == null) {
            Toast.makeText(app, R.string.dev_need_a11y, Toast.LENGTH_LONG).show()
            runCatching {
                app.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        if (Build.VERSION.SDK_INT < 30) {
            // الجوالات القديمة: لقطة عادية، وتختار الصورة بنفسك في المحادثة.
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            AppLauncher(app).openWhatsAppChat(contact.number, null)
            Toast.makeText(app, R.string.shot_attach_manually, Toast.LENGTH_LONG).show()
            return
        }
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            app.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    if (hw == null) {
                        result.hardwareBuffer.close()
                        fail(app)
                        return
                    }
                    ScreenContext.set(hw) // تنسخ الصورة لنسخة عادية قبل ما نقفل الذاكرة
                    result.hardwareBuffer.close()
                    val shot = ScreenContext.fresh() ?: return fail(app)
                    val uri = runCatching { ScreenContext.shareUri(app, shot) }.getOrNull() ?: return fail(app)
                    share(app, contact, uri)
                }

                override fun onFailure(errorCode: Int) = fail(app)
            }
        )
    }

    private fun share(app: Context, contact: Contact, uri: android.net.Uri) {
        val launcher = AppLauncher(app)
        val pkg = AppLauncher.WHATSAPP_PACKAGES.firstOrNull { p ->
            runCatching { app.packageManager.getPackageInfo(p, 0) }.isSuccess
        }
        if (pkg == null) {
            launcher.openWhatsAppChat(contact.number, null)
            return
        }
        val jid = PhoneNumbers.toInternational(contact.number) + "@s.whatsapp.net"
        val intent = Intent(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra("jid", jid)
            .setPackage(pkg)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // قلت «ارسلها»: الخدمة تضغط زر الإرسال في شاشة الصورة.
        WhatsAppSendService.requestSend()
        if (runCatching { app.startActivity(intent) }.isFailure) {
            WhatsAppSendService.cancel()
            fail(app)
        }
    }

    private fun fail(app: Context) {
        Toast.makeText(app, R.string.shot_failed, Toast.LENGTH_LONG).show()
    }
}
