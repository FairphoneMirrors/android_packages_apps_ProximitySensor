package com.fairphone.psensor.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.fairphone.psensor.Utils;
import com.fairphone.psensor.helpers.CalibrationStatusHelper;

public class BootUpReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        Utils.handleCheckCalibrationPending(context);

        if (CalibrationStatusHelper.isCalibrationPending(context)) {
            CalibrationStatusHelper.setCalibrationCompleted(context);
        }

        Utils.handleBootComplete(context);
    }
}
