package org.telegram.ui;

import android.content.Context;
import android.graphics.Insets;
import android.os.Build;
import android.os.CancellationSignal;
import android.util.AttributeSet;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowInsetsAnimationControlListener;
import android.view.WindowInsetsAnimationController;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.view.NestedScrollingParent3;
import androidx.core.view.NestedScrollingParentHelper;
import androidx.core.view.ViewCompat;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

@RequiresApi(api = Build.VERSION_CODES.R)
public class ImeWindowInsetsWrapperView extends FrameLayout implements NestedScrollingParent3 {
    private final NestedScrollingParentHelper nestedScrollingParentHelper = new NestedScrollingParentHelper(this);
    private final ImeAnimationController imeAnimationController = new ImeAnimationController();
    private int dropNextY = 0;

    private final boolean scrollImeOnScreenWhenNotVisible = true;
    private final boolean scrollImeOffScreenWhenVisible = true;

    private int[] startViewLocation = new int[2];
    private int[] tempLocationArray = new int[2];

    private View currentNestedScrollingChild = null;

    public ImeWindowInsetsWrapperView(@NonNull Context context) {
        super(context);
    }

    public ImeWindowInsetsWrapperView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    private void reset() {
        dropNextY = 0;
        startViewLocation[0] = 0;
        startViewLocation[1] = 0;
        suppressLayout(false);
    }

    @Override
    public boolean onStartNestedScroll(@NonNull View child, @NonNull View target, int axes, int type) {
        return (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0 && type == ViewCompat.TYPE_TOUCH;
    }

    @Override
    public void dispatchWindowInsetsAnimationPrepare(@NonNull WindowInsetsAnimation animation) {
        super.dispatchWindowInsetsAnimationPrepare(animation);
        suppressLayout(false);
    }

    @Override
    public void onNestedScrollAccepted(@NonNull View child, @NonNull View target, int axes, int type) {
        nestedScrollingParentHelper.onNestedScrollAccepted(child, target, axes, type);
        currentNestedScrollingChild = child;
    }

    @Override
    public void onStopNestedScroll(@NonNull View target, int type) {
        nestedScrollingParentHelper.onStopNestedScroll(target, type);

        if (imeAnimationController.isInsetAnimationInProgress() && !imeAnimationController.isInsetAnimationFinishing()) {
            imeAnimationController.animateToFinish(null);
        }

        reset();
    }

    @Override
    public void onNestedScroll(@NonNull View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int type, @NonNull int[] consumed) {
        if (dyUnconsumed > 0) {
            if (imeAnimationController.isInsetAnimationInProgress()) {
                consumed[1] = -imeAnimationController.insetBy(-dyUnconsumed);
            } else if (scrollImeOnScreenWhenNotVisible && !imeAnimationController.isInsetAnimationRequestPending() && !getRootWindowInsets().isVisible(WindowInsets.Type.ime())) {
                startControlRequest();
                consumed[1] = dyUnconsumed;
            }
        }
    }

    @Override
    public void onNestedPreScroll(@NonNull View target, int dx, int dy, @NonNull int[] consumed, int type) {
        if (imeAnimationController.isInsetAnimationRequestPending()) {
            consumed[0] = dx;
            consumed[1] = dy;
            return;
        }

        int deltaY = dy;
        if (dropNextY != 0) {
            consumed[1] = dropNextY;
            deltaY -= dropNextY;
            dropNextY = 0;
        }

        if (deltaY < 0) {
            if (imeAnimationController.isInsetAnimationInProgress()) {
                consumed[1] -= imeAnimationController.insetBy(-deltaY);
            } else if (scrollImeOffScreenWhenVisible && !imeAnimationController.isInsetAnimationRequestPending() && getRootWindowInsets().isVisible(WindowInsets.Type.ime())) {
                startControlRequest();
                consumed[1] = deltaY;
            }
        }
    }

    @Override
    public boolean onNestedFling(@NonNull View target, float velocityX, float velocityY, boolean consumed) {
        if (imeAnimationController.isInsetAnimationInProgress()) {
            imeAnimationController.animateToFinish(velocityY);
            return true;
        } else {
            boolean imeVisible = getRootWindowInsets().isVisible(WindowInsets.Type.ime());

            if (velocityY > 0 && scrollImeOnScreenWhenNotVisible && !imeVisible) {
                imeAnimationController.startAndFling(this, velocityY);
                return true;
            } else if (velocityY < 0 && scrollImeOffScreenWhenVisible && imeVisible) {
                imeAnimationController.startAndFling(this, velocityY);
                return true;
            }
        }

        return false;
    }

    @Override
    public void onNestedScrollAccepted(View child, View target, int axes) {
        onNestedScrollAccepted(child, target, axes, ViewCompat.TYPE_TOUCH);
    }

    @Override
    public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int type) {
        onNestedScroll(
                target,
                dxConsumed,
                dyConsumed,
                dxUnconsumed,
                dyUnconsumed,
                type,
                tempLocationArray
        );
    }

    private void startControlRequest() {
        suppressLayout(true);

        if (currentNestedScrollingChild != null) {
            currentNestedScrollingChild.getLocationInWindow(startViewLocation);
        }

        imeAnimationController.startControlRequest(this, (c) -> onControllerReady());
    }

    private void onControllerReady() {
        if (currentNestedScrollingChild != null) {
            imeAnimationController.insetBy(0);
            int[] location = tempLocationArray;
            currentNestedScrollingChild.getLocationInWindow(location);
            dropNextY = location[1] - startViewLocation[1];
        }
    }

    @Override
    public void onStopNestedScroll(View target) {
        onStopNestedScroll(target, ViewCompat.TYPE_TOUCH);
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private static class ImeAnimationController {
        private static final float SCROLL_THRESHOLD = 0.15f;

        interface PendingWIACRequest {
            void received(WindowInsetsAnimationController controller);
        }

        private WindowInsetsAnimationController insetsAnimationController = null;
        private CancellationSignal pendingRequestCancellationSignal = null;
        private PendingWIACRequest pendingRequestOnReady = null;
        private SpringAnimation currentSpringAnimation = null;

        private boolean isImeShownAtStart = false;

        private WindowInsetsAnimationControlListener animationControlListener = new WindowInsetsAnimationControlListener() {
            @Override
            public void onReady(@NonNull WindowInsetsAnimationController controller, int types) {
                onRequestReady(controller);
            }

            @Override
            public void onFinished(@NonNull WindowInsetsAnimationController controller) {
                reset();
            }

            @Override
            public void onCancelled(@Nullable WindowInsetsAnimationController controller) {
                reset();
            }
        };

        public void startControlRequest(View view, PendingWIACRequest onRequestReady) {
            if (isInsetAnimationInProgress()) {
                throw new IllegalStateException("Animation is in progress, controlling not available!");
            }

            isImeShownAtStart = view.getRootWindowInsets().isVisible(WindowInsets.Type.ime());
            pendingRequestCancellationSignal = new CancellationSignal();
            pendingRequestOnReady = onRequestReady;

            if (view.getWindowInsetsController() != null) {
                view.getWindowInsetsController().controlWindowInsetsAnimation(
                        WindowInsets.Type.ime(), -1, new LinearInterpolator(), pendingRequestCancellationSignal, animationControlListener
                );
            }
        }

        public void startAndFling(View view, Float velocityY) {
            startControlRequest(view, controller -> animateToFinish(velocityY));
        }

        public int insetBy(int dy) {
            if (insetsAnimationController == null) throw new NullPointerException("insetsAnimationController == null");
            return insetTo(insetsAnimationController.getCurrentInsets().bottom - dy);
        }

        public int insetTo(int inset) {
            if (insetsAnimationController == null) throw new NullPointerException("insetsAnimationController == null");

            int hiddenBottom = insetsAnimationController.getHiddenStateInsets().bottom;
            int shownBottom = insetsAnimationController.getShownStateInsets().bottom;
            int startBottom = isImeShownAtStart ? shownBottom : hiddenBottom;
            int endBottom = isImeShownAtStart ? hiddenBottom : shownBottom;

            int coercedBottom;

            if (inset < hiddenBottom) {
                coercedBottom = hiddenBottom;
            } else {
                coercedBottom = Math.min(inset, shownBottom);
            }

            int consumedDy = insetsAnimationController.getCurrentInsets().bottom - coercedBottom;

            insetsAnimationController.setInsetsAndAlpha(
                    Insets.of(0, 0, 0, coercedBottom),
                    1f,
                    (coercedBottom - startBottom) / (float) (endBottom - startBottom)
            );

            return consumedDy;
        }

        public boolean isInsetAnimationInProgress() {
            return insetsAnimationController != null;
        }

        public boolean isInsetAnimationFinishing() {
            return currentSpringAnimation != null;
        }

        public boolean isInsetAnimationRequestPending() {
            return pendingRequestCancellationSignal != null;
        }

        public void cancel() {
            if (insetsAnimationController != null) insetsAnimationController.finish(isImeShownAtStart);
            if (pendingRequestCancellationSignal != null) pendingRequestCancellationSignal.cancel();
            if (currentSpringAnimation != null) currentSpringAnimation.cancel();
            reset();
        }

        public void finish() {
            if (insetsAnimationController == null) {
                if (pendingRequestCancellationSignal != null) pendingRequestCancellationSignal.cancel();
                return;
            }

            int current = insetsAnimationController.getCurrentInsets().bottom;
            int shown = insetsAnimationController.getShownStateInsets().bottom;
            int hidden = insetsAnimationController.getHiddenStateInsets().bottom;

            if (current == shown) {
                insetsAnimationController.finish(true);
            } else if (current == hidden) {
                insetsAnimationController.finish(false);
            } else {
                if (insetsAnimationController.getCurrentFraction() >= SCROLL_THRESHOLD) {
                    insetsAnimationController.finish(!isImeShownAtStart);
                } else {
                    insetsAnimationController.finish(isImeShownAtStart);
                }
            }
        }

        public void animateToFinish(Float velocityY) {
            if (insetsAnimationController == null) {
                if (pendingRequestCancellationSignal != null) pendingRequestCancellationSignal.cancel();
                return;
            }

            int current = insetsAnimationController.getCurrentInsets().bottom;
            int shown = insetsAnimationController.getShownStateInsets().bottom;
            int hidden = insetsAnimationController.getHiddenStateInsets().bottom;

            if (velocityY != null) {
                animateImeToVisibility(velocityY > 0, velocityY);
            } else if (current == shown) {
                insetsAnimationController.finish(true);
            } else if (current == hidden) {
                insetsAnimationController.finish(false);
            } else {
                if (insetsAnimationController.getCurrentFraction() >= SCROLL_THRESHOLD) {
                    animateImeToVisibility(!isImeShownAtStart, null);
                } else {
                    animateImeToVisibility(isImeShownAtStart, null);
                }
            }
        }

        private void onRequestReady(WindowInsetsAnimationController controller) {
            pendingRequestCancellationSignal = null;
            insetsAnimationController = controller;
            if (pendingRequestOnReady != null) pendingRequestOnReady.received(controller);
            pendingRequestOnReady = null;
        }

        private void reset() {
            insetsAnimationController = null;
            pendingRequestCancellationSignal = null;
            isImeShownAtStart = false;
            if (currentSpringAnimation != null) currentSpringAnimation.cancel();
            currentSpringAnimation = null;
            pendingRequestOnReady = null;
        }

        private void animateImeToVisibility(boolean visible, Float velocityY) {
            if (insetsAnimationController == null) throw new NullPointerException("insetsAnimationController == null");

            int finalPosition = visible ? insetsAnimationController.getShownStateInsets().bottom : insetsAnimationController.getHiddenStateInsets().bottom;

            currentSpringAnimation = new SpringAnimation(new FloatValueHolder() {
                @Override
                public void setValue(float value) {
                    insetTo(Math.round(value));
                }

                @Override
                public float getValue() {
                    return insetsAnimationController.getCurrentInsets().bottom;
                }
            }, finalPosition);

            SpringForce force = new SpringForce();
            force.setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY);
            force.setStiffness(SpringForce.STIFFNESS_MEDIUM);
            currentSpringAnimation.setSpring(force);

            if (velocityY != null) {
                currentSpringAnimation.setStartVelocity(velocityY);
            }

            currentSpringAnimation.addEndListener((animation, canceled, value, velocity) -> {
                if (animation == currentSpringAnimation) {
                    currentSpringAnimation = null;
                }

                finish();
            });

            currentSpringAnimation.start();
        }
    }
}
