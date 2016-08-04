package com.fairphone.psensor;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * 1. Hint user block sensor and read value. if less than 230, hint and wait confirm. <BR>
 * 2. Hint user unblock sensor and read value. if greater than 96, hint and wait confirm. <BR>
 * 3. Use the value of unblock to do the calibration. value+30 as far, value+60 as near. <BR>
 * 4. Write far and near value to /persist/sns.reg binary file. <BR>
 * 5. The file of sns.reg content as "0000100: 0a3c 0000 <near> <far> 6400 6400 01c0 0000" <BR>
 * <br>
 * Using the vendor wording, the "near threshold" is the "Proximity Interrupt High threshold", the "far threshold"
 * is the "Proximity Interrupt LOW threshold", and the "offset compensation" is thr "Proximity Offset Compensation".
 */
public class CalibrationActivity extends Activity {
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
     * Path to the persisted calibration file.
     */
    private static final String CALIBRATION_FILE = "/persist/sns.reg";
    /**
     * Command to read the sensor value.
     */
    private static final String READ_COMMAND = "senread";
    /**
     * Result prefix returned by the reading command.
     */
    private static final String READ_COMMAND_RESULT_PREFIX = "[RESULT]";

    /**
     * Amount of times to perform a sensor reading.
     */
    private static final int READ_N_TIMES = 3;
    /**
     * Time to wait between two sensor readings (in milliseconds).
     */
    private static final int READ_DELAY_MS = 500;
    /**
     * Minimal value to accept as valid from the sensor reading (in sensor units).
     */
    public static final int READ_MIN_LIMIT = 0;
    /**
     * Maximal value to accept as valid from the sensor reading (in sensor units).
     */
    public static final int READ_MAX_LIMIT = 255;
    /**
     * Offset in the calibration file to reach the near threshold value.
     */
    private static final int NEAR_THRESHOLD_OFFSET = 0x00000100 + 4;
    /**
     * Offset in the calibration file to reach the far threshold value.
     */
    private static final int FAR_THRESHOLD_OFFSET = 0x00000100 + 6;
    /**
     * Offset in the calibration file to reach the offset compensation value.
     */
    private static final int OFFSET_COMPENSATION_OFFSET = 0x00000120 + 8;
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
    /**
     * Default value for the offset compensation.
     */
    private static final int DEFAULT_OFFSET_COMPENSATION = 0x01;

    private int mPersistedDataFar;
    private int mPersistedDataNear;
    private int mPersistedDataOffset;
    private int mDataFar;
    private int mDataNear;
    private int mDataOffset;

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

        getPersistedValues();

        mHandler = new Handler();

        setContentView(R.layout.activity_calibration);

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
                final int value = read(BLOCK_LIMIT, READ_MAX_LIMIT);

                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Log.d(TAG, "    blocked value = " + String.format(Locale.ENGLISH, "%3d", value));

                        if (value >= BLOCK_LIMIT) {
                            mDataNear = value - NEAR_THRESHOLD_FROM_BLOCKED_VALUE;
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
                if (write()) {
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
                final int value = read(READ_MIN_LIMIT, (mDataNear + NEAR_THRESHOLD_FROM_BLOCKED_VALUE - 5));

                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Log.d(TAG, "non-blocked value = " + String.format(Locale.ENGLISH, "%3d", value));

                        if (value >= 0 && value <= (mDataNear + NEAR_THRESHOLD_FROM_BLOCKED_VALUE - 5)) {
                            mDataFar = mDataNear - FAR_THRESHOLD_FROM_NEAR_THRESHOLD;
                            mDataOffset = DEFAULT_OFFSET_COMPENSATION;
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

    /**
     * Call to read(1, {@link #READ_MIN_LIMIT}, {@link #READ_MAX_LIMIT})
     *
     * @return The value read or -1 if read failed.
     * @see #read(int, int, int)
     */
    public static int read() {
        return read(1, READ_MIN_LIMIT, READ_MAX_LIMIT);
    }

    /**
     * Call to read({@link #READ_N_TIMES}, min_value, max_value)
     *
     * @param min_value The lower threshold (inclusive) of accepted range.
     * @param max_value The upper threshold (inclusive) of accepted range.
     * @return The mean of all the value read (up to {@link #READ_N_TIMES}) or -1 if no read succeeded.
     * @see #read(int, int, int)
     */
    public static int read(int min_value, int max_value) {
        return read(READ_N_TIMES, min_value, max_value);
    }

    /**
     * Read the proximity sensor value read_times times and return the mean value.
     * <p/>
     * Wait {@link #READ_DELAY_MS} between each read, even if there is only one read planned.
     *
     * @param min_value The lower threshold (inclusive) of accepted range.
     * @param max_value The upper threshold (inclusive) of accepted range.
     * @return The mean of all the value read (up to {@link #READ_N_TIMES}) or -1 if no read succeeded.
     */
    public static int read(int read_times, int min_value, int max_value) {
        String line;
        int result;
        int summed_result = 0;
        int nb_result_read = 0;
        int final_result = -1;

        for (int i = 0; i < read_times; i++) {
            line = exec(READ_COMMAND);

            if (line != null && line.startsWith(READ_COMMAND_RESULT_PREFIX)) {
                try {
                    result = Integer.parseInt(line.replace(READ_COMMAND_RESULT_PREFIX, "").trim());

                    if (min_value <= result && result <= max_value) {
                        summed_result += result;
                        nb_result_read++;
                    } else {
                        Log.d(TAG, "Ignored value out of accepted range (" + result + " not in [" + min_value + "," + max_value + "])");
                    }
                } catch (Exception e) {
                    Log.wtf(TAG, e);
                }
            }

            // wait a bit between two sensor read
            try {
                Thread.sleep(READ_DELAY_MS);
            } catch (Exception e) {
                Log.wtf(TAG, e);
            }
        }

        if (nb_result_read == 0) {
            // something went wrong with READ_COMMAND, are we allowed to execute it?
            Log.e(TAG, "Could not read sensor value " + read_times + " " + ((read_times == 1) ? "time" : "times"));

            // TODO display an error message
        } else {
            if (nb_result_read < read_times) {
                Log.w(TAG, "Read " + nb_result_read + "/" + read_times + " values");
            }

            final_result = Math.round(summed_result / nb_result_read);
        }

        return final_result;
    }

    private void getPersistedValues() {
        byte[] buffer = new byte[4];
        buffer[2] = 0x00;
        buffer[3] = 0x00;
        try {
            RandomAccessFile file = new RandomAccessFile(CALIBRATION_FILE, "r");

            file.seek(NEAR_THRESHOLD_OFFSET);
            file.read(buffer, 0, 2);
            mPersistedDataNear = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getInt();
            Log.d(getString(R.string.logtag), "persisted near data   = " + String.format("%3d", mPersistedDataNear) + " (" + String.format("0x%02x%02x", buffer[1], buffer[0]) + ")");

            file.seek(FAR_THRESHOLD_OFFSET);
            file.read(buffer, 0, 2);
            mPersistedDataFar = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getInt();
            Log.d(getString(R.string.logtag), "persisted far data    = " + String.format("%3d", mPersistedDataFar) + " (" + String.format("0x%02x%02x", buffer[1], buffer[0]) + ")");

            file.seek(OFFSET_COMPENSATION_OFFSET);
            file.read(buffer, 0, 1);
            buffer[1] = 0x00;
            mPersistedDataOffset = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getInt();
            Log.d(getString(R.string.logtag), "persisted offset data = " + String.format("%3d", mPersistedDataOffset) + " (" + String.format("0x%02x", buffer[0]) + ")");

            file.close();
        } catch (Exception e) {
            Log.wtf(TAG, e);
        }
    }

    private boolean write() {
        byte[] far = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(mDataFar).array();
        byte[] near = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(mDataNear).array();
        byte[] offset = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(mDataOffset).array();

        Log.d(getString(R.string.logtag), "near data   = " + String.format("%3d", mDataNear) + " (" + String.format("0x%02x%02x", near[1], near[0]) + ")");
        Log.d(getString(R.string.logtag), "far data    = " + String.format("%3d", mDataFar) + " (" + String.format("0x%02x%02x", far[1], far[0]) + ")");
        Log.d(getString(R.string.logtag), "offset data = " + String.format("%3d", mDataOffset) + " (" + String.format("0x%02x", offset[0]) + ")");

        try {
            RandomAccessFile file = new RandomAccessFile(CALIBRATION_FILE, "rw");
            file.seek(NEAR_THRESHOLD_OFFSET);
            file.writeByte(near[0]);
            file.writeByte(near[1]);
            file.seek(FAR_THRESHOLD_OFFSET);
            file.writeByte(far[0]);
            file.writeByte(far[1]);
            file.seek(OFFSET_COMPENSATION_OFFSET);
            file.writeByte(offset[0]);
            file.close();
            storeCalibrationData();
            return true;
        } catch (Exception e) {
            Log.wtf(TAG, e);
        }
        return false;
    }

    private void storeCalibrationData() {
        CalibrationDbHelper mDbHelper = new CalibrationDbHelper(this);

        // Gets the data repository in write mode
        SQLiteDatabase db = mDbHelper.getWritableDatabase();

        // Create a new map of values, where column names are the keys
        ContentValues values = new ContentValues();
        values.put(CalibrationData.COLUMN_NAME_PREVIOUS_NEAR, mPersistedDataNear);
        values.put(CalibrationData.COLUMN_NAME_PREVIOUS_FAR, mPersistedDataFar);
        values.put(CalibrationData.COLUMN_NAME_PREVIOUS_OFFSET, mPersistedDataOffset);
        values.put(CalibrationData.COLUMN_NAME_NEAR, mDataNear);
        values.put(CalibrationData.COLUMN_NAME_FAR, mDataFar);
        values.put(CalibrationData.COLUMN_NAME_OFFSET, mDataOffset);

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

    private static String exec(String cmd) {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{cmd});
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            return reader.readLine();
        } catch (IOException e) {
            Log.wtf(TAG, "Could not execute command `" + cmd + "`", e);
            return null;
        }
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
        boolean wasCalibrated = sharedPref.getBoolean(ctx.getString(R.string.preference_successfully_calibrated), false);
        boolean wasCalibratedEarlier = false;
        try {
            RandomAccessFile file = new RandomAccessFile(CALIBRATION_FILE, "rw");
            file.seek(NEAR_THRESHOLD_OFFSET);
            file.seek(OFFSET_COMPENSATION_OFFSET);
            byte offset0 = file.readByte();
            byte offset1 = file.readByte();
            file.close();
            /* offset is only 0 on devices that have not been calibrated. */
            wasCalibratedEarlier = (offset0 != 0 || offset1 != 0);
        } catch (Exception e) {
            Log.wtf(TAG, e);
        }
        return !wasCalibrated;
    }

}
