package com.fairphone.psensor.notifications;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;

import com.fairphone.psensor.R;
import com.fairphone.psensor.UpdateFinalizerActivityFromNotification;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

public class NotificationUtils {

    private static final int NOTIFICATION_ID_PLEASE_CALIBRATE = 1;

    public static void showPleaseCalibrateNotification(@NonNull Context context) {
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                UpdateFinalizerActivityFromNotification.getIntent(context),
                PendingIntent.FLAG_ONE_SHOT);

        NotificationCompat.Builder  builder = new NotificationCompat.Builder(context)
                .setContentTitle(context.getString(R.string.NotificationTitle))
                .setStyle(new NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.NotificationText)))
                .setSmallIcon(R.drawable.ic_stat_action_info)
                .setContentIntent(pendingIntent)
                .setColor(context.getResources().getColor(R.color.theme_primary));
        getNotificationManager(context).notify(NOTIFICATION_ID_PLEASE_CALIBRATE, builder.build());
    }

    public static void clearPleaseCalibrateNotification(@NonNull Context context) {
        getNotificationManager(context).cancel(NOTIFICATION_ID_PLEASE_CALIBRATE);
    }

    private static NotificationManager getNotificationManager(@NonNull Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }
}
