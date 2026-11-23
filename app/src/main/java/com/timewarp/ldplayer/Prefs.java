package com.timewarp.ldplayer;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/** Ajustes compartidos entre la pantalla principal y la ventana flotante. */
final class Prefs {

    private static final String NAME = "ui";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static double secondsPerDay(Context c) {
        double s = Double.longBitsToDouble(sp(c).getLong("spd", Double.doubleToLongBits(10.0)));
        return (s >= 0.01 && s <= 10_000_000d) ? s : 10.0;
    }

    static boolean reverse(Context c) {
        return sp(c).getBoolean("reverse", false);
    }

    static boolean loop(Context c) {
        return sp(c).getBoolean("loop", false);
    }

    static boolean restore(Context c) {
        return sp(c).getBoolean("restore", false);
    }

    static boolean unlimited(Context c) {
        return sp(c).getBoolean("unlimited", false);
    }

    static void save(Context c, double spd, boolean reverse, boolean loop, boolean restore,
                     boolean unlimited) {
        sp(c).edit()
                .putLong("spd", Double.doubleToLongBits(spd))
                .putBoolean("reverse", reverse)
                .putBoolean("loop", loop)
                .putBoolean("restore", restore)
                .putBoolean("unlimited", unlimited)
                .apply();
    }

    static int floatX(Context c, int def) {
        return sp(c).getInt("fx", def);
    }

    static int floatY(Context c, int def) {
        return sp(c).getInt("fy", def);
    }

    static void saveFloatPos(Context c, int x, int y) {
        sp(c).edit().putInt("fx", x).putInt("fy", y).apply();
    }

    /** 10.0 -> "10", 2.5 -> "2.5" */
    static String num(double s) {
        return (s == Math.rint(s)) ? String.valueOf((long) s) : String.valueOf(Math.round(s * 100) / 100.0);
    }

    static String label(Context c) {
        return "1 día cada " + num(secondsPerDay(c)) + " s" + (reverse(c) ? " (atrás)" : "");
    }

    /** Arranca TimeWarp con los ajustes guardados. */
    static void start(Context c) {
        Intent i = new Intent(c, TimeWarpService.class)
                .setAction(TimeWarpService.ACTION_START)
                .putExtra(TimeWarpService.EXTRA_DAYS_PER_SEC, 1.0 / secondsPerDay(c))
                .putExtra(TimeWarpService.EXTRA_REVERSE, reverse(c))
                .putExtra(TimeWarpService.EXTRA_LOOP, loop(c))
                .putExtra(TimeWarpService.EXTRA_UNLIMITED, unlimited(c));
        if (Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    /** Detiene TimeWarp; devuelve la hora real solo si el ajuste "restore" está activo. */
    static void stop(Context c) {
        c.startService(new Intent(c, TimeWarpService.class)
                .setAction(TimeWarpService.ACTION_STOP)
                .putExtra(TimeWarpService.EXTRA_RESTORE, restore(c)));
    }
}
