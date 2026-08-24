package com.tetherguardian.app

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.DecimalFormat
import java.util.Locale

class AlertActivity : AppCompatActivity() {

    private val handler =
        Handler(Looper.getMainLooper())

    private var acknowledged =
        false

    private var alertFinished =
        false

    private lateinit var priceText: TextView
    private lateinit var baseText: TextView
    private lateinit var dropText: TextView
    private lateinit var acknowledgeButton: Button
    private lateinit var delayedButton: Button

    /*
     * فقط برای پایان دوره نمایش فعال صفحه استفاده می‌شود.
     *
     * بعد از ۳۰ ثانیه:
     * - Activity بسته نمی‌شود.
     * - صفحه را مجبور به روشن ماندن نمی‌کنیم.
     * - Activity به پس‌زمینه می‌رود.
     *
     * بنابراین کاربر بعداً با باز کردن قفل
     * می‌تواند صفحه هشدار را مشاهده کند.
     */
    private val hideScreenRunnable =
        Runnable {
            if (!acknowledged) {
                finishVisibleAlertMode()
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(
            savedInstanceState
        )

        /*
         * هنگام فعال شدن هشدار:
         * صفحه می‌تواند روی Lock Screen دیده شود
         * و برای مدت هشدار روشن شود.
         */
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        /*
         * FLAG_KEEP_SCREEN_ON را فعلاً اضافه می‌کنیم
         * تا صفحه هشدار هنگام اجرای دوره ۳۰ ثانیه‌ای
         * خاموش نشود.
         */
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.O_MR1
        ) {

            setShowWhenLocked(true)

            setTurnScreenOn(true)
        }

        /*
         * توجه:
         * قفل گوشی را خودکار باز نمی‌کنیم.
         *
         * کاربر خودش گوشی را باز می‌کند و صفحه هشدار
         * را مشاهده خواهد کرد.
         */
        val keyguard =
            getSystemService(
                KeyguardManager::class.java
            )

        /*
         * دیگر requestDismissKeyguard اجرا نمی‌شود.
         *
         * این موضوع مهم است چون نمی‌خواهیم برنامه
         * خودش قفل گوشی را باز کند.
         */

        setContentView(
            R.layout.activity_alert
        )

        priceText =
            findViewById(
                R.id.alertPriceText
            )

        baseText =
            findViewById(
                R.id.alertBaseText
            )

        dropText =
            findViewById(
                R.id.alertDropText
            )

        acknowledgeButton =
            findViewById(
                R.id.acknowledgeButton
            )

        delayedButton =
            findViewById(
                R.id.delayedButton
            )

        /*
         * دریافت اطلاعات هشدار
         */
        val price =
            intent.getDoubleExtra(
                MonitoringService.EXTRA_ALERT_PRICE,
                0.0
            )

        val base =
            intent.getDoubleExtra(
                MonitoringService.EXTRA_ALERT_BASE,
                0.0
            )

        val drop =
            intent.getDoubleExtra(
                MonitoringService.EXTRA_ALERT_DROP,
                0.0
            )

        priceText.text =
            formatPrice(price)

        baseText.text =
            formatPrice(base)

        dropText.text =
            String.format(
                Locale.US,
                "%+.2f%%",
                drop
            )

        /*
         * هر دو دکمه تأیید هشدار را پایان می‌دهند.
         */
        acknowledgeButton.setOnClickListener {
            acknowledgeAndClose()
        }

        delayedButton.setOnClickListener {
            acknowledgeAndClose()
        }

        /*
         * این Activity در ابتدای هشدار ۳۰ ثانیه
         * در حالت نمایش فعال قرار دارد.
         *
         * بعد از آن:
         * - صفحه را روشن نگه نمی‌داریم.
         * - Activity را نمی‌بندیم.
         */
        handler.postDelayed(
            hideScreenRunnable,
            30_000
        )
    }

    /*
     * پایان حالت نمایش فعال
     *
     * Activity بسته نمی‌شود.
     *
     * فقط:
     * - KEEP_SCREEN_ON حذف می‌شود.
     * - صفحه دیگر مجبور به روشن ماندن نیست.
     * - Activity به پس‌زمینه می‌رود.
     */
    private fun finishVisibleAlertMode() {

        if (acknowledged) {
            return
        }

        alertFinished =
            true

        window.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        /*
         * Activity را به پس‌زمینه می‌فرستیم
         * ولی آن را finish نمی‌کنیم.
         *
         * بنابراین اطلاعات هشدار باقی می‌ماند.
         */
        moveTaskToBack(
            true
        )
    }

    /*
     * کاربر هشدار را مشاهده کرده است.
     *
     * این تنها حالتی است که Activity واقعاً بسته می‌شود
     * و سرویس نیز چرخه هشدار را متوقف می‌کند.
     */
    private fun acknowledgeAndClose() {

        if (acknowledged) {
            return
        }

        acknowledged =
            true

        handler.removeCallbacks(
            hideScreenRunnable
        )

        window.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        /*
         * اطلاع به MonitoringService
         */
        startService(
            Intent(
                this,
                MonitoringService::class.java
            )
                .setAction(
                    MonitoringService.ACTION_ALERT_ACKNOWLEDGED
                )
                .putExtra(
                    MonitoringService.EXTRA_ALERT_ACKNOWLEDGED,
                    true
                )
        )

        finish()
    }

    /*
     * دکمه Back نباید به معنی مشاهده هشدار باشد.
     *
     * بنابراین با Back فقط Activity به پس‌زمینه می‌رود
     * و هشدار همچنان تأییدنشده باقی می‌ماند.
     */
    override fun onBackPressed() {

        window.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        moveTaskToBack(
            true
        )
    }

    /*
     * اگر Activity بعداً دوباره به جلو آورده شود،
     * اطلاعات هشدار همچنان باقی است.
     */
    override fun onResume() {
        super.onResume()

        if (!acknowledged) {

            /*
             * اگر هشدار هنوز تأیید نشده باشد،
             * صفحه اجازه دارد هنگام مشاهده روشن باشد.
             */
            if (!alertFinished) {

                window.addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
            }
        }
    }

    override fun onDestroy() {

        handler.removeCallbacks(
            hideScreenRunnable
        )

        window.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        super.onDestroy()
    }

    private fun formatPrice(
        value: Double
    ): String {

        return if (value > 0) {

            DecimalFormat(
                "#,##0"
            ).format(value) +
                " تومان"

        } else {

            "--"
        }
    }
}
