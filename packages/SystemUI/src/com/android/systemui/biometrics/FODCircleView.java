/**
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.biometrics;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.ContentResolver;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.text.TextUtils;
import android.widget.ImageView;
import androidx.palette.graphics.Palette;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.R;

import vendor.omni.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreen;
import vendor.omni.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreenCallback;

import java.io.FileDescriptor;
import java.util.NoSuchElementException;
import java.util.Timer;
import java.util.TimerTask;

public class FODCircleView extends ImageView {
    private final int mPositionX;
    private final int mPositionY;
    private final int mSize;
    private final int mDreamingMaxOffset;
    private final int mNavigationBarSize;
    private final boolean mShouldBoostBrightness;
    private final Paint mPaintFingerprint = new Paint();
    private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();
    private final WindowManager mWindowManager;

    private IFingerprintInscreen mFingerprintInscreenDaemon;

    private int mDreamingOffsetX;
    private int mDreamingOffsetY;

    private boolean mIsBouncer;
    private boolean mIsDreaming;
    private boolean mIsKeyguard;
    private boolean mIsShowing;
    private boolean mIsCircleShowing;

    private Handler mHandler;

    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;

    private Timer mBurnInProtectionTimer;
    private int iconcolor = 0xFF3980FF;

    private FODAnimation mFODAnimation;
    private boolean mIsRecognizingAnimEnabled;

    private IFingerprintInscreenCallback mFingerprintInscreenCallback =
            new IFingerprintInscreenCallback.Stub() {
        @Override
        public void onFingerDown() {
            mHandler.post(() -> showCircle());
        }

        @Override
        public void onFingerUp() {
            mHandler.post(() -> hideCircle());
        }
    };

    private KeyguardUpdateMonitor mUpdateMonitor;

    private KeyguardUpdateMonitorCallback mMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onDreamingStateChanged(boolean dreaming) {
            mIsDreaming = dreaming;
            updateAlpha();

            if (dreaming) {
                mBurnInProtectionTimer = new Timer();
                mBurnInProtectionTimer.schedule(new BurnInProtectionTask(), 0, 60 * 1000);
            } else if (mBurnInProtectionTimer != null) {
                mBurnInProtectionTimer.cancel();
            }
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            mIsKeyguard = showing;
            updatePosition();
            if (mFODAnimation != null) {
                mFODAnimation.setAnimationKeyguard(mIsKeyguard);
            }
        }

        @Override
        public void onKeyguardBouncerChanged(boolean isBouncer) {
            mIsBouncer = isBouncer;

            if (isBouncer) {
                hide();
            } else if (mUpdateMonitor.isFingerprintDetectionRunning()) {
                show();
            }
        }

        @Override
        public void onScreenTurnedOff() {
            hideCircle();
        }
    };

    public FODCircleView(Context context) {
        super(context);

        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        if (daemon == null) {
            throw new RuntimeException("Unable to get IFingerprintInscreen");
        }

        try {
            mShouldBoostBrightness = daemon.shouldBoostBrightness();
            mPositionX = daemon.getPositionX();
            mPositionY = daemon.getPositionY();
            mSize = daemon.getSize();
        } catch (RemoteException e) {
            throw new RuntimeException("Failed to retrieve FOD circle position or size");
        }

        Resources res = context.getResources();

        mPaintFingerprint.setAntiAlias(true);
        mPaintFingerprint.setColor(res.getColor(R.color.config_fodColor));

        mWindowManager = context.getSystemService(WindowManager.class);

        mNavigationBarSize = res.getDimensionPixelSize(R.dimen.navigation_bar_size);

        mDreamingMaxOffset = (int) (mSize * 0.1f);

        mHandler = new Handler(Looper.getMainLooper());

        mParams.height = mSize;
        mParams.width = mSize;
        mParams.format = PixelFormat.TRANSLUCENT;

        mParams.setTitle("Fingerprint on display");
        mParams.packageName = "android";
        mParams.type = WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY;
        mParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_DIM_BEHIND |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        mParams.gravity = Gravity.TOP | Gravity.LEFT;

        mWindowManager.addView(this, mParams);
        updatePosition();
        hide();

        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(context);
        mUpdateMonitor.registerCallback(mMonitorCallback);

        mPowerManager = context.getSystemService(PowerManager.class);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                FODCircleView.class.getSimpleName());

        mFODAnimation = new FODAnimation(context, mPositionX, mPositionY);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mIsCircleShowing) {
            canvas.drawCircle(mSize / 2, mSize / 2, mSize / 2.0f, mPaintFingerprint);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if (mIsCircleShowing) {
            dispatchPress();
        } else {
            dispatchRelease();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getAxisValue(MotionEvent.AXIS_X);
        float y = event.getAxisValue(MotionEvent.AXIS_Y);

        boolean newIsInside = (x > 0 && x < mSize) && (y > 0 && y < mSize);

        if (event.getAction() == MotionEvent.ACTION_DOWN && newIsInside) {
            showCircle();
            if (mIsRecognizingAnimEnabled) {
                mFODAnimation.showFODanimation();
            }
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            hideCircle();
            mFODAnimation.hideFODanimation();
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            return true;
        }
        mFODAnimation.hideFODanimation();
        return false;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        updatePosition();
    }

    public IFingerprintInscreen getFingerprintInScreenDaemon() {
        if (mFingerprintInscreenDaemon == null) {
            try {
                mFingerprintInscreenDaemon = IFingerprintInscreen.getService();
                if (mFingerprintInscreenDaemon != null) {
                    mFingerprintInscreenDaemon.setCallback(mFingerprintInscreenCallback);
                    mFingerprintInscreenDaemon.asBinder().linkToDeath((cookie) -> {
                        mFingerprintInscreenDaemon = null;
                    }, 0);
                }
            } catch (NoSuchElementException | RemoteException e) {
                // do nothing
            }
        }
        return mFingerprintInscreenDaemon;
    }

    public void dispatchPress() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onPress();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchRelease() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onRelease();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchShow() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onShowFODView();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchHide() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onHideFODView();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void showCircle() {
        mIsCircleShowing = true;

        setKeepScreenOn(true);

        if (mIsDreaming) mWakeLock.acquire(500);
        setDim(true);
        updateAlpha();

        setImageDrawable(null);
        invalidate();
    }

    public void hideCircle() {
        mIsCircleShowing = false;

        setCustomIcon();
        if (mFODAnimation != null) {
            mFODAnimation.setFODAnim();
        }
        invalidate();

        setDim(false);
        updateAlpha();

        setKeepScreenOn(false);
    }

    private boolean useWallpaperColor() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.FOD_ICON_WALLPAPER_COLOR, 0) != 0;
    }

    private int getFODIcon() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.FOD_ICON, 0);
    }

/**    private void setFODIcon() {
        int fodicon = getFODIcon();

        mIsRecognizingAnimEnabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.FOD_RECOGNIZING_ANIMATION, 0) != 0;

        if (fodicon == 0) {
            this.setImageResource(R.drawable.fod_icon_default_0);
        } else if (fodicon == 1) {
            this.setImageResource(R.drawable.fod_icon_default_1);
        } else if (fodicon == 2) {
            this.setImageResource(R.drawable.fod_icon_default_2);
        } else if (fodicon == 3) {
            this.setImageResource(R.drawable.fod_icon_default_3);
        } else if (fodicon == 4) {
            this.setImageResource(R.drawable.fod_icon_default_4);
        } else if (fodicon == 5) {
            this.setImageResource(R.drawable.fod_icon_default_5);
        } else if (fodicon == 6) {
            this.setImageResource(R.drawable.fod_icon_arc_reactor);
        } else if (fodicon == 7) {
            this.setImageResource(R.drawable.fod_icon_cpt_america_flat);
        } else if (fodicon == 8) {
            this.setImageResource(R.drawable.fod_icon_cpt_america_flat_gray);
        } else if (fodicon == 9) {
            this.setImageResource(R.drawable.fod_icon_dragon_black_flat);
        } else if (fodicon == 10) {
            this.setImageResource(R.drawable.fod_icon_future);
        } else if (fodicon == 11) {
            this.setImageResource(R.drawable.fod_icon_glow_circle);
        } else if (fodicon == 12) {
            this.setImageResource(R.drawable.fod_icon_neon_arc);
        } else if (fodicon == 13) {
            this.setImageResource(R.drawable.fod_icon_neon_arc_gray);
        } else if (fodicon == 14) {
            this.setImageResource(R.drawable.fod_icon_neon_circle_pink);
        } else if (fodicon == 15) {
            this.setImageResource(R.drawable.fod_icon_neon_triangle);
        } else if (fodicon == 16) {
            this.setImageResource(R.drawable.fod_icon_paint_splash_circle);
        } else if (fodicon == 17) {
            this.setImageResource(R.drawable.fod_icon_rainbow_horn);
        } else if (fodicon == 18) {
            this.setImageResource(R.drawable.fod_icon_shooky);
        } else if (fodicon == 19) {
            this.setImageResource(R.drawable.fod_icon_spiral_blue);
        } else if (fodicon == 20) {
            this.setImageResource(R.drawable.fod_icon_sun_metro);
        }


        if (useWallpaperColor()) {
            try {
                WallpaperManager wallpaperManager = WallpaperManager.getInstance(mContext);
                Drawable wallpaperDrawable = wallpaperManager.getDrawable();
                Bitmap bitmap = ((BitmapDrawable)wallpaperDrawable).getBitmap();
                if (bitmap != null) {
                    Palette p = Palette.from(bitmap).generate();
                    int wallColor = p.getDominantColor(iconcolor);
                    if (iconcolor != wallColor) {
                        iconcolor = wallColor;
                    }
                    this.setColorFilter(lighter(iconcolor, 3));
                }
            } catch (Exception e) {
                // Nothing to do
            }
        } else {
            this.setColorFilter(null);  
        }
    }

    private static int lighter(int color, int factor) {
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);

        blue = blue * factor;
        green = green * factor;
        blue = blue * factor;

        blue = blue > 255 ? 255 : blue;
        green = green > 255 ? 255 : green;
        red = red > 255 ? 255 : red;

        return Color.argb(Color.alpha(color), red, green, blue);
    } **/

    public void show() {
        if (mIsBouncer) {
            // Ignore show calls when Keyguard pin screen is being shown
            return;
        }

        mIsShowing = true;

        dispatchShow();
        setVisibility(View.VISIBLE);
    }

    public void hide() {
        mIsShowing = false;

        setVisibility(View.GONE);
        hideCircle();
        dispatchHide();
    }

    private void updateAlpha() {
        setAlpha(1.0f);
    }

    private void updatePosition() {
        Display defaultDisplay = mWindowManager.getDefaultDisplay();

        Point size = new Point();
        defaultDisplay.getRealSize(size);

        int rotation = defaultDisplay.getRotation();
        switch (rotation) {
            case Surface.ROTATION_0:
                mParams.x = mPositionX;
                mParams.y = mPositionY;
                break;
            case Surface.ROTATION_90:
                mParams.x = mPositionY;
                mParams.y = mPositionX;
                break;
            case Surface.ROTATION_180:
                mParams.x = mPositionX;
                mParams.y = size.y - mPositionY - mSize;
                break;
            case Surface.ROTATION_270:
                mParams.x = size.x - mPositionY - mSize - mNavigationBarSize;
                mParams.y = mPositionX;
                break;
            default:
                throw new IllegalArgumentException("Unknown rotation: " + rotation);
        }

        if (mIsKeyguard) {
            mParams.x = mPositionX;
            mParams.y = mPositionY;
        }

        if (mIsDreaming) {
            //mParams.x += mDreamingOffsetX;
            mParams.y += mDreamingOffsetY;
            mFODAnimation.updateParams(mParams.y);
        }

        mWindowManager.updateViewLayout(this, mParams);
    }

    private void setDim(boolean dim) {
        if (dim) {
            int curBrightness = Settings.System.getInt(getContext().getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 100);
            int dimAmount = 0;

            IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
            try {
                dimAmount = daemon.getDimAmount(curBrightness);
            } catch (RemoteException e) {
                // do nothing
            }

            if (mShouldBoostBrightness) {
                mParams.screenBrightness = 1.0f;
            }

            mParams.dimAmount = dimAmount / 255.0f;
        } else {
            mParams.screenBrightness = 0.0f;
            mParams.dimAmount = 0.0f;
        }

        mWindowManager.updateViewLayout(this, mParams);
    }

    private void setCustomIcon(){
        final String customIconURI = Settings.System.getStringForUser(getContext().getContentResolver(),
                Settings.System.OMNI_CUSTOM_FP_ICON,
                UserHandle.USER_CURRENT);

        mIsRecognizingAnimEnabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.FOD_RECOGNIZING_ANIMATION, 0) != 0;

        if (!TextUtils.isEmpty(customIconURI)) {
            try {
                ParcelFileDescriptor parcelFileDescriptor =
                    getContext().getContentResolver().openFileDescriptor(Uri.parse(customIconURI), "r");
                FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();
                Bitmap image = BitmapFactory.decodeFileDescriptor(fileDescriptor);
                parcelFileDescriptor.close();
                setImageBitmap(image);
            }
            catch (Exception e) {
                setImageResource(R.drawable.fod_icon_default);
            }
        } else {
            setImageResource(R.drawable.fod_icon_default);
        }
    }

    private class BurnInProtectionTask extends TimerTask {
        @Override
        public void run() {
            long now = System.currentTimeMillis() / 1000 / 60;

            mDreamingOffsetX = (int) (now % (mDreamingMaxOffset * 4));
            if (mDreamingOffsetX > mDreamingMaxOffset * 2) {
                mDreamingOffsetX = mDreamingMaxOffset * 4 - mDreamingOffsetX;
            }

            // Let y to be not synchronized with x, so that we get maximum movement
            mDreamingOffsetY = (int) ((now + mDreamingMaxOffset / 3) % (mDreamingMaxOffset * 2));
            if (mDreamingOffsetY > mDreamingMaxOffset * 2) {
                mDreamingOffsetY = mDreamingMaxOffset * 4 - mDreamingOffsetY;
            }

            mDreamingOffsetX -= mDreamingMaxOffset;
            mDreamingOffsetY -= mDreamingMaxOffset;

            mHandler.post(() -> updatePosition());
        }
    };
}

class FODAnimation extends ImageView {

    private Context mContext;
    private int mAnimationPositionY;
    private LayoutInflater mInflater;
    private WindowManager mWindowManager;
    private boolean mShowing = false;
    private boolean mIsKeyguard;
    private AnimationDrawable recognizingAnim;
    private final WindowManager.LayoutParams mAnimParams = new WindowManager.LayoutParams();

    public FODAnimation(Context context, int mPositionX, int mPositionY) {
        super(context);

        mContext = context;
        mInflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mWindowManager = mContext.getSystemService(WindowManager.class);

        mAnimParams.height = mContext.getResources().getDimensionPixelSize(R.dimen.fod_animation_size);
        mAnimParams.width = mContext.getResources().getDimensionPixelSize(R.dimen.fod_animation_size);

        mAnimationPositionY = (int) Math.round(mPositionY - (mContext.getResources().getDimensionPixelSize(R.dimen.fod_animation_size) / 2));

        mAnimParams.format = PixelFormat.TRANSLUCENT;
        mAnimParams.type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY; // it must be behind FOD icon
        mAnimParams.flags =  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        mAnimParams.gravity = Gravity.TOP | Gravity.CENTER;
        mAnimParams.y = mAnimationPositionY;

        this.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        setFODAnim();
        recognizingAnim = (AnimationDrawable) this.getBackground();

    }

    public int getFODAnim() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.FOD_ANIM, 0);
    }

    public void setFODAnim() {
        int fodanim = getFODAnim();

        if (fodanim == 0) {
            this.setBackgroundResource(R.drawable.fod_miui_normal_recognizing_anim);
        } else if (fodanim == 1) {
            this.setBackgroundResource(R.drawable.fod_miui_aod_recognizing_anim);
        } else if (fodanim == 2) {
            this.setBackgroundResource(R.drawable.fod_miui_light_recognizing_anim);
        } else if (fodanim == 3) {
            this.setBackgroundResource(R.drawable.fod_miui_pop_recognizing_anim);
        } else if (fodanim == 4) {
            this.setBackgroundResource(R.drawable.fod_miui_pulse_recognizing_anim);
        } else if (fodanim == 5) {
            this.setBackgroundResource(R.drawable.fod_miui_pulse_recognizing_white_anim);
        } else if (fodanim == 6) {
            this.setBackgroundResource(R.drawable.fod_miui_rhythm_recognizing_anim);
        } else if (fodanim == 7) {
            this.setBackgroundResource(R.drawable.fod_op_cosmos_recognizing_anim);
        } else if (fodanim == 8) {
            this.setBackgroundResource(R.drawable.fod_op_mclaren_recognizing_anim);
        } else if (fodanim == 9) {
            this.setBackgroundResource(R.drawable.fod_op_stripe_recognizing_anim);
        } else if (fodanim == 10) {
            this.setBackgroundResource(R.drawable.fod_op_wave_recognizing_anim);
        } else if (fodanim == 11) {
            this.setBackgroundResource(R.drawable.fod_pureview_dna_recognizing_anim);
        } else if (fodanim == 12) {
            this.setBackgroundResource(R.drawable.fod_pureview_future_recognizing_anim);
        } else if (fodanim == 13) {
            this.setBackgroundResource(R.drawable.fod_pureview_halo_ring_recognizing_anim);
        } else if (fodanim == 14) {
            this.setBackgroundResource(R.drawable.fod_pureview_molecular_recognizing_anim);
        }
        recognizingAnim = (AnimationDrawable) this.getBackground();
    }

    public void updateParams(int mDreamingOffsetY) {
        mAnimationPositionY = (int) Math.round(mDreamingOffsetY - (mContext.getResources().getDimensionPixelSize(R.dimen.fod_animation_size) / 2));
        mAnimParams.y = mAnimationPositionY;
    }

    public void setAnimationKeyguard(boolean state) {
        mIsKeyguard = state;
    }

    public void showFODanimation() {
        if (mAnimParams != null && !mShowing && mIsKeyguard) {
            mShowing = true;
            mWindowManager.addView(this, mAnimParams);
            recognizingAnim.start();
        }
    }

    public void hideFODanimation() {
        if (mShowing) {
            mShowing = false;
            if (recognizingAnim != null) {
                this.clearAnimation();
                recognizingAnim.stop();
                recognizingAnim.selectDrawable(0);
            }
            if (this.getWindowToken() != null) {
                mWindowManager.removeView(this);
            }
        }
    }
}
