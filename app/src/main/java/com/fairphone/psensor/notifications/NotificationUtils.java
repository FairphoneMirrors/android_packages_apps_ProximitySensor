package com.fairphone.psensor.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;

import com.fairphone.psensor.R;
import com.fairphone.psensor.UpdateFinalizerActivityFromNotification;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

public class NotificationUtils {

    private static final int NOTIFICATION_ID_PLEASE_CALIBRATE = 1;

    public static void showPleaseCalibrateNotification(@NonNull Context context) {
        String channelId = context.getString(R.string.notification_channel_id);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                UpdateFinalizerActivityFromNotification.getIntent(context),
                PendingIntent.FLAG_ONE_SHOT);

        NotificationCompat.Builder  builder = new NotificationCompat.Builder(context, channelId)
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

    public static void createNotificationChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        String channelId = context.getString(R.string.notification_channel_id);
        String name = context.getString(R.string.notification_channel_name);
        NotificationChannel channel = new NotificationChannel(
                channelId,
                name,
                NotificationManager.IMPORTANCE_HIGH
        );
        getNotificationManager(context).createNotificationChannel(channel);
    }

    static NotificationManager getNotificationManager(@NonNull Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }
}
