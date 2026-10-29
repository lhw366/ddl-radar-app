package com.ddlradar.app;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/** One card owns its gesture: a swipe must never activate its click or a sibling. */
final class ReminderCardView extends FrameLayout {
    private final int slop;
    private float downX, downY;
    private boolean swiping, cancelled;
    private Runnable swipe;

    ReminderCardView(Context context) {
        super(context);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    void onSwipeUp(Runnable action) { swipe = action; }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            animate().cancel();
            setTranslationY(0);
            setAlpha(1);
            downX = event.getRawX();
            downY = event.getRawY();
            swiping = false;
            cancelled = false;
            // Decide the direction before ScrollView intercepts the first move.
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        } else if (event.getPointerCount() > 1) {
            cancelChild(event);
            cancelled = true;
            swiping = false;
            resetPosition();
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        } else if (action == MotionEvent.ACTION_MOVE && !cancelled) {
            float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
            if (!swiping && dy < -slop && -dy > Math.abs(dx) * 1.25f) {
                cancelChild(event);
                swiping = true;
            }
            if (swiping) {
                setTranslationY(Math.min(0, dy));
                setAlpha(Math.max(.45f, 1 + dy / Math.max(1, getHeight())));
                return true;
            }
            if (Math.abs(dx) > slop || dy > slop) {
                // A sideways or downward drag is not a content tap. A stacked
                // list may take over downward scrolling on the following move.
                cancelChild(event);
                cancelled = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            if (swiping) {
                float distance = downY - event.getRawY();
                boolean dismiss = action == MotionEvent.ACTION_UP
                        && distance >= Math.max(36 * getResources().getDisplayMetrics().density, getHeight() * .32f);
                swiping = false;
                if (dismiss && swipe != null) swipe.run();
                else resetPosition();
                return true;
            }
            if (cancelled) { resetPosition(); return true; }
        }
        // Once cancelled (including a second finger), consume the rest of this
        // sequence rather than feeding MOVE/POINTER events back to the child.
        return cancelled || super.dispatchTouchEvent(event);
    }

    private void cancelChild(MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
    }

    private void resetPosition() {
        animate().translationY(0).alpha(1).setDuration(120).start();
    }
}
