package com.htcrevive;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Stands in for htc.accuweather.com/widget/htc/{forecast-data_v3,lat-lon-search}.asp, the
 * Sense 1/2 era weather API (e.g. HTC Wildfire, Android 2.2). WeatherSyncProvider's SyncService
 * parses both with one parser: the root element's {@code <current>} (temp c/f, condition icon)
 * and {@code <forecast><day>} (name MON..SUN, date mm/dd/yyyy, condition icon, high/low c/f).
 * Data comes from Open-Meteo over plain HTTP.
 */
final class OldWeatherHandler implements MiniHttp.Handler {
    private static final String FORECAST = "http://api.open-meteo.com/v1/forecast";
    private static final String[] DAYS = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};
    private static final long CACHE_MS = 10 * 60 * 1000L;

    private final Context ctx;
    private final Map<String, Object[]> cache = new HashMap<String, Object[]>();

    OldWeatherHandler(Context ctx) {
        this.ctx = ctx;
    }

    public MiniHttp.Response handle(MiniHttp.Request req) throws Exception {
        String lat, lon;
        if (req.path.endsWith("/lat-lon-search.asp")) {
            lat = req.q("lat");
            lon = req.q("lon");
        } else if (req.path.endsWith("/forecast-data_v3.asp")) {
            String code = req.q("loccode");
            Locations.Loc loc = Locations.byCode(ctx, code);
            if (loc != null) {
                lat = loc.lat;
                lon = loc.lon;
            } else {
                // AccuWeather codes end with the city name, e.g. EUR|AT|AU009|VIENNA.
                JSONObject hit = WeatherHandler.firstGeocode(code.substring(code.lastIndexOf('|') + 1));
                if (hit == null) return MiniHttp.Response.notFound();
                lat = String.valueOf(hit.getDouble("latitude"));
                lon = String.valueOf(hit.getDouble("longitude"));
            }
        } else {
            return MiniHttp.Response.notFound();
        }
        if (lat.length() == 0 || lon.length() == 0) return MiniHttp.Response.notFound();
        String url = FORECAST + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&timezone=auto&forecast_days=7";
        return MiniHttp.Response.text(200, "text/xml; charset=iso-8859-1", toXml(new JSONObject(fetchCached(url))));
    }

    private String fetchCached(String url) throws Exception {
        synchronized (cache) {
            Object[] hit = cache.get(url);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < CACHE_MS) return (String) hit[1];
        }
        String body = Net.getString(url);
        synchronized (cache) {
            cache.put(url, new Object[]{System.currentTimeMillis(), body});
        }
        return body;
    }

    static String toXml(JSONObject om) throws Exception {
        JSONObject cur = om.getJSONObject("current");
        boolean isDay = cur.optInt("is_day", 1) == 1;
        double t = cur.getDouble("temperature_2m");
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<adc_database>");
        x.append("<current>");
        temp(x, "temp", t);
        x.append("<condition icon=\"").append(WeatherHandler.icon(cur.optInt("weather_code", 0), isDay)).append("\"/>");
        x.append("</current><forecast>");

        JSONObject daily = om.getJSONObject("daily");
        JSONArray time = daily.getJSONArray("time");
        for (int i = 0; i < time.length(); i++) {
            String iso = time.getString(i);  // yyyy-mm-dd in the location's time zone
            int y = Integer.parseInt(iso.substring(0, 4));
            int m = Integer.parseInt(iso.substring(5, 7));
            int d = Integer.parseInt(iso.substring(8, 10));
            x.append("<day><name>").append(DAYS[dayOfWeek(y, m, d)]).append("</name>");
            x.append("<date>").append(String.format(Locale.US, "%02d/%02d/%04d", m, d, y)).append("</date>");
            x.append("<condition icon=\"").append(WeatherHandler.icon(daily.getJSONArray("weather_code").optInt(i, 0), true)).append("\"/>");
            temp(x, "high", daily.getJSONArray("temperature_2m_max").getDouble(i));
            temp(x, "low", daily.getJSONArray("temperature_2m_min").getDouble(i));
            x.append("</day>");
        }
        return x.append("</forecast></adc_database>").toString();
    }

    private static void temp(StringBuilder x, String tag, double c) {
        x.append('<').append(tag).append(" c=\"").append(Math.round(c))
                .append("\" f=\"").append(Math.round(c * 9 / 5 + 32)).append("\"/>");
    }

    /** 0 = Sunday (Sakamoto's method), independent of the phone's time zone. */
    static int dayOfWeek(int y, int m, int d) {
        int[] t = {0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4};
        if (m < 3) y -= 1;
        return (y + y / 4 - y / 100 + y / 400 + t[m - 1] + d) % 7;
    }
}
