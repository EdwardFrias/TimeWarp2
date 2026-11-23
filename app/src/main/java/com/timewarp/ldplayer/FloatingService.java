package com.timewarp.ldplayer;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Ventana flotante (overlay) arrastrable con la hora del sistema y los controles de TimeWarp.
 * Se puede minimizar a una burbuja pequeña.
 *
 * Ahorro de recursos: la interfaz se actualiza 1 vez por segundo, solo si la ventana está
 * expandida y la pantalla encendida; los textos solo se reasignan cuando cambian.
 */
public class FloatingService extends Service {

    public static final String ACTION_SHOW = "com.timewarp.ldplayer.FLOAT_SHOW";
    public static final String ACTION_HIDE = "com.timewarp.ldplayer.FLOAT_HIDE";

    private static final int NOTIF_ID = 2;
    private static final long UI_TICK_MS = 1000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat fmtDate =
            new SimpleDateFormat("EEE dd/MM/yyyy", new Locale("es"));
    private final SimpleDateFormat fmtTime = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final Date date = new Date();

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private FrameLayout root;
    private LinearLayout panel;
    private TextView bubble;
    private TextView tvTime;
    private TextView tvStatus;
    private TextView btnStart;

    private boolean compact = false;
    private boolean screenOn = true;
    private String lastTime = "";
    private String lastStatus = "";
    private String lastBtn = "";

    private float downX;
    private float downY;
    private int startX;
    private int startY;
    private boolean moved;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            if (screenOn && !compact) {
                ui.postDelayed(this, UI_TICK_MS);
            }
        }
    };

    private final BroadcastReceiver screenRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            ui.removeCallbacks(tick);
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                screenOn = false;
            } else {
                screenOn = true;
                if (!compact) {
                    ui.post(tick);
                }
            }
        }
    };

    private final View.OnTouchListener drag = (v, e) -> {
        if (root == null) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                startX = lp.x;
                startY = lp.y;
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downX;
                float dy = e.getRawY() - downY;
                if (!moved && Math.abs(dx) + Math.abs(dy) > dp(6)) {
                    moved = true;
                }
                if (moved) {
                    DisplayMetrics dm = getResources().getDisplayMetrics();
                    int maxX = Math.max(0, dm.widthPixels - root.getWidth());
                    int maxY = Math.max(0, dm.heightPixels - root.getHeight());
                    lp.x = Math.min(maxX, Math.max(0, startX + (int) dx));
                    lp.y = Math.min(maxY, Math.max(0, startY + (int) dy));
                    wm.updateViewLayout(root, lp);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (moved) {
                    Prefs.saveFloatPos(this, lp.x, lp.y);
                } else if (compact) {
                    setCompact(false);
                }
                return true;
            default:
                return false;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_HIDE.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }

        // Los servicios en primer plano evitan que el sistema cierre la ventana en segundo plano
        startForeground(NOTIF_ID, Notif.build(this, "TimeWarp", "Ventana flotante activa"));

        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Falta el permiso «Mostrar sobre otras apps»", Toast.LENGTH_LONG).show();
            shutdown();
            return START_NOT_STICKY;
        }
        if (root == null) {
            addOverlay();
        }
        return START_STICKY;
    }

    private void addOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Prefs.floatX(this, dp(16));
        lp.y = Prefs.floatY(this, dp(80));

        final int wrap = ViewGroup.LayoutParams.WRAP_CONTENT;
        FrameLayout rootView = new FrameLayout(this);

        // ---- Panel expandido ----
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(10));
        panel.setBackground(shape(0xE6202124, dp(14), GradientDrawable.RECTANGLE));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label("TimeWarp", 12, 0xFFB0BEC5, true),
                new LinearLayout.LayoutParams(0, wrap, 1f));
        TextView btnMin = chip("–");
        TextView btnClose = chip("X");
        btnMin.setOnClickListener(v -> setCompact(true));
        btnClose.setOnClickListener(v -> shutdown());
        header.addView(btnMin);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(wrap, wrap);
        closeLp.leftMargin = dp(4);
        header.addView(btnClose, closeLp);
        panel.addView(header);

        tvTime = label("", 18, 0xFFFFFFFF, true);
        tvTime.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvTime.setPadding(0, dp(6), 0, dp(2));
        panel.addView(tvTime);

        tvStatus = label("", 11, 0xFFB0BEC5, false);
        tvStatus.setMaxLines(2);
        tvStatus.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(tvStatus);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        btnStart = chip("Iniciar");
        TextView btnApp = chip("App");
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(0, wrap, 1f);
        startLp.rightMargin = dp(6);
        actions.addView(btnStart, startLp);
        actions.addView(btnApp, new LinearLayout.LayoutParams(0, wrap, 1f));
        panel.addView(actions);

        btnStart.setOnClickListener(v -> {
            if (TimeWarpService.running) {
                Prefs.stop(this);
            } else {
                Prefs.start(this);
            }
            ui.postDelayed(this::update, 500);
        });
        btnApp.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)));

        // ---- Burbuja (minimizada) ----
        bubble = label("TW", 13, 0xFFFFFFFF, true);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackground(shape(0xE6202124, dp(22), GradientDrawable.OVAL));
        bubble.setVisibility(View.GONE);

        rootView.addView(panel, new FrameLayout.LayoutParams(dp(190), wrap));
        rootView.addView(bubble, new FrameLayout.LayoutParams(dp(44), dp(44)));
        rootView.setOnTouchListener(drag);

        try {
            wm.addView(rootView, lp);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo mostrar la ventana flotante", Toast.LENGTH_LONG).show();
            shutdown();
            return;
        }
        root = rootView;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        screenOn = pm == null || pm.isInteractive();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenRx, f);

        if (screenOn) {
            ui.post(tick);
        }
    }

    private void setCompact(boolean c) {
        compact = c;
        panel.setVisibility(c ? View.GONE : View.VISIBLE);
        bubble.setVisibility(c ? View.VISIBLE : View.GONE);
        ui.removeCallbacks(tick);
        if (!c && screenOn) {
            lastTime = "";
            lastStatus = "";
            lastBtn = "";
            ui.post(tick);
        }
    }

    private void update() {
        if (compact || root == null) {
            return;
        }
        date.setTime(System.currentTimeMillis());
        String t = fmtDate.format(date) + "\n" + fmtTime.format(date);
        if (!t.equals(lastTime)) {
            lastTime = t;
            tvTime.setText(t);
        }

        String st = TimeWarpService.status;
        boolean active = TimeWarpService.running && st.startsWith("Activo");
        String key = active ? "\u0001" : st;
        if (!key.equals(lastStatus)) {
            lastStatus = key;
            tvStatus.setText(active ? "Activo · " + Prefs.label(this) : st);
        }

        String b = TimeWarpService.running ? "Detener" : "Iniciar";
        if (!b.equals(lastBtn)) {
            lastBtn = b;
            btnStart.setText(b);
        }
    }

    private void removeOverlay() {
        ui.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(screenRx);
        } catch (Exception ignored) {
            // no estaba registrado
        }
        if (root != null && wm != null) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
                // ya retirada
            }
        }
        root = null;
    }

    private void shutdown() {
        removeOverlay();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }

    // ---- Utilidades de interfaz ----

    private GradientDrawable shape(int color, int radius, int shape) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(shape);
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private TextView chip(String text) {
        TextView t = label(text, 12, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(6), dp(8), dp(6));
        t.setBackground(shape(0x33FFFFFF, dp(8), GradientDrawable.RECTANGLE));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
