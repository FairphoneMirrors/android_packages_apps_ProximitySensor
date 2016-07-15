
package com.fairphone.psensor;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.android.mmilib.tools.ActivityHelper;
import com.android.mmilib.utils.Common;

import java.io.RandomAccessFile;
import java.util.List;

/**
 * 1. Hint user block sensor and read value. if less than 230, hint and wait confirm. <BR>
 * 2. Hint user unblock sensor and read value. if greater than 96, hint and wait confirm. <BR>
 * 3. Use the value of unblock to do the calibration. value+30 as far, value+60 as near. <BR>
 * 4. Write far and near value to /persist/sns.reg binary file. <BR>
 * 5. The file of sns.reg content as "0000100: 0a3c 0000 <near> <far> 6400 6400 01c0 0000" <BR>
 * @param <StatusBarManager>
 * 
 */
public class CalibrationActivity<StatusBarManager> extends Activity {
    private static final String TAG = CalibrationActivity.class.getSimpleName();

    private static final String CAL_FLIE = "/persist/sns.reg";
    private static final String CMD = "senread";
    private static final String RESULT_PREFIX = "[RESULT]";
    public static final int OFFSET_FAR = 20;
    public static final int OFFSET_NEAR = 30;
    public static final int BLOCK_LIMIT = 235;
    public static final int UNBLOCK_LIMIT = 180;
    private static final int SEEK_NEAR = 0x00000100 + 4;
    private static final int SEEK_FAR = 0x00000100 + 6;

    // in future, can use this to save cal offset...can be set according unblock value. currenly it is hard code as 4 in adsp code
    // private static final int SEEK_OFFSET = 0x00000020 + 8;

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

    public static final int DELAY = 1000;

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

    public int mDataFar;
    public int mDataNear;
    private int mState = STATE_START;

    private ActionBar mActionBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler();
        setContentView(R.layout.activity_calibration);

//        mActionBar = getActionBar();
//        if (mActionBar != null) {
//            mActionBar.setDisplayHomeAsUpEnabled(true);
//            mActionBar.setHomeButtonEnabled(true);
//        }

        ActivityHelper helper = new ActivityHelper(this);
        mStep1 = helper.getTextView(R.id.step1_text);
        mText1 = helper.getTextView(R.id.step1);
        mButton1 = helper.getButton(R.id.btn1);
        mButton1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeState(STATE_BLOCK_READ);
            }
        });
        mStep2 = helper.getTextView(R.id.step2_text);
        mText2 = helper.getTextView(R.id.step2);
        mButton2 = helper.getButton(R.id.btn2);
        mButton2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeState(STATE_UNBLOCK_READ);
            }
        });
        mStep3 = helper.getTextView(R.id.step3_text);
        mText3 = helper.getTextView(R.id.step3);
        mButton3 = helper.getButton(R.id.btn3);
        mButton3.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
                powerManager.reboot(null);
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        mButton1.setOnClickListener(null);
        Log.d(TAG, "onPause");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        changeState(STATE_START);
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
                updateToUnlock();
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
        mStep1.setEnabled(true);
        mText1.setEnabled(true);
        mButton1.setEnabled(true);

        mStep2.setEnabled(false);
        mText2.setEnabled(false);
        mText2.setText(R.string.msg_unblock);
        mButton2.setEnabled(false);
        mStep3.setEnabled(false);
        mText3.setEnabled(false);
        mText3.setText(R.string.msg_calibration_success);
        mButton3.setEnabled(false);
    }

    private void updateToBlockRead() {
        mText1.setText(getString(R.string.msg_reading));
        mButton1.setEnabled(false);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                int value = read();
                Log.d(TAG, "block value=" + value);

                Common.sleep(DELAY);
                if (value >= BLOCK_LIMIT) {
                    mDataNear = value - OFFSET_NEAR;
                    Log.d(TAG, "near value = " + mDataNear);
                    Log.d(TAG, "changen to STATE_UNBLOCK");
                    changeState(STATE_UNBLOCK);
                } else {
                    mText1.setText(getString(R.string.msg_fail_block));
                    changeState(STATE_FAIL);
                }
            }
        });
    }

    private void updateToCal() {
        mText2.setText(R.string.msg_step_success);
        mStep3.setEnabled(true);
        mText3.setEnabled(true);
        mText3.setText(getString(R.string.msg_cal));
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Common.sleep(DELAY);
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
        // mText.setText(getString(R.string.msg_fail));
        mButton1.setEnabled(true);
        changeState(STATE_BLOCK);
    }

    private void updateToFailStep2() {
        // mText.setText(getString(R.string.msg_fail));
        mButton2.setEnabled(true);
        changeState(STATE_UNBLOCK);
    }

    private void updateToSuccess() {
        // mText.setText(getString(R.string.msg_success));
        mText2.setText(R.string.msg_step_success);
        mButton3.setEnabled(true);
    }

    private void updateToUnblockRead() {
        mText2.setText(getString(R.string.msg_reading));
        mButton2.setEnabled(false);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                int value = read();
                Log.d(TAG, "unblock value=" + value);
                Common.sleep(DELAY);

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
                    changeState(STATE_CAL);
                    //    }
                } else {
                    Log.d(TAG, "Fail--cal " + (mDataNear - mDataFar));
                    mText2.setText(getString(R.string.msg_fail_unlock));
                    changeState(STATE_FAIL_STEP_2);
                }
            }
        });
    }

    private void updateToUnlock() {
        mText1.setText(R.string.msg_step_success);
        mStep2.setEnabled(true);
        mText2.setEnabled(true);
        mButton2.setEnabled(true);
    }

    public static int read() {
        List<String> lines = Common.execAs(CMD);
        String data = null;
        if (lines != null && lines.size() > 0) {
            for (String line : lines) {
                if (TextUtils.isEmpty(line)) {
                    continue;
                }
                if (line.startsWith(RESULT_PREFIX)) {
                    data = line.replace(RESULT_PREFIX, "").trim();
                    break;
                }
            }
        }
        if (TextUtils.isEmpty(data)) {
            return -1;
        }
        try {
            return Integer.parseInt(data);
        } catch (Exception e) {
            Log.wtf(TAG, e);
        }
        return -1;
    }

    private boolean write() {
        byte[] far = Common.toBytes(mDataFar);
        byte[] near = Common.toBytes(mDataNear);
        //  byte[] offset =  Common.toBytes(0x04);
        Log.d(TAG, "far data  = " + Common.toHexString(far));
        Log.d(TAG, "near data = " + Common.toHexString(near));
        try {
            RandomAccessFile file = new RandomAccessFile(CAL_FLIE, "rw");
            file.seek(SEEK_NEAR);
            file.writeByte(near[0]);
            file.writeByte(near[1]);
            file.seek(SEEK_FAR);
            file.writeByte(far[0]);
            file.writeByte(far[1]);
           /* file.seek(SEEK_OFFSET);
            file.writeByte(offset[0]);
            file.writeByte(offset[1]);*/
            file.close();
            return true;
        } catch (Exception e) {
            Log.wtf(TAG, e);
        }
        return false;
    }
}
