package com.amlogic.osdoverlay;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.RelativeLayout;
import android.view.KeyEvent;
import android.util.Log;

public class PipLayout extends RelativeLayout {
    private static final String TAG = "PipLayout";
    private Context mContext = null;
    private boolean mKeycodeBackDown = false;

    public PipLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        Log.d(TAG, this + " dispatchKeyEvent, event: " + event);
        if (KeyEvent.KEYCODE_BACK == event.getKeyCode() && KeyEvent.ACTION_DOWN == event.getAction()) {
            mKeycodeBackDown = true;
            return true;
        } else if (mKeycodeBackDown && KeyEvent.KEYCODE_BACK == event.getKeyCode() && KeyEvent.ACTION_UP == event.getAction()) {
            mKeycodeBackDown = false;
            ((FloatWindowService)mContext).stopHdmiin(true);
            return true;
        }
        if (KeyEvent.KEYCODE_F10 == event.getKeyCode() && KeyEvent.ACTION_DOWN == event.getAction()) {
            ((FloatWindowService)mContext).updateViewFocusable(false);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
