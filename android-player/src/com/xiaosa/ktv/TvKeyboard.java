package com.xiaosa.ktv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 电视遥控器专用屏幕键盘。
 *
 * 为什么 APK 里要自带键盘：
 *  - 很多电视盒子/投影仪根本没有系统输入法，或者遥控器按 OK 不会弹出输入法；
 *  - 参考的原生 APK（骏耀K歌）也自带一个 AlphaKeyboard，这是电视端的必备能力；
 *  - 有了它，登录账号、输入服务器 IP 都不再依赖系统输入法或 USB 键盘。
 *
 * 键盘本身只用最普通的 Button + 焦点态背景，不依赖任何新 API，
 * Android 5.1 也能正常用遥控器方向键移动、OK 输入。
 */
final class TvKeyboard {
    interface Host {
        /** 键盘标题，例如「正在输入：账号」。 */
        String kbLabel();
        /** 当前输入内容预览（密码用圆点）。 */
        String kbValue();
        void kbChar(String c);
        void kbBackspace();
        void kbClear();
        void kbDone();
        /** 切换输入目标（账号/密码）。 */
        void kbSwitch();
        /** 收起键盘。 */
        void kbClose();
    }

    private final Context ctx;
    private final Host host;
    private final boolean numeric;
    private final LinearLayout panel;
    private TextView labelView;
    private TextView valueView;
    private final List<Button> letterKeys = new ArrayList<>();
    private Button firstKey;
    private boolean shift;

    TvKeyboard(Context context, Host host, boolean numeric) {
        this.ctx = context;
        this.host = host;
        this.numeric = numeric;
        this.panel = build();
    }

    View getView() { return panel; }

    void setVisible(boolean visible) { panel.setVisibility(visible ? View.VISIBLE : View.GONE); }

    boolean isVisible() { return panel.getVisibility() == View.VISIBLE; }

    void refresh() {
        if (labelView != null) labelView.setText(host.kbLabel());
        if (valueView != null) {
            String v = host.kbValue();
            if (v == null || v.isEmpty()) v = "（空）";
            valueView.setText(v);
        }
    }

    /** 把遥控器焦点送到键盘第一个按键，用户按 OK 就能开始输入。 */
    void focusFirst() {
        if (firstKey != null && firstKey.getVisibility() == View.VISIBLE) {
            firstKey.requestFocus();
        }
    }

    // ------------------------------------------------------------------ build

    private LinearLayout build() {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.setBackground(panelBackground());
        root.setFocusable(false);

        // 标题行：标签 + 当前内容 + 切换/收起
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, dp(4), dp(8));

        labelView = new TextView(ctx);
        labelView.setTextColor(0xFFB8B8D8);
        labelView.setTextSize(13);
        labelView.setSingleLine(true);
        labelView.setText(host.kbLabel());
        header.addView(labelView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        valueView = new TextView(ctx);
        valueView.setTextColor(0xFF36D9F7);
        valueView.setTextSize(15);
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        valueView.setSingleLine(true);
        valueView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        valueView.setPadding(dp(10), 0, dp(10), 0);
        header.addView(valueView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (!numeric) {
            Button switchBtn = makeKey("切换账号/密码", 13, dp(34), false);
            switchBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { host.kbSwitch(); refresh(); }
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
            slp.rightMargin = dp(8);
            header.addView(switchBtn, slp);
        }

        Button closeBtn = makeKey(numeric ? "取消" : "收起", 13, dp(34), false);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!numeric) setVisible(false);
                host.kbClose();
            }
        });
        header.addView(closeBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (numeric) buildNumeric(root);
        else buildFull(root);

        return root;
    }

    private void buildFull(LinearLayout root) {
        addRow(root, new String[]{"1","2","3","4","5","6","7","8","9","0"}, true);
        addRow(root, new String[]{"q","w","e","r","t","y","u","i","o","p"}, true);
        addRow(root, new String[]{"a","s","d","f","g","h","j","k","l"}, true);
        addRow(root, new String[]{"z","x","c","v","b","n","m",".","-","_"}, true);

        // 最后一行：Shift / @ / : / 空格 / 退格 / 清空 / 完成
        LinearLayout row = newRow();
        Button shiftBtn = actionKey("大小写", 1.3f);
        shiftBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                shift = !shift;
                for (Button b : letterKeys) {
                    Object tag = b.getTag();
                    if (tag instanceof String) {
                        String s = (String) tag;
                        b.setText(shift ? s.toUpperCase() : s);
                    }
                }
            }
        });
        row.addView(shiftBtn, weight(1.3f));

        addSymbol(row, "@", 1);
        addSymbol(row, ":", 1);
        addSymbol(row, "/", 1);

        Button space = actionKey("空格", 2.2f);
        space.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.kbChar(" "); refresh(); }
        });
        row.addView(space, weight(2.2f));

        Button back = actionKey("退格", 1.3f);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.kbBackspace(); refresh(); }
        });
        row.addView(back, weight(1.3f));

        Button clear = actionKey("清空", 1.1f);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.kbClear(); refresh(); }
        });
        row.addView(clear, weight(1.1f));

        Button done = actionKey("完成", 1.2f);
        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.kbDone(); }
        });
        row.addView(done, weight(1.2f));

        root.addView(row, rowParams());
    }

    private void buildNumeric(LinearLayout root) {
        String[][] rows = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {".", "0", "退格"}
        };
        for (String[] keys : rows) {
            LinearLayout row = newRow();
            for (String k : keys) {
                Button b;
                if ("退格".equals(k)) {
                    b = actionKey("退格", 1);
                    b.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { host.kbBackspace(); refresh(); }
                    });
                } else {
                    b = makeKey(k, 22, dp(48), true);
                    b.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { host.kbChar(v.getTag().toString()); refresh(); }
                    });
                    b.setTag(k);
                    if (firstKey == null) firstKey = b;
                }
                row.addView(b, weight(1));
            }
            root.addView(row, rowParams());
        }

        LinearLayout row = newRow();
        Button clear = actionKey("清空", 1);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.kbClear(); refresh(); }
        });
        row.addView(clear, weight(1));

        // 端口分隔符：允许输入 192.168.1.10:8080 这种非默认端口。
        Button colon = actionKey(":", 1);
        colon.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.kbChar(":"); refresh(); }
        });
        row.addView(colon, weight(1));

        Button ok = actionKey("确定", 1);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.kbDone(); }
        });
        row.addView(ok, weight(1));
        root.addView(row, rowParams());
    }

    private void addRow(LinearLayout root, String[] keys, boolean letters) {
        LinearLayout row = newRow();
        for (String k : keys) {
            Button b = makeKey(k, 20, dp(42), false);
            if (firstKey == null) firstKey = b;
            if (letters && k.matches("[a-z0-9]")) {
                b.setTag(k);
                if (k.matches("[a-z]")) letterKeys.add(b);
            }
            final String ch = k;
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String out = ch;
                    if (shift && ch.matches("[a-z]")) out = ch.toUpperCase();
                    host.kbChar(out);
                    refresh();
                }
            });
            row.addView(b, weight(1));
        }
        root.addView(row, rowParams());
    }

    private void addSymbol(LinearLayout row, String sym, float weight) {
        Button b = actionKey(sym, weight);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.kbChar(sym); refresh(); }
        });
        row.addView(b, weight(weight));
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(numeric ? 56 : 50));
        lp.topMargin = dp(numeric ? 6 : 5);
        return lp;
    }

    private LinearLayout.LayoutParams weight(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w);
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        return lp;
    }

    private Button actionKey(String text, float weight) {
        return makeKey(text, 16, dp(numeric ? 48 : 42), false);
    }

    private Button makeKey(String text, int textSize, int height, boolean big) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(textSize);
        b.setTextColor(0xFFF4F4FF);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setPadding(dp(2), 0, dp(2), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setBackground(keyBackground());
        try { b.setStateListAnimator(null); } catch (Exception ignored) {}
        return b;
    }

    private GradientDrawable roundRect(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (strokeColor != 0) d.setStroke(dp(strokeWidth), strokeColor);
        return d;
    }

    private StateListDrawable keyBackground() {
        GradientDrawable normal = roundRect(0xFF16162E, 10, 0x33FFFFFF, 1);
        GradientDrawable focused = roundRect(0xFF2C2450, 10, 0xFFFFFFFF, 2);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(new int[]{android.R.attr.state_pressed}, focused);
        sl.addState(new int[]{}, normal);
        return sl;
    }

    private GradientDrawable panelBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xF210102A);
        d.setCornerRadius(dp(16));
        d.setStroke(dp(1), 0x33FFFFFF);
        return d;
    }

    private int dp(float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
