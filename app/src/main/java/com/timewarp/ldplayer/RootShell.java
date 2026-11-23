package com.timewarp.ldplayer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Shell "su" persistente. Lanzar un proceso su nuevo cada segundo sería pesado y ruidoso, así que
 * se mantiene uno abierto y cada orden se sincroniza con un token de fin.
 *
 * Los plazos usan System.nanoTime() (monotónico): la hora del sistema es justo lo que cambiamos.
 */
final class RootShell {

    static final class Result {
        final boolean ok;
        final int code;
        final String output;

        Result(boolean ok, int code, String output) {
            this.ok = ok;
            this.code = code;
            this.output = output;
        }
    }

    private static final String EOF = "\u0000EOF";

    private final Process process;
    private final OutputStream stdin;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<String>();
    private int counter = 0;

    private RootShell(final Process p) {
        this.process = p;
        this.stdin = p.getOutputStream();
        Thread reader = new Thread(() -> {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    lines.offer(line);
                }
            } catch (IOException ignored) {
                // el proceso terminó
            } finally {
                lines.offer(EOF);
            }
        }, "RootShell-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** Abre una shell root y comprueba que realmente es uid 0. Devuelve null si no hay root. */
    static RootShell open() {
        try {
            ProcessBuilder pb = new ProcessBuilder("su");
            pb.redirectErrorStream(true);
            RootShell sh = new RootShell(pb.start());
            Result r = sh.exec("id -u", 5000);
            if (r.ok) {
                for (String l : r.output.split("\n")) {
                    if (l.trim().equals("0")) {
                        return sh;
                    }
                }
            }
            sh.close();
        } catch (Exception ignored) {
            // su no existe o fue denegado
        }
        return null;
    }

    synchronized Result exec(String cmd, long timeoutMs) {
        final String token = "__TW" + (++counter) + "__";
        try {
            stdin.write((cmd + "\necho " + token + "$?\n").getBytes("UTF-8"));
            stdin.flush();
        } catch (IOException e) {
            return new Result(false, -1, "");
        }

        StringBuilder out = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return new Result(false, -2, out.toString());
                }
                String line = lines.poll(left, TimeUnit.NANOSECONDS);
                if (line == null) {
                    return new Result(false, -2, out.toString());
                }
                if (line.equals(EOF)) {
                    return new Result(false, -3, out.toString());
                }
                if (line.startsWith(token)) {
                    int code;
                    try {
                        code = Integer.parseInt(line.substring(token.length()).trim());
                    } catch (NumberFormatException e) {
                        code = -4;
                    }
                    return new Result(code == 0, code, out.toString());
                }
                if (line.startsWith("__TW")) {
                    continue; // token de una orden anterior que expiró
                }
                out.append(line).append('\n');
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, -5, out.toString());
        }
    }

    void close() {
        try {
            stdin.write("exit\n".getBytes("UTF-8"));
            stdin.flush();
        } catch (IOException ignored) {
            // ya cerrado
        }
        process.destroy();
    }
}
