package com.example.ioslauncher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int COLS = 4;

    static class AppItem {
        String label;
        String pkg;
        ComponentName cn;
        Intent intent;
    }

    private final List<AppItem> allApps = new ArrayList<>();
    private final List<AppItem> dockApps = new ArrayList<>();
    private final List<AppItem> searchHits = new ArrayList<>();
    private final Map<String, Bitmap> iconCache = new HashMap<>();

    private PackageManager pm;
    private float density;
    private int screenW;
    private int iconSize;
    private int rows = 6;
    private int currentPage = 0;

    private FrameLayout root;
    private LinearLayout content;
    private LinearLayout pagesRow;
    private LinearLayout dotsRow;
    private LinearLayout dock;
    private SnapScroll pager;
    private FrameLayout searchOverlay;
    private LinearLayout searchResults;
    private EditText searchField;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            iconCache.clear();
            loadApps();
            rebuildHome();
            if (searchOverlay.getVisibility() == View.VISIBLE) {
                populateSearch(searchField.getText().toString());
            }
        }
    };

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();
        density = getResources().getDisplayMetrics().density;
        screenW = getResources().getDisplayMetrics().widthPixels;
        iconSize = dp(60);

        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);

        buildShell();
        setContentView(root);

        loadApps();
        rebuildHome();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(pkgReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home button pressed while launcher is already visible
        if (searchOverlay.getVisibility() == View.VISIBLE) {
            closeSearch();
        } else if (currentPage != 0) {
            pager.snapTo(0);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (searchOverlay.getVisibility() == View.VISIBLE) {
            closeSearch();
        }
        // A launcher never finishes on Back.
    }

    // ------------------------------------------------------------ UI shell

    private void buildShell() {
        root = new FrameLayout(this);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        pagesRow = new LinearLayout(this);
        pagesRow.setOrientation(LinearLayout.HORIZONTAL);

        pager = new SnapScroll(this);
        pager.addView(pagesRow, new FrameLayout.LayoutParams(WRAP, MATCH));
        pager.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            int h = (b - t) - dp(12);
            int nr = Math.max(3, Math.min(8, h / dp(96)));
            if (nr != rows) {
                rows = nr;
                pager.post(this::rebuildHome);
            }
        });
        content.addView(pager, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        dotsRow = new LinearLayout(this);
        dotsRow.setOrientation(LinearLayout.HORIZONTAL);
        dotsRow.setGravity(Gravity.CENTER);
        content.addView(dotsRow, new LinearLayout.LayoutParams(MATCH, dp(24)));

        TextView pill = new TextView(this);
        pill.setText("Search");
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(roundBg(0x40FFFFFF, 17));
        pill.setOnClickListener(v -> openSearch());
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(dp(130), dp(34));
        pillLp.gravity = Gravity.CENTER_HORIZONTAL;
        pillLp.bottomMargin = dp(10);
        content.addView(pill, pillLp);

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(8), 0, dp(8), 0);
        dock.setBackground(roundBg(0x40FFFFFF, 30));
        LinearLayout.LayoutParams dockLp = new LinearLayout.LayoutParams(MATCH, dp(86));
        dockLp.setMargins(dp(12), 0, dp(12), dp(10));
        content.addView(dock, dockLp);

        root.addView(content, new FrameLayout.LayoutParams(MATCH, MATCH));

        buildSearchOverlay();

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            content.setPadding(0, bars.top, 0, bars.bottom);
            searchOverlay.setPadding(0, bars.top, 0, Math.max(bars.bottom, ime.bottom));
            return WindowInsets.CONSUMED;
        });
    }

    private void buildSearchOverlay() {
        searchOverlay = new FrameLayout(this);
        searchOverlay.setBackgroundColor(0xE6101014);
        searchOverlay.setVisibility(View.GONE);
        searchOverlay.setClickable(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), 0);

        searchField = new EditText(this);
        searchField.setHint("Search apps");
        searchField.setHintTextColor(0x99FFFFFF);
        searchField.setTextColor(Color.WHITE);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        searchField.setSingleLine(true);
        searchField.setImeOptions(EditorInfo.IME_ACTION_GO);
        searchField.setBackground(roundBg(0x33FFFFFF, 14));
        searchField.setPadding(dp(16), 0, dp(16), 0);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                populateSearch(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        searchField.setOnEditorActionListener((tv, id, ev) -> {
            if (!searchHits.isEmpty()) {
                launch(searchHits.get(0), tv);
            }
            return true;
        });
        box.addView(searchField, new LinearLayout.LayoutParams(MATCH, dp(48)));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        searchResults = new LinearLayout(this);
        searchResults.setOrientation(LinearLayout.VERTICAL);
        sv.addView(searchResults, new FrameLayout.LayoutParams(MATCH, WRAP));
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        svLp.topMargin = dp(12);
        box.addView(sv, svLp);

        searchOverlay.addView(box, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(searchOverlay, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    // ------------------------------------------------------------ data

    private void loadApps() {
        allApps.clear();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        for (ResolveInfo ri : list) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            AppItem it = new AppItem();
            it.pkg = pkg;
            it.cn = new ComponentName(pkg, ri.activityInfo.name);
            it.label = String.valueOf(ri.loadLabel(pm));
            it.intent = new Intent(main).setComponent(it.cn);
            allApps.add(it);
        }
        Collections.sort(allApps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        loadDock();
    }

    private void loadDock() {
        dockApps.clear();
        List<Intent> slots = new ArrayList<>();
        slots.add(new Intent(Intent.ACTION_DIAL));
        slots.add(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING));
        slots.add(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER));
        slots.add(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
        for (Intent base : slots) {
            AppItem it = resolveSlot(base);
            if (it == null) continue;
            boolean dup = false;
            for (AppItem d : dockApps) {
                if (d.pkg.equals(it.pkg)) dup = true;
            }
            if (!dup) dockApps.add(it);
        }
    }

    private AppItem resolveSlot(Intent base) {
        ResolveInfo ri = pm.resolveActivity(base, 0);
        if (ri == null || ri.activityInfo == null || "android".equals(ri.activityInfo.packageName)) {
            List<ResolveInfo> l = pm.queryIntentActivities(base, 0);
            ri = l.isEmpty() ? null : l.get(0);
        }
        if (ri == null || ri.activityInfo == null) return null;
        AppItem it = new AppItem();
        it.pkg = ri.activityInfo.packageName;
        it.cn = new ComponentName(it.pkg, ri.activityInfo.name);
        it.label = String.valueOf(ri.loadLabel(pm));
        it.intent = new Intent(base).setComponent(it.cn);
        return it;
    }

    private List<AppItem> gridApps() {
        List<AppItem> out = new ArrayList<>();
        for (AppItem a : allApps) {
            boolean inDock = false;
            for (AppItem d : dockApps) {
                if (d.pkg.equals(a.pkg)) inDock = true;
            }
            if (!inDock) out.add(a);
        }
        return out;
    }

    // ------------------------------------------------------------ home grid

    private void rebuildHome() {
        pagesRow.removeAllViews();
        List<AppItem> grid = gridApps();
        int per = rows * COLS;
        int pages = Math.max(1, (grid.size() + per - 1) / per);
        int cellW = (screenW - dp(16)) / COLS;

        for (int p = 0; p < pages; p++) {
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(dp(8), dp(12), dp(8), 0);
            for (int r = 0; r < rows; r++) {
                if (p * per + r * COLS >= grid.size()) break;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int c = 0; c < COLS; c++) {
                    int idx = p * per + r * COLS + c;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cellW, dp(96));
                    if (idx < grid.size()) {
                        row.addView(makeCell(grid.get(idx), true), lp);
                    } else {
                        row.addView(new View(this), lp);
                    }
                }
                page.addView(row, new LinearLayout.LayoutParams(MATCH, dp(96)));
            }
            pagesRow.addView(page, new LinearLayout.LayoutParams(screenW, MATCH));
        }

        if (currentPage >= pages) currentPage = pages - 1;
        buildDots(pages);
        buildDock();
        final int target = currentPage;
        pager.post(() -> pager.scrollTo(target * screenW, 0));
    }

    private void buildDots(int pages) {
        dotsRow.removeAllViews();
        if (pages < 2) return;
        for (int i = 0; i < pages; i++) {
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Color.WHITE);
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(7), dp(7));
            lp.setMargins(dp(4), 0, dp(4), 0);
            dotsRow.addView(d, lp);
        }
        updateDots(currentPage);
    }

    private void updateDots(int page) {
        if (dotsRow == null) return;
        for (int i = 0; i < dotsRow.getChildCount(); i++) {
            dotsRow.getChildAt(i).setAlpha(i == page ? 1f : 0.4f);
        }
    }

    private void buildDock() {
        dock.removeAllViews();
        for (AppItem it : dockApps) {
            dock.addView(makeCell(it, false), new LinearLayout.LayoutParams(0, MATCH, 1f));
        }
        dock.setVisibility(dockApps.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private View makeCell(final AppItem item, boolean withLabel) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(withLabel ? (Gravity.TOP | Gravity.CENTER_HORIZONTAL) : Gravity.CENTER);
        cell.setPadding(0, withLabel ? dp(6) : 0, 0, 0);

        ImageView iv = new ImageView(this);
        iv.setImageBitmap(getIcon(item));
        cell.addView(iv, new LinearLayout.LayoutParams(iconSize, iconSize));

        if (withLabel) {
            TextView tv = new TextView(this);
            tv.setText(item.label);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setGravity(Gravity.CENTER);
            tv.setShadowLayer(4f, 0f, 1f, 0xAA000000);
            tv.setPadding(dp(2), 0, dp(2), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
            lp.topMargin = dp(5);
            cell.addView(tv, lp);
        }

        cell.setOnClickListener(v -> launch(item, v));
        cell.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            showMenu(item, v);
            return true;
        });
        cell.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    break;
                default:
                    break;
            }
            return false;
        });
        return cell;
    }

    // ------------------------------------------------------------ actions

    private void launch(AppItem item, View from) {
        if (searchOverlay.getVisibility() == View.VISIBLE) closeSearch();
        Intent i = new Intent(item.intent);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            Bundle opts = ActivityOptions.makeScaleUpAnimation(
                    from, 0, 0, Math.max(1, from.getWidth()), Math.max(1, from.getHeight())).toBundle();
            startActivity(i, opts);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open " + item.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void showMenu(final AppItem item, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "App info");
        menu.getMenu().add(0, 2, 1, "Uninstall");
        menu.setOnMenuItemClickListener(mi -> {
            try {
                if (mi.getItemId() == 1) {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", item.pkg, null)));
                } else {
                    startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + item.pkg)));
                }
            } catch (Exception e) {
                Toast.makeText(this, "Not available", Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        menu.show();
    }

    // ------------------------------------------------------------ search

    private void openSearch() {
        searchField.setText("");
        populateSearch("");
        searchOverlay.setAlpha(0f);
        searchOverlay.setVisibility(View.VISIBLE);
        searchOverlay.animate().alpha(1f).setDuration(150).start();
        searchField.requestFocus();
        searchField.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    private void closeSearch() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        searchOverlay.setVisibility(View.GONE);
        searchField.setText("");
    }

    private void populateSearch(String q) {
        searchResults.removeAllViews();
        searchHits.clear();
        String needle = q.trim().toLowerCase(Locale.getDefault());
        for (AppItem a : allApps) {
            if (needle.isEmpty() || a.label.toLowerCase(Locale.getDefault()).contains(needle)) {
                searchHits.add(a);
            }
        }
        int cellW = (screenW - dp(32)) / COLS;
        LinearLayout row = null;
        for (int i = 0; i < searchHits.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                searchResults.addView(row, new LinearLayout.LayoutParams(MATCH, dp(96)));
            }
            row.addView(makeCell(searchHits.get(i), true), new LinearLayout.LayoutParams(cellW, dp(96)));
        }
    }

    // ------------------------------------------------------------ icons

    private Bitmap getIcon(AppItem item) {
        String key = item.cn.flattenToString();
        Bitmap b = iconCache.get(key);
        if (b != null) return b;
        Drawable d;
        try {
            d = pm.getActivityIcon(item.cn);
        } catch (Exception e) {
            d = pm.getDefaultActivityIcon();
        }
        b = renderIcon(d, iconSize);
        iconCache.put(key, b);
        return b;
    }

    /** Draws the app icon filling a rounded-square (iOS style) instead of the system mask. */
    private Bitmap renderIcon(Drawable d, int s) {
        Bitmap tmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(tmp);
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            int ex = Math.round(s * 0.25f); // layers are 108dp, visible area is the central 72dp
            Drawable bg = a.getBackground();
            Drawable fg = a.getForeground();
            if (bg != null) {
                bg.setBounds(-ex, -ex, s + ex, s + ex);
                bg.draw(c);
            } else {
                c.drawColor(Color.WHITE);
            }
            if (fg != null) {
                fg.setBounds(-ex, -ex, s + ex, s + ex);
                fg.draw(c);
            }
        } else {
            c.drawColor(Color.WHITE);
            int pad = Math.round(s * 0.12f);
            d.setBounds(pad, pad, s - pad, s - pad);
            d.draw(c);
        }

        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new BitmapShader(tmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float r = s * 0.225f;
        new Canvas(out).drawRoundRect(new RectF(0, 0, s, s), r, r, p);
        return out;
    }

    // ------------------------------------------------------------ helpers

    private int dp(float v) {
        return Math.round(v * density);
    }

    private GradientDrawable roundBg(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** Horizontal pager that always snaps to a full page, like the iOS home screen. */
    private class SnapScroll extends HorizontalScrollView {
        private VelocityTracker vt;
        private int startPage;

        SnapScroll(Context ctx) {
            super(ctx);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
        }

        private int pageNow() {
            return Math.round(getScrollX() / (float) screenW);
        }

        void snapTo(int page) {
            int max = Math.max(0, pagesRow.getChildCount() - 1);
            int t = Math.max(0, Math.min(max, page));
            currentPage = t;
            updateDots(t);
            smoothScrollTo(t * screenW, 0);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                startPage = pageNow();
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
            }
            if (vt != null) vt.addMovement(ev);
            return super.dispatchTouchEvent(ev);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            int a = ev.getActionMasked();
            boolean handled = super.onTouchEvent(ev);
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                float vx = 0f;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    vx = vt.getXVelocity();
                }
                int target = pageNow();
                if (Math.abs(vx) > 700f) {
                    target = startPage + (vx < 0 ? 1 : -1);
                }
                snapTo(target);
            }
            return handled;
        }

        @Override
        public void fling(int velocityX) {
            // handled by snapTo()
        }

        @Override
        protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            updateDots(Math.round(l / (float) screenW));
        }
    }
}