package com.alarmpair.app

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
    private lateinit var tvStatus: TextView; private lateinit var tvCountdown: TextView; private lateinit var tvClock: TextView
    private lateinit var panelPairing: LinearLayout; private lateinit var panelAlarm: LinearLayout; private lateinit var panelAlarmActive: LinearLayout
    private lateinit var panelGoals: LinearLayout; private lateinit var panelWakeCheck: LinearLayout; private lateinit var panelWakeOther: LinearLayout
    private lateinit var btnCreatePair: Button; private lateinit var btnJoinPair: Button; private lateinit var btnSetAlarm: Button; private lateinit var btnStop: Button
    private lateinit var btnSubmitGoals: Button; private lateinit var btnImHere: Button; private lateinit var btnWakeOther: Button
    private lateinit var timePicker: TimePicker; private lateinit var etGoals: EditText; private lateinit var tvGoalsStatus: TextView
    private lateinit var tvPartnerGoalsLabel: TextView; private lateinit var tvPartnerGoals: TextView; private lateinit var tvAlarmActiveInfo: TextView
    private lateinit var tvWakeCheckTimer: TextView; private lateinit var tvManualWakeInfo: TextView

    private val prefs by lazy { getSharedPreferences("alarmpair_v3", MODE_PRIVATE) }
    private lateinit var ws: WsManager
    private val handler = Handler(Looper.getMainLooper())
    private var role=""; private var token=""; private var serverOffset=0L; private var connected=false
    private var status="IDLE"; private var alarmTarget=0L; private var alarmActive=false; private var stoppedMe=false; private var stoppedPartner=false
    private var myGoals=false; private var partnerGoals=false; private var myHere=false; private var partnerHere=false; private var wakeDeadline=0L

    private val tick = object: Runnable { override fun run(){
        tvClock.text=SimpleDateFormat("hh:mm:ss a",Locale.getDefault()).format(Date())
        if(alarmTarget>0){ tvCountdown.text=formatMs(alarmTarget-(System.currentTimeMillis()+serverOffset)) }
        if(status=="WAKE_CHECK_ACTIVE" && wakeDeadline>0){ tvWakeCheckTimer.text="Confirm in ${max(0,(wakeDeadline-(System.currentTimeMillis()+serverOffset))/1000)}s or we'll ask again" }
        handler.postDelayed(this,500)
    }}

    override fun onCreate(b:Bundle?){ super.onCreate(b); setContentView(R.layout.activity_main); bind(); requestPermissions(); setupButtons()
        token=prefs.getString("deviceToken","")?:""; role=prefs.getString("role","")?:""; alarmTarget=prefs.getLong("alarmTargetAt",0L)
        ws=WsManager(BuildConfig.SERVER_URL,::handle,{tvStatus.text="🟢 Connected to server"},{tvStatus.text="🔴 Connection lost — reconnecting…"})
        ws.setDeviceToken(token); ws.connect(); handler.post(tick); updatePanels()
    }
    private fun bind(){
        tvStatus=findViewById(R.id.tvStatus); tvCountdown=findViewById(R.id.tvCountdown); tvClock=findViewById(R.id.tvClock)
        panelPairing=findViewById(R.id.panelPairing); panelAlarm=findViewById(R.id.panelAlarm); panelAlarmActive=findViewById(R.id.panelAlarmActive); panelGoals=findViewById(R.id.panelGoals); panelWakeCheck=findViewById(R.id.panelWakeCheck); panelWakeOther=findViewById(R.id.panelWakeOther)
        btnCreatePair=findViewById(R.id.btnCreatePair); btnJoinPair=findViewById(R.id.btnJoinPair); btnSetAlarm=findViewById(R.id.btnSetAlarm); btnStop=findViewById(R.id.btnStop); btnSubmitGoals=findViewById(R.id.btnSubmitGoals); btnImHere=findViewById(R.id.btnImHere); btnWakeOther=findViewById(R.id.btnWakeOther)
        timePicker=findViewById(R.id.timePicker); etGoals=findViewById(R.id.etGoals); tvGoalsStatus=findViewById(R.id.tvGoalsStatus); tvPartnerGoalsLabel=findViewById(R.id.tvPartnerGoalsLabel); tvPartnerGoals=findViewById(R.id.tvPartnerGoals); tvAlarmActiveInfo=findViewById(R.id.tvAlarmActiveInfo); tvWakeCheckTimer=findViewById(R.id.tvWakeCheckTimer); tvManualWakeInfo=findViewById(R.id.tvManualWakeInfo)
    }
    private fun requestPermissions(){ if(Build.VERSION.SDK_INT>=33) ActivityCompat.requestPermissions(this,arrayOf("android.permission.POST_NOTIFICATIONS"),42); if(Build.VERSION.SDK_INT>=31) try{val am=getSystemService(AlarmManager::class.java); if(!am.canScheduleExactAlarms()) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))}catch(_:Exception){} }
    private fun setupButtons(){ btnCreatePair.setOnClickListener{ws.send(JSONObject().put("type","createPair"))}; btnJoinPair.setOnClickListener{joinDialog()}; btnSetAlarm.setOnClickListener{setAlarm()}; btnStop.setOnClickListener{stop()}; btnSubmitGoals.setOnClickListener{submitGoals()}; btnImHere.setOnClickListener{confirmHere()}; btnWakeOther.setOnClickListener{ws.send(JSONObject().put("type","manualWake"))} }

    private fun handle(m:JSONObject){ when(m.optString("type")){
        "paired"->{role=m.optString("role"); token=m.optString("deviceToken"); prefs.edit().putString("role",role).putString("deviceToken",token).apply(); ws.setDeviceToken(token); val invite=m.optString("invite"); if(invite.isNotBlank()) AlertDialog.Builder(this).setTitle("🔐 Private Pair Created").setMessage("Send this one-time invite code:\n\n$invite").setPositiveButton("Copy") {_,_->(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("invite",invite))}.show(); applyState(m.optJSONObject("state"))}
        "resumed"->{role=m.optString("role",role); applyState(m.optJSONObject("state"))}
        "sync"->{serverOffset=m.optLong("serverNow",System.currentTimeMillis())-System.currentTimeMillis(); applyState(m.optJSONObject("state"))}
        "state"->applyState(m.optJSONObject("state"))
        "pairStatus"->{connected=m.optBoolean("connected",false); applyState(m.optJSONObject("state"))}
        "alarm","alarmRinging"->{m.optJSONObject("alarm")?.let{applyAlarm(it)}; applyState(m.optJSONObject("state"))}
        "goals"->{if(m.optString("from")!=role){partnerGoals=true; tvPartnerGoalsLabel.visibility=View.VISIBLE; tvPartnerGoals.visibility=View.VISIBLE; tvPartnerGoals.text=m.optString("text"); updateGoalText()}}
        "wakeCheck"->{wakeDeadline=m.optLong("deadlineAt",0); status="WAKE_CHECK_ACTIVE"; myHere=false; showWake()}
        "hereConfirmed"->{if(m.optString("from")!=role){partnerHere=true; updateWake()}}
        "sessionComplete"->{status="COMPLETED"; wakeDeadline=0; panelWakeCheck.visibility=View.GONE; panelWakeOther.visibility=View.GONE; tvStatus.text="✅ Session complete!"; tvCountdown.text="✅"}
        "manualWake"->{val by=m.optString("triggeredBy","Your partner"); tvManualWakeInfo.text="⚠️ $by is waking you up!"; tvManualWakeInfo.visibility=View.VISIBLE; sendBroadcast(Intent(this,AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_MANUAL_WAKE).putExtra("triggeredBy",by))}
        "manualWakeSent"->{tvManualWakeInfo.text=if(m.optBoolean("queued"))"Wake queued — partner will receive it when connected." else "Wake signal sent to your partner!"; tvManualWakeInfo.visibility=View.VISIBLE}
        "error"->Toast.makeText(this,m.optString("message"),Toast.LENGTH_LONG).show()
    }}
    private fun applyState(s:JSONObject?){ if(s==null)return; connected=s.optBoolean("connected",connected); status=s.optString("status",status); wakeDeadline=s.optLong("wakeCheckDeadlineAt",wakeDeadline)
        val goals=s.optJSONObject("goals"); if(goals!=null){myGoals=goals.optString(role,null)!=null; val other=if(role=="A")"B" else "A"; partnerGoals=goals.optString(other,null)!=null}
        val wr=s.optJSONObject("wakeResponses"); if(wr!=null){myHere=wr.optBoolean(role,false); partnerHere=wr.optBoolean(if(role=="A")"B" else "A",false)}
        s.optJSONObject("alarm")?.let{applyAlarm(it)}
        when(status){"AWAITING_GOALS","GOALS","WAITING_5_MIN"->showGoals();"WAKE_CHECK_ACTIVE"->showWake();"COMPLETED"->panelWakeCheck.visibility=View.GONE}
        updateGoalText(); updateWake(); updatePanels()
    }
    private fun applyAlarm(a:JSONObject){alarmTarget=a.optLong("targetAt",0); alarmActive=a.optBoolean("active",false); stoppedMe=if(role=="A")a.optBoolean("stoppedA") else a.optBoolean("stoppedB"); stoppedPartner=if(role=="A")a.optBoolean("stoppedB") else a.optBoolean("stoppedA"); prefs.edit().putLong("alarmTargetAt",alarmTarget).apply(); if(alarmActive){scheduleLocalAlarm(max(System.currentTimeMillis()+1000,alarmTarget-serverOffset)); btnStop.isEnabled=!stoppedMe}else{cancelLocalAlarm(); btnStop.isEnabled=false}; updatePanels() }
    private fun joinDialog(){val e=EditText(this); e.hint="Paste one-time invite"; AlertDialog.Builder(this).setTitle("Join Private Pair").setView(e).setPositiveButton("Join"){_,_->ws.send(JSONObject().put("type","joinPair").put("invite",e.text.toString().trim()))}.setNegativeButton("Cancel",null).show()}
    private fun setAlarm(){if(role.isBlank()||!connected){Toast.makeText(this,"Pair both devices first.",Toast.LENGTH_SHORT).show();return}; val c=Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY,timePicker.hour); c.set(Calendar.MINUTE,timePicker.minute); c.set(Calendar.SECOND,0); c.set(Calendar.MILLISECOND,0); if(c.timeInMillis<=System.currentTimeMillis())c.add(Calendar.DATE,1); ws.send(JSONObject().put("type","setAlarm").put("targetAt",c.timeInMillis+serverOffset))}
    private fun stop(){stoppedMe=true; btnStop.isEnabled=false; cancelLocalAlarm(); ws.send(JSONObject().put("type","stop")); if(!stoppedPartner)tvStatus.text="⏳ Waiting for partner…"}
    private fun showGoals(){panelAlarmActive.visibility=View.GONE; panelGoals.visibility=View.VISIBLE; panelWakeOther.visibility=View.VISIBLE; tvStatus.text="🎯 Both awake! Enter your goals."}
    private fun submitGoals(){val t=etGoals.text.toString().trim(); if(t.isBlank()){Toast.makeText(this,"Write at least one goal!",Toast.LENGTH_SHORT).show();return}; myGoals=true; btnSubmitGoals.isEnabled=false; ws.send(JSONObject().put("type","submitGoals").put("text",t)); updateGoalText()}
    private fun updateGoalText(){tvGoalsStatus.text="You: ${if(myGoals)"✓" else "⏳"}  |  Partner: ${if(partnerGoals)"✓" else "⏳"}"}
    private fun showWake(){panelGoals.visibility=View.GONE; panelWakeCheck.visibility=View.VISIBLE; panelWakeOther.visibility=View.VISIBLE; btnImHere.isEnabled=!myHere; btnImHere.text=if(myHere)"Confirmed ✓" else "I'M HERE ✓"; updateWake()}
    private fun updateWake(){if(status=="WAKE_CHECK_ACTIVE"){panelWakeCheck.visibility=View.VISIBLE; tvWakeCheckTimer.text=if(myHere)"You confirmed. Waiting for partner…" else "Confirm in 30s or we'll ask again"; if(myHere&&partnerHere)panelWakeCheck.visibility=View.GONE}}
    private fun confirmHere(){if(myHere)return; myHere=true; btnImHere.isEnabled=false; ws.send(JSONObject().put("type","confirmHere")); updateWake()}
    private fun scheduleLocalAlarm(at:Long){val am=getSystemService(AlarmManager::class.java); val pi=PendingIntent.getBroadcast(this,AlarmReceiver.NOTIF_ID_ALARM,Intent(this,AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_ALARM),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE); try{if(Build.VERSION.SDK_INT>=23)am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi) else am.setExact(AlarmManager.RTC_WAKEUP,at,pi)}catch(_:SecurityException){am.set(AlarmManager.RTC_WAKEUP,at,pi)}}
    private fun cancelLocalAlarm(){val am=getSystemService(AlarmManager::class.java); val pi=PendingIntent.getBroadcast(this,AlarmReceiver.NOTIF_ID_ALARM,Intent(this,AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_ALARM),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE); am.cancel(pi)}
    private fun updatePanels(){val has=token.isNotBlank(); panelPairing.visibility=if(has)View.GONE else View.VISIBLE; val alarm=alarmActive; panelAlarm.visibility=if(has&&!alarm&&status!="COMPLETED")View.VISIBLE else View.GONE; panelAlarmActive.visibility=if(alarm)View.VISIBLE else View.GONE; tvAlarmActiveInfo.text=if(stoppedMe)"⏳ Waiting for your partner…" else "🔔 ALARM RINGING / SCHEDULED"}
    private fun formatMs(ms:Long):String{val t=max(0,ms/1000); val h=t/3600; val m=(t%3600)/60; val s=t%60; return if(h>0)"%02d:%02d:%02d".format(h,m,s) else "%02d:%02d".format(m,s)}
    override fun onDestroy(){handler.removeCallbacks(tick); ws.disconnect(); super.onDestroy()}
}
