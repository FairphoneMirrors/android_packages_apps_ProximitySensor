package com.fairphone.psensor;

import android.app.Activity;
import android.app.DialogFragment;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ViewFlipper;

import com.fairphone.psensor.CalibrationContract.CalibrationData;
import com.fairphone.psensor.fragments.IncompatibleDeviceDialog;
import com.fairphone.psensor.helper.ProximitySensorHelper;

import java.util.Locale;

/**
 * Activity to start the calibration process.<br>
 * <br>
 * The calibration steps are:
 * <ol>
 * <li>Ask for a blocked sensor and read the (blocked) value.</li>
 * <li>Ask for a non-blocked sensor and read the (non-blocked) value.</li>
 * <li>Compute a new calibration (near and far threshold as well as the offset compensation) and persist it into the
 * memory.</li>
 * </ol>
 * <br>
 * The offset compensation is -was- 0 out of factory and could cause issues because certain devices require a higher
 * compensation.<br>
 * <br>
 * The dynamic offset compensation is computed from the non-blocked value read at step 2.<br>
 * The rules are as follow:
 * <ol>
 * <li>The read value is reduced by approx. 32 (sensor units) for each offset compensation increment (from the
 * specification).</li>
 * <li>According to the vendor, the value read must be above 0 when non-blocked, so we use the offset value directly
 * lower than floor("value read"/32) to be on the safe side.</li>
 * <li>This also allows to take into account a dirty current state. The non-blocked value then belongs to [32;63] in
 * the current conditions.</li>
 * <li>If the value read is already 0, we lower the persisted offset by 2 to reach a similar non-blocked range than
 * above.</li>
 * <li>The proximity sensor offset compensation belongs to [{@link ProximitySensorConfiguration#MIN_OFFSET_COMPENSATION}, {@link ProximitySensorConfiguration#MAX_OFFSET_COMPENSATION}].</li>
 * </ol>
 */
public class CalibrationActivity extends Activity implements IncompatibleDeviceDialog.IncompatibleDeviceDialogListener {
    private static final String TAG = CalibrationActivity.class.getSimpleName();

    /* Calibration step status */
    private static final int STEP_CURRENT = 0;
    private static final int STEP_IN_PROGRESS = 1;
    private static final int STEP_ERROR = 2;
    private static final int STEP_OK = 3;

    /**
     * Value to compute the near threshold from the blocked value (in sensor units).
     */
    public static final int NEAR_THRESHOLD_FROM_BLOCKED_VALUE = 30;
    /**
     * Value to compute the far threshold from the near threshold (in sensor units).
     */
    public static final int FAR_THRESHOLD_FROM_NEAR_THRESHOLD = 30;
    /**
     * Minimal accepted value for the blocked value (in sensor units).
     */
    public static final int BLOCKED_MINIMAL_VALUE = 235;
    /**
     * Maximal accepted value for the non-blocked value in relation to the read blocked value (in sensor units).
     */
    public static final int NON_BLOCKED_MAXIMAL_VALUE_FROM_BLOCKED_VALUE = 5;
    /**
     * Delay to emulate a long calibration (in ms).
     */
    public static final int CALIBRATION_DELAY_MS = 3000;

    private ProximitySensorConfiguration mPersistedConfiguration;
    private ProximitySensorConfiguration mCalibratedConfiguration;

    private int mBlockedValue;
    private int mNonBlockedValue;

    private Handler mHandler;

    private ViewFlipper mFlipper;
    private View mViewStep1;
    private View mViewStep2;
    private View mViewStep3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mHandler = new Handler();

        setContentView(R.layout.activity_calibration);

        if (!ProximitySensorHelper.canReadProximitySensorValue()) {
            Log.w(TAG, "Proximity sensor value not read-able, aborting.");

            showIncompatibleDeviceDialog();
        } else if (!ProximitySensorConfiguration.canReadFromAndPersistToMemory()) {
            Log.w(TAG, "Proximity sensor configuration not accessible (R/W), aborting.");

            showIncompatibleDeviceDialog();
        } else {
            init();
        }
    }

    private void init() {
        mFlipper = (ViewFlipper) findViewById(R.id.viewFlipper);
        mFlipper.setInAnimation(this, R.anim.slide_in_from_left);
        mFlipper.setOutAnimation(this, R.anim.slide_out_to_right);

        final LayoutInflater inflater = LayoutInflater.from(this);
        mViewStep1 = inflater.inflate(R.layout.view_calibration_step, mFlipper, false);
        mViewStep2 = inflater.inflate(R.layout.view_calibration_step, mFlipper, false);
        mViewStep3 = inflater.inflate(R.layout.view_calibration_step, mFlipper, false);

        mFlipper.addView(mViewStep1);
        mFlipper.addView(mViewStep2);
        mFlipper.addView(mViewStep3);

        reset();
    }

    private void reset() {
        mPersistedConfiguration = ProximitySensorConfiguration.readFromMemory();
        mCalibratedConfiguration = new ProximitySensorConfiguration();

        updateCalibrationStepView(mViewStep1, STEP_CURRENT, R.string.step_1, R.string.msg_block, -1, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doReadBlockedValue();
            }
        }, R.string.next);

        updateCalibrationStepView(mViewStep2, STEP_CURRENT, R.string.step_2, R.string.msg_unblock, -1, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doReadNonBlockedValue();
            }
        }, R.string.next);

        updateCalibrationStepView(mViewStep3, STEP_CURRENT, R.string.step_3, R.string.msg_cal, -1, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
                powerManager.reboot(null);
            }
        }, R.string.reboot);

        mFlipper.setDisplayedChild(0);
    }

    private void updateCalibrationStepView(View stepView, int viewStatus, int title, int instructions, int errorNotice, View.OnClickListener action, int actionLabel) {
        final TextView viewTitle = (TextView) stepView.findViewById(R.id.current_step);
        final TextView viewInstructions = (TextView) stepView.findViewById(R.id.instructions);
        final TextView viewErrorNotice = (TextView) stepView.findViewById(R.id.error_notice);
        final View viewInProgress = stepView.findViewById(R.id.progress_bar);
        final Button buttonAction = (Button) stepView.findViewById(R.id.button);

        switch (viewStatus) {
            case STEP_CURRENT:
                viewErrorNotice.setVisibility(View.GONE);
                viewInProgress.setVisibility(View.GONE);

                buttonAction.setEnabled(true);
                break;

            case STEP_IN_PROGRESS:
                viewErrorNotice.setVisibility(View.GONE);
                viewInProgress.setVisibility(View.VISIBLE);

                buttonAction.setEnabled(false);
                break;

            case STEP_ERROR:
                viewErrorNotice.setVisibility(View.VISIBLE);
                viewInProgress.setVisibility(View.GONE);

                buttonAction.setEnabled(true);
                break;

            case STEP_OK:
                viewErrorNotice.setVisibility(View.GONE);
                viewInProgress.setVisibility(View.GONE);

                buttonAction.setEnabled(false);
                break;

            default:
                Log.wtf(TAG, "Unknown calibration step reached: " + viewStatus);
        }

        if (title != -1) {
            viewTitle.setText(title);
        }

        if (instructions != -1) {
            viewInstructions.setText(instructions);
        }

        if (errorNotice != -1) {
            viewErrorNotice.setText(errorNotice);
        }

        if (action != null) {
            buttonAction.setOnClickListener(action);
        }

        if (actionLabel != -1) {
            buttonAction.setText(actionLabel);
        }
    }

    private void updateCalibrationStepView(View stepView, int viewStatus, int instructions) {
        updateCalibrationStepView(stepView, viewStatus, -1, instructions, -1, null, -1);
    }

    private void updateCalibrationStepView(View stepView, int viewStatus, int instructions, int errorNotice) {
        updateCalibrationStepView(stepView, viewStatus, -1, instructions, errorNotice, null, -1);
    }

    private void updateCalibrationStepView(View stepView, int viewStatus, int instructions, int errorNotice, View.OnClickListener action, int actionLabel) {
        updateCalibrationStepView(stepView, viewStatus, -1, instructions, errorNotice, action, actionLabel);
    }

    private void doReadBlockedValue() {
        updateCalibrationStepView(mViewStep1, STEP_IN_PROGRESS, R.string.msg_reading);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final int value = ProximitySensorHelper.read(BLOCKED_MINIMAL_VALUE, ProximitySensorHelper.READ_MAX_LIMIT);

                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Log.d(TAG, "    blocked value = " + String.format(Locale.ENGLISH, "%3d", value));

                        doSaveBlockedValue(value);
                    }
                });
            }
        }).start();
    }

    private void doSaveBlockedValue(int value) {
        if (value >= 0) {
            mBlockedValue = value;

            updateCalibrationStepView(mViewStep1, STEP_OK, R.string.msg_step_success);
            mFlipper.setDisplayedChild(1);
        } else {
            updateCalibrationStepView(mViewStep1, STEP_ERROR, R.string.msg_block, R.string.msg_fail_block);
            mFlipper.setDisplayedChild(0);
        }
    }

    private void doReadNonBlockedValue() {
        updateCalibrationStepView(mViewStep2, STEP_IN_PROGRESS, R.string.msg_reading);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final int value = ProximitySensorHelper.read(ProximitySensorHelper.READ_MIN_LIMIT, (mBlockedValue - NON_BLOCKED_MAXIMAL_VALUE_FROM_BLOCKED_VALUE));

                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Log.d(TAG, "non-blocked value = " + String.format(Locale.ENGLISH, "%3d", value));

                        doSaveNonBlockedValue(value);
                    }
                });
            }
        }).start();
    }

    private void doSaveNonBlockedValue(int value) {
        if (value >= 0) {
            mNonBlockedValue = value;

            updateCalibrationStepView(mViewStep2, STEP_OK, R.string.msg_step_success);
            mFlipper.setDisplayedChild(2);

            doCalibrate();
        } else {
            updateCalibrationStepView(mViewStep2, STEP_ERROR, R.string.msg_unblock, R.string.msg_fail_unlock);
            mFlipper.setDisplayedChild(1);
        }
    }

    public void doCalibrate() {
        updateCalibrationStepView(mViewStep3, STEP_IN_PROGRESS, R.string.msg_cal);

        mCalibratedConfiguration.nearThreshold = mBlockedValue - NEAR_THRESHOLD_FROM_BLOCKED_VALUE;
        mCalibratedConfiguration.farThreshold = mCalibratedConfiguration.nearThreshold - FAR_THRESHOLD_FROM_NEAR_THRESHOLD;

        if (mNonBlockedValue == 0) {
            // TODO ignore if there was a calibration but no reboot as the persisted data will not be read until next reboot
            mCalibratedConfiguration.offsetCompensation = Math.min(Math.max(mPersistedConfiguration.offsetCompensation - 2, ProximitySensorConfiguration.MIN_OFFSET_COMPENSATION), ProximitySensorConfiguration.MAX_OFFSET_COMPENSATION);
            Log.d(TAG, "New offset based on current offset only");
        } else {
            mCalibratedConfiguration.offsetCompensation = Math.min(Math.max(mPersistedConfiguration.offsetCompensation + (int)Math.floor(mNonBlockedValue / 32) - 1, ProximitySensorConfiguration.MIN_OFFSET_COMPENSATION), ProximitySensorConfiguration.MAX_OFFSET_COMPENSATION);
            Log.d(TAG, "New offset based on unblock value and current offset");
        }

        if (mCalibratedConfiguration.persistToMemory()) {
            storeCalibrationData();
            setSuccessfullyCalibrated(this, true);

            // wait a bit because the calibration is otherwise too fast
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(CALIBRATION_DELAY_MS);
                    } catch (InterruptedException e) {
                        // Log but ignore interruption.
                        Log.e(TAG, e.getMessage());
                    }

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            updateCalibrationStepView(mViewStep3, STEP_CURRENT, R.string.msg_calibration_success);
                            mFlipper.setDisplayedChild(2);
                        }
                    });
                }
            }).start();
        } else {
            updateCalibrationStepView(mViewStep3, STEP_ERROR, R.string.msg_cal, R.string.msg_fail_write_sns, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    reset();
                    startFairphoneUpdaterActivity();
                }
            }, R.string.go_to_updater);
        }

    }

    @Override
    protected void onPause() {
        UpdateFinalizerService.startActionCheckCalibrationPending(this);
        super.onPause();
    }

    private void storeCalibrationData() {
        CalibrationDbHelper mDbHelper = new CalibrationDbHelper(this);

        // Gets the data repository in write mode
        SQLiteDatabase db = mDbHelper.getWritableDatabase();

        // Create a new map of values, where column names are the keys
        ContentValues values = new ContentValues();
        values.put(CalibrationData.COLUMN_NAME_PREVIOUS_NEAR, mPersistedConfiguration.nearThreshold);
        values.put(CalibrationData.COLUMN_NAME_PREVIOUS_FAR, mPersistedConfiguration.farThreshold);
        values.put(CalibrationData.COLUMN_NAME_PREVIOUS_OFFSET, mPersistedConfiguration.offsetCompensation);
        values.put(CalibrationData.COLUMN_NAME_NEAR, mCalibratedConfiguration.nearThreshold);
        values.put(CalibrationData.COLUMN_NAME_FAR, mCalibratedConfiguration.farThreshold);
        values.put(CalibrationData.COLUMN_NAME_OFFSET, mCalibratedConfiguration.offsetCompensation);

        PackageInfo pInfo = null;
        try {
            pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.wtf(TAG, e);
        }
        int verCode = pInfo.versionCode;
        values.put(CalibrationData.COLUMN_NAME_APP_VERSION, verCode);

        // Insert the new row, returning the primary key value of the new row
        long newRowId;
        newRowId = db.insert(
                CalibrationData.TABLE_NAME,
                null,
                values);

    }

    protected static void setSuccessfullyCalibrated(Context ctx, boolean isSuccessfullyCalibrated) {
        SharedPreferences sharedPref = ctx.getSharedPreferences(
                ctx.getString(R.string.preference_file_key), MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putBoolean(ctx.getString(R.string.preference_successfully_calibrated), isSuccessfullyCalibrated);
        editor.apply();
    }

    /**
     * Determine if the current device needs to be calibrated.<br>
     * <br>
     * The conditions are as follows:
     * <ol>
     * <li>The memory needs to be accessible (R/W).</li>
     * <li>There must not be an evidence that the device has been calibrated in the shared preferences.</li>
     * <li>Optional: the persisted offset compensation must be equal to 0.</li>
     * </ol>
     *
     * @param ctx The context.
     * @param calibrateNullCompensation Flag to check for a null compensation (leading to a calibration).
     * @return <em>true</em> if a calibration should take place, <em>false</em> if the device has been calibrated at
     * one point.
     */
    protected static boolean hasToBeCalibrated(Context ctx, boolean calibrateNullCompensation) {
        boolean hasToBeCalibrated;

        if (ProximitySensorConfiguration.canReadFromAndPersistToMemory()) {
            final SharedPreferences sharedPref = ctx.getSharedPreferences(
                ctx.getString(R.string.preference_file_key), MODE_PRIVATE);
            hasToBeCalibrated = !sharedPref.getBoolean(ctx.getString(R.string.preference_successfully_calibrated), false);

            if (!hasToBeCalibrated && calibrateNullCompensation) {
                final ProximitySensorConfiguration persistedConfiguration = ProximitySensorConfiguration.readFromMemory();
                hasToBeCalibrated = (persistedConfiguration != null) && (persistedConfiguration.offsetCompensation == 0);
            }
        } else {
            /* Memory is not accessible, so no calibration is required. */
            hasToBeCalibrated = false;
        }

        return hasToBeCalibrated;
    }

    /**
     * Call to <code>hasToBeCalibrated(ctx, false)</code>.
     *
     * @param ctx The context.
     * @return <em>true</em> if a calibration should take place, <em>false</em> if the device has been calibrated at
     * one point.
     * @see CalibrationActivity#hasToBeCalibrated(Context, boolean)
     */
    protected static boolean hasToBeCalibrated(Context ctx) {
        return hasToBeCalibrated(ctx, false);
    }

    private void startFairphoneUpdaterActivity() {
        final Intent intent = new Intent();
        intent.setComponent(new ComponentName(getString(R.string.package_fairphone_updater), getString(R.string.activity_fairphone_updater_check_for_updates)));
        intent.setFlags(Intent.FLAG_ACTIVITY_TASK_ON_HOME | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void showIncompatibleDeviceDialog() {
        final DialogFragment dialog = new IncompatibleDeviceDialog();
        dialog.show(getFragmentManager(), getString(R.string.fragment_tag_incompatible_device_dialog));
    }

    @Override
    public void onIncompatibleDeviceDialogPositiveAction(DialogFragment dialog) {
        startFairphoneUpdaterActivity();
    }

    @Override
    public void onIncompatibleDeviceDialogNegativeAction(DialogFragment dialog) {
        // fall-through
    }

    @Override
    public void onDismissIncompatibleDeviceDialog(DialogFragment dialog) {
        finish();
    }
}
