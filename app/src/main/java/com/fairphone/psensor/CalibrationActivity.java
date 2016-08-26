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
import android.widget.ProgressBar;
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

    /* Activity states */
    private static final int STATE_START = 0;
    private static final int STATE_BLOCK = 11;
    private static final int STATE_BLOCK_READ = 12;
    private static final int STATE_BLOCK_WARN = 13;
    private static final int STATE_UNBLOCK = 21;
    private static final int STATE_UNBLOCK_READ = 22;
    private static final int STATE_UNBLOCK_WARN = 23;
    private static final int STATE_CAL = 3;
    private static final int STATE_SUCCESS = 4;
    private static final int STATE_FAIL = 5;
    private static final int STATE_FAIL_STEP_2 = 6;

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
    public static final int BLOCK_LIMIT = 235;
    /**
     * Maximal accepted value for the non-blocked value (in sensor units).
     */
    public static final int UNBLOCK_LIMIT = 180;

    private ProximitySensorConfiguration mPersistedConfiguration;
    private ProximitySensorConfiguration mCalibratedConfiguration;

    private int mBlockedValue;
    private int mNonBlockedValue;

    private int mState = STATE_START;
    private Handler mHandler;

    private ViewFlipper mFlipper;
    private View mViewStep1;
    private View mViewStep2;
    private View mViewStep3;
    private ProgressBar mProgressBar1;
    private ProgressBar mProgressBar2;
    private TextView mStep1;
    private TextView mText1;
    private Button mButton1;
    private TextView mStep2;
    private TextView mText2;
    private Button mButton2;
    private TextView mStep3;
    private TextView mText3;
    private Button mButton3;


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
            mPersistedConfiguration = ProximitySensorConfiguration.readFromMemory();
            mCalibratedConfiguration = new ProximitySensorConfiguration();
        }

        mFlipper = (ViewFlipper) findViewById(R.id.viewFlipper);

        mFlipper.setInAnimation(this, R.anim.slide_in_from_left);
        mFlipper.setOutAnimation(this, R.anim.slide_out_to_right);

        LayoutInflater inflater = LayoutInflater.from(this);
        mViewStep1 = inflater.inflate(R.layout.view_calibration_step, null);
        mViewStep2 = inflater.inflate(R.layout.view_calibration_step, null);
        mViewStep3 = inflater.inflate(R.layout.view_calibration_step, null);

        mFlipper.addView(mViewStep1);
        mFlipper.addView(mViewStep2);
        mFlipper.addView(mViewStep3);


        mStep1 = (TextView) mViewStep1.findViewById(R.id.current_step);
        mText1 = (TextView) mViewStep1.findViewById(R.id.instructions);
        mButton1 = (Button) mViewStep1.findViewById(R.id.button);
        mProgressBar1 = (ProgressBar) mViewStep1.findViewById(R.id.progress_bar);
        mProgressBar1.setVisibility(View.INVISIBLE);

        mButton1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeState(STATE_BLOCK_READ);
            }
        });

        mStep2 = (TextView) mViewStep2.findViewById(R.id.current_step);
        mText2 = (TextView) mViewStep2.findViewById(R.id.instructions);
        mButton2 = (Button) mViewStep2.findViewById(R.id.button);
        mStep2.setText(getText(R.string.step_2));
        mText2.setText(getText(R.string.msg_unblock));
        mProgressBar2 = (ProgressBar) mViewStep2.findViewById(R.id.progress_bar);
        mProgressBar2.setVisibility(View.INVISIBLE);

        mButton2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeState(STATE_UNBLOCK_READ);
            }
        });
        mStep3 = (TextView) mViewStep3.findViewById(R.id.current_step);
        mText3 = (TextView) mViewStep3.findViewById(R.id.instructions);
        mButton3 = (Button) mViewStep3.findViewById(R.id.button);
        mStep3.setText(getText(R.string.step_3));
        mText3.setText(getText(R.string.msg_calibration_success));
        mButton3.setText(R.string.reboot);

        mButton3.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mFlipper.showNext();
                PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
                powerManager.reboot(null);
            }
        });
    }

    private void changeState(int state) {
        mState = state;
        update();
    }

    private void update() {
        switch (mState) {
            case STATE_START:
                changeState(STATE_BLOCK);
                break;
            case STATE_BLOCK:
                updateToBlock();
                break;
            case STATE_BLOCK_READ:
                updateToBlockRead();
                break;
            case STATE_BLOCK_WARN:
            case STATE_UNBLOCK:
                updateToUnblock();
                break;
            case STATE_UNBLOCK_READ:
                updateToUnblockRead();
                break;
            case STATE_UNBLOCK_WARN:
            case STATE_CAL:
                updateToCal();
                break;
            case STATE_SUCCESS:
                updateToSuccess();
                break;
            case STATE_FAIL:
                updateToFail();
                break;
            case STATE_FAIL_STEP_2:
                updateToFailStep2();
                break;
            default:
                break;
        }
    }

    private void updateToBlock() {
        //mText1.setText(getString(R.string.msg_block));
        mFlipper.setDisplayedChild(0);

        mStep1.setEnabled(true);
        mText1.setEnabled(true);
        mButton1.setEnabled(true);
        mProgressBar1.setVisibility(View.INVISIBLE);
    }

    private void updateToBlockRead() {
        mText1.setText(getString(R.string.msg_reading));
        mButton1.setEnabled(false);
        mProgressBar1.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int value = ProximitySensorHelper.read(BLOCK_LIMIT, ProximitySensorHelper.READ_MAX_LIMIT);

                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Log.d(TAG, "    blocked value = " + String.format(Locale.ENGLISH, "%3d", value));

                        if (value >= BLOCK_LIMIT) {
                            mBlockedValue = value;
                            changeState(STATE_UNBLOCK);
                        } else {
                            mText1.setText(getString(R.string.msg_fail_block));
                            changeState(STATE_FAIL);
                        }
                    }
                });
            }
        }).start();
    }

    private void updateToCal() {
        mText2.setText(R.string.msg_step_success);
        mStep3.setEnabled(true);
        mText3.setEnabled(true);
        mText3.setText(getString(R.string.msg_cal));
        mHandler.post(new Runnable() {
            @Override
            public void run() {
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
                    mText3.setText(getString(R.string.msg_calibration_success));
                    mButton3.setEnabled(true);
                    changeState(STATE_SUCCESS);
                } else {
                    mText3.setText(getString(R.string.msg_fail_write_sns));
                    changeState(STATE_FAIL);
                }
            }
        });
    }

    private void updateToFail() {
        mButton1.setEnabled(true);
        changeState(STATE_BLOCK);
    }

    private void updateToFailStep2() {
        mText2.setText(getString(R.string.msg_fail_unlock));
        mButton2.setEnabled(true);
        changeState(STATE_UNBLOCK);
    }

    private void updateToSuccess() {
        mFlipper.setDisplayedChild(2);
        setSuccessfullyCalibrated(this, true);
        mText2.setText(R.string.msg_step_success);
        mButton3.setEnabled(true);
    }

    private void updateToUnblockRead() {
        mText2.setText(getString(R.string.msg_reading));
        mButton2.setEnabled(false);
        mProgressBar2.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int value = ProximitySensorHelper.read(ProximitySensorHelper.READ_MIN_LIMIT, (mCalibratedConfiguration.nearThreshold + NEAR_THRESHOLD_FROM_BLOCKED_VALUE - 5));

                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Log.d(TAG, "non-blocked value = " + String.format(Locale.ENGLISH, "%3d", value));

                        if (value >= 0 && value <= (mBlockedValue - 5)) {
                            mNonBlockedValue = value;
                            changeState(STATE_CAL);
                        } else {
                            mText1.setText(getString(R.string.msg_fail_unlock));
                            changeState(STATE_FAIL_STEP_2);
                        }
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onPause() {
        UpdateFinalizerService.startActionCheckCalibrationPending(this);
        super.onPause();
    }

    private void updateToUnblock() {
        mFlipper.setDisplayedChild(1);
        mProgressBar2.setVisibility(View.INVISIBLE);
        mStep2.setEnabled(true);
        mText2.setEnabled(true);
        mButton2.setEnabled(true);
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

    private void showIncompatibleDeviceDialog() {
        final DialogFragment dialog = new IncompatibleDeviceDialog();
        dialog.show(getFragmentManager(), getString(R.string.fragment_tag_incompatible_device_dialog));
    }

    @Override
    public void onIncompatibleDeviceDialogPositiveAction(DialogFragment dialog) {
        final Intent intent = new Intent();
        intent.setComponent(new ComponentName(getString(R.string.package_fairphone_updater), getString(R.string.activity_fairphone_updater_check_for_updates)));
        intent.setFlags(Intent.FLAG_ACTIVITY_TASK_ON_HOME | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
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
