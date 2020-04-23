package com.fairphone.psensor;

import android.content.Context;

import com.fairphone.psensor.helpers.CalibrationStatusHelper;
import com.fairphone.psensor.notifications.NotificationUtils;
import com.fairphone.psensor.notifications.ReceiverModuleChangedNotification;
import com.fairphone.psensor.workers.WorkerUtils;

import androidx.annotation.NonNull;

public class Utils {

    /**
     * Handle the receiver module changed action.
     */
    public static void handleActionReceiverModuleChanged(@NonNull Context context) {
        CalibrationStatusHelper.setCalibrationNeededAfterReceiverModuleChanged(context);
        ReceiverModuleChangedNotification.show(context);
    }

    public static void handleCheckCalibrationPending(@NonNull Context context) {
        if (!UpdateFinalizerActivityFromNotification.isNotShowAnymore(context)
                && CalibrationStatusHelper.hasToBeCalibrated(context)
        ) {
            NotificationUtils.showPleaseCalibrateNotification(context);
            WorkerUtils.setCheckCalibrationPendingAlarm(context);
        }
        else {
            NotificationUtils.clearPleaseCalibrateNotification(context);
        }
    }

    /**
     * Handle the boot completed action.
     */
    public static void handleBootComplete(@NonNull Context context) {
        if (CalibrationStatusHelper.isCalibrationNeededAfterReceiverModuleChanged(context)) {
            ReceiverModuleChangedNotification.show(context);
        }
    }
}
