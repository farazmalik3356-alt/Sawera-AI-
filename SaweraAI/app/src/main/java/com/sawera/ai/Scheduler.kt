package com.sawera.ai

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.util.Calendar

object Scheduler {
    /** time = "HH:mm". Fires today if still ahead, else tomorrow. */
    fun schedule(ctx: Context, time: String, task: String): String {
        val (h, m) = time.split(":").map { it.toInt() }
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val i = Intent(ctx, TaskAlarmReceiver::class.java).putExtra("task", task)
        val pi = PendingIntent.getBroadcast(ctx, task.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
        return "%02d:%02d".format(h, m)
    }
}

/** At the scheduled time, Sawera notifies; tapping it opens the app, which then runs the task. */
class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val task = intent.getStringExtra("task") ?: return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("sawera", "Sawera", NotificationManager.IMPORTANCE_HIGH))
        val open = Intent(ctx, MainActivity::class.java).putExtra("run_task", task).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, task.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(task.hashCode(), NotificationCompat.Builder(ctx, "sawera")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Sawera AI")
            .setContentText("Faraz Boss, waqt ho gaya: $task")
            .setContentIntent(pi).setAutoCancel(true).build())
    }
}
