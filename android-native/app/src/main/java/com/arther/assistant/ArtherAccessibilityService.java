
package com.arther.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class ArtherAccessibilityService extends AccessibilityService {

    private static ArtherAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    public static boolean perform(String command) {
        if (instance == null || command == null) return false;

        try {
            switch (command) {
                case "HOME":
                    return instance.performGlobalAction(
                        GLOBAL_ACTION_HOME
                    );

                case "BACK":
                    return instance.performGlobalAction(
                        GLOBAL_ACTION_BACK
                    );

                case "SCROLL_DOWN":
                    return instance.scroll(true);

                case "SCROLL_UP":
                    return instance.scroll(false);

                default:
                    if (command.startsWith("TYPE:")) {
                        return instance.typeText(command.substring(5));
                    }

                    if (command.startsWith("CLICK_TEXT:")) {
                        return instance.clickText(command.substring(11));
                    }

                    return false;
            }
        } catch (Exception e) {
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

    private boolean clickText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        return clickMatchingNode(root, text);
    }

    private boolean clickMatchingNode(
        AccessibilityNodeInfo node,
        String text
    ) {
        CharSequence label = node.getText();

        if (label != null &&
            label.toString().toLowerCase().contains(text.toLowerCase()) &&
            node.isClickable()) {

            return node.performAction(
                AccessibilityNodeInfo.ACTION_CLICK
            );
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);

            if (child != null &&
                clickMatchingNode(child, text)) {
                return true;
            }
        }

        return false;
    }

    private boolean scroll(boolean down) {
        Path path = new Path();

        float x = getResources().getDisplayMetrics().widthPixels / 2f;
        float height = getResources().getDisplayMetrics().heightPixels;

        if (down) {
            path.moveTo(x, height * 0.78f);
            path.lineTo(x, height * 0.28f);
        } else {
            path.moveTo(x, height * 0.28f);
            path.lineTo(x, height * 0.78f);
        }

        GestureDescription gesture =
            new GestureDescription.Builder()
                .addStroke(
                    new GestureDescription.StrokeDescription(
                        path, 0, 450
                    )
                )
                .build();

        return dispatchGesture(gesture, null, null);
    }
}
