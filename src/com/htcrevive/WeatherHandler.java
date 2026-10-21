package com.htcrevive;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Stands in for htc2.accu-weather.com/widget/htc2/{weather-data,city-find}.asp.
 * The XML mirrors what com.htc.sync.provider.weather.XmlParser and the Weather app's
 * OnlineSearchHandler read; data comes from Open-Meteo (plain HTTP, no key).
 */
final class WeatherHandler implements MiniHttp.Handler {
    private static final String FORECAST = "http://api.open-meteo.com/v1/forecast";
    private static final String GEOCODE = "http://geocoding-api.open-meteo.com/v1/search";
    /** Prefix for cities found through our own online search: OM|lat|lon|name. */
    private static final String OM = "OM|";

    private final Context ctx;

    WeatherHandler(Context ctx) {
        this.ctx = ctx;
    }

    public MiniHttp.Response handle(MiniHttp.Request req) throws Exception {
        if (req.path.endsWith("/weather-data.asp")) return weather(req);
        if (req.path.endsWith("/city-find.asp")) return cityFind(req);
        return MiniHttp.Response.notFound();
    }

    private MiniHttp.Response weather(MiniHttp.Request req) throws Exception {
        String lat = req.q("slat"), lon = req.q("slon"), name = "";
        String location = req.q("location");
        if (location.startsWith("cityId:")) location = location.substring(7);
        if (lat.length() == 0 && location.length() > 0) {
            if (location.startsWith(OM)) {
                String[] f = location.split("\\|", 4);
                lat = f[1];
                lon = f[2];
                name = f.length > 3 ? f[3] : "";
            } else {
                Locations.Loc loc = Locations.byCode(ctx, location);
                if (loc != null) {
                    lat = loc.lat;
                    lon = loc.lon;
                } else {
                    // Unknown code: AccuWeather codes end with the city name, e.g. EUR|AT|AU009|WIEN.
                    String city = location.substring(location.lastIndexOf('|') + 1);
                    JSONObject hit = firstGeocode(city);
                    if (hit == null) return MiniHttp.Response.notFound();
                    lat = String.valueOf(hit.getDouble("latitude"));
                    lon = String.valueOf(hit.getDouble("longitude"));
                }
            }
        }
        if (lat.length() == 0 || lon.length() == 0) return MiniHttp.Response.notFound();
        String url = FORECAST + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m,wind_direction_10m,visibility"
                + "&hourly=temperature_2m,apparent_temperature,precipitation_probability,weather_code"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max,apparent_temperature_min,sunrise,sunset,precipitation_sum"
                + "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch&timezone=auto&forecast_days=7";
        JSONObject om = new JSONObject(fetchCached(url));
        return MiniHttp.Response.text(200, "text/xml; charset=utf-8", toAccuXml(om, lat, lon, name));
    }

    private static final long CACHE_MS = 10 * 60 * 1000L;
    private final java.util.Map<String, Object[]> cache = new java.util.HashMap<String, Object[]>();

    /** Syncs fire one request per saved city in a burst; Open-Meteo answers bursts with 503. */
    private String fetchCached(String url) throws Exception {
        synchronized (cache) {
            Object[] hit = cache.get(url);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < CACHE_MS) return (String) hit[1];
        }
        String body;
        try {
            body = Net.getString(url);
        } catch (java.io.IOException e) {
            Thread.sleep(1500);
            body = Net.getString(url);
        }
        synchronized (cache) {
            cache.put(url, new Object[]{System.currentTimeMillis(), body});
        }
        return body;
    }

    static String toAccuXml(JSONObject om, String lat, String lon, String name) throws Exception {
        JSONObject cur = om.getJSONObject("current");
        JSONObject hourly = om.getJSONObject("hourly");
        JSONObject daily = om.getJSONObject("daily");
        int offsetSec = om.optInt("utc_offset_seconds", 0);
        String nowLocal = cur.getString("time"); // yyyy-MM-ddTHH:mm in the city's zone
        boolean isDay = cur.optInt("is_day", 1) == 1;

        StringBuilder x = new StringBuilder(8192);
        x.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<adc_database>");
        x.append("<local>");
        tag(x, "city", name);
        tag(x, "lat", lat);
        tag(x, "lon", lon);
        tag(x, "time", nowLocal.substring(11, 16));
        String gmt = gmtOffset(offsetSec);
        tag(x, "timeZone", gmt);
        tag(x, "currentGmtOffset", gmt);
        x.append("</local>");

        x.append("<currentconditions daylight=\"").append(isDay ? "True" : "False").append("\">");
        tag(x, "url", "https://open-meteo.com/");
        tag(x, "temperature", round(cur.getDouble("temperature_2m")));
        tag(x, "realfeel", round(cur.getDouble("apparent_temperature")));
        tag(x, "humidity", round(cur.optDouble("relative_humidity_2m", 0)) + "%");
        tag(x, "weathericon", icon(cur.optInt("weather_code", 0), isDay));
        tag(x, "windspeed", round(cur.optDouble("wind_speed_10m", 0)));
        tag(x, "winddirection", compass(cur.optDouble("wind_direction_10m", 0)));
        // Open-Meteo visibility is always meters; AccuWeather sent miles.
        tag(x, "visibility", round(Math.min(cur.optDouble("visibility", 16093) / 1609.344, 10)));
        x.append("</currentconditions>");

        x.append("<forecast>");
        JSONArray days = daily.getJSONArray("time");
        JSONArray hTime = hourly.getJSONArray("time");
        for (int d = 0; d < days.length(); d++) {
            String date = days.getString(d);
            x.append("<day number=\"").append(d + 1).append("\">");
            tag(x, "obsdate", usDate(date));
            tag(x, "daycode", dayName(date));
            tag(x, "sunrise", clock12(daily.getJSONArray("sunrise").getString(d)));
            tag(x, "sunset", clock12(daily.getJSONArray("sunset").getString(d)));
            String precip = String.format(Locale.US, "%.2f", daily.getJSONArray("precipitation_sum").optDouble(d, 0));
            Span day = span(hourly, hTime, date + "T06:00", 12);
            Span night = span(hourly, hTime, date + "T18:00", 12);
            x.append("<daytime>");
            tag(x, "weathericon", icon(daily.getJSONArray("weather_code").optInt(d, day.code), true));
            tag(x, "hightemperature", round(daily.getJSONArray("temperature_2m_max").getDouble(d)));
            tag(x, "lowtemperature", round(daily.getJSONArray("temperature_2m_min").getDouble(d)));
            tag(x, "realfeelhigh", round(daily.getJSONArray("apparent_temperature_max").getDouble(d)));
            tag(x, "realfeellow", round(daily.getJSONArray("apparent_temperature_min").getDouble(d)));
            tag(x, "precipamount", precip);
            x.append("</daytime><nighttime>");
            tag(x, "weathericon", icon(night.n > 0 ? night.code : day.code, false));
            tag(x, "hightemperature", round(night.n > 0 ? night.hi : daily.getJSONArray("temperature_2m_max").getDouble(d)));
            tag(x, "lowtemperature", round(night.n > 0 ? night.lo : daily.getJSONArray("temperature_2m_min").getDouble(d)));
            tag(x, "realfeelhigh", round(night.n > 0 ? night.feelHi : daily.getJSONArray("apparent_temperature_max").getDouble(d)));
            tag(x, "realfeellow", round(night.n > 0 ? night.feelLo : daily.getJSONArray("apparent_temperature_min").getDouble(d)));
            tag(x, "precipamount", precip);
            x.append("</nighttime></day>");
        }

        x.append("<hourly>");
        int start = indexOfHour(hTime, nowLocal.substring(0, 13) + ":00");
        if (start < 0) start = 0;
        for (int i = start; i < Math.min(start + 24, hTime.length()); i++) {
            String t = hTime.getString(i);
            int hour = Integer.parseInt(t.substring(11, 13));
            String label = (hour % 12 == 0 ? 12 : hour % 12) + (hour < 12 ? " AM" : " PM");
            boolean day = hour >= 6 && hour < 18;
            x.append("<hour time=\"").append(label).append("\">");
            tag(x, "weathericon", icon(hourly.getJSONArray("weather_code").optInt(i, 0), day));
            tag(x, "temperature", round(hourly.getJSONArray("temperature_2m").getDouble(i)));
            tag(x, "realfeel", round(hourly.getJSONArray("apparent_temperature").getDouble(i)));
            // The Weather app multiplies this by 100 to get a percentage.
            tag(x, "precip", String.format(Locale.US, "%.2f", hourly.getJSONArray("precipitation_probability").optDouble(i, 0) / 100.0));
            x.append("</hour>");
        }
        x.append("</hourly></forecast></adc_database>");
        return x.toString();
    }

    private static final class Span {
        int n, code;
        double hi = -999, lo = 999, feelHi = -999, feelLo = 999;
    }

    /** Aggregates `hours` hourly samples starting at `from` (local ISO time). */
    private static Span span(JSONObject hourly, JSONArray hTime, String from, int hours) throws Exception {
        Span s = new Span();
        int i0 = indexOfHour(hTime, from);
        if (i0 < 0) return s;
        int worst = -1;
        for (int i = i0; i < Math.min(i0 + hours, hTime.length()); i++) {
            double t = hourly.getJSONArray("temperature_2m").getDouble(i);
            double f = hourly.getJSONArray("apparent_temperature").getDouble(i);
            s.hi = Math.max(s.hi, t);
            s.lo = Math.min(s.lo, t);
            s.feelHi = Math.max(s.feelHi, f);
            s.feelLo = Math.min(s.feelLo, f);
            worst = Math.max(worst, hourly.getJSONArray("weather_code").optInt(i, 0));
            s.n++;
        }
        s.code = Math.max(worst, 0);
        return s;
    }

    private static int indexOfHour(JSONArray times, String t) throws Exception {
        for (int i = 0; i < times.length(); i++) if (times.getString(i).equals(t)) return i;
        return -1;
    }

    private MiniHttp.Response cityFind(MiniHttp.Request req) throws Exception {
        String keyword = req.q("location");
        JSONArray results = geocode(keyword, 10);
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        x.append("<adc_database><citylist us=\"0\" intl=\"").append(results.length()).append("\">");
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String name = r.optString("name");
            String code = OM + r.getDouble("latitude") + "|" + r.getDouble("longitude") + "|" + name;
            x.append("<location city=\"").append(esc(name))
                    .append("\" adminArea=\"").append(esc(r.optString("admin1")))
                    .append("\" country=\"").append(esc(r.optString("country")))
                    .append("\" location=\"").append(esc(code)).append("\"/>");
        }
        x.append("</citylist></adc_database>");
        return MiniHttp.Response.text(200, "text/xml; charset=utf-8", x.toString());
    }

    private static JSONArray geocode(String name, int count) throws Exception {
        String lang = Locale.getDefault().getLanguage();
        JSONObject o = new JSONObject(Net.getString(GEOCODE + "?name=" + URLEncoder.encode(name, "UTF-8")
                + "&count=" + count + "&language=" + (lang.length() == 0 ? "en" : lang)));
        JSONArray r = o.optJSONArray("results");
        return r == null ? new JSONArray() : r;
    }

    private static JSONObject firstGeocode(String name) throws Exception {
        JSONArray r = geocode(name, 1);
        if (r.length() == 0) {
            Log.w(MiniHttp.TAG, "geocode found nothing for " + name);
            return null;
        }
        return r.getJSONObject(0);
    }

    /** WMO weather code to AccuWeather icon number (1-44), which HTC's icon sets are keyed on. */
    static String icon(int wmo, boolean day) {
        int a;
        switch (wmo) {
            case 0: a = day ? 1 : 33; break;
            case 1: a = day ? 2 : 34; break;
            case 2: a = day ? 3 : 35; break;
            case 3: a = 7; break;
            case 45: case 48: a = 11; break;
            case 51: case 53: case 55: a = day ? 14 : 39; break;
            case 56: case 57: case 66: case 67: a = 26; break;
            case 61: a = day ? 14 : 39; break;
            case 63: a = 12; break;
            case 65: a = 18; break;
            case 71: case 77: a = day ? 19 : 43; break;
            case 73: case 75: a = 22; break;
            case 80: a = day ? 14 : 39; break;
            case 81: a = day ? 13 : 40; break;
            case 82: a = 18; break;
            case 85: a = day ? 21 : 43; break;
            case 86: a = day ? 23 : 44; break;
            case 95: a = day ? 16 : 42; break;
            case 96: case 99: a = 15; break;
            default: a = day ? 6 : 38; break;
        }
        return String.format(Locale.US, "%02d", a);
    }

    private static String compass(double deg) {
        String[] p = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        return p[(int) Math.round(((deg % 360) + 360) % 360 / 22.5) % 16];
    }

    private static String gmtOffset(int sec) {
        // XmlParser turns this into "GMT+<n>:00", so whole hours only.
        return String.valueOf(Math.round(sec / 3600f));
    }

    private static String usDate(String iso) throws Exception {
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        in.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat out = new SimpleDateFormat("M/d/yyyy", Locale.US);
        out.setTimeZone(TimeZone.getTimeZone("UTC"));
        return out.format(in.parse(iso));
    }

    private static String dayName(String iso) throws Exception {
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        in.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat out = new SimpleDateFormat("EEEE", Locale.US);
        out.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date dt = in.parse(iso);
        return out.format(dt);
    }

    /** "2026-10-21T07:15" -> "7:15 AM", the format WSPPData.convertHourMinute expects. */
    private static String clock12(String iso) {
        int h = Integer.parseInt(iso.substring(11, 13));
        String m = iso.substring(14, 16);
        return (h % 12 == 0 ? 12 : h % 12) + ":" + m + (h < 12 ? " AM" : " PM");
    }

    private static String round(double v) {
        return String.valueOf(Math.round(v));
    }

    private static void tag(StringBuilder x, String name, String value) {
        x.append('<').append(name).append('>').append(esc(value)).append("</").append(name).append('>');
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
