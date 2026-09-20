package com.alarmpair.app

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    companion object { private const val TAG = "AlarmPair" }
    private lateinit var tvStatus: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var tvClock: TextView
    private lateinit var panelPairing: LinearLayout
    private lateinit var panelAlarm: LinearLayout
    private lateinit var panelAlarmActive: LinearLayout
    private lateinit var panelGoals: LinearLayout
    private lateinit var panelWakeCheck: LinearLayout
    private lateinit var panelWakeOther: LinearLayout
    private lateinit var btnCreatePair: Button
    private lateinit var btnJoinPair: Button
    private lateinit var btnSetAlarm: Button
    private lateinit var btnStop: Button
    private lateinit var btnSubmitGoals: Button
    private lateinit var btnImHere: Button
    private lateinit var btnWakeOther: Button
    private lateinit var timePicker: TimePicker
    private lateinit var etGoals: EditText
    private lateinit var tvGoalsStatus: TextView
    private lateinit var tvPartnerGoalsLabel: TextView
    private lateinit var tvPartnerGoals: TextView
    private lateinit var tvAlarmActiveInfo: TextView
    private lateinit var tvWakeCheckTimer: TextView
    private lateinit var tvManualWakeInfo: TextView

    private val prefs by lazy { getSharedPreferences("alarmpair_v3", MODE_PRIVATE) }
    private lateinit var ws: WsManager
    private val handler = Handler(Looper.getMainLooper())

    private var serverConnected = false
    private var pairValidated = false
    private var partnerConnected = false
    private var pairId = ""
    private var token = ""
    private var role = ""
    private var serverOffset = 0L
    private var status = "IDLE"
    private var alarmTarget = 0L
    private var alarmActive = false
    private var stoppedMe = false
    private var stoppedPartner = false
    private var myGoals = false
    private var partnerGoals = false
    private var myHere = false
    private var partnerHere = false
    private var wakeDeadline = 0L

    private val tick = object : Runnable {
        override fun run() {
            tvClock.text = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
            if (alarmTarget > 0) tvCountdown.text = formatMs(alarmTarget - (System.currentTimeMillis() + serverOffset))
            if (status == "WAKE_CHECK_ACTIVE" && wakeDeadline > 0) {
                tvWakeCheckTimer.text = "Confirm in ${max(0, (wakeDeadline - (System.currentTimeMillis() + serverOffset)) / 1000)}s or we'll ask again"
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        bind(); requestPermissions(); setupButtons()
        loadCredentials()
        Log.d(TAG, "startup: savedCredentials=${hasCredentials()} pairId=${pairId.isNotBlank()} token=${token.isNotBlank()} role=$role")
        updatePanels()
        ws = WsManager(BuildConfig.SERVER_URL, ::handle, {
            serverConnected = true
            Log.d(TAG, "server connected; awaiting authentication/validation")
            tvStatus.text = "SERVER CONNECTED"
            updatePanels()
        }, {
            serverConnected = false
            Log.d(TAG, "server disconnected; preserving pairing credentials")
            tvStatus.text = "SERVER DISCONNECTED — reconnecting…"
            updatePanels()
        })
        ws.setDeviceToken(token)
        ws.connect()
        handler.post(tick)
    }

    private fun loadCredentials() {
        pairId = prefs.getString("pairId", "") ?: ""
        token = prefs.getString("deviceToken", "") ?: ""
        role = prefs.getString("role", "") ?: ""
        alarmTarget = prefs.getLong("alarmTargetAt", 0L)
        pairValidated = false
    }

    private fun hasCredentials() = pairId.isNotBlank() && token.isNotBlank() && (role == "A" || role == "B")

    private fun persistCredentials(id: String, deviceToken: String, deviceRole: String) {
        pairId = id; token = deviceToken; role = deviceRole; pairValidated = true
        prefs.edit().putString("pairId", id).putString("deviceToken", deviceToken).putString("role", deviceRole).apply()
        ws.setDeviceToken(deviceToken)
        Log.d(TAG, "pair credentials persisted: pairId=${id.isNotBlank()} token=${deviceToken.isNotBlank()} role=$deviceRole")
    }

    private fun clearCredentials() {
        Log.w(TAG, "clearing rejected pairing credentials")
        pairId = ""; token = ""; role = ""; pairValidated = false; partnerConnected = false
        prefs.edit().remove("pairId").remove("deviceToken").remove("role").remove("invite").apply()
        ws.setDeviceToken("")
        updatePanels()
    }

    private fun bind() {
        tvStatus = findViewById(R.id.tvStatus); tvCountdown = findViewById(R.id.tvCountdown); tvClock = findViewById(R.id.tvClock)
        panelPairing = findViewById(R.id.panelPairing); panelAlarm = findViewById(R.id.panelAlarm); panelAlarmActive = findViewById(R.id.panelAlarmActive)
        panelGoals = findViewById(R.id.panelGoals); panelWakeCheck = findViewById(R.id.panelWakeCheck); panelWakeOther = findViewById(R.id.panelWakeOther)
        btnCreatePair = findViewById(R.id.btnCreatePair); btnJoinPair = findViewById(R.id.btnJoinPair); btnSetAlarm = findViewById(R.id.btnSetAlarm); btnStop = findViewById(R.id.btnStop)
        btnSubmitGoals = findViewById(R.id.btnSubmitGoals); btnImHere = findViewById(R.id.btnImHere); btnWakeOther = findViewById(R.id.btnWakeOther)
        timePicker = findViewById(R.id.timePicker); etGoals = findViewById(R.id.etGoals); tvGoalsStatus = findViewById(R.id.tvGoalsStatus)
        tvPartnerGoalsLabel = findViewById(R.id.tvPartnerGoalsLabel); tvPartnerGoals = findViewById(R.id.tvPartnerGoals); tvAlarmActiveInfo = findViewById(R.id.tvAlarmActiveInfo)
        tvWakeCheckTimer = findViewById(R.id.tvWakeCheckTimer); tvManualWakeInfo = findViewById(R.id.tvManualWakeInfo)
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(this, arrayOf("android.permission.POST_NOTIFICATIONS"), 42)
        if (Build.VERSION.SDK_INT >= 31) try {
            val am = getSystemService(AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        } catch (_: Exception) { }
    }

    private fun setupButtons() {
        btnCreatePair.setOnClickListener { ws.send(JSONObject().put("type", "createPair")) }
        btnJoinPair.setOnClickListener { joinDialog() }
        btnSetAlarm.setOnClickListener { setAlarm() }; btnStop.setOnClickListener { stop() }
        btnSubmitGoals.setOnClickListener { submitGoals() }; btnImHere.setOnClickListener { confirmHere() }
        btnWakeOther.setOnClickListener { if (pairValidated) ws.send(JSONObject().put("type", "manualWake")) }
    }

    private fun handle(m: JSONObject) {
        val type = m.optString("type")
        Log.d(TAG, "server message: type=$type")
        when (type) {
            "paired" -> {
                val id = m.optString("pairId")
                val newToken = m.optString("deviceToken")
                val newRole = m.optString("role")
                if (id.isBlank() || newToken.isBlank() || (newRole != "A" && newRole != "B")) { Log.e(TAG, "invalid paired response"); return }
                persistCredentials(id, newToken, newRole)
                val invite = m.optString("invite")
                if (invite.isNotBlank()) {
                    prefs.edit().putString("invite", invite).apply()
                    AlertDialog.Builder(this).setTitle("🔐 Private Pair Created").setMessage("Send this invite code to the other phone:\n\n$invite\n\nWaiting for the other phone…").setPositiveButton("Copy") { _, _ ->
                        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("invite", invite))
                    }.show()
                }
                applyState(m.optJSONObject("state")); updatePanels()
            }
            "resumed" -> {
                val id = m.optString("pairId", pairId); val newToken = m.optString("deviceToken", token); val newRole = m.optString("role", role)
                if (id.isBlank() || newToken.isBlank() || (newRole != "A" && newRole != "B")) { clearCredentials(); return }
                persistCredentials(id, newToken, newRole); Log.d(TAG, "authentication and pairing validation succeeded")
                applyState(m.optJSONObject("state")); updatePanels()
            }
            "pairStatus" -> { partnerConnected = m.optBoolean("connected", false); applyState(m.optJSONObject("state")); updatePanels() }
            "sync", "state" -> { applyState(m.optJSONObject("state")); updatePanels() }
            "alarm", "alarmRinging" -> { m.optJSONObject("alarm")?.let { applyAlarm(it) }; applyState(m.optJSONObject("state")) }
            "goals" -> if (m.optString("from") != role) { partnerGoals = true; tvPartnerGoalsLabel.visibility = View.VISIBLE; tvPartnerGoals.visibility = View.VISIBLE; tvPartnerGoals.text = m.optString("text"); updateGoalText() }
            "wakeCheck" -> { wakeDeadline = m.optLong("deadlineAt"); status = "WAKE_CHECK_ACTIVE"; myHere = false; showWake() }
            "hereConfirmed" -> if (m.optString("from") != role) { partnerHere = true; updateWake() }
            "sessionComplete" -> { status = "COMPLETED"; wakeDeadline = 0; panelWakeCheck.visibility = View.GONE; panelWakeOther.visibility = View.GONE; updatePanels() }
            "manualWake" -> { tvManualWakeInfo.text = "⚠️ ${m.optString("triggeredBy", "Your partner")} is waking you up!"; tvManualWakeInfo.visibility = View.VISIBLE; sendBroadcast(Intent(this, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_MANUAL_WAKE).putExtra("triggeredBy", m.optString("triggeredBy"))) }
            "manualWakeSent" -> { tvManualWakeInfo.text = if (m.optBoolean("queued")) "Wake queued — partner will receive it when connected." else "Wake signal sent to your partner!"; tvManualWakeInfo.visibility = View.VISIBLE }
            "error" -> {
                val message = m.optString("message", "Server rejected the request")
                Log.w(TAG, "server authentication/state error: $message")
                if (message.contains("Saved pairing", true) || message.contains("Authenticate", true) || message.contains("recognized", true)) { clearCredentials(); tvStatus.text = "PAIRING REQUIRED" }
                else Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                updatePanels()
            }
        }
    }

    private fun applyState(s: JSONObject?) {
        if (s == null || !pairValidated) return
        partnerConnected = s.optBoolean("connected", partnerConnected); status = s.optString("status", status); wakeDeadline = s.optLong("wakeCheckDeadlineAt", wakeDeadline)
        val goals = s.optJSONObject("goals"); if (goals != null) { myGoals = goals.optString(role, null) != null; val other = if (role == "A") "B" else "A"; partnerGoals = goals.optString(other, null) != null }
        val responses = s.optJSONObject("wakeResponses"); if (responses != null) { myHere = responses.optBoolean(role, false); partnerHere = responses.optBoolean(if (role == "A") "B" else "A", false) }
        s.optJSONObject("alarm")?.let { applyAlarm(it) }
        when (status) { "AWAITING_GOALS", "GOALS", "WAITING_5_MIN" -> showGoals(); "WAKE_CHECK_ACTIVE" -> showWake(); "COMPLETED" -> panelWakeCheck.visibility = View.GONE }
        updateGoalText(); updateWake(); updatePanels()
    }

    private fun applyAlarm(a: JSONObject) { alarmTarget = a.optLong("targetAt", 0); alarmActive = a.optBoolean("active", false); stoppedMe = if (role == "A") a.optBoolean("stoppedA") else a.optBoolean("stoppedB"); stoppedPartner = if (role == "A") a.optBoolean("stoppedB") else a.optBoolean("stoppedA"); prefs.edit().putLong("alarmTargetAt", alarmTarget).apply(); if (alarmActive) { scheduleLocalAlarm(max(System.currentTimeMillis() + 1000, alarmTarget - serverOffset)); btnStop.isEnabled = !stoppedMe } else { cancelLocalAlarm(); btnStop.isEnabled = false }; updatePanels() }

    private fun joinDialog() { val input = EditText(this); input.hint = "Paste invite code"; AlertDialog.Builder(this).setTitle("Join Private Pair").setView(input).setPositiveButton("Join") { _, _ -> ws.send(JSONObject().put("type", "joinPair").put("invite", input.text.toString().trim())) }.setNegativeButton("Cancel", null).show() }
    private fun setAlarm() { if (!pairValidated || !serverConnected || !partnerConnected) { Toast.makeText(this, "Both devices must be paired and connected first.", Toast.LENGTH_SHORT).show(); return }; val c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, timePicker.hour); c.set(Calendar.MINUTE, timePicker.minute); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DATE, 1); ws.send(JSONObject().put("type", "setAlarm").put("targetAt", c.timeInMillis + serverOffset)) }
    private fun stop() { if (!pairValidated) return; stoppedMe = true; btnStop.isEnabled = false; cancelLocalAlarm(); ws.send(JSONObject().put("type", "stop")) }
    private fun showGoals() { panelAlarmActive.visibility = View.GONE; panelGoals.visibility = View.VISIBLE; panelWakeOther.visibility = View.VISIBLE }
    private fun submitGoals() { val text = etGoals.text.toString().trim(); if (text.isBlank()) { Toast.makeText(this, "Write at least one goal!", Toast.LENGTH_SHORT).show(); return }; myGoals = true; ws.send(JSONObject().put("type", "submitGoals").put("text", text)); updateGoalText() }
    private fun updateGoalText() { tvGoalsStatus.text = "You: ${if (myGoals) "✓" else "⏳"}  |  Partner: ${if (partnerGoals) "✓" else "⏳"}" }
    private fun showWake() { panelGoals.visibility = View.GONE; panelWakeCheck.visibility = View.VISIBLE; panelWakeOther.visibility = View.VISIBLE; updateWake() }
    private fun updateWake() { if (status == "WAKE_CHECK_ACTIVE") { btnImHere.isEnabled = !myHere; tvWakeCheckTimer.text = if (myHere) "You confirmed. Waiting for partner…" else "Confirm in 30s or we'll ask again" } }
    private fun confirmHere() { if (!myHere && pairValidated) { myHere = true; ws.send(JSONObject().put("type", "confirmHere")); updateWake() } }

    private fun scheduleLocalAlarm(at: Long) { val am = getSystemService(AlarmManager::class.java); val pi = PendingIntent.getBroadcast(this, AlarmReceiver.NOTIF_ID_ALARM, Intent(this, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_ALARM), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE); try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) } catch (_: Exception) { am.setExact(AlarmManager.RTC_WAKEUP, at, pi) } }
    private fun cancelLocalAlarm() { val am = getSystemService(AlarmManager::class.java); val pi = PendingIntent.getBroadcast(this, AlarmReceiver.NOTIF_ID_ALARM, Intent(this, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_ALARM), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE); am.cancel(pi) }
    private fun updatePanels() { val ready = pairValidated && hasCredentials(); panelPairing.visibility = if (ready) View.GONE else View.VISIBLE; panelAlarm.visibility = if (ready && !alarmActive && status != "COMPLETED") View.VISIBLE else View.GONE; panelAlarmActive.visibility = if (ready && alarmActive) View.VISIBLE else View.GONE; tvStatus.text = when { !serverConnected -> "SERVER DISCONNECTED"; !ready -> "PAIRING REQUIRED"; partnerConnected -> "PAIRED — PARTNER CONNECTED"; else -> "PAIRED — PARTNER DISCONNECTED" }; tvAlarmActiveInfo.text = if (stoppedMe) "⏳ Waiting for your partner…" else "🔔 ALARM RINGING / SCHEDULED" }
    private fun formatMs(ms: Long): String {
    val t = max(0, ms / 1000)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60

    return if (h > 0) {
        "%02d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
    }
    override fun onDestroy() { handler.removeCallbacks(tick); ws.disconnect(); super.onDestroy() }
}
