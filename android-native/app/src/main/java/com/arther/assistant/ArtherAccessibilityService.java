
package com.arther.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;

public class ArtherAccessibilityService extends AccessibilityService {

    private static ArtherAccessibilityService instance;
    private volatile String cachedScreenText = "";
    private volatile String cachedPackageName = "";
    private long lastSnapshotAt = 0L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        refreshScreenSnapshot();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastSnapshotAt >= 250L) {
                lastSnapshotAt = now;
                refreshScreenSnapshot();
            }
        }
    }

    private void refreshScreenSnapshot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            cachedScreenText = "";
            cachedPackageName = "";
            return;
        }
        StringBuilder out = new StringBuilder();
        appendVisibleText(root, out, 0, new int[]{0});
        cachedScreenText = out.toString().trim();
        CharSequence pkg = root.getPackageName();
        cachedPackageName = pkg == null ? "" : pkg.toString();
    }

    private void appendVisibleText(
        AccessibilityNodeInfo node,
        StringBuilder out,
        int depth,
        int[] count
    ) {
        if (node == null || depth > 18 || count[0] >= 140 || out.length() >= 6000) return;
        count[0]++;
        if (!node.isVisibleToUser()) return;

        // Never collect text from editable controls: this avoids reading
        // typed drafts, search queries, and other potentially sensitive input.
        if (!node.isPassword() && !node.isEditable()) {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            appendUniqueLine(out, text);
            if (desc != null && (text == null || !desc.toString().contentEquals(text)))
                appendUniqueLine(out, desc);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (count[0] >= 140 || out.length() >= 6000) break;
            appendVisibleText(node.getChild(i), out, depth + 1, count);
        }
    }

    private void appendUniqueLine(StringBuilder out, CharSequence value) {
        if (value == null) return;
        String line = value.toString().replaceAll("\\s+", " ").trim();
        if (line.isEmpty() || out.indexOf(line) >= 0) return;
        if (out.length() > 0) out.append('\n');
        out.append(line);
    }

    public static String getCachedScreenText() {
        ArtherAccessibilityService service = instance;
        return service == null ? "" : service.cachedScreenText;
    }

    public static String getCachedPackageName() {
        ArtherAccessibilityService service = instance;
        return service == null ? "" : service.cachedPackageName;
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean isEnabled() {
        return instance != null;
    }

    public static boolean perform(String raw) {
        if (instance == null || raw == null) return false;

        String command = raw.trim();

        try {
            switch (command.toUpperCase(Locale.ROOT)) {
                case "HOME":
                    return instance.performGlobalAction(GLOBAL_ACTION_HOME);

                case "BACK":
                    return instance.performGlobalAction(GLOBAL_ACTION_BACK);

                case "RECENTS":
                    return instance.performGlobalAction(GLOBAL_ACTION_RECENTS);

                case "NOTIFICATIONS":
                    return instance.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);

                case "QUICK_SETTINGS":
                    return instance.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);

                case "POWER_DIALOG":
                    return instance.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);

                case "LOCK_SCREEN":
                    return Build.VERSION.SDK_INT >= 28 &&
                        instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);

                case "TAKE_SCREENSHOT":
                    return Build.VERSION.SDK_INT >= 28 &&
                        instance.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);

                case "SCROLL_DOWN":
                    return instance.scroll(true);

                case "SCROLL_UP":
                    return instance.scroll(false);

                default:
                    if (command.startsWith("TYPE:"))
                        return instance.typeText(command.substring(5));

                    if (command.startsWith("CLICK_TEXT:"))
                        return instance.findAndAct(command.substring(11), false);

                    if (command.startsWith("LONG_CLICK_TEXT:"))
                        return instance.findAndAct(command.substring(16), true);

                    if (command.startsWith("TAP:"))
                        return instance.tap(command.substring(4), false);

                    if (command.startsWith("LONG_PRESS:"))
                        return instance.tap(command.substring(11), true);

                    if (command.startsWith("SWIPE:"))
                        return instance.swipe(command.substring(6));

                    return false;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean typeText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        AccessibilityNodeInfo focused =
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);

        if (focused == null) return false;

        Bundle args = new Bundle();
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        );

        return focused.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            args
        );
    }

    private boolean findAndAct(String text, boolean longClick) {
        AccessibilityNodeInfo root = getRootInActiveWindow();

        return root != null &&
            findAndActNode(root, text.toLowerCase(Locale.ROOT), longClick, 0, new int[]{0});
    }

    private boolean findAndActNode(
        AccessibilityNodeInfo node,
        String wanted,
        boolean longClick,
        int depth,
        int[] visited
    ) {
        if (node == null || depth > 30 || visited[0] >= 500) return false;
        visited[0]++;

        CharSequence label = node.getText();
        CharSequence description = node.getContentDescription();

        String searchable =
            (label == null ? "" : label.toString()) + " " +
            (description == null ? "" : description.toString());

        if (searchable.toLowerCase(Locale.ROOT).contains(wanted)) {
            int action = longClick
                ? AccessibilityNodeInfo.ACTION_LONG_CLICK
                : AccessibilityNodeInfo.ACTION_CLICK;

            if (node.isClickable() ||
                (longClick && node.isLongClickable())) {
                if (node.performAction(action)) return true;
            }

            AccessibilityNodeInfo parent = node.getParent();
            int parentDepth = 0;

            while (parent != null && parentDepth++ < 30) {
                if (parent.isClickable() ||
                    (longClick && parent.isLongClickable())) {
                    if (parent.performAction(action)) return true;
                }
                parent = parent.getParent();
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);

            if (child != null &&
                findAndActNode(child, wanted, longClick, depth + 1, visited)) {
                return true;
            }
        }

        return false;
    }

    private boolean tap(String coordinates, boolean longPress) {
        String[] parts = coordinates.split(",");
        if (parts.length != 2) return false;

        float x = Float.parseFloat(parts[0].trim());
        float y = Float.parseFloat(parts[1].trim());

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(
                path, 0, longPress ? 850 : 90
            );

        return dispatchGesture(
            new GestureDescription.Builder()
                .addStroke(stroke)
                .build(),
            null,
            null
        );
    }

    private boolean swipe(String coordinates) {
        String[] p = coordinates.split(",");
        if (p.length < 4) return false;

        float x1 = Float.parseFloat(p[0].trim());
        float y1 = Float.parseFloat(p[1].trim());
        float x2 = Float.parseFloat(p[2].trim());
        float y2 = Float.parseFloat(p[3].trim());

        long duration = p.length > 4
            ? Long.parseLong(p[4].trim())
            : 350L;

        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);

        GestureDescription gesture =
            new GestureDescription.Builder()
                .addStroke(
                    new GestureDescription.StrokeDescription(
                        path, 0, Math.max(80L, duration)
                    )
                )
                .build();

        return dispatchGesture(gesture, null, null);
    }

    private boolean scroll(boolean down) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        int action = down
            ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;

        // Prefer the app's own scroll container so nested lists and web views
        // receive a semantic scroll action before using a screen-wide gesture.
        if (root != null && scrollNode(root, action, 0, new int[]{0})) return true;

        float x = getResources().getDisplayMetrics().widthPixels / 2f;
        float h = getResources().getDisplayMetrics().heightPixels;
        return swipe(
            x + "," + (down ? h * 0.78f : h * 0.28f) + "," +
            x + "," + (down ? h * 0.28f : h * 0.78f) + ",450"
        );
    }

    private boolean scrollNode(
        AccessibilityNodeInfo node,
        int action,
        int depth,
        int[] visited
    ) {
        if (node == null || depth > 30 || visited[0] >= 500 ||
            !node.isVisibleToUser()) return false;
        visited[0]++;
        if (node.isScrollable() && node.performAction(action)) return true;

        for (int i = 0; i < node.getChildCount() && visited[0] < 500; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && scrollNode(child, action, depth + 1, visited)) return true;
        }
        return false;
    }
}
