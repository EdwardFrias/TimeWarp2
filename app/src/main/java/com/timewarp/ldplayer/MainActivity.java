package com.timewarp.ldplayer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private Button btnToggle;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat fmt =
            new SimpleDateFormat("EEE dd/MM/yyyy HH:mm:ss", new Locale("es"));

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateUi();
            ui.postDelayed(this, 250);
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
        root.addView(text("1 segundo real = N días en el reloj del sistema Android.", 14, false));

        tvRoot = text("Root: comprobando…", 14, false);
        tvRoot.setPadding(0, dp(12), 0, dp(12));
        root.addView(tvRoot);

        root.addView(text("Días por segundo", 14, true));
        etDays = new EditText(this);
        etDays.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etDays.setText("1");
        root.addView(etDays);

        cbReverse = new CheckBox(this);
        cbReverse.setText("Retroceder en el tiempo");
        root.addView(cbReverse);

        cbLoop = new CheckBox(this);
        cbLoop.setText("Al llegar al año 2037, volver a la fecha de inicio");
        root.addView(cbLoop);

        btnToggle = new Button(this);
        btnToggle.setOnClickListener(v -> onToggle());
        root.addView(btnToggle, new LinearLayout.LayoutParams(
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
            startService(new Intent(this, TimeWarpService.class)
                    .setAction(TimeWarpService.ACTION_STOP));
            return;
        }
        double dps;
        try {
            dps = Double.parseDouble(etDays.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            toast("Valor no válido");
            return;
        }
        if (!(dps > 0) || dps > 3650) {
            toast("Usa un valor entre 0 y 3650 días por segundo");
            return;
        }
        Intent i = new Intent(this, TimeWarpService.class)
                .setAction(TimeWarpService.ACTION_START)
                .putExtra(TimeWarpService.EXTRA_DAYS_PER_SEC, dps)
                .putExtra(TimeWarpService.EXTRA_REVERSE, cbReverse.isChecked())
                .putExtra(TimeWarpService.EXTRA_LOOP, cbLoop.isChecked());
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
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
                    ? "Root: ✔ disponible"
                    : "Root: ✘ no disponible. En LDPlayer: Ajustes → Otros ajustes → Permiso root."));
        }, "root-check").start();
    }

    private void updateUi() {
        long sys = System.currentTimeMillis();
        boolean on = TimeWarpService.running;
        long real = on ? TimeWarpService.realNowMs() : sys;
        long diffDays = (sys - real) / WarpClock.DAY_MS;

        tvSystem.setText("Hora del sistema (emulador):\n" + fmt.format(new Date(sys)));
        tvReal.setText("Hora real:\n" + fmt.format(new Date(real))
                + (on ? "\nAdelanto simulado: " + diffDays + " días" : ""));
        tvStatus.setText("Estado: " + TimeWarpService.status);
        btnToggle.setText(on ? "Detener y restaurar hora real" : "Iniciar TimeWarp");
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
