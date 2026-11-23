package com.timewarp.ldplayer;

import android.app.AlarmManager;
import android.content.Context;
import android.os.SystemClock;

/**
 * Cambia la hora del sistema. Como cada versión de Android/LDPlayer trae un `date` distinto
 * (toolbox en 5.x/6.x, toybox en 7+), se PRUEBAN varios métodos y se queda con el primero cuyo
 * efecto se puede comprobar leyendo la hora del sistema después.
 */
final class TimeSetter {

    static final String[] NAMES = {
            "AlarmManager.setTime (app de sistema)",
            "date -u MMDDhhmmAAAA.ss",
            "date -u -s \"AAAA-MM-DD hh:mm:ss\"",
            "date -s AAAAMMDD.hhmmss",
            "service call alarm 3",
            "service call alarm 2"
    };

    private final Context ctx;
    private volatile RootShell shell; // puede ser null (sin root)
    private volatile int selected = -1;

    TimeSetter(Context ctx, RootShell shell) {
        this.ctx = ctx;
        this.shell = shell;
    }

    void setShell(RootShell shell) {
        this.shell = shell;
    }

    boolean isReady() {
        return selected >= 0;
    }

    String strategyName() {
        return selected >= 0 ? NAMES[selected] : "ninguno";
    }

    /**
     * Prueba cada método fijando la hora a "ahora + 60 s" y comprobando que el reloj del sistema
     * realmente se movió (un `date` que falla en silencio dejaría la hora igual, con 60 s de
     * diferencia respecto a lo esperado). No llamar desde el hilo principal.
     */
    boolean probe() {
        for (int id = 0; id < NAMES.length; id++) {
            if (id > 0 && shell == null) {
                break; // del 1 en adelante hace falta root
            }
            long e0 = SystemClock.elapsedRealtime();
            long target = System.currentTimeMillis() + 60_000L;
            if (!run(id, target)) {
                continue;
            }
            long e1 = SystemClock.elapsedRealtime();
            long expected = target + (e1 - e0);
            long actual = System.currentTimeMillis();
            if (Math.abs(actual - expected) <= 5_000L) {
                selected = id;
                return true;
            }
        }
        return false;
    }

    /** true si ningún método logró fijar una fecha posterior a 2038 en este Android. */
    volatile boolean highFailed = false;

    private static boolean landed(long ms) {
        return Math.abs(System.currentTimeMillis() - ms) <= 5_000L;
    }

    /**
     * Para fechas posteriores a 2038. Muchos `date` de 32 bits fallan o dan la vuelta en silencio,
     * así que se comprueba que la hora realmente quedó puesta; si no, se prueban los demás
     * métodos (p. ej. `service call alarm`, que pasa milisegundos de 64 bits).
     */
    boolean setHigh(long ms) {
        int cur = selected;
        if (cur >= 0 && run(cur, ms) && landed(ms)) {
            return true;
        }
        for (int id = 0; id < NAMES.length; id++) {
            if (id == cur) {
                continue;
            }
            if (id > 0 && shell == null) {
                break;
            }
            if (run(id, ms) && landed(ms)) {
                selected = id;
                return true;
            }
        }
        highFailed = true;
        return false;
    }

    /** Fija la hora del sistema con el método elegido en probe(). */
    boolean set(long epochMs) {
        int id = selected;
        return id >= 0 && run(id, epochMs);
    }

    private boolean run(int id, long ms) {
        try {
            switch (id) {
                case 0: {
                    AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
                    am.setTime(ms); // SecurityException si la app no es de sistema
                    return true;
                }
                case 1:
                    return sh(WarpClock.cmdDateToybox(ms));
                case 2:
                    return sh(WarpClock.cmdDateToyboxS(ms));
                case 3:
                    return sh(WarpClock.cmdDateToolbox(ms));
                case 4:
                    return sh(WarpClock.cmdAlarm(3, ms));
                case 5:
                    return sh(WarpClock.cmdAlarm(2, ms));
                default:
                    return false;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean sh(String cmd) {
        RootShell s = shell;
        return s != null && s.exec(cmd, 3000).ok;
    }
}
