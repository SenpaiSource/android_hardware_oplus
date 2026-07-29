package com.oplus.view;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.ViewRootImpl;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable;

/**
 * ColorOS entry point for window-background ("material") blur, re-implemented on top of the AOSP
 * cross-window blur (BackgroundBlurDrawable).
 *
 * OPlus apps build one of these per blurred view and then cache it for the life of that view --
 * COUIBackgroundBlurBuilder keeps it in a field, the camera's BlurBackgroundImpl keeps it in a
 * view tag. The stock implementation tolerates being constructed before the view is attached;
 * ViewRootImpl.createBackgroundBlurDrawable() does not, because View.getViewRootImpl() is null
 * until attach. A manager built one frame too early therefore handed back a null drawable, the
 * caller skipped setBackground(), and that panel silently never blurred again -- which is why
 * blur showed up on some launches/panels and not others.
 *
 * So: hand out a stable wrapper drawable immediately and bind the real blur drawable lazily, the
 * first time the view is drawn (by definition attached), re-binding if the view later lands in a
 * different window. Everything the app set before the bind is replayed onto the drawable.
 */
public class ViewRootManager {
    private static final String TAG = "ViewRootManager";

    private final View mView;
    private final BlurDrawableWrapper mWrapper = new BlurDrawableWrapper();

    private BackgroundBlurDrawable mBlurDrawable;
    private ViewRootImpl mBoundViewRoot;

    // Latest state requested by the app, replayed onto every (re)bound blur drawable.
    private boolean mHasBlurRadius;
    private int mBlurRadius;
    private float mCornerRadiusTL;
    private float mCornerRadiusTR;
    private float mCornerRadiusBL;
    private float mCornerRadiusBR;

    public ViewRootManager(View view) {
        this.mView = view;
        if (view == null) {
            Log.d(TAG, "view is null, blur unavailable");
            return;
        }
        bind();
    }

    /**
     * Binds to the view's current ViewRootImpl, replaying whatever the app has set so far.
     * Cheap and idempotent: everything after the first frame is a reference comparison.
     */
    private void bind() {
        if (mView == null) {
            return;
        }
        ViewRootImpl viewRootImpl = mView.getViewRootImpl();
        if (viewRootImpl == null) {
            // Not attached yet. The first draw after attach binds us.
            return;
        }
        if (mBlurDrawable != null && viewRootImpl == mBoundViewRoot) {
            return;
        }
        if (mBlurDrawable != null) {
            // Belongs to the window we are leaving: drop its region instead of leaving a stale
            // blur rect behind in the old aggregator.
            mBlurDrawable.setVisible(false, false);
        }

        BackgroundBlurDrawable drawable = viewRootImpl.createBackgroundBlurDrawable();
        if (mHasBlurRadius) {
            drawable.setBlurRadius(mBlurRadius);
        }
        drawable.setCornerRadius(
                mCornerRadiusTL, mCornerRadiusTR, mCornerRadiusBL, mCornerRadiusBR);
        drawable.setColor(mWrapper.getColor());
        drawable.setAlpha(mWrapper.getAlpha());
        drawable.setVisible(mWrapper.isVisible(), false);
        Rect bounds = mWrapper.getBounds();
        if (!bounds.isEmpty()) {
            drawable.setBounds(bounds);
        }

        mBlurDrawable = drawable;
        mBoundViewRoot = viewRootImpl;
        mWrapper.setDelegate(drawable);
    }

    public Drawable getBackgroundBlurDrawable() {
        return mWrapper;
    }

    public void setBlurRadius(int blurRadius) {
        mBlurRadius = blurRadius;
        mHasBlurRadius = true;
        bind();
        if (mBlurDrawable != null) {
            mBlurDrawable.setBlurRadius(blurRadius);
        }
    }

    public void setCornerRadius(float cornerRadius) {
        setCornerRadius(cornerRadius, cornerRadius, cornerRadius, cornerRadius);
    }

    public void setCornerRadius(float cornerRadiusTL, float cornerRadiusTR,
            float cornerRadiusBL, float cornerRadiusBR) {
        mCornerRadiusTL = cornerRadiusTL;
        mCornerRadiusTR = cornerRadiusTR;
        mCornerRadiusBL = cornerRadiusBL;
        mCornerRadiusBR = cornerRadiusBR;
        mWrapper.setCornerRadius(cornerRadiusTL, cornerRadiusTR, cornerRadiusBL, cornerRadiusBR);
        bind();
        if (mBlurDrawable != null) {
            mBlurDrawable.setCornerRadius(
                    cornerRadiusTL, cornerRadiusTR, cornerRadiusBL, cornerRadiusBR);
        }
    }

    public void setColor(int color) {
        mWrapper.setColor(color);
        bind();
        if (mBlurDrawable != null) {
            mBlurDrawable.setColor(color);
        }
    }

    /**
     * ColorOS's "glass" material: a blur type plus a blend/mix colour pair that the OPlus
     * SurfaceFlinger composites over the blurred content. AOSP's blur regions have no material,
     * but BackgroundBlurDrawable does alpha-blend a colour over the blur, which carries the tint
     * -- the visible half of the effect. Without this the app's glass surfaces come out as bare
     * blur with no tint at all, because the camera never calls setColor(): the whole look is
     * handed over in these params (BLUR_TYPE_FAST_KAWASE + BLUR_BLEND_MODE_GLOW_OVERLAY, with the
     * colours animated by the panel's expand progress).
     *
     * The glow/colour-dodge blend modes have no AOSP equivalent and are approximated by the tint.
     */
    public void setBlurParams(com.oplus.graphics.OplusBlurParam params) {
        if (params == null) {
            return;
        }
        float[] p = params.toFloatArray();
        if (p == null || p.length < 16) {
            return;
        }
        // Layout: [0] blurType, [1] tileMode, [2] zoomFactor, [3] blendMode,
        //         [4..7] blend rgba, [8..11] mix rgba, [12..15] arcylic rgba.
        int color = toArgb(p, 8);           // mix colour is the material's base tint
        if (Color.alpha(color) == 0) {
            color = toArgb(p, 4);           // then the blend colour
        }
        if (Color.alpha(color) == 0) {
            color = toArgb(p, 12);          // then the arcylic colour
        }
        if (Color.alpha(color) == 0) {
            return;                         // fully transparent material: leave the blur bare
        }
        setColor(color);
    }

    private static int toArgb(float[] components, int offset) {
        return Color.argb(
                clamp255(components[offset + 3]),
                clamp255(components[offset]),
                clamp255(components[offset + 1]),
                clamp255(components[offset + 2]));
    }

    private static int clamp255(float component) {
        return Math.max(0, Math.min(255, Math.round(component * 255f)));
    }

    /**
     * Stand-in for the blur drawable, so the app can install a background straight away even when
     * the view is not attached yet. Until the real drawable exists it paints the app's blur colour
     * -- which is the colour ColorOS itself uses for its no-blur fallback -- and it starts
     * delegating on the first frame after attach.
     */
    private final class BlurDrawableWrapper extends Drawable implements Drawable.Callback {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final float[] mRadii = new float[8];

        private Drawable mDelegate;
        private int mAlpha = 255;
        private int mColor = Color.TRANSPARENT;

        void setDelegate(Drawable delegate) {
            if (mDelegate == delegate) {
                return;
            }
            if (mDelegate != null) {
                mDelegate.setCallback(null);
            }
            mDelegate = delegate;
            if (delegate != null) {
                delegate.setCallback(this);
            }
            invalidateSelf();
        }

        void setColor(int color) {
            if (mColor != color) {
                mColor = color;
                mPaint.setColor(color);
                invalidateSelf();
            }
        }

        int getColor() {
            return mColor;
        }

        void setCornerRadius(float tl, float tr, float bl, float br) {
            mRadii[0] = mRadii[1] = tl;
            mRadii[2] = mRadii[3] = tr;
            mRadii[4] = mRadii[5] = bl;
            mRadii[6] = mRadii[7] = br;
            updatePath();
            invalidateSelf();
        }

        private void updatePath() {
            mPath.reset();
            Rect bounds = getBounds();
            if (bounds.isEmpty()) {
                return;
            }
            mPath.addRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom, mRadii,
                    Path.Direction.CW);
        }

        @Override
        public void draw(Canvas canvas) {
            // First draw after attach is where the real blur drawable gets created.
            bind();
            Drawable delegate = mDelegate;
            // BackgroundBlurDrawable draws a RenderNode, which software canvases reject.
            if (delegate != null && canvas.isHardwareAccelerated()) {
                delegate.draw(canvas);
                return;
            }
            if (mAlpha == 0 || Color.alpha(mColor) == 0 || mPath.isEmpty()) {
                return;
            }
            mPaint.setAlpha(Color.alpha(mColor) * mAlpha / 255);
            canvas.drawPath(mPath, mPaint);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            updatePath();
            bind();
            if (mDelegate != null) {
                mDelegate.setBounds(bounds);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            if (mAlpha != alpha) {
                mAlpha = alpha;
                invalidateSelf();
            }
            if (mDelegate != null) {
                mDelegate.setAlpha(alpha);
            }
        }

        @Override
        public int getAlpha() {
            return mAlpha;
        }

        @Override
        public boolean setVisible(boolean visible, boolean restart) {
            boolean changed = super.setVisible(visible, restart);
            if (mDelegate != null) {
                mDelegate.setVisible(visible, restart);
            }
            return changed;
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            // BackgroundBlurDrawable throws on this; the blur colour comes from setColor().
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public void invalidateDrawable(Drawable who) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(Drawable who, Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override
        public void unscheduleDrawable(Drawable who, Runnable what) {
            unscheduleSelf(what);
        }
    }
}
