package com.htcrevive;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/** AccuWeather location codes from WeatherProvider's bundled WPDB, mapped to coordinates. */
final class Locations {
    static final class Loc {
        final String lat, lon, tz;

        Loc(String lat, String lon, String tz) {
            this.lat = lat;
            this.lon = lon;
            this.tz = tz;
        }
    }

    private static Map<String, Loc> table;

    private Locations() {}

    static synchronized Loc byCode(Context ctx, String code) {
        if (table == null) {
            table = new HashMap<String, Loc>();
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("locations.tsv"), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    String[] f = line.split("\t", -1);
                    if (f.length >= 4) table.put(f[0], new Loc(f[1], f[2], f[3]));
                }
                r.close();
            } catch (IOException e) {
                throw new RuntimeException("locations.tsv missing", e);
            }
        }
        return table.get(code);
    }
}
