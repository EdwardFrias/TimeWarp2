package com.timewarp.ldplayer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private TextView tvRoot;
    private TextView tvSystem;
    private TextView tvReal;
    private TextView tvStatus;
    private EditText etDays;
    private CheckBox cbReverse;
    private CheckBox cbLoop;
    private CheckBox cbRestore;
    private CheckBox cbUnlimited;
    private Button btnToggle;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat fmt =
            new SimpleDateFormat("EEE dd/MM/yyyy HH:mm:ss", new Locale("es"));
    private final Date date = new Date();

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateUi();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int p = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        root.addView(text("TimeWarp", 28, true));
        root.addView(text("N segundos reales = 1 día en el reloj del sistema Android.", 14, false));

        tvRoot = text("Root: comprobando…", 14, false);
        tvRoot.setPadding(0, dp(12), 0, dp(12));
        root.addView(tvRoot);

        root.addView(text("Segundos reales por cada día", 14, true));
        etDays = new EditText(this);
        etDays.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etDays.setText(Prefs.num(Prefs.secondsPerDay(this)));
        root.addView(etDays);

        cbReverse = new CheckBox(this);
        cbReverse.setText("Retroceder en el tiempo");
        cbReverse.setChecked(Prefs.reverse(this));
        root.addView(cbReverse);

        cbLoop = new CheckBox(this);
        cbLoop.setText("Al llegar al límite, volver a la fecha de inicio");
        cbLoop.setChecked(Prefs.loop(this));
        root.addView(cbLoop);

        cbUnlimited = new CheckBox(this);
        cbUnlimited.setText("Quitar el límite de 2037 (hasta el año 9999; se detiene solo si el Android no lo admite)");
        cbUnlimited.setChecked(Prefs.unlimited(this));
        root.addView(cbUnlimited);

        cbRestore = new CheckBox(this);
        cbRestore.setText("Al detener, devolver la hora real (si no, el reloj se queda donde está)");
        cbRestore.setChecked(Prefs.restore(this));
        root.addView(cbRestore);

        btnToggle = new Button(this);
        btnToggle.setOnClickListener(v -> onToggle());
        root.addView(btnToggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button btnFloat = new Button(this);
        btnFloat.setText("Abrir ventana flotante");
        btnFloat.setOnClickListener(v -> openFloating());
        root.addView(btnFloat, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button btnRoot = new Button(this);
        btnRoot.setText("Comprobar root");
        btnRoot.setOnClickListener(v -> checkRoot());
        root.addView(btnRoot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvSystem = text("", 16, false);
        tvSystem.setPadding(0, dp(16), 0, 0);
        root.addView(tvSystem);
        tvReal = text("", 16, false);
        tvReal.setPadding(0, dp(8), 0, 0);
        root.addView(tvReal);
        tvStatus = text("", 14, false);
        tvStatus.setPadding(0, dp(12), 0, 0);
        root.addView(tvStatus);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        checkRoot();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    private void onToggle() {
        if (TimeWarpService.running) {
            Prefs.save(this, Prefs.secondsPerDay(this), cbReverse.isChecked(),
                    cbLoop.isChecked(), cbRestore.isChecked(), cbUnlimited.isChecked());
            Prefs.stop(this);
            return;
        }
        double secondsPerDay;
        try {
            secondsPerDay = Double.parseDouble(etDays.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            toast("Valor no válido");
            return;
        }
        if (!(secondsPerDay >= 0.01 && secondsPerDay <= 10_000_000d)) {
            toast("Usa un valor entre 0.01 y 10000000 segundos por día");
            return;
        }
        Prefs.save(this, secondsPerDay, cbReverse.isChecked(), cbLoop.isChecked(),
                cbRestore.isChecked(), cbUnlimited.isChecked());
        Prefs.start(this);
    }

    /** Abre la ventana flotante; si falta el permiso, intenta concederlo con root. */
    private void openFloating() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            new Thread(() -> {
                RootShell s = RootShell.open();
                if (s != null) {
                    s.exec("appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow", 4000);
                    s.close();
                }
                runOnUiThread(() -> {
                    if (Settings.canDrawOverlays(this)) {
                        startFloating();
                    } else {
                        requestOverlayPermission();
                    }
                });
            }, "grant-overlay").start();
        } else {
            startFloating();
        }
    }

    private void requestOverlayPermission() {
        toast("Concede «Mostrar sobre otras apps» a TimeWarp y pulsa el botón otra vez");
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
            // la pantalla de permisos no existe en este sistema
        }
    }

    private void startFloating() {
        Intent i = new Intent(this, FloatingService.class).setAction(FloatingService.ACTION_SHOW);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        moveTaskToBack(true);
    }

    private void checkRoot() {
        tvRoot.setText("Root: comprobando…");
        new Thread(() -> {
            RootShell s = RootShell.open();
            final boolean ok = s != null;
            if (s != null) {
                s.close();
            }
            runOnUiThread(() -> tvRoot.setText(ok
                    ? "Root: disponible"
                    : "Root: no disponible. En LDPlayer: Ajustes → Otros ajustes → Permiso root."));
        }, "root-check").start();
    }

    private void updateUi() {
        long sys = System.currentTimeMillis();
        boolean on = TimeWarpService.running;
        long real = on ? TimeWarpService.realNowMs() : sys;
        long diffDays = (sys - real) / WarpClock.DAY_MS;

        date.setTime(sys);
        tvSystem.setText("Hora del sistema (emulador):\n" + fmt.format(date));
        date.setTime(real);
        tvReal.setText("Hora real:\n" + fmt.format(date)
                + (on ? "\nAdelanto simulado: " + diffDays + " días" : ""));
        tvStatus.setText("Estado: " + TimeWarpService.status);
        btnToggle.setText(on ? "Detener" : "Iniciar TimeWarp");
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
