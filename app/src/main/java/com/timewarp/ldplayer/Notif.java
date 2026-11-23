package com.timewarp.ldplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Notificación de primer plano compartida por los dos servicios. */
final class Notif {

    private static final String CHANNEL_ID = "timewarp";

    private Notif() {
    }

    static Notification build(Context c, String title, String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "TimeWarp", NotificationManager.IMPORTANCE_LOW));
            }
        }
        Intent open = new Intent(c, MainActivity.class);
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL_ID)
                : new Notification.Builder(c);
        return b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
