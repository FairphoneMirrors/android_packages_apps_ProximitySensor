package com.fairphone.psensor.workers;

import android.content.Context;

import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

public class WorkerUtils {

    private static final int CHECK_CALIBRATION_DELAY_MS = 1000 /*seconds*/ * 60 /* minutes */ * 60 /* hours */ * 3;

    /**
     * Grace period before firing a reminder (in milliseconds): 1 day.
     */
    private static final long REMINDER_GRACE_PERIOD_MS = 1000 * 60 * 60 * 24;

    public static void setReminder(@NonNull Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ProximitySensorNotificationWorker.class)
                .setInitialDelay(REMINDER_GRACE_PERIOD_MS, TimeUnit.MILLISECONDS)
                .build();
        WorkManager.getInstance(context).enqueue(request);
    }

    public static void setCheckCalibrationPendingAlarm(@NonNull Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CheckCalibrationPendingWorker.class)
                .setInitialDelay(CHECK_CALIBRATION_DELAY_MS, TimeUnit.MILLISECONDS)
                .build();
        WorkManager.getInstance(context).enqueue(request);
    }
}
