package com.timewarp.ldplayer;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Lógica pura (sin dependencias de Android) del reloj acelerado.
 *
 * La hora virtual se calcula SIEMPRE a partir de un reloj monotónico (elapsedRealtime), nunca
 * leyendo la hora del sistema que nosotros mismos estamos modificando. Así no hay deriva
 * acumulada y, si algo (p. ej. el host de LDPlayer) cambia la hora, el siguiente tick la corrige.
 */
final class WarpClock {

    static final long DAY_MS = 86_400_000L;

    /** 2037-12-31 00:00:00 UTC: margen antes del desbordamiento de time_t de 32 bits (2038-01-19). */
    static final long MAX_MS = 2_145_830_400_000L;

    /** 1971-01-01 00:00:00 UTC. */
    static final long MIN_MS = 31_536_000_000L;

    private final long baseVirtualMs;
    private final long baseElapsedMs;
    private final double daysPerSecond; // con signo: negativo = hacia atrás

    WarpClock(long baseVirtualMs, long baseElapsedMs, double daysPerSecond) {
        this.baseVirtualMs = baseVirtualMs;
        this.baseElapsedMs = baseElapsedMs;
        this.daysPerSecond = daysPerSecond;
    }

    /** Hora virtual (epoch ms) para el instante monotónico dado. */
    long virtualAt(long elapsedNowMs) {
        // 1 ms real = daysPerSecond * 86400 ms virtuales
        double deltaMs = (elapsedNowMs - baseElapsedMs) * daysPerSecond * 86_400.0;
        return baseVirtualMs + Math.round(deltaMs);
    }

    static boolean outOfRange(long ms) {
        return ms >= MAX_MS || ms <= MIN_MS;
    }

    private static String fmt(long ms, String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    // ---- Comandos de shell para fijar la hora (siempre en UTC, para no depender de TZ) ----

    /** toybox (Android 7+): date -u MMDDhhmmCCYY.ss */
    static String cmdDateToybox(long ms) {
        return "date -u " + fmt(ms, "MMddHHmmyyyy.ss");
    }

    /** toybox: date -u -s "YYYY-MM-DD hh:mm:ss" */
    static String cmdDateToyboxS(long ms) {
        return "date -u -s \"" + fmt(ms, "yyyy-MM-dd HH:mm:ss") + "\"";
    }

    /** toolbox (Android 5/6): date -s YYYYMMDD.HHMMSS */
    static String cmdDateToolbox(long ms) {
        return "date -s " + fmt(ms, "yyyyMMdd.HHmmss");
    }

    /** IAlarmManager.setTime(long) por binder; el código de transacción cambia según versión. */
    static String cmdAlarm(int code, long ms) {
        return "service call alarm " + code + " i64 " + ms;
    }
}
