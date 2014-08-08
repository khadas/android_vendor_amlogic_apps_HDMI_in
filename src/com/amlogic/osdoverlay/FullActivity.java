package com.amlogic.osdoverlay;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
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
import android.media.AudioSystem;

import android.widget.Button;
import android.widget.ImageView;
import android.widget.OverlayView;

import java.util.Timer;
import java.util.TimerTask;

public class FullActivity extends Activity implements SurfaceHolder.Callback
{
    private static final String TAG = "FullActivity";
    private SurfaceHolder mSurfaceHolder = null;
    private boolean mSurfaceCreated = false;

    private OverlayView mOverlayView = null;

    private Button mPipBtn = null;
    private Button mQuitBtn = null;

    private Context mContext = null;
    private int mInputSource = -1;

    public static final String[] MODES = {"1080p", "1080p50hz", "1080i", "1080i50hz", "720p", "720p50hz", "480p", "480i", "576p", "576i"};
    public static final int[] MODES_INTERLACE = {0, 0, 1, 1, 0, 0, 0, 1, 0, 1};
    public static final int[] MODES_WIDTH = {1920, 1920, 1920, 1920, 1280, 1280, 720, 720, 720, 720};
    public static final int[] MODES_HEIGHT = {1080, 1080, 1080, 1080, 720, 720, 480, 480, 576, 576};
    public static final int[] MODES_HZ = {60, 50, 60, 50, 60, 50, 0, 0, 0, 0};
    private int mHdmiInWidth = 0;
    private int mHdmiInHeight = 0;
    private int mHdmiInInterlace = -1;
    private int mHdmiInHz = -1;
    private TimerTask mHdmiInSizeTask = null;
    private Timer mHdmiInSizeTimer = null;
    private Handler mHdmiInSizeHandler = null;
    private boolean mHdmiPlugged = false;

    private TimerTask mAudioTask = null;
    private Timer mAudioTimer = null;
    private Handler mAudioHandler = null;
    private final int HDMI_IN_START = 0x10001;
    private final int HDMI_IN_STOP = 0x10002;
    private final int STOP_MOV = 1;
    private final int START_MOV = 2;
    private final int SHOW_BLACK = 3;
    private final int EXIT = 4;
    private int mHdmiInStatus = HDMI_IN_STOP;
    private AudioManager mAudioManager;
    private boolean mAudioDeviceConnected = false;
    private static final String VOLUME_PROP = "mbx.hdmiin.vol";
    private boolean mAudioRequested = false;

    private Button.OnClickListener mPipBtnListener = new Button.OnClickListener() {
        @Override
        public void onClick(View v) {
            Log.d(TAG, "mPipBtn onClick(), stopAudioHandleTimer");
            stopAudioHandleTimer();
            startPip();
            finish();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) 
    {
        super.onCreate(savedInstanceState);

        mContext = this;
        mInputSource = getIntent().getIntExtra("source", -1);
        Log.d(TAG, "onCreate(), mInputSource: " + mInputSource);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, 
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        setContentView(R.layout.activity_full);

        mSurfaceCreated = false;
        mOverlayView = (OverlayView)findViewById(R.id.surfaceview);
        mSurfaceHolder = mOverlayView.getHolder();
        mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        mSurfaceHolder.addCallback(this);
        mAudioManager = (AudioManager)mContext.getSystemService(Context.AUDIO_SERVICE);
        Log.d(TAG, "onCreate(), mbx.hdmiin.vol: 15");
        SystemProperties.set(VOLUME_PROP, "15");

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
                Log.d(TAG, "mQuitBtn onClick(), stopHdmiInSizeTimer");
                stopHdmiInSizeTimer();
                if (mHdmiPlugged) {
                    mOverlayView.displayPip(0, 0, 0, 0);
                    mOverlayView.invalidate();
                    Log.d(TAG, "mQuitBtn onClick(), stopMov");
                    mOverlayView.stopMov();
                }
                mOverlayView.deinit();
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

        if (mOverlayView != null) {
            mHdmiPlugged = false;
            Log.d(TAG, "onResume(), init");
            mOverlayView.init(mInputSource);
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
                SystemProperties.set(VOLUME_PROP, "15");
            }
        }
    }

    public void abandonAudioFocus() {
        int status = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        if (mAudioRequested) {
            status = mAudioManager.abandonAudioFocus(mAudioFocusListener);
            if (status == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                mAudioRequested = false;
                SystemProperties.set(VOLUME_PROP, "0");
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
                    SystemProperties.set(VOLUME_PROP, "0");
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    Log.d(TAG, "onAudioFocusChange, AUDIOFOCUS_GAIN, volume 15");
                    SystemProperties.set(VOLUME_PROP, "15");
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

                    int audioReady = 0;
                    if (mOverlayView != null) {
                        audioReady = mOverlayView.handleAudio();
                        if (audioReady == 1 && !mAudioDeviceConnected) {
                            mAudioManager.setWiredDeviceConnectionState(AudioSystem.DEVICE_IN_AUX_DIGITAL, 1, "hdmi in");
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
            mOverlayView.enableAudio(0);
        }
        if (mAudioTask != null) {
            mAudioTask.cancel();
            mAudioTask = null;
        }
        if (mOverlayView != null) {
            if (mAudioDeviceConnected) {
                mAudioManager.setWiredDeviceConnectionState(AudioSystem.DEVICE_IN_AUX_DIGITAL, 0, "hdmi in");
                Log.d(TAG, "stopAudioHandleTimer, abandonAudioFocus");
                abandonAudioFocus();
            }
            mAudioDeviceConnected = false;
            Log.d(TAG, "stopAudioHandleTimer() invoke handleAudio()");
            mOverlayView.handleAudio();
        }
    }

    private void startHdmiInSizeTimer() {
        if (mHdmiInSizeHandler == null) {
            mHdmiInSizeHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    if (mHdmiInSizeTimer == null || mHdmiInSizeTask == null)
                        return;

                    if (mOverlayView != null && mSurfaceCreated) {
                        boolean enabled = mOverlayView.isEnable();
                        Log.d(TAG, "startHdmiInSizeTimer(), enabled: " + enabled);
                        boolean plugged = mOverlayView.hdmiPlugged();
                        Log.d(TAG, "startHdmiInSizeTimer(), plugged: " + plugged);
                        boolean signal = mOverlayView.hdmiSignal();
                        Log.d(TAG, "startHdmiInSizeTimer(), signal: " + signal);
                        if (!enabled || !plugged) {
                            if (mHdmiInStatus == HDMI_IN_START && mHdmiPlugged) {
                                Log.d(TAG, "startHdmiInSizeTimer(), HDMI_IN_STOP, SHOW_BLACK, EXIT");
                                Message message = mHandler.obtainMessage(HDMI_IN_STOP, SHOW_BLACK, EXIT);
                                mHandler.sendMessageDelayed(message, 0);
                                mHdmiPlugged = false;
                            }
                            return;
                        }

                        String hdmiInMode = mOverlayView.getHdmiInSize();
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
                                    if (mHdmiInWidth != width || mHdmiInHeight != height || mHdmiInInterlace != interlace || mHdmiInHz != hz) {
                                        int flag = STOP_MOV;
                                        if (mHdmiInWidth == 0 && mHdmiInHeight == 0 && mHdmiInInterlace == -1 && mHdmiInHz == -1)
                                            flag = START_MOV;

                                        Log.d(TAG, "startHdmiInSizeTimer(), stopAudioHandleTimer");
                                        stopAudioHandleTimer();
                                        if (flag == STOP_MOV) {
                                            mOverlayView.displayPip(0, 0, 0, 0);
                                            mOverlayView.invalidate();
                                            mOverlayView.stopMov();
                                            mOverlayView.setEnable(false);
                                            mOverlayView.setVisibility(View.INVISIBLE);
                                        } else
                                            mOverlayView.setEnable(true);

                                        mHdmiInWidth = width;
                                        mHdmiInHeight = height;
                                        mHdmiInInterlace = interlace;
                                        mHdmiInHz = hz;
                                        Log.d(TAG, "startHdmiInSizeTimer(), startAudioHandleTimer");
                                        startAudioHandleTimer();
                                        Log.d(TAG, "startHdmiInSizeTimer(), enableAudio 1");
                                        mOverlayView.enableAudio(1);
                                        Log.d(TAG, "startHdmiInSizeTimer(), HDMI_IN_START");
                                        Message message = mHandler.obtainMessage(HDMI_IN_START, flag, 0);
                                        mHandler.sendMessageDelayed(message, 500);
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
        if (mHdmiPlugged) {
            mOverlayView.displayPip(0, 0, 0, 0);
            mOverlayView.invalidate();
            mOverlayView.stopMov();
        }
        mOverlayView.deinit();
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
                startPip();
                finish();
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

            switch(msg.what)
            {
                case HDMI_IN_START:
                    {
                        Log.d(TAG, "HDMI_IN_START");
                        mHdmiInStatus = HDMI_IN_START;
                        if (msg.arg1 == STOP_MOV) {
                            mOverlayView.setVisibility(View.VISIBLE);
                            mOverlayView.setEnable(true);
                        }

                        Log.d(TAG, "width: " + mHdmiInWidth + ", height: " + mHdmiInHeight);
                        mOverlayView.displayPip(0, 0, mHdmiInWidth, mHdmiInHeight);
                        mOverlayView.invalidate();
                        if (mSurfaceHolder != null) {
                            Surface sur = mSurfaceHolder.getSurface();
                            mOverlayView.setPreviewWindow(sur);
                            mOverlayView.startMov();
                            mPipBtn.setOnClickListener(mPipBtnListener);
                        }
                    }
                    break;
                case HDMI_IN_STOP: {
                    Log.d(TAG, "HDMI_IN_STOP");
                    mHdmiInStatus = HDMI_IN_STOP;
                    if (msg.arg2 == EXIT) {
                        Log.d(TAG, "HDMI_IN_STOP, stopHdmiInSizeTimer");
                        stopHdmiInSizeTimer();
                    }
                    if (msg.arg1 == SHOW_BLACK && mSurfaceHolder != null)
                        mOverlayView.setVisibility(View.INVISIBLE);
                    Log.d(TAG, "HDMI_IN_STOP, stopAudioHandleTimer");
                    stopAudioHandleTimer();
                    mOverlayView.displayPip(0, 0, 0, 0);
                    mOverlayView.invalidate();
                    mOverlayView.stopMov();
                    mHdmiInWidth = 0;
                    mHdmiInHeight = 0;
                    mHdmiInInterlace = -1;
                    mHdmiInHz = -1;
                    if (msg.arg2 == EXIT) {
                        mOverlayView.deinit();
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
        mSurfaceCreated = true;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed()");
    }
}
