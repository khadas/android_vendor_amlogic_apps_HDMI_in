package com.amlogic.osdoverlay;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.SystemProperties;
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
import android.widget.OverlayView;
import android.widget.RelativeLayout;
import android.text.TextUtils;
import android.util.Log;

import java.util.Timer;
import java.util.TimerTask;

public class FloatWindowService extends Service implements SurfaceHolder.Callback
{
    private static final String TAG = "FloatWindowService";
    private static final String packageName = "com.amlogic.osdoverlay";
    private static final String fullActivityName = "com.amlogic.osdoverlay.FullActivity";
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

    public static boolean mIsFloating = false;

    private Context mContext = null;
    private int mInputSource = -1;
    private OverlayView mOverlayView = null;
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
    private TimerTask mHdmiInSizeTask = null;
    private Timer mHdmiInSizeTimer = null;
    private Handler mHdmiInSizeHandler = null;
    private boolean mHdmiPlugged = false;
    private static final String OUTPUT_MODE_CHANGED = "android.amlogic.settings.CHANGE_OUTPUT_MODE";
    private static final String HDMIIN_PIP_FOCUS = "com.amlogic.hdmiin.pipfocus";
    private BroadcastReceiver mReceiver = null;

    private Timer mAudioTimer = null;
    private Handler mAudioHandler = null;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);

            switch (msg.what) {
                case HDMI_IN_START:
                    Log.d(TAG, "HDMI_IN_START");
                    mHdmiInStatus = HDMI_IN_START;
                    if (msg.arg1 == STOP_MOV) {
                        mOverlayView.setVisibility(View.VISIBLE);
                        mOverlayView.setEnable(true);
                    }
                    int width = mOverlayView.getWidth();
                    int height = mOverlayView.getHeight();
                    Log.d(TAG, "HDMI_IN_START, width: " + width + ", height: " + height);

                    mOverlayView.displayPip(0, 0, width, height);
                    mOverlayView.invalidate();
                    
                    if (!isShowingOnGraphic) {
                        isShowingOnGraphic = true;
                        if (mSurfaceHolder != null) {
                            Surface sur = mSurfaceHolder.getSurface();
                            mOverlayView.setPreviewWindow(sur);
                            mOverlayView.startMov();
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
                    Log.d(TAG, "HDMI_IN_STOP, stopAudioHandleTimer");
                    stopAudioHandleTimer();
                    mOverlayView.displayPip(0, 0, 0, 0);
                    mOverlayView.invalidate();
                    mOverlayView.stopMov();
                    mHdmiInWidth = 0;
                    mHdmiInHeight = 0;
                    mHdmiInInterlace = -1;
                    if (msg.arg1 == SHOW_BLACK && mSurfaceHolder != null)
                        mOverlayView.setVisibility(View.INVISIBLE);
                    if (msg.arg2 == EXIT) {
                        hidePipWindow();
                        isShowingOnGraphic = false;
                        mOverlayView.deinit();
                        mIsFloating = false;
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

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startHdmiin(int flag) {
        Log.d(TAG, "startHdmiin(), stopAudioHandleTimer");
        stopAudioHandleTimer();
        if (flag == STOP_MOV) {
            mOverlayView.displayPip(0, 0, 0, 0);
            mOverlayView.invalidate();
            mOverlayView.stopMov();
            mOverlayView.setEnable(false);
            isShowingOnGraphic = false;
            mOverlayView.setVisibility(View.INVISIBLE);
        } else
            mOverlayView.setEnable(true);

        Log.d(TAG, "startHdmiin(), startAudioHandleTimer");
        startAudioHandleTimer();
        Log.d(TAG, "startHdmiin(), enableAudio 1");
        mOverlayView.enableAudio(1);
        Log.d(TAG, "startHdmiin(), HDMI_IN_START");
        Message message = mHandler.obtainMessage(HDMI_IN_START, flag, 0);
        mHandler.sendMessageDelayed(message, 500);
    }

	public void stopHdmiin() {
		Log.d(TAG, "stopHdmiin, hidePipWindow");
		hidePipWindow();
		Log.d(TAG, "stopHdmiin, stopAudioHandleTimer");
		stopAudioHandleTimer();
		Log.d(TAG, "stopHdmiin, stopHdmiInSizeTimer");
		stopHdmiInSizeTimer();
		if (mHdmiPlugged) {
			mOverlayView.displayPip(0, 0, 0, 0);
			mOverlayView.invalidate();
			Log.d(TAG, "stopHdmiin, stopMov");
			mOverlayView.stopMov();
		}
		Log.d(TAG, "stopHdmiin, deinit");
		mOverlayView.deinit();
		isShowingOnGraphic = false;
		mIsFloating = false;
		Log.d(TAG, "stopHdmiin, stop FloatWindowService");
		mContext.stopService(new Intent(mContext, FloatWindowService.class));
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
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(OUTPUT_MODE_CHANGED);
            filter.addAction(HDMIIN_PIP_FOCUS);
            registerReceiver(mReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mContext = this;
        mInputSource = intent.getIntExtra("source", -1);
        Log.d(TAG, "onStartCommand(), mInputSource: " + mInputSource);
        registerOutputModeChangedListener();
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
            if (mHdmiPlugged) {
                mOverlayView.displayPip(0, 0, 0, 0);
                mOverlayView.invalidate();
                Log.d(TAG, "mQuitBtn onClick(), stopMov");
                mOverlayView.stopMov();
            }
            mOverlayView.deinit();
            stopHdmiInSizeTimer();
            isShowingOnGraphic = false;
            mIsFloating = false;
            mHdmiPlugged = false;
        }
        SystemProperties.set(PIP_FOCUS_PROP, "true");
    }

	private void gainLayoutParams(boolean focusable) {
        if (mLayoutParams == null) {
            mLayoutParams = new WindowManager.LayoutParams();
            mLayoutParams.type = WindowManager.LayoutParams.TYPE_PHONE;
            mLayoutParams.format = PixelFormat.RGBA_8888;
            mLayoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
			if (!focusable) {
				mLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                SystemProperties.set(PIP_FOCUS_PROP, "false");
            } else {
				mLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                SystemProperties.set(PIP_FOCUS_PROP, "true");
            }
            mLayoutParams.gravity = Gravity.LEFT | Gravity.TOP;
            mLayoutParams.x = 100;
            mLayoutParams.y = 100;
            mLayoutParams.width = 560;
            mLayoutParams.height = 420;
        } else {
			if (!focusable) {
				mLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                SystemProperties.set(PIP_FOCUS_PROP, "false");
            } else {
				mLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                SystemProperties.set(PIP_FOCUS_PROP, "true");
            }
		}
	}

	public void updateViewFocusable(boolean focusable) {
		gainLayoutParams(focusable);
		mWindowManager.updateViewLayout(mLayout, mLayoutParams);
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
            mOverlayView = (OverlayView)mLayout.findViewById(R.id.surfaceview);
            mSurfaceHolder = mOverlayView.getHolder();
            mSurfaceHolder.addCallback(this);
            mOverlayView.setZOrderOnTop(false);

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

        mFullBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent();
                ComponentName name = new ComponentName(packageName, fullActivityName);
                intent.setComponent(name);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra("source", mInputSource);

                hidePipWindow();
                if (mHdmiPlugged) {
                    stopAudioHandleTimer();
                    mOverlayView.displayPip(0, 0, 0, 0);
                    mOverlayView.invalidate();
                    mOverlayView.stopMov();
                }
                isShowingOnGraphic = false;
                mOverlayView.deinit();
                stopHdmiInSizeTimer();
                mSurfaceCreated = false;

                mIsFloating = false;
                Log.d(TAG, "mFullBtn, stop FloatWindowService");
                mContext.stopService(new Intent(mContext, FloatWindowService.class));
                mContext.startActivity(intent);
            }
        });

        mQuitBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
				stopHdmiin();
            }
        });

        mFullBtn.requestFocus();
        if (mOverlayView != null) {
            mOverlayView.init(mInputSource);
        }
        mHdmiPlugged = false;
        startHdmiInSizeTimer();
    }

    private void updateViewPosition() {
        mLayoutParams.x = (int)(x - mTouchStartX);
        mLayoutParams.y = (int)(y - mTouchStartY);
        mWindowManager.updateViewLayout(mLayout, mLayoutParams);
    }

    private void startAudioHandleTimer() {
        if (mAudioHandler == null) {
            mAudioHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    if (mOverlayView != null) {
                        mOverlayView.handleAudio();
                    }
                    super.handleMessage(msg);
                }
            };
        }

        if (mAudioTimer == null) {
            mAudioTimer = new Timer();
            mAudioTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    Message message = new Message();
                    message.what = 0;
                    mAudioHandler.sendMessage(message);
                }
            }, 0, 20);
        }
    }

    private void stopAudioHandleTimer() {
        if (mAudioTimer != null) {
            mAudioTimer.cancel();
            mAudioTimer = null;
            Log.d(TAG, "stopAudioHandleTimer(), enableAudio 0");
            mOverlayView.enableAudio(0);
        }
        if (mOverlayView != null) {
            Log.d(TAG, "stopAudioHandleTimer() invoke handleAudio()");
            mOverlayView.handleAudio();
        }
    }

    private void startHdmiInSizeTimer() {
        if (mHdmiInSizeHandler == null) {
            mHdmiInSizeHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    if (mOverlayView != null && mSurfaceCreated) {
                        boolean enabled = mOverlayView.isEnable();
                        Log.d(TAG, "startHdmiInSizeTimer(), enabled: " + enabled);
                        boolean plugged = mOverlayView.hdmiPlugged();
                        Log.d(TAG, "startHdmiInSizeTimer(), plugged: " + plugged);
                        boolean signal = mOverlayView.hdmiSignal();
                        Log.d(TAG, "startHdmiInSizeTimer(), signal: " + signal);
                        if (!enabled || (!plugged && !signal)) {
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
                            for (int i = 0; i < FullActivity.MODES.length; i++) {
                                if (mode.equals(FullActivity.MODES[i])) {
                                    width = FullActivity.MODES_WIDTH[i];
                                    height = FullActivity.MODES_HEIGHT[i];
                                    interlace = FullActivity.MODES_INTERLACE[i];
                                    break;
                                }
                            }
                            Log.d(TAG, "startHdmiInSizeTimer(), width: " + width + ", height: " + height + ", interlace: " + interlace);

                            if (plugged && signal) {
                                if (!mHdmiPlugged)
                                    mHdmiPlugged = true;

                                if (width > 0 && height > 0) {
                                    if (mHdmiInWidth != width || mHdmiInHeight != height || mHdmiInInterlace != interlace) {
                                        int flag = STOP_MOV;
                                        if (mHdmiInWidth == 0 && mHdmiInHeight == 0)
                                            flag = START_MOV;

                                        Log.d(TAG, "startHdmiInSizeTimer(), stopAudioHandleTimer");
                                        stopAudioHandleTimer();
                                        if (flag == STOP_MOV) {
                                            mOverlayView.displayPip(0, 0, 0, 0);
                                            mOverlayView.invalidate();
                                            mOverlayView.stopMov();
                                            mOverlayView.setEnable(false);
                                            isShowingOnGraphic = false;
                                            mOverlayView.setVisibility(View.INVISIBLE);
                                        } else
                                            mOverlayView.setEnable(true);

                                        mHdmiInWidth = width;
                                        mHdmiInHeight = height;
                                        mHdmiInInterlace = interlace;
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
                        } else if (invalidMode && !plugged) {
                            if (mHdmiInStatus == HDMI_IN_START) {
                                Log.d(TAG, "startHdmiInSizeTimer(), HDMI_IN_STOP, SHOW_BLACK");
                                Message message = mHandler.obtainMessage(HDMI_IN_STOP, SHOW_BLACK, 0);
                                mHandler.sendMessageDelayed(message, 500);
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
