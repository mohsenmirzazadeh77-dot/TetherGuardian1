package com.tetherguardian.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tetherguardian.app.data.NobitexApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

class MainActivity : AppCompatActivity() {
    companion object { private const val REQUEST_SOUND = 3101; private const val PREF_BATTERY_PROMPT_SHOWN = "battery_prompt_shown" }
    private lateinit var connectionText: TextView; private lateinit var priceText: TextView; private lateinit var updateText: TextView
    private lateinit var baseText: TextView; private lateinit var baseTimeText: TextView; private lateinit var dropPercentText: TextView; private lateinit var dropLimitText: TextView
    private lateinit var monitoringStatusText: TextView; private lateinit var monitoringButton: Button; private lateinit var tradeMonitoringButton: Button
    private lateinit var dropPercentSpinner: Spinner; private lateinit var soundSpinner: Spinner; private lateinit var soundTestButton: Button; private lateinit var alertPreviewButton: Button
    private var settingUpSoundSelector = false
    private val nobitexApi = NobitexApi()
    private val decimalFormat = DecimalFormat("#,##0.########"); private val integerPriceFormat = DecimalFormat("#,##0")
    private val dropOptions = (1..10).map { it * 0.5 }

    private val priceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MonitoringService.ACTION_PRICE_UPDATE) return
            val price = intent.getDoubleExtra(MonitoringService.EXTRA_PRICE, 0.0)
            val base = intent.getDoubleExtra(MonitoringService.EXTRA_BASE_PRICE, 0.0)
            val baseTime = intent.getLongExtra(MonitoringService.EXTRA_BASE_TIME, 0L)
            val receivedTime = intent.getLongExtra(MonitoringService.EXTRA_TIME, 0L)
            val dropLimit = intent.getDoubleExtra(MonitoringService.EXTRA_DROP_LIMIT, 0.0)
            if (price > 0) { priceText.text = formatPrice(price); connectionText.text = "● اتصال موفق به نوبیتکس"; connectionText.setTextColor(getColorCompat(android.R.color.holo_green_dark)) }
            if (base > 0) baseText.text = formatPrice(base)
            if (baseTime > 0) baseTimeText.text = "زمان ثبت: ${formatTime(baseTime)}"
            if (dropLimit > 0) dropLimitText.text = formatRoundedPrice(dropLimit)
            if (receivedTime > 0) updateText.text = "آخرین دریافت: ${formatTime(receivedTime)}"
            setMonitoringActiveAppearance()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main); initializeViews(); setupDropPercentSpinner(); setupSoundSpinner(); registerPriceReceiver(); restoreMonitoringState()
        monitoringButton.setOnClickListener { toggleMonitoring() }
        tradeMonitoringButton.setOnClickListener { startActivity(Intent(this, TradeMonitoringActivity::class.java)) }
        soundTestButton.setOnClickListener { testSelectedSound() }; alertPreviewButton.setOnClickListener { previewAlertScreen() }; loadCurrentPrice()
    }

    private fun initializeViews() {
        connectionText=findViewById(R.id.connectionText); priceText=findViewById(R.id.priceText); updateText=findViewById(R.id.updateText); baseText=findViewById(R.id.baseText); baseTimeText=findViewById(R.id.baseTimeText); dropPercentText=findViewById(R.id.dropPercentText); dropLimitText=findViewById(R.id.dropLimitText); monitoringStatusText=findViewById(R.id.monitoringStatusText); monitoringButton=findViewById(R.id.monitoringButton); tradeMonitoringButton=findViewById(R.id.tradeMonitoringButton); dropPercentSpinner=findViewById(R.id.dropPercentSpinner); soundSpinner=findViewById(R.id.soundSpinner); soundTestButton=findViewById(R.id.soundTestButton); alertPreviewButton=findViewById(R.id.alertPreviewButton)
    }

    private fun setupDropPercentSpinner() {
        val labels=dropOptions.map{formatPercent(it)}; val adapter=ArrayAdapter(this,android.R.layout.simple_spinner_item,labels); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); dropPercentSpinner.adapter=adapter
        val prefs=getSharedPreferences(MonitoringService.PREFS_NAME,MODE_PRIVATE); val saved=prefs.getString(MonitoringService.KEY_DROP_PERCENT,"3.0")?.toDoubleOrNull()?:3.0; val index=dropOptions.indexOfFirst{abs(it-saved)<.001}; if(index>=0) dropPercentSpinner.setSelection(index)
        dropPercentSpinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){val x=dropOptions.getOrNull(pos)?:3.0;dropPercentText.text=formatPercent(x);prefs.edit().putString(MonitoringService.KEY_DROP_PERCENT,x.toString()).apply();updateDropLimit(x)};override fun onNothingSelected(p:AdapterView<*>?){} }
        dropPercentText.text=formatPercent(dropOptions.getOrNull(if(index>=0)index else 5)?:3.0)
    }

    private fun setupSoundSpinner() {
        settingUpSoundSelector=true; val prefs=getSharedPreferences(MonitoringService.PREFS_NAME,MODE_PRIVATE); val title=prefs.getString(MonitoringService.KEY_SOUND_TITLE,null); val label=if(title.isNullOrBlank())"انتخاب صدای هشدار از گوشی" else "صدای انتخاب‌شده: $title"; val adapter=ArrayAdapter(this,android.R.layout.simple_spinner_item,listOf(label)); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); soundSpinner.adapter=adapter; soundSpinner.setSelection(0,false); soundSpinner.setOnTouchListener{_,e->if(e.action==MotionEvent.ACTION_UP&&!settingUpSoundSelector)openSoundPicker();true};settingUpSoundSelector=false
    }
    private fun openSoundPicker(){val prefs=getSharedPreferences(MonitoringService.PREFS_NAME,MODE_PRIVATE);val uri=prefs.getString(MonitoringService.KEY_SOUND_URI,null)?.let(Uri::parse);val picker=Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply{putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,RingtoneManager.TYPE_NOTIFICATION);putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE,"انتخاب صدای هشدار");putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT,false);putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT,false);if(uri!=null)putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,uri)};try{startActivityForResult(picker,REQUEST_SOUND)}catch(_:Exception){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="audio/*";addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)},REQUEST_SOUND)}}
    @Deprecated("Android activity result API retained for compatibility") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode!=REQUEST_SOUND||resultCode!=RESULT_OK)return;val uri=data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)?:data?.data?:return;val title=try{RingtoneManager.getRingtone(this,uri)?.getTitle(this)}catch(_:Exception){null}?:"صدای انتخاب‌شده";getSharedPreferences(MonitoringService.PREFS_NAME,MODE_PRIVATE).edit().putInt(MonitoringService.KEY_SOUND_INDEX,3).putString(MonitoringService.KEY_SOUND_URI,uri.toString()).putString(MonitoringService.KEY_SOUND_TITLE,title).apply();setupSoundSpinner()}
    private fun previewAlertScreen(){val prefs=getSharedPreferences(MonitoringService.PREFS_NAME,MODE_PRIVATE);val base=prefs.getString(MonitoringService.KEY_BASE_PRICE,null)?.toDoubleOrNull()?:100000.0;val percent=prefs.getString(MonitoringService.KEY_DROP_PERCENT,"0.5")?.toDoubleOrNull()?:.5;startActivity(Intent(this,AlertActivity::class.java).apply{putExtra(MonitoringService.EXTRA_ALERT_PRICE,base*(1-percent/100));putExtra(MonitoringService.EXTRA_ALERT_BASE,base);putExtra(MonitoringService.EXTRA_ALERT_DROP,-percent);putExtra("preview_mode",true)})}
    private fun toggleMonitoring(){val prefs=getSharedPreferences(MonitoringService.PREFS_NAME,MODE_PRIVATE);val active=prefs.getBoolean(MonitoringService.KEY_ACTIVE,false);if(active){ContextCompat.startForegroundService(this,Intent(this,MonitoringService::class.java).setAction(MonitoringService.ACTION_STOP));setMonitoringInactiveAppearance()}else{if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.POST_NOTIFICATIONS),2001);val selected=dropOptions.getOrNull(dropPercentSpinner.selectedItemPosition)?:3.0;prefs.edit().putString(MonitoringService.KEY_DROP_PERCENT,selected.toString()).apply();ContextCompat.startForegroundService(this,Intent(this,MonitoringService::class.java).setAction(MonitoringService.ACTION_START));setMonitoringActiveAppearance();maybePromptBatteryOptimization()}}
    private fun maybePromptBatteryOptimization(){if(Build.VERSION.SDK_INT<23)return;val prefs=getSharedPreferences(MonitoringService.PREFS_NAME,MODE_PRIVATE);if(prefs.getBoolean(PREF_BATTERY_PROMPT_SHOWN,false))return;val pm=getSystemService(PowerManager::class.java);if(pm.isIgnoringBatteryOptimizations(packageName)){prefs.edit().putBoolean(PREF_BATTERY_PROMPT_SHOWN,true).apply();return};AlertDialog.Builder(this).setTitle("پایداری پایش تتر").setMessage("برای اطمینان از ادامه پایش قیمت و اجرای هشدار در پس‌زمینه، پیشنهاد می‌شود «نگهبان تتر» را از بهینه‌سازی مصرف باتری مستثنی کنید.").setPositiveButton("باز کردن تنظیمات باتری"){_,_->try{startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply{data=Uri.parse("package:$packageName")})}catch(_:Exception){try{startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))}catch(_:Exception){}};prefs.edit().putBoolean(PREF_BATTERY_PROMPT_SHOWN,true).apply()}.setNegativeButton("بعداً"){_,_->prefs.edit().putBoolean(PREF_BATTERY_PROMPT_SHOWN,true).apply()}.setCancelable(false).show()}
    private fun restoreMonitoringState(){val prefs=getSharedPreferences(MonitoringService.PREFS_NAME,MODE_PRIVATE);val active=prefs.getBoolean(MonitoringService.KEY_ACTIVE,false);val saved=prefs.getString(MonitoringService.KEY_DROP_PERCENT,"3.0")?.toDoubleOrNull()?:3.0;dropPercentText.text=formatPercent(saved);if(active){setMonitoringActiveAppearance();prefs.getString(MonitoringService.KEY_BASE_PRICE,null)?.toDoubleOrNull()?.let{baseText.text=formatPrice(it)};val t=prefs.getLong(MonitoringService.KEY_BASE_TIME,0);if(t>0)baseTimeText.text="زمان ثبت: ${formatTime(t)}";updateDropLimit(saved)}else setMonitoringInactiveAppearance()}
    private fun setMonitoringActiveAppearance(){monitoringStatusText.text="● پایش فعال";monitoringStatusText.setTextColor(getColorCompat(android.R.color.holo_green_dark));monitoringButton.text="غیرفعال کردن برنامه"}
    private fun setMonitoringInactiveAppearance(){monitoringStatusText.text="● پایش غیرفعال";monitoringStatusText.setTextColor(getColorCompat(android.R.color.holo_red_dark));monitoringButton.text="فعال کردن برنامه";baseText.text="--";baseTimeText.text="زمان ثبت: --";dropLimitText.text="--"}
    private fun updateDropLimit(percent:Double){val base=getSharedPreferences(MonitoringService.PREFS_NAME,MODE_PRIVATE).getString(MonitoringService.KEY_BASE_PRICE,null)?.toDoubleOrNull();dropLimitText.text=if(base!=null&&base>0)formatRoundedPrice(base*(1-percent/100)) else "--"}
    private fun loadCurrentPrice(){connectionText.text="● در حال اتصال...";connectionText.setTextColor(getColorCompat(android.R.color.holo_orange_dark));CoroutineScope(Dispatchers.Main).launch{try{val price=withContext(Dispatchers.IO){nobitexApi.getCurrentPrice("USDTIRT")};priceText.text=formatPrice(price);connectionText.text="● اتصال موفق به نوبیتکس";connectionText.setTextColor(getColorCompat(android.R.color.holo_green_dark));updateText.text="آخرین دریافت: ${currentTime()}"}catch(e:Exception){connectionText.text="● اتصال ناموفق به نوبیتکس";connectionText.setTextColor(getColorCompat(android.R.color.holo_red_dark);updateText.text=e.message?:"خطای نامشخص"}}}
    private fun testSelectedSound(){ContextCompat.startForegroundService(this,Intent(this,MonitoringService::class.java).setAction(MonitoringService.ACTION_TEST_SOUND))}
    private fun registerPriceReceiver(){val f=IntentFilter(MonitoringService.ACTION_PRICE_UPDATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(priceReceiver,f,Context.RECEIVER_NOT_EXPORTED)else registerReceiver(priceReceiver,f)}
    override fun onDestroy(){try{unregisterReceiver(priceReceiver)}catch(_:Exception){};super.onDestroy()}
    private fun formatPrice(value:String)=value.toDoubleOrNull()?.let{formatPrice(it)}?:value
    private fun formatPrice(value:Double)=decimalFormat.format(value)+" تومان"
    private fun formatRoundedPrice(value:Double)=integerPriceFormat.format(round(value))+" تومان"
    private fun formatPercent(value:Double)=String.format(Locale.US,"%.1f%%",value)
    private fun currentTime()=SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date())
    private fun formatTime(timestamp:Long)=SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(timestamp))
    private fun getColorCompat(colorRes:Int)=if(Build.VERSION.SDK_INT>=23)getColor(colorRes) else { @Suppress("DEPRECATION") resources.getColor(colorRes) }
}
