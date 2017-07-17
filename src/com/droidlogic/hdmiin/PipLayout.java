/*
 * Copyright (c) 2014 Amlogic, Inc. All rights reserved.
 *
 * This source code is subject to the terms and conditions defined in the
 * file 'LICENSE' which is part of this source code package.
 *
 * Description:
 */

package com.droidlogic.hdmiin;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.RelativeLayout;
import android.view.KeyEvent;
import android.util.Log;

public class PipLayout extends RelativeLayout {
    private static final String TAG = "PipLayout";
    private Context mContext = null;
    private boolean mKeycodeBackDown = false;
    public static final int FLOATWINDOW_MODE = 0;
    public static final int SWITCHFULL_MODE = 1;
    private int mMode = FLOATWINDOW_MODE;

    public PipLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    public void setMode(int mode) {
        mMode = mode;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        Log.d(TAG, this + " dispatchKeyEvent, event: " + event);
        if (KeyEvent.KEYCODE_BACK == event.getKeyCode() && KeyEvent.ACTION_DOWN == event.getAction()) {
            mKeycodeBackDown = true;
            return true;
        } else if (mKeycodeBackDown && KeyEvent.KEYCODE_BACK == event.getKeyCode() && KeyEvent.ACTION_UP == event.getAction()) {
            mKeycodeBackDown = false;
            if (mMode == SWITCHFULL_MODE)
                ((SwitchFullService)mContext).stopHdmiin(true);
            else
                ((FloatWindowService)mContext).stopHdmiin(true);
            return true;
        }
        if (KeyEvent.KEYCODE_F10 == event.getKeyCode() && KeyEvent.ACTION_DOWN == event.getAction()) {
            if (mMode == SWITCHFULL_MODE)
                ((SwitchFullService)mContext).updateViewFocusable(false);
            else
                ((FloatWindowService)mContext).updateViewFocusable(false);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
