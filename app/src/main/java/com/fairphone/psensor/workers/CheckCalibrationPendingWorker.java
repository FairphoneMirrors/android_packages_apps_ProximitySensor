package com.fairphone.psensor.workers;

import android.content.Context;

import com.fairphone.psensor.Utils;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class CheckCalibrationPendingWorker extends Worker {

    public CheckCalibrationPendingWorker(
            @NonNull Context context,
            @NonNull WorkerParameters workerParams
    ) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Utils.handleCheckCalibrationPending(getApplicationContext());
        return Result.success();
    }
}
