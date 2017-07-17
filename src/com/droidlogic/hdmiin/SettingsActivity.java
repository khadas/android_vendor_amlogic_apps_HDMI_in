/*
 * Copyright (c) 2014 Amlogic, Inc. All rights reserved.
 *
 * This source code is subject to the terms and conditions defined in the
 * file 'LICENSE' which is part of this source code package.
 *
 * Description:
 */

package com.droidlogic.hdmiin;

import android.app.Activity;
import android.app.DialogFragment;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.os.Bundle;
import android.util.Log;

import com.droidlogic.app.SystemControlManager;

import java.io.File;

public class SettingsActivity extends Activity {
    private static final String TAG = "SettingsActivity";
    private static final String SII9233A_PATH = "/sys/class/sii9233a/enable";
    private static SystemControlManager mScm = null;
    private static boolean mSwitchFullMode = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mScm = new SystemControlManager(this);
        mSwitchFullMode = mScm.getPropertyBoolean("mbx.hdmiin.switchfull", true);
        if (mSwitchFullMode) {
            if (SwitchFullService.mIsFloating)
                finish();
            if (FloatWindowService.mIsFloating)
                finish();
        } else {
            if (FloatWindowService.mIsFloating)
                finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!new File(SII9233A_PATH).exists()) {
            Log.d(TAG, SII9233A_PATH + " not exists");
            Intent intent = null;
            if (mSwitchFullMode) {
                intent = new Intent(SettingsActivity.this, SwitchFullService.class);
                intent.putExtra("source", -1);
                startService(intent);
                SwitchFullService.mIsFloating = true;
            } else {
                intent = new Intent(SettingsActivity.this, FullActivity.class);
                intent.putExtra("source", -1);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            }
            finish();
        } else {
            Log.d(TAG, SII9233A_PATH + " exists");
            DialogFragment fragment = ChooseDialogFragment.newInstance(this.getResources().getText(R.string.settings_activity).toString());
            fragment.show(getFragmentManager(), "HdmiIn");
        }
    }

    public void doConfirm(int inputSource) {
        Log.d(TAG, "doConfirm()");
        Intent intent = null;
        if (mSwitchFullMode) {
            intent = new Intent(SettingsActivity.this, SwitchFullService.class);
            intent.putExtra("source", inputSource);
            startService(intent);
            SwitchFullService.mIsFloating = true;
        } else {
            intent = new Intent(SettingsActivity.this, FullActivity.class);
            intent.putExtra("source", inputSource);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        }
        finish();
    }

    public void doDiscard() {
        Log.d(TAG, "doDiscard()");
        finish();
    }

    public void doDismiss() {
        Log.d(TAG, "doDismiss()");
        finish();
    }

    @Override
    public void onPause() {
        super.onPause();
    }
}
