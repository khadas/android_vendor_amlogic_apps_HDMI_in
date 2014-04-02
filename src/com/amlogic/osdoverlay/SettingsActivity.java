package com.amlogic.osdoverlay;

import android.app.Activity;
import android.app.DialogFragment;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.os.Bundle;
import android.util.Log;

import java.io.File;

public class SettingsActivity extends Activity {
    private static final String TAG = "SettingsActivity";
    private static final String SII9233A_PATH = "/sys/class/sii9233a/enable";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

		if (FloatWindowService.mIsFloating) {
            finish();
		} else
			Log.d(TAG, "service is stop");
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!new File(SII9233A_PATH).exists()) {
            Log.d(TAG, SII9233A_PATH + " not exists");
            Intent intent = new Intent(SettingsActivity.this, FullActivity.class);
            intent.putExtra("source", -1);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        } else {
            Log.d(TAG, SII9233A_PATH + " exists");
            DialogFragment fragment = ChooseDialogFragment.newInstance(this.getResources().getText(R.string.settings_activity).toString());
            fragment.show(getFragmentManager(), "HdmiIn");
        }
    }

    public void doConfirm(int inputSource) {
        Log.d(TAG, "doConfirm()");
        Intent intent = new Intent(SettingsActivity.this, FullActivity.class);
        intent.putExtra("source", inputSource);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
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
