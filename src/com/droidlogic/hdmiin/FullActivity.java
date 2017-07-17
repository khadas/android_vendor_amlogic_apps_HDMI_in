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
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.text.TextUtils;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;

import android.widget.Button;
import android.widget.ImageView;

import com.droidlogic.app.SystemControlManager;
import com.droidlogic.app.HdmiInManager;

import java.util.Timer;
import java.util.TimerTask;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

public class FullActivity extends Activity implements SurfaceHolder.Callback
{
    private static final String TAG = "FullActivity";
    private SurfaceHolder mSurfaceHolder = null;
    private boolean mSurfaceCreated = false;

    private SurfaceView mSurfaceView = null;
    private SystemControlManager mScm = null;
    private HdmiInManager mHdmiInManager = null;

    private Button mPipBtn = null;
    private Button mQuitBtn = null;

    private Context mContext = null;
    private int mInputSource = -1;

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
    private final int STOP_MOV = 1;
    private final int START_MOV = 2;
    private final int SHOW_BLACK = 3;
    private final int EXIT = 4;
    private int mHdmiInStatus = HDMI_IN_STOP;
    private boolean mStartSent = false;
    private AudioManager mAudioManager;
    private boolean mAudioDeviceConnected = false;
    private static final String VOLUME_PROP = "mbx.hdmiin.vol";
    private static final String MUTE_PROP = "sys.hdmiin.mute";
    private boolean mAudioRequested = false;
    private static boolean mUseVideoLayer  = true;
    private PowerManager.WakeLock mScreenLock = null;
    private static int mDeviceInAuxDigital = -1;

    private Button.OnClickListener mPipBtnListener = new Button.OnClickListener() {
        @Override
        public void onClick(View v) {
            Log.d(TAG, "mPipBtn onClick(), stopAudioHandleTimer");
            stopAudioHandleTimer();
            startPip();
            finish();
        }
    };

    private void closeScreenOffTimeout() {
        if (mScreenLock.isHeld() == false)
            mScreenLock.acquire();
    }

    private void openScreenOffTimeout() {
        if (mScreenLock.isHeld() == true)
            mScreenLock.release();
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
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mContext = this;
        mInputSource = getIntent().getIntExtra("source", -1);
        Log.d(TAG, "onCreate(), mInputSource: " + mInputSource);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        setContentView(R.layout.activity_full);

        mScreenLock = ((PowerManager)mContext.getSystemService(Context.POWER_SERVICE))
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, TAG);
        getDeviceInAuxDigital();
        mScm = new SystemControlManager(this);
        mHdmiInManager = new HdmiInManager(this);
        mSurfaceCreated = false;
        mSurfaceView = (SurfaceView)findViewById(R.id.surfaceview);
        mSurfaceHolder = mSurfaceView.getHolder();
        mUseVideoLayer  = mScm.getPropertyBoolean("mbx.hdmiin.videolayer", true);
        // if (mUseVideoLayer)
        //     mSurfaceHolder.setFormat(PixelFormat.VIDEO_HOLE_REAL);
        mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        mSurfaceHolder.addCallback(this);
        mAudioManager = (AudioManager)mContext.getSystemService(Context.AUDIO_SERVICE);
        Log.d(TAG, "onCreate(), mbx.hdmiin.vol: 15");
        mScm.setProperty(VOLUME_PROP, "15");
        mHdmiinStoped = false;

        mPipBtn = (Button)findViewById(R.id.pip);

        mPipBtn.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.setOnClickListener(null);
            }
        });

        mQuitBtn = (Button)findViewById(R.id.quit);
        mQuitBtn.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "mQuitBtn onClick(), stopAudioHandleTimer");
                stopAudioHandleTimer();
                Log.d(TAG, "mQuitBtn, onClick(), setVisibility INVISIBLE");
                mSurfaceView.setVisibility(View.INVISIBLE);
                Log.d(TAG, "mQuitBtn onClick(), stopHdmiInSizeTimer");
                stopHdmiInSizeTimer();
                if (mHdmiPlugged) {
                    if (mUseVideoLayer) {
                        mHdmiInManager.stopVideo();
                    } else {
                        mHdmiInManager.displayOSD(0, 0);
                        mSurfaceView.invalidate();
                        Log.d(TAG, "mQuitBtn onClick(), stopMov");
                        mHdmiInManager.stopMov();
                    }
                }
                mHdmiInManager.deinit();
                mScm.setProperty(MUTE_PROP, "false");
                mHdmiinStoped = true;
                mHdmiPlugged = false;

                Log.d(TAG, "mQuitBtn onClick(), finish");
                finish();
            }
        });
    }

    @Override
    protected void onResume()
    {
        super.onResume();

        if (mHdmiInManager != null) {
            closeScreenOffTimeout();
            mHdmiPlugged = false;
            Log.d(TAG, "onResume(), init");
            mHdmiInManager.init(mInputSource, true);
            startHdmiInSizeTimer();
        }
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

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause");
        if (mHdmiInManager != null && (!mHdmiinStoped || mStartSent)) {
            if (mStartSent) {
                Log.d(TAG, "onPause, mStartSent");
                mHandler.removeMessages(HDMI_IN_START);
                mStartSent = false;
            }
            Log.d(TAG, "onPause, stopAudioHandleTimer");
            stopAudioHandleTimer();
            Log.d(TAG, "onPause, setVisibility INVISIBLE");
            mSurfaceView.setVisibility(View.INVISIBLE);
            Log.d(TAG, "onPause, stopHdmiInSizeTimer");
            stopHdmiInSizeTimer();
            if (mHdmiPlugged) {
                if (mUseVideoLayer) {
                    mHdmiInManager.stopVideo();
                } else {
                    mHdmiInManager.displayOSD(0, 0);
                    mSurfaceView.invalidate();
                    Log.d(TAG, "onPause, stopMov");
                    mHdmiInManager.stopMov();
                }
            }
            mHdmiInManager.deinit();
            mScm.setProperty(MUTE_PROP, "false");
            mHdmiinStoped = true;
            mHdmiPlugged = false;
            openScreenOffTimeout();
        }

        super.onPause();
    }

    private void startAudioHandleTimer() {
        if (mAudioHandler == null) {
            mAudioHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    if (mAudioTimer == null || mAudioTask == null)
                        return;

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
        Log.d(TAG, "stopAudioHandleTimer()");
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

    private boolean isSurfaceAvailable() {
        if (mSurfaceHolder == null)
            return false;
        return mHdmiInManager.isSurfaceAvailable(mSurfaceHolder.getSurface());
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
                            Log.d(TAG, "startHdmiInSizeTimer, setVisibility VISIBLE");
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
                                        int flag = STOP_MOV;
                                        if (mHdmiInWidth == 0 && mHdmiInHeight == 0 && mHdmiInInterlace == -1 && mHdmiInHz == -1)
                                            flag = START_MOV;

                                        Log.d(TAG, "startHdmiInSizeTimer(), stopAudioHandleTimer");
                                        stopAudioHandleTimer();
                                        if (flag == STOP_MOV && !mHdmiinStoped) {
                                            if (mUseVideoLayer) {
                                                mHdmiInManager.stopVideo();
                                            } else {
                                                mHdmiInManager.displayOSD(0, 0);
                                                mSurfaceView.invalidate();
                                                mHdmiInManager.stopMov();
                                            }
                                            // mHdmiInManager.setEnable(false);
                                            Log.d(TAG, "startHdmiInSizeTimer, setVisibility INVISIBLE");
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
                                        Message message = mHandler.obtainMessage(HDMI_IN_START, flag, 0);
                                        mHandler.sendMessageDelayed(message, 500);
                                        mStartSent = true;
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
        Log.d(TAG, "stopHdmiInSizeTimer()");
        if (mHdmiInSizeTimer != null) {
            mHdmiInSizeTimer.cancel();
            mHdmiInSizeTimer = null;
        }

        if (mHdmiInSizeTask != null) {
            mHdmiInSizeTask.cancel();
            mHdmiInSizeTask = null;
        }
    }

    private void startPip() {
        Log.d(TAG, "startPip()");
        Log.d(TAG, "startPip(), setVisibility INVISIBLE");
        mSurfaceView.setVisibility(View.INVISIBLE);
        if (mHdmiPlugged) {
            if (mUseVideoLayer) {
                mHdmiInManager.stopVideo();
            } else {
                mHdmiInManager.displayOSD(0, 0);
                mSurfaceView.invalidate();
                mHdmiInManager.stopMov();
            }
        }
        mHdmiInManager.deinit();
        mScm.setProperty(MUTE_PROP, "false");
        mHdmiinStoped = true;
        stopHdmiInSizeTimer();
        mSurfaceCreated = false;

        Intent intent = new Intent(FullActivity.this, FloatWindowService.class);
        intent.putExtra("source", mInputSource);
        Log.d(TAG, "startPip(), start FloatWindowService");
        startService(intent);
        FloatWindowService.mIsFloating = true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event)
    {
        if (keyCode == KeyEvent.KEYCODE_BACK)
        {
            if (event.getAction() == KeyEvent.ACTION_UP)
            {
                if (mHdmiInStatus == HDMI_IN_START) {
                    Log.d(TAG, "onKeyUp(), stopAudioHandleTimer");
                    stopAudioHandleTimer();
                    startPip();
                    finish();
                }
                return true;
            }
        }

        return super.onKeyUp(keyCode, event);
    }

    private Handler mHandler = new Handler()
    {
        @Override
        public void handleMessage(Message msg)
        {
            super.handleMessage(msg);

            switch (msg.what)
            {
                case HDMI_IN_START:
                    {
                        Log.d(TAG, "HDMI_IN_START");
                        mHdmiInStatus = HDMI_IN_START;
                        mStartSent = false;
                        removeMessages(HDMI_IN_START);
                        if (mHdmiinStoped)
                            mHdmiinStoped = false;
                        if (msg.arg1 == STOP_MOV) {
                            Log.d(TAG, "HDMI_IN_START, setVisibility VISIBLE");
                            mSurfaceView.setVisibility(View.VISIBLE);
                        }
                        mHdmiInManager.setEnable(true);

                        Log.d(TAG, "width: " + mHdmiInWidth + ", height: " + mHdmiInHeight);
                        mHdmiInManager.displayOSD(mHdmiInWidth, mHdmiInHeight);
                        mSurfaceView.invalidate();
                        if (mSurfaceHolder != null) {
                            Surface sur = mSurfaceHolder.getSurface();
                            if (mUseVideoLayer) {
                                mHdmiInManager.startVideo();
                            } else {
                                if (mHdmiInManager.setPreviewWindow(sur))
                                    mHdmiInManager.startMov();
                            }
                            mPipBtn.setOnClickListener(mPipBtnListener);
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
                    if (msg.arg1 == SHOW_BLACK && mSurfaceHolder != null) {
                        Log.d(TAG, "HDMI_IN_STOP, setVisibility INVISIBLE");
                        mSurfaceView.setVisibility(View.INVISIBLE);
                    }
                    Log.d(TAG, "HDMI_IN_STOP, stopAudioHandleTimer");
                    stopAudioHandleTimer();
                    if (mUseVideoLayer) {
                        mHdmiInManager.stopVideo();
                    } else {
                        mHdmiInManager.displayOSD(0, 0);
                        mSurfaceView.invalidate();
                        mHdmiInManager.stopMov();
                    }
                    mHdmiInWidth = 0;
                    mHdmiInHeight = 0;
                    mHdmiInInterlace = -1;
                    mHdmiInHz = -1;
                    if (msg.arg2 == EXIT) {
                        mHdmiInManager.deinit();
                        mScm.setProperty(MUTE_PROP, "false");
                        mHdmiinStoped = true;
                        finish();
                    }
                }
                break;
            }
        }
    };

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
}
