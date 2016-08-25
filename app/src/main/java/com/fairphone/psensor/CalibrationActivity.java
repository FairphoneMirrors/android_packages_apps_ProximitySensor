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
 * 1. Hint user block sensor and read value. if less than 230, hint and wait confirm. <BR>
 * 2. Hint user unblock sensor and read value. if greater than 96, hint and wait confirm. <BR>
 * 3. Use the value of unblock to do the calibration. value+30 as far, value+60 as near. <BR>
 * 4. Write far and near value to /persist/sns.reg binary file. <BR>
 * 5. The file of sns.reg content as "0000100: 0a3c 0000 <near> <far> 6400 6400 01c0 0000" <BR>
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


        mStep1 = (TextView) mViewStep1.findViewById(R.id.textview_heading);
        mText1 = (TextView) mViewStep1.findViewById(R.id.maintext);
        mButton1 = (Button) mViewStep1.findViewById(R.id.button);
        mProgressBar1 = (ProgressBar) mViewStep1.findViewById(R.id.progressBar);
        mProgressBar1.setVisibility(View.INVISIBLE);

        mButton1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeState(STATE_BLOCK_READ);
            }
        });

        mStep2 = (TextView) mViewStep2.findViewById(R.id.textview_heading);
        mText2 = (TextView) mViewStep2.findViewById(R.id.maintext);
        mButton2 = (Button) mViewStep2.findViewById(R.id.button);
        mStep2.setText(getText(R.string.step_2));
        mText2.setText(getText(R.string.msg_unblock));
        mProgressBar2 = (ProgressBar) mViewStep2.findViewById(R.id.progressBar);
        mProgressBar2.setVisibility(View.INVISIBLE);

        mButton2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeState(STATE_UNBLOCK_READ);
            }
        });
        mStep3 = (TextView) mViewStep3.findViewById(R.id.textview_heading);
        mText3 = (TextView) mViewStep3.findViewById(R.id.maintext);
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
                            mCalibratedConfiguration.nearThreshold = value - NEAR_THRESHOLD_FROM_BLOCKED_VALUE;
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

                        if (value >= 0 && value <= (mCalibratedConfiguration.nearThreshold + NEAR_THRESHOLD_FROM_BLOCKED_VALUE - 5)) {
                            mCalibratedConfiguration.farThreshold = mCalibratedConfiguration.nearThreshold - FAR_THRESHOLD_FROM_NEAR_THRESHOLD;
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

    protected static boolean hasToBeCalibrated(Context ctx) {
        SharedPreferences sharedPref = ctx.getSharedPreferences(
                ctx.getString(R.string.preference_file_key), MODE_PRIVATE);
        boolean hasToBeCalibrated = !sharedPref.getBoolean(ctx.getString(R.string.preference_successfully_calibrated), false);

        if (ProximitySensorConfiguration.canReadFromAndPersistToMemory()) {
            /* offset is only 0 on devices that have not been calibrated. */
            final ProximitySensorConfiguration persistedConfiguration = ProximitySensorConfiguration.readFromMemory();

            if ((persistedConfiguration != null) && (persistedConfiguration.offsetCompensation != 0)) {
                // TODO un-comment following to make sure a persisted offset != 0 leads to a calibration
                // hasToBeCalibrated = true;
            }
        } else {
            /* Memory is not accessible, so no calibration is required. */
            hasToBeCalibrated = false;
        }

        return hasToBeCalibrated;
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
