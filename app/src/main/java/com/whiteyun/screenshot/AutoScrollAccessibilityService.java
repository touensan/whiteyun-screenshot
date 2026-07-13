package com.whiteyun.screenshot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class AutoScrollAccessibilityService extends AccessibilityService {
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final int UNDEFINED_SCROLL_DELTA = -1;
    private static final float SWIPE_START_FRACTION = 0.78f;
    private static final float SWIPE_END_FRACTION = 0.62f;
    private static final int NORMAL_SWIPE_DURATION_MS = 900;
    private static final int FAST_SWIPE_DURATION_MS = 280;
    private static volatile AutoScrollAccessibilityService instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile AutoScrollEvidenceStore.LatestEvent latestEvent =
            new AutoScrollEvidenceStore.LatestEvent("", "", "");
    private volatile ScrollObserver scrollObserver;
    private String lastScrollSourceKey = "";
    private String lastScrollPackage = "";
    private int lastScrollWindowId = -1;
    private int lastScrollY = UNDEFINED_SCROLL_DELTA;

    interface ScrollCallback {
        void onFinished(boolean completed);
    }

    interface WindowCallback {
        void onCaptured(AutoScrollEvidenceStore.WindowSnapshot snapshot);
    }

    interface ScrollObserver {
        void onScrolled(
                int deltaY,
                boolean hasReliableDelta,
                String packageName,
                int windowId,
                String className);
    }

    static boolean isRunning() {
        return instance != null;
    }

    static boolean isEnabled(Context context) {
        if (Settings.Secure.getInt(
                context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0) != 1) {
            return false;
        }
        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        String expected = context.getPackageName() + "/" + AutoScrollAccessibilityService.class.getName();
        for (String service : enabled.split(":")) {
            String trimmed = service.trim();
            ComponentName component = ComponentName.unflattenFromString(trimmed);
            if (expected.equalsIgnoreCase(trimmed)
                    || (component != null
                            && context.getPackageName().equals(component.getPackageName())
                            && AutoScrollAccessibilityService.class.getName().equals(component.getClassName()))) {
                return true;
            }
        }
        return false;
    }

    static boolean requestScroll(ScrollCallback callback) {
        return requestScroll(false, callback);
    }

    static boolean requestScroll(boolean reverse, ScrollCallback callback) {
        return requestScroll(reverse, false, callback);
    }

    static boolean requestScroll(boolean reverse, boolean fast, ScrollCallback callback) {
        AutoScrollAccessibilityService service = instance;
        if (service == null) {
            return false;
        }
        service.mainHandler.post(() -> service.dispatchScroll(reverse, fast, callback));
        return true;
    }

    static boolean captureActiveWindow(WindowCallback callback) {
        AutoScrollAccessibilityService service = instance;
        if (service == null) {
            return false;
        }
        service.mainHandler.post(() -> callback.onCaptured(service.buildWindowSnapshot()));
        return true;
    }

    static boolean setScrollObserver(ScrollObserver observer) {
        AutoScrollAccessibilityService service = instance;
        if (service == null) {
            return false;
        }
        service.resetObservedScrollPosition();
        service.scrollObserver = observer;
        return true;
    }

    static void clearScrollObserver(ScrollObserver observer) {
        AutoScrollAccessibilityService service = instance;
        if (service != null && service.scrollObserver == observer) {
            service.scrollObserver = null;
        }
    }

    static boolean dismissNotificationShade() {
        AutoScrollAccessibilityService service = instance;
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }
        service.mainHandler.post(() -> {
            service.performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
            service.mainHandler.postDelayed(() -> {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                CharSequence packageName = root == null ? null : root.getPackageName();
                if (root != null) {
                    root.recycle();
                }
                if (SYSTEM_UI_PACKAGE.contentEquals(packageName)) {
                    service.performGlobalAction(GLOBAL_ACTION_BACK);
                }
            }, 300);
        });
        return true;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        String packageName = safeText(event.getPackageName());
        latestEvent = new AutoScrollEvidenceStore.LatestEvent(
                AccessibilityEvent.eventTypeToString(event.getEventType()),
                packageName,
                safeText(event.getClassName()));
        ScrollObserver observer = scrollObserver;
        if (observer == null
                || event.getEventType() != AccessibilityEvent.TYPE_VIEW_SCROLLED
                || getPackageName().equals(packageName)
                || SYSTEM_UI_PACKAGE.equals(packageName)) {
            return;
        }
        int deltaY = event.getScrollDeltaY();
        int deltaX = event.getScrollDeltaX();
        String sourceKey = scrollSourceKey(event);
        int scrollY = event.getScrollY();
        boolean sameSource = packageName.equals(lastScrollPackage)
                && event.getWindowId() == lastScrollWindowId
                && sourceKey.equals(lastScrollSourceKey);
        deltaY = resolveVerticalScrollDelta(deltaY, scrollY, lastScrollY, sameSource);
        boolean reliableY = deltaY != UNDEFINED_SCROLL_DELTA && deltaY != 0;
        boolean reliableX = deltaX != UNDEFINED_SCROLL_DELTA && deltaX != 0;
        lastScrollPackage = packageName;
        lastScrollWindowId = event.getWindowId();
        lastScrollSourceKey = sourceKey;
        lastScrollY = scrollY;
        if ((reliableY && (!reliableX || Math.abs(deltaY) > Math.abs(deltaX)))
                || (!reliableY && !reliableX)) {
            observer.onScrolled(
                    reliableY ? deltaY : 0,
                    reliableY,
                    packageName,
                    event.getWindowId(),
                    sourceKey);
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        scrollObserver = null;
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    private void dispatchScroll(boolean reverse, boolean fast, ScrollCallback callback) {
        dispatchSwipe(reverse, fast, completed -> {
            if (completed) {
                callback.onFinished(true);
            } else if (!performNodeScroll(reverse, callback)) {
                callback.onFinished(false);
            }
        });
    }

    private boolean performNodeScroll(boolean reverse, ScrollCallback callback) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        AccessibilityNodeInfo scrollable = null;
        try {
            scrollable = findScrollableNode(root);
            if (scrollable == null) {
                return false;
            }
            int action = reverse
                    ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            if (!scrollable.performAction(action)) {
                return false;
            }
            // ponytail: accessibility scroll actions have no completion callback; if an app lies here, the duplicate-frame bottom check is the fallback.
            mainHandler.postDelayed(() -> callback.onFinished(true), 220);
            return true;
        } finally {
            if (scrollable != null) {
                scrollable.recycle();
            }
            root.recycle();
        }
    }

    private AccessibilityNodeInfo findScrollableNode(AccessibilityNodeInfo node) {
        if (node.isScrollable()) {
            return AccessibilityNodeInfo.obtain(node);
        }
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            try {
                AccessibilityNodeInfo result = findScrollableNode(child);
                if (result != null) {
                    return result;
                }
            } finally {
                child.recycle();
            }
        }
        return null;
    }

    private void dispatchSwipe(boolean reverse, boolean fast, ScrollCallback callback) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float x = metrics.widthPixels / 2f;
        Path path = new Path();
        float start = reverse ? SWIPE_END_FRACTION : SWIPE_START_FRACTION;
        float end = reverse ? SWIPE_START_FRACTION : SWIPE_END_FRACTION;
        path.moveTo(x, metrics.heightPixels * start);
        path.lineTo(x, metrics.heightPixels * end);

        GestureDescription gesture = new GestureDescription.Builder()
                // ponytail: the 16%-screen gesture trades about 1.5x more frames for safer visual overlap; adaptive node-anchored steps are the upgrade path.
                .addStroke(new GestureDescription.StrokeDescription(
                        path,
                        0,
                        fast ? FAST_SWIPE_DURATION_MS : NORMAL_SWIPE_DURATION_MS))
                .build();
        boolean accepted = dispatchGesture(
                gesture,
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        callback.onFinished(true);
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        callback.onFinished(false);
                    }
                },
                mainHandler);
        if (!accepted) {
            callback.onFinished(false);
        }
    }

    private AutoScrollEvidenceStore.WindowSnapshot buildWindowSnapshot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return AutoScrollEvidenceStore.WindowSnapshot.unavailable("no active window root");
        }
        try {
            Rect bounds = new Rect();
            root.getBoundsInScreen(bounds);
            AutoScrollEvidenceStore.WindowStats stats = new AutoScrollEvidenceStore.WindowStats();
            collectStats(root, stats, 0);
            AutoScrollEvidenceStore.LatestEvent event = latestEvent;
            return new AutoScrollEvidenceStore.WindowSnapshot(
                    true,
                    "",
                    safeText(root.getPackageName()),
                    safeText(root.getClassName()),
                    AutoScrollEvidenceStore.rectToString(bounds),
                    stats.nodeCount,
                    stats.scrollableNodeCount,
                    stats.firstScrollableClass,
                    stats.firstScrollableBounds,
                    stats.traversalTruncated,
                    event.type,
                    event.packageName,
                    event.className);
        } finally {
            root.recycle();
        }
    }

    private static String scrollSourceKey(AccessibilityEvent event) {
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) {
            return safeText(event.getClassName());
        }
        try {
            Rect bounds = new Rect();
            source.getBoundsInScreen(bounds);
            String viewId = source.getViewIdResourceName();
            return safeText(source.getClassName())
                    + "|" + (viewId == null ? "" : viewId)
                    + "|" + bounds.flattenToString();
        } finally {
            source.recycle();
        }
    }

    static int resolveVerticalScrollDelta(
            int reportedDelta,
            int scrollY,
            int previousScrollY,
            boolean sameSource) {
        if (sameSource
                && scrollY != UNDEFINED_SCROLL_DELTA
                && previousScrollY != UNDEFINED_SCROLL_DELTA) {
            // ponytail: absolute scroll positions telescope correctly across animated WebView events; node anchors replace this when the tree is available.
            return scrollY - previousScrollY;
        }
        return reportedDelta;
    }

    private void resetObservedScrollPosition() {
        lastScrollSourceKey = "";
        lastScrollPackage = "";
        lastScrollWindowId = -1;
        lastScrollY = UNDEFINED_SCROLL_DELTA;
    }

    private void collectStats(
            AccessibilityNodeInfo node,
            AutoScrollEvidenceStore.WindowStats stats,
            int depth) {
        stats.nodeCount++;
        if (node.isScrollable()) {
            stats.scrollableNodeCount++;
            if (stats.firstScrollableClass.isEmpty()) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                stats.firstScrollableClass = safeText(node.getClassName());
                stats.firstScrollableBounds = AutoScrollEvidenceStore.rectToString(bounds);
            }
        }
        if (depth >= 4) {
            // ponytail: C23 only needs structural evidence; raise this depth if future node-level matching needs more of the tree.
            stats.traversalTruncated = true;
            return;
        }
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            try {
                collectStats(child, stats, depth + 1);
            } finally {
                child.recycle();
            }
        }
    }

    private static String safeText(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
