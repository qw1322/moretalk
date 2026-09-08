package com.example.onepass.presentation.activity

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.onepass.R
import com.example.onepass.core.config.GlobalScaleManager
import com.example.onepass.service.BundledSpeechSupport
import com.example.onepass.service.DouyinReturnButtonService
import com.example.onepass.service.FloatingHomeButtonService
import com.example.onepass.service.SosHelper
import com.example.onepass.service.SpeechEngineMode
import com.example.onepass.service.WeChatMessageReader
import com.google.android.accessibility.selecttospeak.SelectToSpeakService

class SettingsActivity : AppCompatActivity() {

    private val requestHomeRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateDefaultLauncherButtonState()
        if (isAppDefaultLauncher()) {
            Toast.makeText(this, "已设为默认桌面", Toast.LENGTH_SHORT).show()
        }
    }

    private lateinit var speechSupport: BundledSpeechSupport

    private lateinit var radioLunar: RadioButton
    private lateinit var radioSolar: RadioButton
    private lateinit var dateStyleGroup: RadioGroup

    private lateinit var radioSpeechAuto: RadioButton
    private lateinit var radioSpeechSystem: RadioButton
    private lateinit var radioSpeechBundled: RadioButton
    private lateinit var speechEngineGroup: RadioGroup
    private lateinit var textCurrentSpeechEngine: TextView
    private lateinit var textBundledSpeechModel: TextView

    private lateinit var seekBarIconSize: SeekBar
    private lateinit var textIconSize: TextView
    private lateinit var btnSetDefaultLauncher: Button
    private lateinit var btnClearDefaultLauncher: Button

    private lateinit var btnCommonApps: Button
    private lateinit var btnRemoteAssist: Button
    private lateinit var switchWeather: Switch
    private lateinit var switchDirectCall: Switch
    private lateinit var switchLowBatteryReminder: Switch
    private lateinit var detailDisplayGroup: RadioGroup
    private lateinit var radioDetailBattery: RadioButton
    private lateinit var radioDetailWeather: RadioButton
    private lateinit var seekBarBroadcastVolume: SeekBar
    private lateinit var textBroadcastVolume: TextView
    private lateinit var seekBarSpeechRate: SeekBar
    private lateinit var textSpeechRate: TextView
    private lateinit var commonAppsScrollView: HorizontalScrollView
    private lateinit var commonAppsContainer: LinearLayout
    private lateinit var textNoCommonApps: TextView
    private lateinit var btnContacts: Button

    private lateinit var textDateStyle: TextView
    private lateinit var textCommonAppsTitle: TextView
    private lateinit var textContactsTitle: TextView
    private lateinit var textDirectCallTitle: TextView
    private lateinit var textDirectCallDesc: TextView
    private lateinit var textDetailDisplayTitle: TextView
    private lateinit var textDetailDisplayDesc: TextView
    private lateinit var textLowBatteryReminderTitle: TextView
    private lateinit var textLowBatteryReminderDesc: TextView
    private lateinit var textBroadcastVolumeTitle: TextView
    private lateinit var textBroadcastVolumeDesc: TextView
    private lateinit var textIconSizeTitle: TextView
    private lateinit var textWeatherTitle: TextView
    private lateinit var textWeatherDesc: TextView
    private lateinit var textSpeechRateTitle: TextView
    private lateinit var textSpeechEngineTitle: TextView
    private lateinit var switchFloatingBall: Switch
    private lateinit var textFloatingBallTitle: TextView
    private lateinit var textFloatingBallDesc: TextView
    private lateinit var seekBarFloatBallSize: SeekBar
    private lateinit var textFloatBallSize: TextView
    private lateinit var seekBarFloatBallAlpha: SeekBar
    private lateinit var textFloatBallAlpha: TextView
    private lateinit var switchBlockShade: Switch
    private lateinit var textBlockShadeTitle: TextView
    private lateinit var textBlockShadeDesc: TextView
    private lateinit var switchDouyinSafe: Switch
    private lateinit var textDouyinSafeTitle: TextView
    private lateinit var textDouyinSafeDesc: TextView
    private lateinit var switchDouyinBtnHideOnExit: Switch
    private lateinit var seekBarDouyinBtnSize: SeekBar
    private lateinit var textDouyinBtnSize: TextView
    private lateinit var seekBarDouyinBtnAlpha: SeekBar
    private lateinit var textDouyinBtnAlpha: TextView
    private lateinit var switchWechatMsgRead: Switch
    private lateinit var textWechatMsgReadTitle: TextView
    private lateinit var textWechatMsgReadDesc: TextView

    private lateinit var textRemoteAssistStatus: TextView
    private lateinit var btnStopRemoteAssist: Button

    // 紧急呼救设置（v1.8.7）
    private lateinit var switchSosEnabled: Switch
    private lateinit var editSosPhones: EditText
    private lateinit var editSosSmsText: EditText
    private lateinit var editPushdeerKey: EditText
    private lateinit var editServerchanKey: EditText
    private lateinit var editSosPushText: EditText
    private lateinit var btnSosSave: Button
    private lateinit var btnSosTestSms: Button
    private lateinit var btnSosTestPush: Button

    /** 测试短信的短信权限请求 */
    private val sosSmsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        SosHelper.testSms(this) { r -> Toast.makeText(this, r, Toast.LENGTH_LONG).show() }
    }

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        speechSupport = BundledSpeechSupport(this)

        initViews()
        loadSettings()
        setupListeners()
        updateDefaultLauncherButtonState()
        refreshSpeechStatus()
    }

    override fun onResume() {
        super.onResume()

        val commonAppsSet = getSharedPreferences(COMMON_APPS_PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_COMMON_APPS, HashSet<String>())
        loadCommonApps(commonAppsSet)
        updateDefaultLauncherButtonState()
        refreshSpeechStatus()

        val scalePercentage = GlobalScaleManager.getScalePercentage(this)
        applyScaleEffects(scalePercentage)
        syncFloatingBallService()
        syncDouyinButtonService()
        updateRemoteAssistStatus()
    }

    /**
     * 按开关状态与悬浮窗权限同步悬浮球服务（从权限设置页返回时也会执行）
     */
    private fun syncFloatingBallService() {
        val enabled = prefs.getBoolean(KEY_FLOAT_BALL_ENABLED, true)
        if (enabled && Settings.canDrawOverlays(this)) {
            startFloatingBallService()
        } else if (!enabled) {
            stopFloatingBallService()
        }
    }

    private fun startFloatingBallService() {
        startService(Intent(this, FloatingHomeButtonService::class.java))
    }

    private fun stopFloatingBallService() {
        stopService(Intent(this, FloatingHomeButtonService::class.java))
    }

    /**
     * 重启悬浮球服务使大小/透明度设置立即生效（仅当开关开启且已授予悬浮窗权限）
     */
    private fun restartFloatingBallService() {
        val enabled = prefs.getBoolean(KEY_FLOAT_BALL_ENABLED, true)
        if (enabled && Settings.canDrawOverlays(this)) {
            stopService(Intent(this, FloatingHomeButtonService::class.java))
            startService(Intent(this, FloatingHomeButtonService::class.java))
        }
    }

    /**
     * 启动抖音安心刷悬浮按钮服务（仅在已授予悬浮窗权限时）
     */
    private fun startDouyinSafeService() {
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, DouyinReturnButtonService::class.java))
        }
    }

    /**
     * 重启抖音按钮服务使大小/透明度设置立即生效（仅当开关开启且已授予悬浮窗权限）
     */
    private fun restartDouyinButtonService() {
        val enabled = prefs.getBoolean(SelectToSpeakService.KEY_DOUYIN_SAFE_MODE, false)
        if (enabled && Settings.canDrawOverlays(this)) {
            stopService(Intent(this, DouyinReturnButtonService::class.java))
            startService(Intent(this, DouyinReturnButtonService::class.java))
        }
    }

    /**
     * 按开关状态与悬浮窗权限同步抖音安心刷服务（从权限设置页返回时也会执行）
     */
    private fun syncDouyinButtonService() {
        val enabled = prefs.getBoolean(SelectToSpeakService.KEY_DOUYIN_SAFE_MODE, false)
        if (enabled && Settings.canDrawOverlays(this)) {
            startService(Intent(this, DouyinReturnButtonService::class.java))
        } else if (!enabled) {
            stopService(Intent(this, DouyinReturnButtonService::class.java))
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        intent.data = android.net.Uri.parse("package:$packageName")
        startActivity(intent)
    }

    private fun initViews() {
        radioLunar = findViewById(R.id.radioLunar)
        radioSolar = findViewById(R.id.radioSolar)
        dateStyleGroup = findViewById(R.id.dateStyleGroup)

        radioSpeechAuto = findViewById(R.id.radioSpeechAuto)
        radioSpeechSystem = findViewById(R.id.radioSpeechSystem)
        radioSpeechBundled = findViewById(R.id.radioSpeechBundled)
        speechEngineGroup = findViewById(R.id.speechEngineGroup)
        textCurrentSpeechEngine = findViewById(R.id.textCurrentSpeechEngine)
        textBundledSpeechModel = findViewById(R.id.textBundledSpeechModel)

        seekBarIconSize = findViewById(R.id.seekBarIconSize)
        textIconSize = findViewById(R.id.textIconSize)
        btnSetDefaultLauncher = findViewById(R.id.btnSetDefaultLauncher)
        btnClearDefaultLauncher = findViewById(R.id.btnClearDefaultLauncher)

        btnCommonApps = findViewById(R.id.btnCommonApps)
        btnRemoteAssist = findViewById(R.id.btnRemoteAssist)
        switchWeather = findViewById(R.id.switchWeather)
        switchDirectCall = findViewById(R.id.switchDirectCall)
        switchLowBatteryReminder = findViewById(R.id.switchLowBatteryReminder)
        detailDisplayGroup = findViewById(R.id.detailDisplayGroup)
        radioDetailBattery = findViewById(R.id.radioDetailBattery)
        radioDetailWeather = findViewById(R.id.radioDetailWeather)
        seekBarBroadcastVolume = findViewById(R.id.seekBarBroadcastVolume)
        textBroadcastVolume = findViewById(R.id.textBroadcastVolume)
        seekBarSpeechRate = findViewById(R.id.seekBarSpeechRate)
        textSpeechRate = findViewById(R.id.textSpeechRate)
        commonAppsScrollView = findViewById(R.id.commonAppsScrollView)
        commonAppsContainer = findViewById(R.id.commonAppsContainer)
        textNoCommonApps = findViewById(R.id.textNoCommonApps)
        btnContacts = findViewById(R.id.btnContacts)
        switchFloatingBall = findViewById(R.id.switchFloatingBall)
        textFloatingBallTitle = findViewById(R.id.textFloatingBallTitle)
        textFloatingBallDesc = findViewById(R.id.textFloatingBallDesc)
        seekBarFloatBallSize = findViewById(R.id.seekBarFloatBallSize)
        textFloatBallSize = findViewById(R.id.textFloatBallSize)
        seekBarFloatBallAlpha = findViewById(R.id.seekBarFloatBallAlpha)
        textFloatBallAlpha = findViewById(R.id.textFloatBallAlpha)
        switchBlockShade = findViewById(R.id.switchBlockShade)
        textBlockShadeTitle = findViewById(R.id.textBlockShadeTitle)
        textBlockShadeDesc = findViewById(R.id.textBlockShadeDesc)
        switchDouyinSafe = findViewById(R.id.switchDouyinSafe)
        textDouyinSafeTitle = findViewById(R.id.textDouyinSafeTitle)
        textDouyinSafeDesc = findViewById(R.id.textDouyinSafeDesc)
        switchDouyinBtnHideOnExit = findViewById(R.id.switchDouyinBtnHideOnExit)
        seekBarDouyinBtnSize = findViewById(R.id.seekBarDouyinBtnSize)
        textDouyinBtnSize = findViewById(R.id.textDouyinBtnSize)
        seekBarDouyinBtnAlpha = findViewById(R.id.seekBarDouyinBtnAlpha)
        textDouyinBtnAlpha = findViewById(R.id.textDouyinBtnAlpha)
        switchWechatMsgRead = findViewById(R.id.switchWechatMsgRead)
        textWechatMsgReadTitle = findViewById(R.id.textWechatMsgReadTitle)
        textWechatMsgReadDesc = findViewById(R.id.textWechatMsgReadDesc)

        textRemoteAssistStatus = findViewById(R.id.textRemoteAssistStatus)
        btnStopRemoteAssist = findViewById(R.id.btnStopRemoteAssist)

        switchSosEnabled = findViewById(R.id.switchSosEnabled)
        editSosPhones = findViewById(R.id.editSosPhones)
        editSosSmsText = findViewById(R.id.editSosSmsText)
        editPushdeerKey = findViewById(R.id.editPushdeerKey)
        editServerchanKey = findViewById(R.id.editServerchanKey)
        editSosPushText = findViewById(R.id.editSosPushText)
        btnSosSave = findViewById(R.id.btnSosSave)
        btnSosTestSms = findViewById(R.id.btnSosTestSms)
        btnSosTestPush = findViewById(R.id.btnSosTestPush)

        textDateStyle = findViewById(R.id.textDateStyle)
        textCommonAppsTitle = findViewById(R.id.textCommonAppsTitle)
        textContactsTitle = findViewById(R.id.textContactsTitle)
        textDirectCallTitle = findViewById(R.id.textDirectCallTitle)
        textDirectCallDesc = findViewById(R.id.textDirectCallDesc)
        textDetailDisplayTitle = findViewById(R.id.textDetailDisplayTitle)
        textDetailDisplayDesc = findViewById(R.id.textDetailDisplayDesc)
        textLowBatteryReminderTitle = findViewById(R.id.textLowBatteryReminderTitle)
        textLowBatteryReminderDesc = findViewById(R.id.textLowBatteryReminderDesc)
        textBroadcastVolumeTitle = findViewById(R.id.textBroadcastVolumeTitle)
        textBroadcastVolumeDesc = findViewById(R.id.textBroadcastVolumeDesc)
        textIconSizeTitle = findViewById(R.id.textIconSizeTitle)
        textWeatherTitle = findViewById(R.id.textWeatherTitle)
        textWeatherDesc = findViewById(R.id.textWeatherDesc)
        textSpeechRateTitle = findViewById(R.id.textSpeechRateTitle)
        textSpeechEngineTitle = findViewById(R.id.textSpeechEngineTitle)

        seekBarIconSize.min = 60
        seekBarIconSize.max = 100
    }

    private fun loadSettings() {
        val dateStyle = prefs.getString(KEY_DATE_STYLE, VALUE_SOLAR)
        if (dateStyle == VALUE_LUNAR) {
            radioLunar.isChecked = true
        } else {
            radioSolar.isChecked = true
        }

        when (speechSupport.getMode()) {
            SpeechEngineMode.AUTO -> radioSpeechAuto.isChecked = true
            SpeechEngineMode.SYSTEM -> radioSpeechSystem.isChecked = true
            SpeechEngineMode.BUNDLED_MATCHA -> radioSpeechBundled.isChecked = true
        }

        val weatherEnabled = prefs.getBoolean(KEY_WEATHER_ENABLED, true)
        switchWeather.isChecked = weatherEnabled

        val directCallEnabled = prefs.getBoolean(KEY_DIRECT_CALL_ENABLED, true)
        switchDirectCall.isChecked = directCallEnabled

        val lowBatteryReminderEnabled = prefs.getBoolean(KEY_LOW_BATTERY_REMINDER_ENABLED, true)
        switchLowBatteryReminder.isChecked = lowBatteryReminderEnabled

        switchFloatingBall.isChecked = prefs.getBoolean(KEY_FLOAT_BALL_ENABLED, true)

        // 悬浮球大小：进度 0..100 → 百分比 50%..150%（默认 100%）
        val floatBallSizePct = prefs.getInt(FloatingHomeButtonService.KEY_FLOAT_BALL_SIZE_PCT, 100)
        seekBarFloatBallSize.progress = (floatBallSizePct - 50).coerceIn(0, 100)
        textFloatBallSize.text = "$floatBallSizePct%"
        // 悬浮球透明度：进度 0..80 → 百分比 20%..100%（默认 100%=不透明）
        val floatBallAlphaPct = prefs.getInt(FloatingHomeButtonService.KEY_FLOAT_BALL_ALPHA_PCT, 100)
        seekBarFloatBallAlpha.progress = (floatBallAlphaPct - 20).coerceIn(0, 80)
        textFloatBallAlpha.text = "$floatBallAlphaPct%"

        switchWechatMsgRead.isChecked = prefs.getBoolean(
            WeChatMessageReader.KEY_WECHAT_MSG_READ_ENABLED,
            false
        )

        switchBlockShade.isChecked = prefs.getBoolean(
            SelectToSpeakService.KEY_BLOCK_NOTIFICATION_SHADE,
            false
        )

        switchDouyinSafe.isChecked = prefs.getBoolean(
            SelectToSpeakService.KEY_DOUYIN_SAFE_MODE,
            false
        )

        switchDouyinBtnHideOnExit.isChecked = prefs.getBoolean(
            DouyinReturnButtonService.KEY_HIDE_ON_EXIT,
            true
        )
        // 按钮大小：进度 0..100 → 百分比 50%..150%（默认 100%）
        val douyinBtnSizePct = prefs.getInt(DouyinReturnButtonService.KEY_BTN_SIZE_PCT, 100)
        seekBarDouyinBtnSize.progress = (douyinBtnSizePct - 50).coerceIn(0, 100)
        textDouyinBtnSize.text = "$douyinBtnSizePct%"
        // 按钮透明度：进度 0..80 → 百分比 20%..100%（默认 100%=不透明）
        val douyinBtnAlphaPct = prefs.getInt(DouyinReturnButtonService.KEY_BTN_ALPHA_PCT, 100)
        seekBarDouyinBtnAlpha.progress = (douyinBtnAlphaPct - 20).coerceIn(0, 80)
        textDouyinBtnAlpha.text = "$douyinBtnAlphaPct%"

        when (prefs.getString(KEY_HOME_DETAIL_MODE, VALUE_HOME_DETAIL_BATTERY)) {
            VALUE_HOME_DETAIL_WEATHER -> radioDetailWeather.isChecked = true
            else -> radioDetailBattery.isChecked = true
        }

        val broadcastVolume = getSavedBroadcastVolume()
        seekBarBroadcastVolume.progress = broadcastVolume
        textBroadcastVolume.text = "$broadcastVolume%"

        val speechRateProgress = prefs.getInt(KEY_SPEECH_RATE, 50)
        seekBarSpeechRate.progress = speechRateProgress
        textSpeechRate.text = String.format("%.1fx", 0.5f + (speechRateProgress / 50f))

        val scalePercentage = GlobalScaleManager.getScalePercentage(this)
        seekBarIconSize.progress = scalePercentage
        textIconSize.text = "$scalePercentage%"
        applyScaleEffects(scalePercentage)

        val commonAppsSet = getSharedPreferences(COMMON_APPS_PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_COMMON_APPS, HashSet<String>())
        loadCommonApps(commonAppsSet)
    }

    private fun setupListeners() {
        dateStyleGroup.setOnCheckedChangeListener { _, checkedId ->
            val isLunar = checkedId == R.id.radioLunar
            prefs.edit()
                .putString(KEY_DATE_STYLE, if (isLunar) VALUE_LUNAR else VALUE_SOLAR)
                .apply()
            Toast.makeText(
                this,
                if (isLunar) "已切换到农历显示" else "已切换到阳历显示",
                Toast.LENGTH_SHORT
            ).show()
        }

        speechEngineGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radioSpeechSystem -> SpeechEngineMode.SYSTEM
                R.id.radioSpeechBundled -> SpeechEngineMode.BUNDLED_MATCHA
                else -> SpeechEngineMode.AUTO
            }
            speechSupport.setMode(mode)
            speechSupport.syncResolvedEngineLabel()
            refreshSpeechStatus()
            Toast.makeText(
                this,
                when (mode) {
                    SpeechEngineMode.AUTO -> "已切换到自动语音引擎"
                    SpeechEngineMode.SYSTEM -> "已切换到系统 TTS"
                    SpeechEngineMode.BUNDLED_MATCHA -> "已切换到内置 Matcha"
                },
                Toast.LENGTH_SHORT
            ).show()
        }

        seekBarIconSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceIn(60, 100)
                textIconSize.text = "$clamped%"
                applyScaleEffects(clamped)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val clamped = seekBarIconSize.progress.coerceIn(60, 100)
                GlobalScaleManager.setScalePercentage(this@SettingsActivity, clamped)
                Toast.makeText(
                    this@SettingsActivity,
                    "图标大小已调整为 $clamped%",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        btnSetDefaultLauncher.setOnClickListener { setAsDefaultLauncher() }
        btnClearDefaultLauncher.setOnClickListener { clearDefaultLauncher() }

        btnCommonApps.setOnClickListener {
            startActivity(Intent(this, CommonAppsActivity::class.java))
        }

        btnRemoteAssist.setOnClickListener {
            startActivity(Intent(this, RemoteAssistActivity::class.java))
        }

        btnStopRemoteAssist.setOnClickListener {
            if (com.example.onepass.service.RemoteAssistService.isRunning) {
                com.example.onepass.service.RemoteAssistService.stop(this)
                Toast.makeText(this, "已停止远程协助并清理资源", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "远程协助当前未运行，无需停止", Toast.LENGTH_SHORT).show()
            }
            updateRemoteAssistStatus()
        }

        // ===== 紧急呼救设置（v1.8.7）=====
        loadSosSettings()
        btnSosSave.setOnClickListener { saveSosSettings() }
        btnSosTestSms.setOnClickListener {
            saveSosSettings()
            if (checkSelfPermission(android.Manifest.permission.SEND_SMS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                sosSmsPermissionLauncher.launch(android.Manifest.permission.SEND_SMS)
            } else {
                SosHelper.testSms(this) { r -> Toast.makeText(this, r, Toast.LENGTH_LONG).show() }
            }
        }
        btnSosTestPush.setOnClickListener {
            saveSosSettings()
            SosHelper.testPush(this) { r -> Toast.makeText(this, "测试推送：$r", Toast.LENGTH_LONG).show() }
        }

        btnContacts.setOnClickListener {
            startActivity(Intent(this, ContactsActivity::class.java))
        }

        switchWeather.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_WEATHER_ENABLED, isChecked).apply()
            Toast.makeText(
                this,
                if (isChecked) "已开启天气播报" else "已关闭天气播报",
                Toast.LENGTH_SHORT
            ).show()
        }

        switchDirectCall.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_DIRECT_CALL_ENABLED, isChecked).apply()
            Toast.makeText(
                this,
                if (isChecked) "已开启单一通话方式直接拨打" else "已关闭单一通话方式直接拨打",
                Toast.LENGTH_SHORT
            ).show()
        }

        detailDisplayGroup.setOnCheckedChangeListener { _, checkedId ->
            val detailMode = if (checkedId == R.id.radioDetailWeather) {
                VALUE_HOME_DETAIL_WEATHER
            } else {
                VALUE_HOME_DETAIL_BATTERY
            }
            prefs.edit().putString(KEY_HOME_DETAIL_MODE, detailMode).apply()
            Toast.makeText(
                this,
                if (detailMode == VALUE_HOME_DETAIL_BATTERY) "首页已切换为显示电量" else "首页已切换为显示天气详细数据",
                Toast.LENGTH_SHORT
            ).show()
        }

        switchLowBatteryReminder.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_LOW_BATTERY_REMINDER_ENABLED, isChecked).apply()
            Toast.makeText(
                this,
                if (isChecked) "已开启低电量提醒" else "已关闭低电量提醒",
                Toast.LENGTH_SHORT
            ).show()
        }

        switchFloatingBall.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_FLOAT_BALL_ENABLED, isChecked).apply()
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "请授予悬浮窗权限后自动开启悬浮球", Toast.LENGTH_LONG).show()
                    requestOverlayPermission()
                } else {
                    startFloatingBallService()
                }
            } else {
                stopFloatingBallService()
                Toast.makeText(this, "已关闭桌面悬浮球", Toast.LENGTH_SHORT).show()
            }
        }

        // 悬浮球大小：拖动后保存并重启服务立即生效
        seekBarFloatBallSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                textFloatBallSize.text = "${progress + 50}%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val pct = seekBarFloatBallSize.progress + 50
                prefs.edit().putInt(FloatingHomeButtonService.KEY_FLOAT_BALL_SIZE_PCT, pct).apply()
                restartFloatingBallService()
                Toast.makeText(this@SettingsActivity, "悬浮球大小已调整为 $pct%", Toast.LENGTH_SHORT).show()
            }
        })

        // 悬浮球透明度：拖动后保存并重启服务立即生效（100%=不透明）
        seekBarFloatBallAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                textFloatBallAlpha.text = "${progress + 20}%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val pct = seekBarFloatBallAlpha.progress + 20
                prefs.edit().putInt(FloatingHomeButtonService.KEY_FLOAT_BALL_ALPHA_PCT, pct).apply()
                restartFloatingBallService()
                Toast.makeText(this@SettingsActivity, "悬浮球透明度已调整为 $pct%", Toast.LENGTH_SHORT).show()
            }
        })

        switchWechatMsgRead.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(WeChatMessageReader.KEY_WECHAT_MSG_READ_ENABLED, isChecked).apply()
            Toast.makeText(
                this,
                if (isChecked) "已开启微信消息点读" else "已关闭微信消息点读",
                Toast.LENGTH_SHORT
            ).show()
        }

        switchBlockShade.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(SelectToSpeakService.KEY_BLOCK_NOTIFICATION_SHADE, isChecked).apply()
            Toast.makeText(
                this,
                if (isChecked) "已开启禁用下拉通知栏" else "已关闭禁用下拉通知栏",
                Toast.LENGTH_SHORT
            ).show()
        }

        switchDouyinSafe.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(SelectToSpeakService.KEY_DOUYIN_SAFE_MODE, isChecked).apply()
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "请授予悬浮窗权限后自动开启抖音安心刷", Toast.LENGTH_LONG).show()
                    requestOverlayPermission()
                } else {
                    startDouyinSafeService()
                }
            } else {
                stopService(Intent(this, DouyinReturnButtonService::class.java))
            }
            Toast.makeText(
                this,
                if (isChecked) "已开启抖音安心刷" else "已关闭抖音安心刷",
                Toast.LENGTH_SHORT
            ).show()
        }

        switchDouyinBtnHideOnExit.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(DouyinReturnButtonService.KEY_HIDE_ON_EXIT, isChecked).apply()
            Toast.makeText(
                this,
                if (isChecked) "已开启：退出抖音后自动隐藏按钮" else "已关闭：退出抖音后按钮保留片刻",
                Toast.LENGTH_SHORT
            ).show()
        }

        // 按钮大小：拖动后保存并重启按钮服务立即生效
        seekBarDouyinBtnSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                textDouyinBtnSize.text = "${progress + 50}%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val pct = seekBarDouyinBtnSize.progress + 50
                prefs.edit().putInt(DouyinReturnButtonService.KEY_BTN_SIZE_PCT, pct).apply()
                restartDouyinButtonService()
                Toast.makeText(this@SettingsActivity, "按钮大小已调整为 $pct%", Toast.LENGTH_SHORT).show()
            }
        })

        // 按钮透明度：拖动后保存并重启按钮服务立即生效（100%=不透明）
        seekBarDouyinBtnAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                textDouyinBtnAlpha.text = "${progress + 20}%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val pct = seekBarDouyinBtnAlpha.progress + 20
                prefs.edit().putInt(DouyinReturnButtonService.KEY_BTN_ALPHA_PCT, pct).apply()
                restartDouyinButtonService()
                Toast.makeText(this@SettingsActivity, "按钮透明度已调整为 $pct%", Toast.LENGTH_SHORT).show()
            }
        })

        seekBarBroadcastVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                textBroadcastVolume.text = "$progress%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveBroadcastVolume(seekBarBroadcastVolume.progress)
                Toast.makeText(
                    this@SettingsActivity,
                    "播报音量已调整为 ${seekBarBroadcastVolume.progress}%",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        seekBarSpeechRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = 0.5f + (progress / 50f)
                textSpeechRate.text = String.format("%.1fx", rate)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.edit().putInt(KEY_SPEECH_RATE, seekBarSpeechRate.progress).apply()
                val rate = 0.5f + (seekBarSpeechRate.progress / 50f)
                Toast.makeText(
                    this@SettingsActivity,
                    "语速已调整为 ${String.format("%.1fx", rate)}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }

    private fun refreshSpeechStatus() {
        val bundledStatus = speechSupport.getBundledVoiceStatus()
        val bundledModelLabel = if (bundledStatus.isInstalled) {
            "内置语音模型：${speechSupport.getPreferredVoiceDisplayName()}"
        } else {
            "内置语音模型：未检测到（缺少 ${bundledStatus.missingAssets.joinToString("、")}）"
        }

        textCurrentSpeechEngine.text = "当前实际引擎：${speechSupport.getResolvedEngineLabel()}"
        textBundledSpeechModel.text = bundledModelLabel

        radioSpeechBundled.isEnabled = bundledStatus.isInstalled
        if (!bundledStatus.isInstalled && speechSupport.getMode() == SpeechEngineMode.BUNDLED_MATCHA) {
            speechSupport.setMode(SpeechEngineMode.AUTO)
            speechSupport.syncResolvedEngineLabel()
            radioSpeechAuto.isChecked = true
        }
    }

    private fun setAsDefaultLauncher() {
        if (isAppDefaultLauncher()) {
            Toast.makeText(this, "当前已是默认桌面", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    requestHomeRoleLauncher.launch(
                        roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
                    )
                    return
                }
            }

            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            Toast.makeText(this, "请在系统设置中选择 MoreTalk 作为默认桌面", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开桌面设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearDefaultLauncher() {
        if (!isAppDefaultLauncher()) {
            Toast.makeText(this, "当前未设为默认桌面", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            @Suppress("DEPRECATION")
            packageManager.clearPackagePreferredActivities(packageName)
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            Toast.makeText(this, "请改选其他桌面应用以取消默认桌面", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开桌面设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDefaultLauncherButtonState() {
        val isDefaultLauncher = isAppDefaultLauncher()
        btnSetDefaultLauncher.isEnabled = !isDefaultLauncher
        btnSetDefaultLauncher.alpha = if (isDefaultLauncher) 0.6f else 1f
        btnSetDefaultLauncher.text = if (isDefaultLauncher) {
            "已设为默认桌面"
        } else {
            "设为默认桌面"
        }

        btnClearDefaultLauncher.isEnabled = isDefaultLauncher
        btnClearDefaultLauncher.alpha = if (isDefaultLauncher) 1f else 0.6f
        btnClearDefaultLauncher.text = "取消默认桌面"
    }

    private fun isAppDefaultLauncher(): Boolean {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = packageManager.resolveActivity(
            homeIntent,
            PackageManager.MATCH_DEFAULT_ONLY
        ) ?: return false

        return resolveInfo.activityInfo?.packageName == packageName
    }

    /**
     * 刷新远程协助运行状态显示（是否运行、房间号）
     */
    private fun updateRemoteAssistStatus() {
        val running = com.example.onepass.service.RemoteAssistService.isRunning
        textRemoteAssistStatus.text = if (running) {
            "运行中 · 房间 ${com.example.onepass.service.RemoteAssistService.tunnelRoom}\n" +
                "外网访问：http://" +
                com.example.onepass.service.RemoteAssistService.VPS_HOST + ":" +
                com.example.onepass.service.RemoteAssistService.VPS_PORT + "/ctrl?room=" +
                com.example.onepass.service.RemoteAssistService.tunnelRoom
        } else {
            "未运行"
        }
        btnStopRemoteAssist.isEnabled = running
        btnStopRemoteAssist.alpha = if (running) 1f else 0.6f
    }

    /** 紧急呼救：加载配置到设置控件 */
    private fun loadSosSettings() {
        val cfg = SosHelper.loadConfig(this)
        switchSosEnabled.isChecked = cfg.enabled
        editSosPhones.setText(cfg.phones.joinToString(","))
        editSosSmsText.setText(cfg.smsText)
        editPushdeerKey.setText(cfg.pushdeerKey)
        editServerchanKey.setText(cfg.serverchanKey)
        editSosPushText.setText(cfg.pushText)
    }

    /** 紧急呼救：保存控件内容到配置 */
    private fun saveSosSettings() {
        val phones = editSosPhones.text.toString()
            .split(',', '，', ';', '；', ' ', '\n')
            .map { it.trim() }.filter { it.isNotBlank() }
        SosHelper.saveConfig(
            this,
            SosHelper.SosConfig(
                enabled = switchSosEnabled.isChecked,
                phones = phones,
                smsText = editSosSmsText.text.toString().ifBlank { SosHelper.DEFAULT_SMS_TEXT },
                pushdeerKey = editPushdeerKey.text.toString().trim(),
                serverchanKey = editServerchanKey.text.toString().trim(),
                pushText = editSosPushText.text.toString().ifBlank { SosHelper.DEFAULT_PUSH_TEXT }
            )
        )
        Toast.makeText(this, "紧急呼救设置已保存（${phones.size} 个号码）", Toast.LENGTH_SHORT).show()
        // 预热推送连接：首次连接可达 20s，提前建立后紧急呼救即时送达
        SosHelper.warmup(this)
    }

    private fun loadCommonApps(commonAppsSet: Set<String>?) {
        commonAppsContainer.removeAllViews()

        if (commonAppsSet.isNullOrEmpty()) {
            commonAppsScrollView.visibility = View.GONE
            textNoCommonApps.visibility = View.VISIBLE
            return
        }

        commonAppsScrollView.visibility = View.VISIBLE
        textNoCommonApps.visibility = View.GONE

        val savedOrders = getSharedPreferences(COMMON_APPS_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_APP_ORDERS, null)
        val appOrders = if (savedOrders != null) {
            runCatching { parseAppOrders(savedOrders) }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        val sortedApps = commonAppsSet.sortedWith(
            compareBy<String> { appOrders[it] ?: Int.MAX_VALUE }
        )

        for (packageName in sortedApps) {
            runCatching {
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                val appIcon = packageInfo.applicationInfo?.loadIcon(packageManager) ?: return@runCatching
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(16, 0, 16, 0)
                    }
                }

                val iconView = ImageView(this).apply {
                    setImageDrawable(appIcon)
                    layoutParams = LinearLayout.LayoutParams(
                        GlobalScaleManager.getScaledValue(this@SettingsActivity, 120),
                        GlobalScaleManager.getScaledValue(this@SettingsActivity, 120)
                    )
                }

                item.addView(iconView)
                item.setOnClickListener {
                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        startActivity(launchIntent)
                    } else {
                        Toast.makeText(this@SettingsActivity, "无法打开该应用", Toast.LENGTH_SHORT).show()
                    }
                }
                commonAppsContainer.addView(item)
            }
        }
    }

    private fun parseAppOrders(ordersString: String): Map<String, Int> {
        val orders = mutableMapOf<String, Int>()
        for (pair in ordersString.split(",")) {
            val parts = pair.split(":")
            if (parts.size == 2) {
                parts[1].toIntOrNull()?.let { order ->
                    orders[parts[0]] = order
                }
            }
        }
        return orders
    }

    private fun applyScaleEffects(scalePercentage: Int) {
        val scaledTitleSize = GlobalScaleManager.getScaledValue(this, 27f)
        val scaledOptionSize = GlobalScaleManager.getScaledValue(this, 24f)
        val scaledButtonSize = GlobalScaleManager.getScaledValue(this, 20f)

        textDateStyle.textSize = scaledTitleSize
        textCommonAppsTitle.textSize = scaledTitleSize
        textContactsTitle.textSize = scaledTitleSize
        textDirectCallTitle.textSize = scaledTitleSize
        textLowBatteryReminderTitle.textSize = scaledTitleSize
        textDetailDisplayTitle.textSize = scaledTitleSize
        textBroadcastVolumeTitle.textSize = scaledTitleSize
        textIconSizeTitle.textSize = scaledTitleSize
        textWeatherTitle.textSize = scaledTitleSize
        textSpeechEngineTitle.textSize = scaledTitleSize
        textSpeechRateTitle.textSize = scaledTitleSize
        textFloatingBallTitle.textSize = scaledTitleSize
        textWechatMsgReadTitle.textSize = scaledTitleSize
        textBlockShadeTitle.textSize = scaledTitleSize
        textDouyinSafeTitle.textSize = scaledTitleSize
        findViewById<TextView>(R.id.textDouyinBtnHideTitle).textSize = scaledOptionSize
        findViewById<TextView>(R.id.textDouyinBtnSizeTitle).textSize = scaledOptionSize
        findViewById<TextView>(R.id.textDouyinBtnAlphaTitle).textSize = scaledOptionSize
        findViewById<TextView>(R.id.textFloatBallSizeTitle).textSize = scaledOptionSize
        findViewById<TextView>(R.id.textFloatBallAlphaTitle).textSize = scaledOptionSize

        radioLunar.textSize = scaledOptionSize
        radioSolar.textSize = scaledOptionSize
        radioSpeechAuto.textSize = scaledOptionSize
        radioSpeechSystem.textSize = scaledOptionSize
        radioSpeechBundled.textSize = scaledOptionSize
        radioDetailBattery.textSize = scaledOptionSize
        radioDetailWeather.textSize = scaledOptionSize
        textIconSize.textSize = scaledOptionSize
        textBroadcastVolume.textSize = scaledOptionSize
        textFloatBallSize.textSize = scaledOptionSize
        textFloatBallAlpha.textSize = scaledOptionSize
        textCurrentSpeechEngine.textSize = scaledOptionSize
        textBundledSpeechModel.textSize = scaledOptionSize
        textNoCommonApps.textSize = scaledOptionSize
        textDirectCallDesc.textSize = GlobalScaleManager.getScaledValue(this, 16f)
        textDetailDisplayDesc.textSize = GlobalScaleManager.getScaledValue(this, 16f)
        textLowBatteryReminderDesc.textSize = GlobalScaleManager.getScaledValue(this, 16f)
        textBroadcastVolumeDesc.textSize = GlobalScaleManager.getScaledValue(this, 16f)
        textWeatherDesc.textSize = GlobalScaleManager.getScaledValue(this, 16f)
        textFloatingBallDesc.textSize = GlobalScaleManager.getScaledValue(this, 16f)
        textWechatMsgReadDesc.textSize = GlobalScaleManager.getScaledValue(this, 16f)
        textBlockShadeDesc.textSize = GlobalScaleManager.getScaledValue(this, 16f)
        textDouyinSafeDesc.textSize = GlobalScaleManager.getScaledValue(this, 16f)
        textDouyinBtnSize.textSize = scaledOptionSize
        textDouyinBtnAlpha.textSize = scaledOptionSize

        btnSetDefaultLauncher.textSize = scaledOptionSize
        btnClearDefaultLauncher.textSize = scaledOptionSize
        btnCommonApps.textSize = scaledButtonSize
        btnContacts.textSize = scaledButtonSize
        btnRemoteAssist.textSize = scaledButtonSize
        btnStopRemoteAssist.textSize = scaledButtonSize
        textRemoteAssistStatus.textSize = GlobalScaleManager.getScaledValue(this, 20f)
        // 语速显示固定大小，不随图标缩放变化。
        textSpeechRate.textSize = 24f
    }

    private fun getSavedBroadcastVolume(): Int {
        return if (prefs.contains(KEY_BROADCAST_VOLUME)) {
            prefs.getInt(KEY_BROADCAST_VOLUME, 50)
        } else {
            prefs.getInt(KEY_LEGACY_WEATHER_VOLUME, 50)
        }
    }

    private fun saveBroadcastVolume(progress: Int) {
        prefs.edit()
            .putInt(KEY_BROADCAST_VOLUME, progress)
            .putInt(KEY_LEGACY_WEATHER_VOLUME, progress)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "OnePassPrefs"
        private const val COMMON_APPS_PREFS = "common_apps_prefs"
        private const val KEY_DATE_STYLE = "date_style"
        private const val VALUE_LUNAR = "lunar"
        private const val VALUE_SOLAR = "solar"
        private const val KEY_WEATHER_ENABLED = "weather_enabled"
        private const val KEY_DIRECT_CALL_ENABLED = "direct_call_enabled"
        private const val KEY_HOME_DETAIL_MODE = "home_detail_mode"
        private const val VALUE_HOME_DETAIL_BATTERY = "battery"
        private const val VALUE_HOME_DETAIL_WEATHER = "weather"
        private const val KEY_LOW_BATTERY_REMINDER_ENABLED = "low_battery_reminder_enabled"
        private const val KEY_BROADCAST_VOLUME = "broadcast_volume"
        private const val KEY_LEGACY_WEATHER_VOLUME = "weather_volume"
        private const val KEY_SPEECH_RATE = "speech_rate"
        private const val KEY_COMMON_APPS = "common_apps"
        private const val KEY_APP_ORDERS = "app_orders"
        private const val KEY_FLOAT_BALL_ENABLED = "float_ball_enabled"
    }
}
