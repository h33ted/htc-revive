package com.htcrevive;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/** Last few requests served, shown on the status screen. */
final class RequestLog {
    private static final LinkedList<String> LINES = new LinkedList<String>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private RequestLog() {}

    static synchronized void add(String line) {
        LINES.addFirst(FMT.format(new Date()) + "  " + line);
        while (LINES.size() > 40) LINES.removeLast();
    }

    static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) sb.append(l).append('\n');
        return sb.toString();
    }
}
