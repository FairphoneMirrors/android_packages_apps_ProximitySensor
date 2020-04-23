package com.fairphone.psensor.workers;

import android.content.Context;

import com.fairphone.psensor.Utils;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class ProximitySensorNotificationWorker extends Worker {

    public ProximitySensorNotificationWorker(
            @NonNull Context context,
            @NonNull WorkerParameters workerParams
    ) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Utils.handleActionReceiverModuleChanged(getApplicationContext());
        return Result.success();
    }
}
