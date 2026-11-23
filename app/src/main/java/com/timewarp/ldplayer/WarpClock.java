package com.timewarp.ldplayer;

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

    /** 2038-01-19 03:14:07 UTC: último instante que cabe en un time_t de 32 bits con signo. */
    static final long INT32_MS = 2_147_483_647_000L;

    /** 9999-12-31 00:00:00 UTC: tope cuando se quita el límite (el año sigue teniendo 4 cifras). */
    static final long MAX_UNLIMITED_MS = 253_402_214_400_000L;

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
        return outOfRange(ms, false);
    }

    static boolean outOfRange(long ms, boolean unlimited) {
        return ms >= (unlimited ? MAX_UNLIMITED_MS : MAX_MS) || ms <= MIN_MS;
    }

    /**
     * {año, mes, día, hora, minuto, segundo} en UTC, sin SimpleDateFormat ni TimeZone (esto se
     * ejecuta cada segundo, y esas clases asignan bastante memoria). Algoritmo "civil_from_days"
     * de Howard Hinnant. Válido para fechas posteriores a 1970, que es todo nuestro rango.
     */
    static int[] utcFields(long ms) {
        long secs = ms / 1000L;
        long days = secs / 86_400L;
        int sod = (int) (secs - days * 86_400L);

        long z = days + 719_468L;
        long era = z / 146_097L;
        long doe = z - era * 146_097L;
        long yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L;
        long y = yoe + era * 400L;
        long doy = doe - (365L * yoe + yoe / 4L - yoe / 100L);
        long mp = (5L * doy + 2L) / 153L;
        long d = doy - (153L * mp + 2L) / 5L + 1L;
        long m = mp < 10 ? mp + 3 : mp - 9;
        if (m <= 2) {
            y++;
        }
        return new int[]{(int) y, (int) m, (int) d, sod / 3600, (sod % 3600) / 60, sod % 60};
    }

    private static void p2(StringBuilder sb, int v) {
        if (v < 10) {
            sb.append('0');
        }
        sb.append(v);
    }

    // ---- Comandos de shell para fijar la hora (siempre en UTC, para no depender de TZ) ----

    /** toybox (Android 7+): date -u MMDDhhmmCCYY.ss */
    static String cmdDateToybox(long ms) {
        int[] f = utcFields(ms);
        StringBuilder sb = new StringBuilder(24).append("date -u ");
        p2(sb, f[1]);
        p2(sb, f[2]);
        p2(sb, f[3]);
        p2(sb, f[4]);
        sb.append(f[0]).append('.');
        p2(sb, f[5]);
        return sb.toString();
    }

    /** toybox: date -u -s "YYYY-MM-DD hh:mm:ss" */
    static String cmdDateToyboxS(long ms) {
        int[] f = utcFields(ms);
        StringBuilder sb = new StringBuilder(36).append("date -u -s \"").append(f[0]).append('-');
        p2(sb, f[1]);
        sb.append('-');
        p2(sb, f[2]);
        sb.append(' ');
        p2(sb, f[3]);
        sb.append(':');
        p2(sb, f[4]);
        sb.append(':');
        p2(sb, f[5]);
        return sb.append('"').toString();
    }

    /** toolbox (Android 5/6): date -s YYYYMMDD.HHMMSS */
    static String cmdDateToolbox(long ms) {
        int[] f = utcFields(ms);
        StringBuilder sb = new StringBuilder(24).append("date -s ").append(f[0]);
        p2(sb, f[1]);
        p2(sb, f[2]);
        sb.append('.');
        p2(sb, f[3]);
        p2(sb, f[4]);
        p2(sb, f[5]);
        return sb.toString();
    }

    /** IAlarmManager.setTime(long) por binder; el código de transacción cambia según versión. */
    static String cmdAlarm(int code, long ms) {
        return "service call alarm " + code + " i64 " + ms;
    }
}
