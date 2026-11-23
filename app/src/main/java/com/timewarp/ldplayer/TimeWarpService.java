package com.timewarp.ldplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Servicio en primer plano que, cada segundo real, fija la hora del sistema a
 * (hora_inicio + segundos_reales * días_por_segundo * 86400).
 */
public class TimeWarpService extends Service {

    public static final String ACTION_START = "com.timewarp.ldplayer.START";
    public static final String ACTION_STOP = "com.timewarp.ldplayer.STOP";
    public static final String EXTRA_DAYS_PER_SEC = "days_per_sec";
    public static final String EXTRA_REVERSE = "reverse";
    public static final String EXTRA_LOOP = "loop";
    public static final String EXTRA_RESTORE = "restore"; // solo en ACTION_STOP
    public static final String EXTRA_UNLIMITED = "unlimited";

    private static final String CHANNEL_ID = "timewarp";
    private static final int NOTIF_ID = 1;
    private static final long TICK_MS = 1000L; // 1 s real = N días
    private static final String PREFS = "timewarp";

    // Estado visible desde la actividad (mismo proceso)
    public static volatile boolean running = false;
    public static volatile String status = "Detenido";
    private static volatile long startWall = 0L;     // hora real (epoch ms) al iniciar
    private static volatile long startElapsed = 0L;  // elapsedRealtime al iniciar

    /** Hora real "de verdad": no se ve afectada por el salto que provocamos en el reloj. */
    public static long realNowMs() {
        return startWall + (SystemClock.elapsedRealtime() - startElapsed);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile ScheduledExecutorService worker;
    private volatile ScheduledFuture<?> tickFuture;
    private volatile boolean stopping = false;
    private volatile WarpClock clock;
    private RootShell shell;
    private TimeSetter setter;
    private double daysPerSecond = 1.0;
    private boolean loop = false;
    private boolean unlimited = false;
    private String prevAutoTime = null;
    private int failures = 0;
    private long loops = 0;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            if (worker != null) {
                boolean restore = intent.getBooleanExtra(EXTRA_RESTORE, false);
                requestStop(restore
                        ? "Detenido: hora real restaurada"
                        : "Detenido: el reloj se queda en la hora actual", restore);
            } else {
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        // Cualquier otro arranque debe pasar a primer plano de inmediato (límite de 5 s en API 26+)
        startForeground(NOTIF_ID, buildNotification("Iniciando…"));

        if (worker != null) {
            return START_STICKY; // ya está en marcha
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (ACTION_START.equals(action)) {
            double dps = intent.getDoubleExtra(EXTRA_DAYS_PER_SEC, 1.0);
            if (!(dps > 0) || dps > 3650) {
                dps = 1.0;
            }
            boolean reverse = intent.getBooleanExtra(EXTRA_REVERSE, false);
            loop = intent.getBooleanExtra(EXTRA_LOOP, false);
            unlimited = intent.getBooleanExtra(EXTRA_UNLIMITED, false);
            daysPerSecond = reverse ? -dps : dps;
            startElapsed = SystemClock.elapsedRealtime();
            startWall = System.currentTimeMillis();
        } else if (intent == null && prefs.getBoolean("running", false)
                && SystemClock.elapsedRealtime() >= prefs.getLong("startElapsed", Long.MAX_VALUE)) {
            // El sistema mató el proceso y lo reinició (mismo arranque del emulador): continuar
            startWall = prefs.getLong("startWall", System.currentTimeMillis());
            startElapsed = prefs.getLong("startElapsed", SystemClock.elapsedRealtime());
            daysPerSecond = Double.longBitsToDouble(
                    prefs.getLong("dps", Double.doubleToLongBits(1.0)));
            loop = prefs.getBoolean("loop", false);
            unlimited = prefs.getBoolean("unlimited", false);
        } else {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(dpsLabel(daysPerSecond)));
        }

        stopping = false;
        failures = 0;
        loops = 0;
        clock = new WarpClock(startWall, startElapsed, daysPerSecond);
        running = true;
        status = "Iniciando…";
        acquireWakeLock();
        worker = Executors.newSingleThreadScheduledExecutor();
        worker.execute(this::boot);
        return START_STICKY;
    }

    /** Hilo de trabajo: abre root, desactiva la hora automática y busca un método que funcione. */
    private void boot() {
        try {
            status = "Abriendo root…";
            shell = RootShell.open();
            setter = new TimeSetter(this, shell);
            if (shell != null) {
                RootShell.Result r = shell.exec("settings get global auto_time", 3000);
                prevAutoTime = r.output.trim();
                shell.exec("settings put global auto_time 0", 3000);
            }
            status = "Probando cómo cambiar la hora…";
            if (!setter.probe()) {
                fail(shell == null
                        ? "Sin root. Activa «Permiso root» en los ajustes de LDPlayer."
                        : "Hay root, pero ningún método pudo cambiar la hora.");
                return;
            }
            ScheduledExecutorService w = worker;
            if (stopping || w == null) {
                return;
            }
            saveState(true);
            status = "Activo · " + setter.strategyName();
            tickFuture = w.scheduleWithFixedDelay(this::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            fail("Error: " + t);
        }
    }

    private void tick() {
        if (stopping) {
            return;
        }
        try {
            long nowElapsed = SystemClock.elapsedRealtime();
            long virtual = clock.virtualAt(nowElapsed);
            boolean loopChanged = false;

            if (WarpClock.outOfRange(virtual, unlimited)) {
                if (loop) {
                    clock = new WarpClock(startWall, nowElapsed, daysPerSecond);
                    virtual = startWall;
                    loops++;
                    loopChanged = true;
                } else {
                    requestStop(unlimited
                            ? "Límite de fecha alcanzado (1971–9999): reloj detenido"
                            : "Límite de fecha alcanzado (1971–2037): reloj detenido", false);
                    return;
                }
            }

            // Pasada 2038 se verifica que la hora realmente cambió (ver TimeSetter.setHigh)
            boolean high = virtual >= WarpClock.INT32_MS;
            if (high && setter.highFailed) {
                requestStop("Este Android no admite fechas posteriores a 2038: reloj detenido antes de ese límite", false);
                return;
            }
            boolean applied = high ? setter.setHigh(virtual) : setter.set(virtual);
            if (high && !applied && setter.highFailed) {
                requestStop("Este Android no admite fechas posteriores a 2038: reloj detenido antes de ese límite", false);
                return;
            }
            if (applied) {
                // El texto de estado solo se reconstruye cuando cambia algo (sin basura por tick)
                if (failures != 0 || loopChanged) {
                    failures = 0;
                    status = activeText();
                }
            } else {
                failures++;
                if (failures >= 3) {
                    reopenShell();
                }
                if (failures >= 10) {
                    requestStop("Fallo repetido al cambiar la hora; detenido.", false);
                }
            }
        } catch (Throwable t) {
            status = "Error: " + t.getMessage();
        }
    }

    private void reopenShell() {
        if (shell != null) {
            shell.close();
        }
        shell = RootShell.open();
        setter.setShell(shell);
    }

    private void fail(String msg) {
        requestStop(msg, false);
    }

    /** Puede llamarse desde cualquier hilo. La restauración se hace en el hilo de trabajo. */
    private void requestStop(final String reason, final boolean restore) {
        stopping = true;
        ScheduledFuture<?> f = tickFuture;
        if (f != null) {
            f.cancel(false);
        }
        ScheduledExecutorService w = worker;
        if (w == null || w.isShutdown()) {
            finishStop(reason);
            return;
        }
        try {
            w.execute(() -> doStop(reason, restore));
        } catch (Exception e) {
            finishStop(reason);
        }
    }

    private void doStop(String reason, boolean restore) {
        try {
            ScheduledFuture<?> f = tickFuture;
            if (f != null) {
                f.cancel(false);
            }
            if (restore && setter != null && setter.isReady()) {
                if (!setter.set(realNowMs())) {
                    setter.set(realNowMs());
                }
            }
            if (shell != null) {
                // Si se deja el reloj congelado, auto_time sigue en 0 para que el sistema no lo
                // vuelva a sincronizar solo.
                if (restore && "1".equals(prevAutoTime)) {
                    shell.exec("settings put global auto_time 1", 3000);
                }
                shell.close();
                shell = null;
            }
        } catch (Throwable ignored) {
            // mejor esfuerzo
        }
        finishStop(reason);
    }

    private void finishStop(String reason) {
        running = false;
        status = reason;
        saveState(false);
        releaseWakeLock();
        ScheduledExecutorService w = worker;
        worker = null;
        if (w != null) {
            w.shutdown();
        }
        main.post(() -> {
            stopForeground(true);
            stopSelf();
        });
    }

    @Override
    public void onDestroy() {
        // Si el sistema nos destruye con el warp activo, el estado queda guardado y START_STICKY
        // reanuda; no se restaura la hora aquí a propósito.
        releaseWakeLock();
        ScheduledExecutorService w = worker;
        if (w != null) {
            w.shutdownNow();
        }
        super.onDestroy();
    }

    private void saveState(boolean on) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("running", on)
                .putLong("startWall", startWall)
                .putLong("startElapsed", startElapsed)
                .putLong("dps", Double.doubleToLongBits(daysPerSecond))
                .putBoolean("loop", loop)
                .putBoolean("unlimited", unlimited)
                .commit();
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "timewarp:tick");
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private static String dpsLabel(double d) {
        double s = 1.0 / Math.abs(d); // segundos reales por día
        String n = (s == Math.rint(s)) ? String.valueOf((long) s) : String.valueOf(Math.round(s * 100) / 100.0);
        return (d < 0 ? "Hacia atrás: " : "") + "1 día cada " + n + " s";
    }

    private String activeText() {
        return "Activo · " + setter.strategyName() + (loops > 0 ? " · vueltas: " + loops : "");
    }

    private Notification buildNotification(String text) {
        return Notif.build(this, "TimeWarp activo", text);
    }
}
