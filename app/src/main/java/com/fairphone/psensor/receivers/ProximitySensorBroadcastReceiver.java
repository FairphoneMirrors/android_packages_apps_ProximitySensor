package com.fairphone.psensor.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.fairphone.psensor.Utils;

public class ProximitySensorBroadcastReceiver extends BroadcastReceiver {

    /**
     * Action to handle a change of receiver module by scheduling a new calibration.
     */
    private static final String ACTION_HANDLE_RECEIVER_MODULE_CHANGED =
            "com.fairphone.psensor.receivers.ACTION_HANDLE_RECEIVER_MODULE_CHANGED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_HANDLE_RECEIVER_MODULE_CHANGED.equals(intent.getAction())) {
            return;
        }
        Utils.handleActionReceiverModuleChanged(context);
    }
}
