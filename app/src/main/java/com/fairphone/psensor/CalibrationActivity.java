
package com.fairphone.psensor;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.ViewFlipper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 1. Hint user block sensor and read value. if less than 230, hint and wait confirm. <BR>
 * 2. Hint user unblock sensor and read value. if greater than 96, hint and wait confirm. <BR>
 * 3. Use the value of unblock to do the calibration. value+30 as far, value+60 as near. <BR>
 * 4. Write far and near value to /persist/sns.reg binary file. <BR>
 * 5. The file of sns.reg content as "0000100: 0a3c 0000 <near> <far> 6400 6400 01c0 0000" <BR> 
 */
public class CalibrationActivity extends Activity {
    private static final String TAG = CalibrationActivity.class.getSimpleName();

    private static final String CALIBRATION_FILE = "/persist/sns.reg";
    private static final String CMD = "senread";
    private static final String RESULT_PREFIX = "[RESULT]";
    private static final int DEFAULT_OFFSET = 0x3;
    protected static final int OFFSET_FAR = 20;
    protected static final int OFFSET_NEAR = 30;
    protected static final int BLOCK_LIMIT = 235;
    protected static final int UNBLOCK_LIMIT = 180;
    private static final int SEEK_NEAR = 0x00000100 + 4;
    private static final int SEEK_FAR = 0x00000100 + 6;
    private static final int SEEK_OFFSET = 0x00000020 + 8;

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

    protected static final int DELAY = 1000;

    private Handler mHandler;
    private TextView mStep1;
    private TextView mText1;
    private Button mButton1;
    private TextView mStep2;
    private TextView mText2;
    private Button mButton2;
    private TextView mStep3;
    private TextView mText3;
    private Button mButton3;

    private int mDataFar;
    private int mDataNear;
    private int mState = STATE_START;

    private ViewFlipper mFlipper;
    private View mViewStep1;
    private View mViewStep2;
    private View mViewStep3;
    private ProgressBar mProgressBar1;
    private ProgressBar mProgressBar2;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mHandler=new Handler();

        setContentView(R.layout.activity_calibration);

        mFlipper = (ViewFlipper) findViewById(R.id.viewFlipper);

        mFlipper.setInAnimation(this, R.anim.slide_in_from_left);
        mFlipper.setOutAnimation(this, R.anim.slide_out_to_right);


        LayoutInflater inflater = LayoutInflater.from(this);
        mViewStep1 = inflater.inflate(R.layout.view_calibration_step,null);
        mViewStep2 = inflater.inflate(R.layout.view_calibration_step,null);
        mViewStep3 = inflater.inflate(R.layout.view_calibration_step,null);

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
        mProgressBar1.setVisibility(View.INVISIBLE);

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
                powerManager.reboot(null);            }
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

                final int value = read();

                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Log.d(TAG, "block value=" + value);
                        sleep(DELAY);
                        if (value >= BLOCK_LIMIT) {
                            mDataNear = value - OFFSET_NEAR;
                            Log.d(TAG, "near value = " + mDataNear);
                            Log.d(TAG, "change to STATE_UNBLOCK");
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
                sleep(DELAY);
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
        setSuccesfullyCalibrated(this, true);
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
                int value = read();
                Log.d(TAG, "unblock value=" + value);
                sleep(DELAY);

                //cal far value
                mDataFar = mDataNear - OFFSET_FAR;
                //Set the limitation to allow user debug
                if (value >= 0 && value <= UNBLOCK_LIMIT && mDataFar > UNBLOCK_LIMIT) {
                    mDataFar = mDataNear - OFFSET_FAR;
                    //mDataNear = value + OFFSET_NEAR;
                    Log.d(TAG, "far value  = " + mDataFar);
                    // Log.d(TAG, "near value = " + mDataNear);
                    // following cal method can adjust based on actual test result
                    // if(mDataNear > mDataFar && ((mDataNear - mDataFar) > 20)) {
                    Log.d(TAG, "pass--cal " + (mDataNear - mDataFar));
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            changeState(STATE_CAL);
                        }
                    });
                    //    }
                } else {
                    Log.d(TAG, "Fail--cal " + (mDataNear - mDataFar));
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mText2.setText(getString(R.string.msg_fail_unlock));
                            changeState(STATE_FAIL_STEP_2);
                        }
                    });
                }
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

    public static int read() {
        String line = exec(CMD);
        String data = null;
        if (line.startsWith(RESULT_PREFIX)) {
            data = line.replace(RESULT_PREFIX, "").trim();
        }
        try {
            return Integer.parseInt(data);
        } catch (Exception e) {
            Log.wtf(TAG, e);
        }
        return -1;
    }

    private void logPreviousValues() {
        byte[] buffer = new byte[2];
        try {
            RandomAccessFile file = new RandomAccessFile(CALIBRATION_FILE, "rw");
            file.seek(SEEK_NEAR);

            file.read(buffer, 0, 2);
            Log.d(getString(R.string.logtag), "old near data  = " + String.format("%%02x%02x", buffer[1], buffer[0]));
            file.seek(SEEK_FAR);
            file.read(buffer, 0, 2);
            Log.d(getString(R.string.logtag), "old far data  = " + String.format("%%02x%02x", buffer[1], buffer[0]));
            file.seek(SEEK_OFFSET);
            file.read(buffer, 0, 2);
            Log.d(getString(R.string.logtag), "old offset data  = " + String.format("%%02x%02x", buffer[1], buffer[0]));
            file.close();
        } catch (Exception e) {
            Log.wtf(TAG, e);
        }
    }


    private boolean write() {
        byte[] far  = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(mDataFar).array();
        byte[] near = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(mDataNear).array();
        byte[] offset = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(DEFAULT_OFFSET).array();

        logPreviousValues();

        Log.d(getString(R.string.logtag), "far data  = " + String.format("%%02x%02x", far[1], far[0]));
        Log.d(getString(R.string.logtag), "near data = " + String.format("%02x%02x", near[1], near[0]));
        Log.d(getString(R.string.logtag), "offset data = " + String.format("%02x%02x", offset[1], offset[0]));

        try {
            RandomAccessFile file = new RandomAccessFile(CALIBRATION_FILE, "rw");
            file.seek(SEEK_NEAR);
            file.writeByte(near[0]);
            file.writeByte(near[1]);
            file.seek(SEEK_FAR);
            file.writeByte(far[0]);
            file.writeByte(far[1]);
            file.seek(SEEK_OFFSET);
            file.writeByte(offset[0]);
            file.writeByte(offset[1]);
            file.close();
            return true;
        } catch (Exception e) {
            Log.wtf(TAG, e);
        }
        return false;
    }

    private static String exec(String cmd) {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{cmd});
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            return reader.readLine();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static void sleep(int time) {
        try {
            Thread.sleep(time);
        } catch (Exception e) {

        }
    }

    protected static void setSuccesfullyCalibrated(Context ctx, boolean isSuccessfullyCalibrated) {
        SharedPreferences sharedPref = ctx.getSharedPreferences(
                ctx.getString(R.string.preference_file_key), MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putBoolean(ctx.getString(R.string.preference_successfully_calibrated),isSuccessfullyCalibrated);
        editor.apply();
    }

    protected static boolean hasToBeCalibrated(Context ctx) {
        SharedPreferences sharedPref = ctx.getSharedPreferences(
                ctx.getString(R.string.preference_file_key), MODE_PRIVATE);
        boolean wasCalibrated = sharedPref.getBoolean(ctx.getString(R.string.preference_successfully_calibrated),false);
        boolean wasCalibratedEarlier = false;
        try {
            RandomAccessFile file = new RandomAccessFile(CALIBRATION_FILE, "rw");
            file.seek(SEEK_NEAR);
            file.seek(SEEK_OFFSET);
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
