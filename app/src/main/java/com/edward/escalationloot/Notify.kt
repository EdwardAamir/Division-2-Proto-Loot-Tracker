package com.edward.escalationloot

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notify {
    private const val ID = "escalation_loot"

    fun show(c: Context, i18n: I18n, day: LootDay) {
        // POST_NOTIFICATIONS is runtime-granted on API 33+. Don't crash if denied.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(ID, i18n.channelName, NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val tap = PendingIntent.getActivity(
            c, 0,
            Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val summary = day.entries.joinToString(" · ") { it.lootEn }

        NotificationManagerCompat.from(c).notify(
            1001,
            NotificationCompat.Builder(c, ID)
                .setSmallIcon(R.drawable.ic_stat_loot)
                .setContentTitle(i18n.newLootTitle)
                .setContentText(i18n.newLootBody.format(day.date))
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
        )
    }
}
