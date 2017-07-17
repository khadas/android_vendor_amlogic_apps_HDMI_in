/*
 * Copyright (c) 2014 Amlogic, Inc. All rights reserved.
 *
 * This source code is subject to the terms and conditions defined in the
 * file 'LICENSE' which is part of this source code package.
 *
 * Description:
 */

package com.droidlogic.hdmiin;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.view.Gravity;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;

import com.droidlogic.app.SystemControlManager;
import com.droidlogic.app.HdmiInManager;

import java.util.Timer;
import java.util.TimerTask;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

public class SwitchFullService extends Service {
    private static final String TAG = "SwitchFullService";
    private Button mPipBtn = null;
    private Button mQuitBtn = null;
    private Context mContext = null;
    private int mInputSource = -1;
    public static boolean mIsFloating = false;
    private WindowManager mWindowManager = null;
    private WindowManager.LayoutParams mLayoutParams = null;
    private PipLayout mLayout = null;
    private boolean mIsShowingOnGraphic = false;
    private PowerManager.WakeLock mScreenLock = null;
    private int mHdmiInWidth = 0;
    private int mHdmiInHeight = 0;
    private int mHdmiInInterlace = -1;
    private int mHdmiInHz = -1;
    private TimerTask mHdmiInSizeTask = null;
    private Timer mHdmiInSizeTimer = null;
    private Handler mHdmiInSizeHandler = null;
    private boolean mHdmiPlugged = false;
    private TimerTask mAudioTask = null;
    private boolean mHdmiinStoped = false;
    private Timer mAudioTimer = null;
    private Handler mAudioHandler = null;
    private final int HDMI_IN_START = 0x10001;
    private final int HDMI_IN_STOP = 0x10002;
    private int mHdmiInStatus = HDMI_IN_STOP;
    private boolean mStartSent = false;
    private AudioManager mAudioManager;
    private boolean mAudioDeviceConnected = false;
    private static final String VOLUME_PROP = "mbx.hdmiin.vol";
    private static final String MUTE_PROP = "sys.hdmiin.mute";
    private static final String PIP_FOCUS_PROP = "mbx.hdmiin.pipfocus";
    private boolean mAudioRequested = false;
    private float mTouchStartX;
    private float mTouchStartY;
    private float x;
    private float y;
    private static final String OUTPUT_MODE_CHANGED = "android.amlogic.settings.CHANGE_OUTPUT_MODE";
    private static final String HDMIIN_PIP_FOCUS = "com.droidlogic.hdmiin.pipfocus";
    private static final String HDMIIN_PAUSE = "com.droidlogic.hdmiin.pause";
    private BroadcastReceiver mReceiver = null;
    private static final String V4OSD_PROP = "media.amplayer.v4osd.all";
    private String mDefaultV4OSD = "";
    private static final String MAIN_WINDOW_FULL_PROP = "mbx.hdmiin.mwfull";
    private HdmiInManager mHdmiInManager = null;
    private SystemControlManager mScm = null;
    private static int mDeviceInAuxDigital = -1;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case HDMI_IN_START:
                    Log.d(TAG, "HDMI_IN_START");
                    mHdmiInStatus = HDMI_IN_START;
                    mStartSent = false;
                    removeMessages(HDMI_IN_START);
                    if (mHdmiinStoped)
                        mHdmiinStoped = false;
                    mHdmiInManager.setEnable(true);
                    if (!mIsShowingOnGraphic) {
                        mIsShowingOnGraphic = true;
                        mHdmiInManager.startVideo();
                        mPipBtn.setOnClickListener(mPipBtnListener);
                    }
                    break;
                case HDMI_IN_STOP:
                    Log.d(TAG, "HDMI_IN_STOP");
                    mHdmiInStatus = HDMI_IN_STOP;
                    if (mStartSent) {
                        mHandler.removeMessages(HDMI_IN_START);
                        mStartSent = false;
                    }
                    stopAudioHandleTimer();
                    mHdmiInManager.stopVideo();
                    mHdmiInWidth = 0;
                    mHdmiInHeight = 0;
                    mHdmiInInterlace = -1;
                    mHdmiInHz = -1;
                    mIsShowingOnGraphic = false;
                    break;
            }
        }
    };

    private Button.OnClickListener mPipBtnListener = new Button.OnClickListener() {
        @Override
        public void onClick(View v) {
            mWindowManager.removeView(mLayout);
            if (mHdmiPlugged) {
                stopAudioHandleTimer();
                mHdmiInManager.stopVideo();
            }
            mIsShowingOnGraphic = false;
            mHdmiInManager.deinit();
            mHdmiInManager.setMainWindowFull();
            mScm.setProperty(MAIN_WINDOW_FULL_PROP, "true");
            mScm.setProperty(MUTE_PROP, "false");
            mHdmiinStoped = true;
            stopHdmiInSizeTimer();
            mIsFloating = false;

            mContext.stopService(new Intent(mContext, SwitchFullService.class));
            mScm.setProperty(V4OSD_PROP, mDefaultV4OSD);
            Intent intent = new Intent(mContext, FloatWindowService.class);
            intent.putExtra("source", mInputSource);
            mContext.startService(intent);
            FloatWindowService.mIsFloating = true;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void sendStartMessage(int arg1, int arg2, long delayMillis) {
        Message message = mHandler.obtainMessage(HDMI_IN_START, arg1, arg2);
        mHandler.sendMessageDelayed(message, delayMillis);
        mStartSent = true;
    }

    private void startHdmiin(final boolean needStop) {
        stopAudioHandleTimer();
        if (needStop) {
            mHdmiInManager.stopVideo();
            mIsShowingOnGraphic = false;
        }
        mHdmiInManager.setEnable(true);
        startAudioHandleTimer();
        mHdmiInManager.enableAudio(1);
        sendStartMessage(0, 0, 500);
    }

    public void stopHdmiin(boolean stopService) {
        mWindowManager.removeView(mLayout);
        stopAudioHandleTimer();
        stopHdmiInSizeTimer();
        if (mHdmiPlugged) {
            mHdmiInManager.stopVideo();
        }
        mHdmiInManager.deinit();
        mHdmiInManager.setMainWindowFull();
        mScm.setProperty(MAIN_WINDOW_FULL_PROP, "true");
        mScm.setProperty(MUTE_PROP, "false");
        mHdmiinStoped = true;
        mIsShowingOnGraphic = false;
        mIsFloating = false;
        if (stopService) {
            mContext.stopService(new Intent(mContext, SwitchFullService.class));
            mScm.setProperty(V4OSD_PROP, mDefaultV4OSD);
            openScreenOffTimeout();
        }
    }

    private void registerBroadcastReceiver() {
        if (mReceiver == null) {
            mReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action.equals(OUTPUT_MODE_CHANGED))
                        startHdmiin(true);
                    else if (action.equals(HDMIIN_PIP_FOCUS))
                        updateViewFocusable(true);
                    else if (action.equals(HDMIIN_PAUSE)) {
                        if (!mHdmiinStoped || mStartSent) {
                            if (mStartSent) {
                                mHandler.removeMessages(HDMI_IN_START);
                                mStartSent = false;
                            }
                            stopHdmiin(false);
                        } else
                            showPipWindow();
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(OUTPUT_MODE_CHANGED);
            filter.addAction(HDMIIN_PIP_FOCUS);
            filter.addAction(HDMIIN_PAUSE);
            registerReceiver(mReceiver, filter);
        }
    }

    private static void getDeviceInAuxDigital() {
        try {
            Class c = Class.forName("android.media.AudioSystem");
            Field f = c.getField("DEVICE_IN_AUX_DIGITAL");
            mDeviceInAuxDigital = f.getInt(null);
        } catch (Exception ex) {
            Log.e(TAG, "getDeviceInAuxDigital error: " + ex);
        }
        
        Log.d(TAG, "getDeviceInAuxDigital: " + mDeviceInAuxDigital);
    }

    private static void setWiredDeviceConnectionState(Object audioManagerObj, Object[] args) {
        try {
            Class c = audioManagerObj.getClass();
            Class[] argsClass = new Class[] {int.class, int.class, String.class};
            Method m = c.getMethod("setWiredDeviceConnectionState", argsClass);
            m.invoke(audioManagerObj, args);
        } catch (Exception ex) {
            Log.e(TAG, "setWiredDeviceConnectionState error: " + ex);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mContext = this;
        mInputSource = intent.getIntExtra("source", -1);
        registerBroadcastReceiver();
        mAudioManager = (AudioManager)mContext.getSystemService(Context.AUDIO_SERVICE);
        mScm = new SystemControlManager(this);
        mScm.setProperty(VOLUME_PROP, "15");
        mLayout = null;
        mHdmiinStoped = false;
        mScreenLock = ((PowerManager)mContext.getSystemService(Context.POWER_SERVICE)).
            newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, TAG);
        getDeviceInAuxDigital();
        mHdmiInManager = new HdmiInManager(this);
        showPipWindow();
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
            mReceiver = null;
        }

        if (mIsFloating) {
            mWindowManager.removeView(mLayout);
            if (mHdmiPlugged) {
                mHdmiInManager.stopVideo();
            }
            mHdmiInManager.deinit();
            mHdmiInManager.setMainWindowFull();
            mScm.setProperty(MAIN_WINDOW_FULL_PROP, "true");
            mScm.setProperty(MUTE_PROP, "false");
            mHdmiinStoped = true;
            stopHdmiInSizeTimer();
            mIsShowingOnGraphic = false;
            mIsFloating = false;
            mHdmiPlugged = false;
        }
        mScm.setProperty(PIP_FOCUS_PROP, "true");
    }

    private void gainLayoutParams(boolean focusable) {
        if (mLayoutParams == null) {
            mLayoutParams = new WindowManager.LayoutParams();
            mLayoutParams.type = WindowManager.LayoutParams.TYPE_PHONE;
            mLayoutParams.format = PixelFormat.RGBA_8888;
            mLayoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            if (!focusable) {
                mLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                mScm.setProperty(PIP_FOCUS_PROP, "false");
            } else {
                mLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                mScm.setProperty(PIP_FOCUS_PROP, "true");
            }
            mLayoutParams.gravity = Gravity.LEFT | Gravity.TOP;
            mLayoutParams.x = 100;
            mLayoutParams.y = 100;
            mLayoutParams.width = 450;
            mLayoutParams.height = 80;
        } else {
            if (!focusable) {
                mLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                mScm.setProperty(PIP_FOCUS_PROP, "false");
            } else {
                mLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                mScm.setProperty(PIP_FOCUS_PROP, "true");
            }
        }
    }

    public void updateViewFocusable(boolean focusable) {
        gainLayoutParams(focusable);
        mWindowManager.updateViewLayout(mLayout, mLayoutParams);
        if (mAudioRequested) {
            Log.d(TAG, "updateViewFocusable, abandonAudioFocus");
            abandonAudioFocus();
        } else {
            Log.d(TAG, "updateViewFocusable, requestAudioFocus");
            requestAudioFocus();
        }
    }

    private void showPipWindow() {
        if (mWindowManager == null)
            mWindowManager = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        gainLayoutParams(true);
        if (mLayout == null) {
            LayoutInflater inflater = (LayoutInflater)mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mLayout = (PipLayout)inflater.inflate(R.layout.pip_switchfull, null);
            mLayout.setMode(PipLayout.SWITCHFULL_MODE);
            mPipBtn = (Button)mLayout.findViewById(R.id.pip);
            mQuitBtn = (Button)mLayout.findViewById(R.id.quit);
        }
        mWindowManager.addView(mLayout, mLayoutParams);

        mLayout.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                x = event.getRawX();
                y = event.getRawY();
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        mTouchStartX = event.getX();
                        mTouchStartY = event.getY();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        updateViewPosition();
                        break;
                    case MotionEvent.ACTION_UP:
                        updateViewPosition();
                        mTouchStartX = 0;
                        mTouchStartY = 0;
                        break;
                }
                return true;
            }
        });

        mPipBtn.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.setOnClickListener(null);
            }
        });

        mQuitBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopHdmiin(true);
            }
        });

        mPipBtn.requestFocus();
        closeScreenOffTimeout();

        mDefaultV4OSD = mScm.getProperty(V4OSD_PROP);
        mScm.setProperty(V4OSD_PROP, "true");

        mHdmiInManager.init(mInputSource, true);
        mHdmiInManager.setMainWindowPosition(0, 0);
        mScm.setProperty(MAIN_WINDOW_FULL_PROP, "false");
        mHdmiPlugged = false;

        startHdmiInSizeTimer();
    }

    private void updateViewPosition() {
        mLayoutParams.x = (int)(x - mTouchStartX);
        mLayoutParams.y = (int)(y - mTouchStartY);
        mWindowManager.updateViewLayout(mLayout, mLayoutParams);
    }

    public void requestAudioFocus() {
        int status = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        if (!mAudioRequested) {
            status = mAudioManager.requestAudioFocus(mAudioFocusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
            if (status == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                mAudioRequested = true;
                mScm.setProperty(VOLUME_PROP, "15");
            }
        }
    }

    public void abandonAudioFocus() {
        int status = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        if (mAudioRequested) {
            status = mAudioManager.abandonAudioFocus(mAudioFocusListener);
            if (status == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                mAudioRequested = false;
                mScm.setProperty(VOLUME_PROP, "0");
            }
        }
    }

    private OnAudioFocusChangeListener mAudioFocusListener = new OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int focusChange) {
            int status = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
            Log.d(TAG, "onAudioFocusChange, focusChange: " + focusChange);
            if (!mAudioDeviceConnected && mAudioRequested) {
                Log.d(TAG, "onAudioFocusChange, abandonAudioFocus");
                status = mAudioManager.abandonAudioFocus(this);
                if (status == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    mAudioRequested = false;
                }
                return;
            }
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    Log.d(TAG, "onAudioFocusChange, AUDIOFOCUS_LOSS, volume 0");
                    mScm.setProperty(VOLUME_PROP, "0");
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    Log.d(TAG, "onAudioFocusChange, AUDIOFOCUS_GAIN, volume 15");
                    mScm.setProperty(VOLUME_PROP, "15");
                    break;
            }
        }
    };

    private void startAudioHandleTimer() {
        if (mAudioHandler == null) {
            mAudioHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    if (mAudioTimer == null || mAudioTask == null)
                        return;

                    int audioReady = mHdmiInManager.handleAudio();
                    if (audioReady == 1 && !mAudioDeviceConnected && mDeviceInAuxDigital != -1) {
                        setWiredDeviceConnectionState(mAudioManager, new Object[] {mDeviceInAuxDigital, 1, "hdmi in"});
                        mAudioDeviceConnected = true;
                        requestAudioFocus();
                    }
                    super.handleMessage(msg);
                }
            };
        }

        if (mAudioTask == null) {
            mAudioTask = new TimerTask() {
                @Override
                public void run() {
                    Message message = new Message();
                    message.what = 0;
                    mAudioHandler.sendMessage(message);
                }
            };
        }

        if (mAudioTimer == null) {
            mAudioTimer = new Timer();
            mAudioTimer.schedule(mAudioTask, 0, 20);
        }
    }

    private void stopAudioHandleTimer() {
        if (mAudioTimer != null) {
            mAudioTimer.cancel();
            mAudioTimer = null;
            mHdmiInManager.enableAudio(0);
        }
        if (mAudioTask != null) {
            mAudioTask.cancel();
            mAudioTask = null;
        }
        if (mAudioDeviceConnected && mDeviceInAuxDigital != -1) {
            setWiredDeviceConnectionState(mAudioManager, new Object[] {mDeviceInAuxDigital, 0, "hdmi in"});
            abandonAudioFocus();
        }
        mAudioDeviceConnected = false;
        mHdmiInManager.handleAudio();
    }

    private void startHdmiInSizeTimer() {
        if (mHdmiInSizeHandler == null) {
            mHdmiInSizeHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    if (mHdmiInSizeTimer == null || mHdmiInSizeTask == null)
                        return;
                    boolean enabled = mHdmiInManager.isEnable();
                    boolean plugged = mHdmiInManager.hdmiPlugged();
                    boolean signal = mHdmiInManager.hdmiSignal();
                    Log.d(TAG, "startHdmiInSizeTimer(), mHdmiPlugged: " + mHdmiPlugged + ", enabled: " + enabled + ", plugged: " + plugged + ", signal: " + signal);
                    if (!enabled || !plugged) {
                        if (mHdmiInStatus == HDMI_IN_START && mHdmiPlugged) {
                            Message message = mHandler.obtainMessage(HDMI_IN_STOP, 0, 0);
                            mHandler.sendMessageDelayed(message, 0);
                            mHdmiPlugged = false;
                        }
                        return;
                    }

                    String hdmiInMode = mHdmiInManager.getHdmiInSize();
                    boolean invalidMode = false;
                    if (TextUtils.isEmpty(hdmiInMode))
                        invalidMode = true;
                    else
                        Log.d(TAG, "startHdmiInSizeTimer(), hdmiInMode: " + hdmiInMode);

                    int width = 0;
                    int height = 0;
                    int interlace = -1;
                    int hz = -1;
                    String[] hdmiInSize = null;
                    if (!invalidMode) {
                        hdmiInSize = hdmiInMode.split(":");
                        if (hdmiInSize == null)
                            invalidMode = true;
                        else if (hdmiInSize.length == 1)
                            invalidMode = true;
                    }
                    if (invalidMode) {
                        mHdmiInWidth = 0;
                        mHdmiInHeight = 0;
                        mHdmiInInterlace = -1;
                        mHdmiInHz = -1;
                    }

                    if (!invalidMode) {
                        String mode = hdmiInSize[1];
                        for (int i = 0; i < FloatWindowService.MODES.length; i++) {
                            if (mode.equals(FloatWindowService.MODES[i])) {
                                width = FloatWindowService.MODES_WIDTH[i];
                                height = FloatWindowService.MODES_HEIGHT[i];
                                interlace = FloatWindowService.MODES_INTERLACE[i];
                                hz = FloatWindowService.MODES_HZ[i];
                                break;
                            }
                        }
                        Log.d(TAG, "startHdmiInSizeTimer(), width: " + width + ", height: " + height + ", interlace: " + interlace + ", hz: " + hz);

                        if (plugged && signal) {
                            if (!mHdmiPlugged)
                                mHdmiPlugged = true;
                            if (width > 0 && height > 0) {
                                if (mHdmiInWidth != width || mHdmiInHeight != height || mHdmiInInterlace != interlace || mHdmiInHz != hz || mHdmiinStoped) {
                                    boolean needStop = true;
                                    if (mHdmiInWidth == 0 && mHdmiInHeight == 0 && mHdmiInInterlace == -1 && mHdmiInHz == -1)
                                        needStop = false;
                                    stopAudioHandleTimer();
                                    if (needStop && !mHdmiinStoped) {
                                        mHdmiInManager.stopVideo();
                                        mIsShowingOnGraphic = false;
                                    }
                                    mHdmiInManager.setEnable(true);
                                    mHdmiInWidth = width;
                                    mHdmiInHeight = height;
                                    mHdmiInInterlace = interlace;
                                    mHdmiInHz = hz;
                                    startAudioHandleTimer();
                                    mHdmiInManager.enableAudio(1);
                                    sendStartMessage(0, 0, 500);
                                }
                            }
                        }
                    } else if (invalidMode && (!plugged || !signal)) {
                        if (mHdmiInStatus == HDMI_IN_START) {
                            Message message = mHandler.obtainMessage(HDMI_IN_STOP, 0, 0);
                            mHandler.sendMessageDelayed(message, 0);
                            mHdmiPlugged = false;
                        }
                    } else if (invalidMode) {
                        if (mStartSent) {
                            mHandler.removeMessages(HDMI_IN_START);
                            mStartSent = false;
                        }
                        if (mHdmiInStatus != HDMI_IN_STOP) {
                            Message message = mHandler.obtainMessage(HDMI_IN_STOP, 0, 0);
                            mHandler.sendMessageDelayed(message, 0);
                            mHdmiPlugged = false;
                        }
                    }

                    super.handleMessage(msg);
                }
            };
        }

        if (mHdmiInSizeTask == null) {
            mHdmiInSizeTask = new TimerTask() {
                @Override
                public void run() {
                    Message msg = new Message();
                    msg.what = 0;
                    mHdmiInSizeHandler.sendMessage(msg);
                }
            };
        }

        if (mHdmiInSizeTimer == null) {
            mHdmiInSizeTimer = new Timer();
            mHdmiInSizeTimer.schedule(mHdmiInSizeTask, 0, 500);
        }
    }

    private void stopHdmiInSizeTimer() {
        if (mHdmiInSizeTimer != null) {
            mHdmiInSizeTimer.cancel();
            mHdmiInSizeTimer = null;
        }

        if (mHdmiInSizeTask != null) {
            mHdmiInSizeTask.cancel();
            mHdmiInSizeTask = null;
        }
        mHdmiInWidth = 0;
        mHdmiInHeight = 0;
    }

    private void closeScreenOffTimeout() {
        if (!mScreenLock.isHeld())
            mScreenLock.acquire();
    }

    private void openScreenOffTimeout() {
        if (mScreenLock.isHeld())
            mScreenLock.release();
    }
}
