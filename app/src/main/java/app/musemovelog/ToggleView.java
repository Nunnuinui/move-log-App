package app.musemovelog;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;

/**
 * Galaxy-style toggle drawn directly on a Canvas (40x22dp pill track, 18dp
 * white circular thumb). Replaces the framework Switch, whose track/thumb
 * drawables get stretched (the thumb rendered as a vertical ellipse) and
 * whose animation duration is fixed. Here the circle can never distort and
 * the slide animation runs at a snappy ~110ms while the ON color fills the
 * track progressively behind the thumb.
 */
public class ToggleView extends View {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(ToggleView view, boolean isChecked);
    }

    private static final int COLOR_OFF = 0xFF5A5E63;
    private static final int COLOR_ON = 0xFFB18CFF;
    private static final int COLOR_THUMB = 0xFFFFFFFF;
    private static final long ANIM_MS = 110;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path pillPath = new Path();

    private boolean checked;
    private float pos; // 0 = off, 1 = on (animated)
    private ValueAnimator animator;
    private OnCheckedChangeListener listener;

    public ToggleView(Context context) {
        super(context);
        init();
    }

    public ToggleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(true);
        thumbPaint.setColor(COLOR_THUMB);
        trackPaint.setColor(COLOR_OFF);
    }

    public boolean isChecked() {
        return checked;
    }

    /** Programmatic change: snaps without animation (listener still fires). */
    public void setChecked(boolean value) {
        if (checked == value) {
            pos = value ? 1f : 0f;
            invalidate();
            return;
        }
        checked = value;
        pos = value ? 1f : 0f;
        invalidate();
        if (listener != null) {
            listener.onCheckedChanged(this, checked);
        }
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        listener = l;
    }

    @Override
    public boolean performClick() {
        boolean handled = super.performClick();
        // User toggle: flip state, notify immediately, animate the thumb.
        checked = !checked;
        if (listener != null) {
            listener.onCheckedChanged(this, checked);
        }
        animateTo(checked ? 1f : 0f);
        return handled;
    }

    private void animateTo(float target) {
        if (animator != null) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(pos, target);
        animator.setDuration(ANIM_MS);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            pos = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float d = getResources().getDisplayMetrics().density;
        int w = Math.round(44 * d);
        int h = Math.round(28 * d);
        setMeasuredDimension(
                resolveSize(w, widthMeasureSpec),
                resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        float trackW = 40 * d;
        float trackH = 22 * d;
        float left = (getWidth() - trackW) / 2f;
        float top = (getHeight() - trackH) / 2f;
        float inset = 2 * d;
        float r = 9 * d;

        float right = left + trackW;
        float bottom = top + trackH;

        // Base track in the OFF color.
        trackPaint.setColor(COLOR_OFF);
        canvas.drawRoundRect(left, top, right, bottom, trackH / 2f, trackH / 2f,
                trackPaint);

        // ON color fills the track progressively with the thumb: the colored
        // region ends at left + pos * trackW, so color never appears ahead of
        // the thumb's leading edge (a uniform blend colored the whole track
        // at once, which looked like color stuck to the thumb's right side).
        if (pos > 0f) {
            pillPath.reset();
            pillPath.addRoundRect(left, top, right, bottom, trackH / 2f, trackH / 2f,
                    Path.Direction.CW);
            canvas.save();
            canvas.clipPath(pillPath);
            canvas.clipRect(left, top, left + pos * trackW, bottom);
            trackPaint.setColor(COLOR_ON);
            canvas.drawRoundRect(left, top, right, bottom, trackH / 2f, trackH / 2f,
                    trackPaint);
            canvas.restore();
        }

        float cxOff = left + inset + r;
        float cxOn = left + trackW - inset - r;
        float cx = cxOff + (cxOn - cxOff) * pos;
        float cy = top + trackH / 2f;
        canvas.drawCircle(cx, cy, r, thumbPaint);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(checked);
    }
}
