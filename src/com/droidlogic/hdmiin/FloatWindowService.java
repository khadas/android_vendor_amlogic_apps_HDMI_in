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
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.graphics.PixelFormat;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.text.TextUtils;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.util.Log;

import com.droidlogic.app.SystemControlManager;
import com.droidlogic.app.HdmiInManager;

import java.util.Timer;
import java.util.TimerTask;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

public class FloatWindowService extends Service implements SurfaceHolder.Callback
{
    private static final String TAG = "FloatWindowService";
    public static final String[] MODES = {"1080p", "1080p50hz", "1080i", "1080i50hz", "720p", "720p50hz", "480p", "480i", "576p", "576i"};
    public static final int[] MODES_INTERLACE = {0, 0, 1, 1, 0, 0, 0, 1, 0, 1};
    public static final int[] MODES_WIDTH = {1920, 1920, 1920, 1920, 1280, 1280, 720, 720, 720, 720};
    public static final int[] MODES_HEIGHT = {1080, 1080, 1080, 1080, 720, 720, 480, 480, 576, 576};
    public static final int[] MODES_HZ = {60, 50, 60, 50, 60, 50, 0, 0, 0, 0};
    private static final String packageName = "com.droidlogic.hdmiin";
    private static final String fullActivityName = "com.droidlogic.hdmiin.FullActivity";
    private static final String PIP_FOCUS_PROP = "mbx.hdmiin.pipfocus";
    private static final int HDMI_IN_START = 0x10001;
    private static final int HDMI_IN_STOP = 0x10002;
    private final int STOP_MOV = 1;
    private final int START_MOV = 2;
    private final int SHOW_BLACK = 3;
    private final int EXIT = 4;
    private SurfaceHolder mSurfaceHolder = null;
    private boolean mSurfaceCreated = false;
    private int mHdmiInStatus = HDMI_IN_STOP;
    private boolean mStartSent = false;

    public static boolean mIsFloating = false;

    private Context mContext = null;
    private int mInputSource = -1;
    private SurfaceView mSurfaceView = null;
    private HdmiInManager mHdmiInManager = null;
    private SystemControlManager mScm = null;
    private Button mFullBtn = null;
    private Button mQuitBtn = null;
    private WindowManager mWindowManager = null;
    private WindowManager.LayoutParams mLayoutParams = null;
    private PipLayout mLayout = null;
    private boolean isShowingOnGraphic = false;
    private int statusBarHeight = 0;
    private float mTouchStartX;
    private float mTouchStartY;
    private float x;
    private float y;

    private int mHdmiInWidth = 0;
    private int mHdmiInHeight = 0;
    private int mHdmiInInterlace = -1;
    private int mHdmiInHz = -1;
    private TimerTask mHdmiInSizeTask = null;
    private Timer mHdmiInSizeTimer = null;
    private Handler mHdmiInSizeHandler = null;
    private boolean mHdmiPlugged = false;
    private boolean mHdmiinStoped = false;
    private static final String OUTPUT_MODE_CHANGED = "android.amlogic.settings.CHANGE_OUTPUT_MODE";
    private static final String HDMIIN_PIP_FOCUS = "com.droidlogic.hdmiin.pipfocus";
    private static final String HDMIIN_PAUSE = "com.droidlogic.hdmiin.pause";
    private BroadcastReceiver mReceiver = null;
    private Handler mSurfaceAvailableHandler = new Handler();

    private TimerTask mAudioTask = null;
    private Timer mAudioTimer = null;
    private Handler mAudioHandler = null;
    private AudioManager mAudioManager;
    private boolean mAudioDeviceConnected = false;
    private static final String VOLUME_PROP = "mbx.hdmiin.vol";
    private static final String MUTE_PROP = "sys.hdmiin.mute";
    private boolean mAudioRequested = false;
    private PowerManager.WakeLock mScreenLock = null;
    private static boolean mSwitchFullMode = false;
    private static final String V4OSD_PROP = "media.amplayer.v4osd.all";
    private String mDefaultV4OSD = "";
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
                    if (msg.arg1 == STOP_MOV) {
                        mSurfaceView.setVisibility(View.VISIBLE);
                    }
                    mHdmiInManager.setEnable(true);
                    int width = mSurfaceView.getWidth();
                    int height = mSurfaceView.getHeight();
                    Log.d(TAG, "HDMI_IN_START, width: " + width + ", height: " + height);

                    mHdmiInManager.displayOSD(width, height);
                    mSurfaceView.invalidate();
                    
                    if (!isShowingOnGraphic) {
                        isShowingOnGraphic = true;
                        if (mSurfaceHolder != null) {
                            Surface sur = mSurfaceHolder.getSurface();
                            if (mHdmiInManager.setPreviewWindow(sur))
                                mHdmiInManager.startMov();
                            mFullBtn.setOnClickListener(mFullBtnListener);
                        }
                    }
                    break;
                case HDMI_IN_STOP: {
                    Log.d(TAG, "HDMI_IN_STOP");
                    mHdmiInStatus = HDMI_IN_STOP;
                    if (mStartSent) {
                        Log.d(TAG, "HDMI_IN_STOP, mStartSent");
                        mHandler.removeMessages(HDMI_IN_START);
                        mStartSent = false;
                    }
                    if (msg.arg2 == EXIT) {
                        Log.d(TAG, "HDMI_IN_STOP, stopHdmiInSizeTimer");
                        stopHdmiInSizeTimer();
                    }
                    Log.d(TAG, "HDMI_IN_STOP, stopAudioHandleTimer");
                    stopAudioHandleTimer();
                    mHdmiInManager.displayOSD(0, 0);
                    mSurfaceView.invalidate();
                    mHdmiInManager.stopMov();
                    mHdmiInWidth = 0;
                    mHdmiInHeight = 0;
                    mHdmiInInterlace = -1;
                    mHdmiInHz = -1;
                    if (msg.arg1 == SHOW_BLACK && mSurfaceHolder != null)
                        mSurfaceView.setVisibility(View.INVISIBLE);
                    if (msg.arg2 == EXIT) {
                        hidePipWindow();
                        mHdmiInManager.deinit();
                        mScm.setProperty(MUTE_PROP, "false");
                        mHdmiinStoped = true;
                        mIsFloating = false;
                    }
                    isShowingOnGraphic = false;
                }
                break;
            }
        }
    };

    private Button.OnClickListener mFullBtnListener = new Button.OnClickListener() {
        @Override
        public void onClick(View v) {
            hidePipWindow();
            mSurfaceView.setVisibility(View.INVISIBLE);
            if (mHdmiPlugged) {
                stopAudioHandleTimer();
                mHdmiInManager.displayOSD(0, 0);
                mSurfaceView.invalidate();
                mHdmiInManager.stopMov();
            }
            isShowingOnGraphic = false;
            mHdmiInManager.deinit();
            mScm.setProperty(MUTE_PROP, "false");
            mHdmiinStoped = true;
            stopHdmiInSizeTimer();
            mSurfaceCreated = false;

            mIsFloating = false;
            Log.d(TAG, "mFullBtn, stop FloatWindowService");
            mContext.stopService(new Intent(mContext, FloatWindowService.class));
            if (mSwitchFullMode)
                mScm.setProperty(V4OSD_PROP, mDefaultV4OSD);
            openScreenOffTimeout();

            Intent intent = null;
            if (mSwitchFullMode) {
                intent = new Intent(mContext, SwitchFullService.class);
                intent.putExtra("source", mInputSource);
                startService(intent);
                SwitchFullService.mIsFloating = true;
            } else {
                intent = new Intent();
                ComponentName name = new ComponentName(packageName, fullActivityName);
                intent.setComponent(name);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra("source", mInputSource);
                mContext.startActivity(intent);
            }
        }
    };

    private void closeScreenOffTimeout() {
        if(mScreenLock.isHeld() == false)
            mScreenLock.acquire();
    }
    
    private void openScreenOffTimeout() {
        if(mScreenLock.isHeld() == true)
            mScreenLock.release();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged(), format: " + format + ", width: " + width + ", height: " + height);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated()");
        if (holder != null) {
            Canvas canvas = holder.lockCanvas();
            Log.d(TAG, "surfaceCreated(), drawColor TRANSPARENT");
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            holder.unlockCanvasAndPost(canvas);
        } else
            Log.d(TAG, "surfaceCreated(), holder == null");
        mSurfaceCreated = true;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed()");
        mSurfaceCreated = false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void sendStartMessage(int arg1, int arg2, long delayMillis) {
        Message message = mHandler.obtainMessage(HDMI_IN_START, arg1, arg2);
        mHandler.sendMessageDelayed(message, delayMillis);
        mStartSent = true;
    }

    private boolean isSurfaceAvailable() {
        if (mSurfaceHolder == null)
            return false;
        return mHdmiInManager.isSurfaceAvailable(mSurfaceHolder.getSurface());
    }

    private void startHdmiin(final int flag) {
        Log.d(TAG, "startHdmiin(), stopAudioHandleTimer");
        stopAudioHandleTimer();
        if (flag == STOP_MOV) {
            mHdmiInManager.displayOSD(0, 0);
            mSurfaceView.invalidate();
            mHdmiInManager.stopMov();
            // mHdmiInManager.setEnable(false);
            isShowingOnGraphic = false;
            mSurfaceView.setVisibility(View.INVISIBLE);
        }
        mHdmiInManager.setEnable(true);

        Log.d(TAG, "startHdmiin(), startAudioHandleTimer");
        startAudioHandleTimer();
        Log.d(TAG, "startHdmiin(), enableAudio 1");
        mHdmiInManager.enableAudio(1);

        if (isSurfaceAvailable()) {
            Log.d(TAG, "startHdmiin(), HDMI_IN_START");
            sendStartMessage(flag, 0, 500);
        } else {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    if (!isSurfaceAvailable()) {
                        Log.d(TAG, "startHdmiin(), surace unavailable, delay 100ms");
                        mSurfaceAvailableHandler.postDelayed(this, 100);
                    } else {
                        Log.d(TAG, "startHdmiin(), HDMI_IN_START");
                        sendStartMessage(flag, 0, 500);
                        mSurfaceAvailableHandler.removeCallbacks(this);
                    }
                }
            };
            Log.d(TAG, "startHdmiin(), surace unavailable, delay 100ms");
            mSurfaceAvailableHandler.postDelayed(runnable, 100);
        }
    }

    public void stopHdmiin(boolean stopService) {
        Log.d(TAG, "stopHdmiin, hidePipWindow");
        hidePipWindow();
        mSurfaceView.setVisibility(View.INVISIBLE);
        Log.d(TAG, "stopHdmiin, stopAudioHandleTimer");
        stopAudioHandleTimer();
        Log.d(TAG, "stopHdmiin, stopHdmiInSizeTimer");
        stopHdmiInSizeTimer();
        if (mHdmiPlugged) {
            mHdmiInManager.displayOSD(0, 0);
            mSurfaceView.invalidate();
            Log.d(TAG, "stopHdmiin, stopMov");
            mHdmiInManager.stopMov();
        }
        Log.d(TAG, "stopHdmiin, deinit");
        mHdmiInManager.deinit();
        mScm.setProperty(MUTE_PROP, "false");
        mHdmiinStoped = true;
        isShowingOnGraphic = false;
        mIsFloating = false;
        if (stopService) {
            Log.d(TAG, "stopHdmiin, stop FloatWindowService");
            mContext.stopService(new Intent(mContext, FloatWindowService.class));
            if (mSwitchFullMode)
                mScm.setProperty(V4OSD_PROP, mDefaultV4OSD);
            openScreenOffTimeout();
        }
    }

    private void registerOutputModeChangedListener() {
        if (mReceiver == null) {
            mReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action.equals(OUTPUT_MODE_CHANGED)) {
                        startHdmiin(STOP_MOV);
                    } else if (action.equals(HDMIIN_PIP_FOCUS)) {
                        updateViewFocusable(true);
                    } else if (action.equals(HDMIIN_PAUSE)) {
                        if (!mHdmiinStoped || mStartSent) {
                            if (mStartSent) {
                                Log.d(TAG, "onReceive, mStartSent");
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
        Log.d(TAG, "onStartCommand(), mInputSource: " + mInputSource);
        registerOutputModeChangedListener();
        mAudioManager = (AudioManager)mContext.getSystemService(Context.AUDIO_SERVICE);
        mScm = new SystemControlManager(this);
        mScm.setProperty(VOLUME_PROP, "15");
        mLayout = null;
        mHdmiinStoped = false;
        mScreenLock = ((PowerManager)mContext.getSystemService(Context.POWER_SERVICE))
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, TAG);
        getDeviceInAuxDigital();
        mSwitchFullMode = mScm.getPropertyBoolean("mbx.hdmiin.switchfull", false);
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

        Log.d(TAG, "onDestroy(), mIsFloating: " + mIsFloating);
        if (mIsFloating) {
            hidePipWindow();
            mSurfaceView.setVisibility(View.INVISIBLE);
            if (mHdmiPlugged) {
                mHdmiInManager.displayOSD(0, 0);
                mSurfaceView.invalidate();
                Log.d(TAG, "onDestroy(), stopMov");
                mHdmiInManager.stopMov();
            }
            mHdmiInManager.deinit();
            mScm.setProperty(MUTE_PROP, "false");
            mHdmiinStoped = true;
            stopHdmiInSizeTimer();
            isShowingOnGraphic = false;
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
            mLayoutParams.width = 560;
            mLayoutParams.height = 420;
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
        if (mSwitchFullMode) {
            if (mAudioRequested) {
                Log.d(TAG, "updateViewFocusable, abandonAudioFocus");
                abandonAudioFocus();
            } else {
                Log.d(TAG, "updateViewFocusable, requestAudioFocus");
                requestAudioFocus();
            }
        }
    }

    private void showPipWindow() {
        if (mWindowManager == null)
            mWindowManager = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);

        statusBarHeight = 0;

        gainLayoutParams(true);
        if (mLayout == null) {
            LayoutInflater inflater = (LayoutInflater)mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mLayout = (PipLayout)inflater.inflate(R.layout.pip, null);
            Log.d(TAG, "showPipWindow, mLayout: " + mLayout);

            mSurfaceCreated = false;
            mSurfaceView = (SurfaceView)mLayout.findViewById(R.id.surfaceview);
            mSurfaceHolder = mSurfaceView.getHolder();
            mSurfaceHolder.addCallback(this);
            mSurfaceView.setZOrderOnTop(false);

            mFullBtn = (Button)mLayout.findViewById(R.id.full);
            mQuitBtn = (Button)mLayout.findViewById(R.id.quit);
        }

        mWindowManager.addView(mLayout, mLayoutParams);

        mLayout.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                x = event.getRawX();
                y = event.getRawY() - statusBarHeight;

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

        mFullBtn.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
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

        mFullBtn.requestFocus();
        closeScreenOffTimeout();
        if (mHdmiInManager != null) {
            if (mSwitchFullMode) {
                mDefaultV4OSD = mScm.getProperty(V4OSD_PROP);
                mScm.setProperty(V4OSD_PROP, "true");
            }
            mHdmiInManager.init(mInputSource, false);
        }
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

                    switch (msg.what) {
                        case 0:
                            int audioReady = 0;
                            if (mHdmiInManager != null) {
                                audioReady = mHdmiInManager.handleAudio();
                                if (audioReady == 1 && !mAudioDeviceConnected && mDeviceInAuxDigital != -1) {
                                    setWiredDeviceConnectionState(mAudioManager, new Object[] {mDeviceInAuxDigital, 1, "hdmi in"});
                                    mAudioDeviceConnected = true;
                                    Log.d(TAG, "startAudioHandleTimer, requestAudioFocus");
                                    requestAudioFocus();
                                }
                            }
                            break;
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
            Log.d(TAG, "stopAudioHandleTimer(), enableAudio 0");
            mHdmiInManager.enableAudio(0);
        }
        if (mAudioTask != null) {
            mAudioTask.cancel();
            mAudioTask = null;
        }
        if (mHdmiInManager != null) {
            if (mAudioDeviceConnected && mDeviceInAuxDigital != -1) {
                setWiredDeviceConnectionState(mAudioManager, new Object[] {mDeviceInAuxDigital, 0, "hdmi in"});
                Log.d(TAG, "stopAudioHandleTimer, abandonAudioFocus");
                abandonAudioFocus();
            }
            mAudioDeviceConnected = false;
            Log.d(TAG, "stopAudioHandleTimer() invoke handleAudio()");
            mHdmiInManager.handleAudio();
        }
    }

    private void startHdmiInSizeTimer() {
        if (mHdmiInSizeHandler == null) {
            mHdmiInSizeHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    if (mHdmiInSizeTimer == null || mHdmiInSizeTask == null)
                        return;
                    if (mSurfaceView == null || mHdmiInManager == null)
                        return;

                    boolean enabled = mHdmiInManager.isEnable();
                    boolean plugged = mHdmiInManager.hdmiPlugged();
                    boolean signal = mHdmiInManager.hdmiSignal();
                    Log.d(TAG, "startHdmiInSizeTimer(), mSurfaceCreated: " + mSurfaceCreated + ", mHdmiPlugged: " + mHdmiPlugged + ", enabled: " + enabled + ", plugged: " + plugged + ", signal: " + signal);

                    if (!mHdmiPlugged && enabled && plugged && signal) {
                        if (!mSurfaceCreated) {
                            mSurfaceView.setVisibility(View.VISIBLE);
                        }
                    }
                    if (!isSurfaceAvailable())
                        return;

                    if (mSurfaceCreated) {
                        if (!enabled || !plugged) {
                            if (mHdmiInStatus == HDMI_IN_START && mHdmiPlugged) {
                                Log.d(TAG, "startHdmiInSizeTimer(), HDMI_IN_STOP, SHOW_BLACK");
                                Message message = mHandler.obtainMessage(HDMI_IN_STOP, SHOW_BLACK, 0);
                                mHandler.sendMessageDelayed(message, 0);
                                mHdmiPlugged = false;
                            }
                            return;
                        }

                        String hdmiInMode = mHdmiInManager.getHdmiInSize();
                        boolean invalidMode = false;
                        if (TextUtils.isEmpty(hdmiInMode)) {
                            Log.d(TAG, "startHdmiInSizeTimer(), hdmiInMode: null");
                            invalidMode = true;
                        } else
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
                            if (hdmiInSize.length == 1)
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
                            for (int i = 0; i < MODES.length; i++) {
                                if (mode.equals(MODES[i])) {
                                    width = MODES_WIDTH[i];
                                    height = MODES_HEIGHT[i];
                                    interlace = MODES_INTERLACE[i];
                                    hz = MODES_HZ[i];
                                    break;
                                }
                            }
                            Log.d(TAG, "startHdmiInSizeTimer(), width: " + width + ", height: " + height + ", interlace: " + interlace + ", hz: " + hz);

                            if (plugged && signal) {
                                if (!mHdmiPlugged)
                                    mHdmiPlugged = true;

                                if (width > 0 && height > 0) {
                                    if (mHdmiInWidth != width || mHdmiInHeight != height || mHdmiInInterlace != interlace || mHdmiInHz != hz || mHdmiinStoped) {
                                        int flag = STOP_MOV;
                                        if (mHdmiInWidth == 0 && mHdmiInHeight == 0 && mHdmiInInterlace == -1 && mHdmiInHz == -1)
                                            flag = START_MOV;

                                        Log.d(TAG, "startHdmiInSizeTimer(), stopAudioHandleTimer");
                                        stopAudioHandleTimer();
                                        if (flag == STOP_MOV && !mHdmiinStoped) {
                                            mHdmiInManager.displayOSD(0, 0);
                                            mSurfaceView.invalidate();
                                            mHdmiInManager.stopMov();
                                            // mHdmiInManager.setEnable(false);
                                            isShowingOnGraphic = false;
                                            mSurfaceView.setVisibility(View.INVISIBLE);
                                        }
                                        mHdmiInManager.setEnable(true);

                                        mHdmiInWidth = width;
                                        mHdmiInHeight = height;
                                        mHdmiInInterlace = interlace;
                                        mHdmiInHz = hz;
                                        Log.d(TAG, "startHdmiInSizeTimer(), startAudioHandleTimer");
                                        startAudioHandleTimer();
                                        Log.d(TAG, "startHdmiInSizeTimer(), enableAudio 1");
                                        mHdmiInManager.enableAudio(1);
                                        Log.d(TAG, "startHdmiInSizeTimer(), HDMI_IN_START");
                                        sendStartMessage(flag, 0, 500);
                                    }
                                }
                            }
                        } else if (invalidMode && (!plugged || !signal)) {
                            if (mHdmiInStatus == HDMI_IN_START) {
                                Log.d(TAG, "startHdmiInSizeTimer(), HDMI_IN_STOP, SHOW_BLACK");
                                Message message = mHandler.obtainMessage(HDMI_IN_STOP, SHOW_BLACK, 0);
                                mHandler.sendMessageDelayed(message, 0);
                                mHdmiPlugged = false;
                            }
                        } else if (invalidMode) {
                            if (mStartSent) {
                                Log.d(TAG, "startHdmiInSizeTimer, mStartSent");
                                mHandler.removeMessages(HDMI_IN_START);
                                mStartSent = false;
                            }
                            if (mHdmiInStatus != HDMI_IN_STOP) {
                                Log.d(TAG, "startHdmiInSizeTimer(), invalidMode, HDMI_IN_STOP, SHOW_BLACK");
                                Message message = mHandler.obtainMessage(HDMI_IN_STOP, SHOW_BLACK, 0);
                                mHandler.sendMessageDelayed(message, 0);
                                mHdmiPlugged = false;
                            }
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

    private void hidePipWindow() {
        mWindowManager.removeView(mLayout);
    }
}
