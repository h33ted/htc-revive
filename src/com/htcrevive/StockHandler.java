package com.htcrevive;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stands in for api.htc.go.yahoo.com/dgw, the Stocks app's XML "finance" API. The app POSTs
 * {@code <request><query type="getquotes|getsymbol|getchart">...</query></request>} and
 * com.htc.stock.service.task.StockTask reads quote / meta / point elements back by name.
 * Data comes from Yahoo Finance's public chart and search endpoints (no key; HTTPS via wolfSSL).
 */
final class StockHandler implements MiniHttp.Handler {
    private static final String CHART = "https://query1.finance.yahoo.com/v8/finance/chart/";
    private static final String SEARCH = "https://query1.finance.yahoo.com/v1/finance/search";
    private static final long QUOTE_CACHE_MS = 60 * 1000L;
    private static final long CHART_CACHE_MS = 5 * 60 * 1000L;

    /** App chart ranges -> Yahoo range/interval. */
    private static final Map<String, String[]> RANGES = new HashMap<String, String[]>();

    static {
        RANGES.put("1d", new String[]{"1d", "5m"});
        RANGES.put("5d", new String[]{"5d", "15m"});
        RANGES.put("1m", new String[]{"1mo", "1d"});
        RANGES.put("3m", new String[]{"3mo", "1d"});
        RANGES.put("6m", new String[]{"6mo", "1d"});
        RANGES.put("1y", new String[]{"1y", "1d"});
        RANGES.put("2y", new String[]{"2y", "1wk"});
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Map<String, Object[]> cache = new HashMap<String, Object[]>();

    public MiniHttp.Response handle(MiniHttp.Request req) throws Exception {
        if (!req.path.startsWith("/dgw")) return MiniHttp.Response.notFound();
        String body = new String(req.body, "UTF-8");
        String type = attr(body, "query", "type");
        String xml;
        if ("getquotes".equals(type)) {
            xml = quotes(all(body, "symbol"));
        } else if ("getsymbol".equals(type)) {
            xml = search(first(body, "phrase"));
        } else if ("getchart".equals(type)) {
            xml = chart(first(body, "symbol"), first(body, "range"));
        } else {
            return MiniHttp.Response.text(400, "text/plain", "unknown query type " + type);
        }
        return MiniHttp.Response.text(200, "text/xml; charset=utf-8", xml);
    }

    private String quotes(List<String> symbols) throws Exception {
        List<Future<String>> jobs = new ArrayList<Future<String>>();
        for (final String sym : symbols) {
            jobs.add(pool.submit(new java.util.concurrent.Callable<String>() {
                public String call() {
                    try {
                        return quoteXml(sym);
                    } catch (Exception e) {
                        // Leave it out; the app keeps its last stored values for missing symbols.
                        Log.w(MiniHttp.TAG, "quote " + sym + " failed: " + e);
                        return null;
                    }
                }
            }));
        }
        StringBuilder items = new StringBuilder();
        int n = 0;
        for (Future<String> f : jobs) {
            String q = f.get();
            if (q == null) continue;
            items.append(q);
            n++;
        }
        return result("getquotes", "<list count=\"" + n + "\">" + items + "</list>");
    }

    private String quoteXml(String sym) throws Exception {
        JSONObject r = chartResult(sym, "1d", "1d", QUOTE_CACHE_MS);
        JSONObject m = r.getJSONObject("meta");
        double price = m.getDouble("regularMarketPrice");
        double prev = m.optDouble("previousClose", m.optDouble("chartPreviousClose", price));
        double change = price - prev;
        double pct = prev == 0 ? 0 : change * 100 / prev;
        double open = firstValue(r, "open", price);
        String name = m.optString("shortName", m.optString("longName", sym));
        StringBuilder q = new StringBuilder("<quote>");
        el(q, "name", name);
        el(q, "symbol", m.optString("symbol", sym));
        el(q, "exchange", m.optString("fullExchangeName", m.optString("exchangeName", "")));
        el(q, "status", "0");
        el(q, "timestamp", String.valueOf(m.optLong("regularMarketTime", System.currentTimeMillis() / 1000)));
        el(q, "price", num(price));
        el(q, "change", num(change));
        el(q, "changepercent", num(pct));
        el(q, "open", num(open));
        el(q, "high", num(m.optDouble("regularMarketDayHigh", price)));
        el(q, "low", num(m.optDouble("regularMarketDayLow", price)));
        el(q, "volume", String.valueOf(m.optLong("regularMarketVolume", 0)));
        el(q, "link", "http://finance.yahoo.com/quote/" + URLEncoder.encode(sym, "UTF-8"));
        return q.append("</quote>").toString();
    }

    private String search(String phrase) throws Exception {
        StringBuilder items = new StringBuilder();
        int n = 0;
        if (phrase.trim().length() > 0) {
            String url = SEARCH + "?q=" + URLEncoder.encode(phrase.trim(), "UTF-8")
                    + "&quotesCount=10&newsCount=0&listsCount=0";
            JSONArray hits = new JSONObject(fetch(url, QUOTE_CACHE_MS)).optJSONArray("quotes");
            for (int i = 0; hits != null && i < hits.length(); i++) {
                JSONObject h = hits.getJSONObject(i);
                String sym = h.optString("symbol", "");
                if (sym.length() == 0) continue;
                items.append("<quote>");
                el(items, "name", h.optString("shortname", h.optString("longname", sym)));
                el(items, "symbol", sym);
                el(items, "exchange", h.optString("exchDisp", h.optString("exchange", "")));
                items.append("</quote>");
                n++;
            }
        }
        return result("getsymbol", "<list count=\"" + n + "\">" + items + "</list>");
    }

    private String chart(String sym, String range) throws Exception {
        String[] yr = RANGES.get(range);
        if (yr == null) yr = RANGES.get("1d");
        JSONObject r = chartResult(sym, yr[0], yr[1], range.equals("1d") ? QUOTE_CACHE_MS : CHART_CACHE_MS);
        JSONObject m = r.getJSONObject("meta");
        JSONObject regular = m.optJSONObject("currentTradingPeriod") == null ? null
                : m.getJSONObject("currentTradingPeriod").optJSONObject("regular");
        JSONArray ts = r.optJSONArray("timestamp");
        JSONObject ind = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0);
        JSONArray close = ind.optJSONArray("close"), open = ind.optJSONArray("open"),
                high = ind.optJSONArray("high"), low = ind.optJSONArray("low"), vol = ind.optJSONArray("volume");

        StringBuilder pts = new StringBuilder();
        int n = 0;
        for (int i = 0; ts != null && close != null && i < ts.length(); i++) {
            if (close.isNull(i)) continue;  // Yahoo leaves gaps for empty intervals
            double c = close.getDouble(i);
            pts.append("<point timestamp=\"").append(ts.getLong(i))
                    .append("\" close=\"").append(num(c))
                    .append("\" open=\"").append(num(at(open, i, c)))
                    .append("\" high=\"").append(num(at(high, i, c)))
                    .append("\" low=\"").append(num(at(low, i, c)))
                    .append("\" volume=\"").append(vol == null || vol.isNull(i) ? 0 : vol.getLong(i))
                    .append("\"/>");
            n++;
        }
        StringBuilder meta = new StringBuilder("<meta>");
        el(meta, "symbol", m.optString("symbol", sym));
        el(meta, "marketopen", String.valueOf(regular == null ? 0 : regular.optLong("start")));
        el(meta, "marketclose", String.valueOf(regular == null ? 0 : regular.optLong("end")));
        el(meta, "gmtoffset", String.valueOf(m.optLong("gmtoffset", 0)));
        meta.append("</meta>");
        return result("getchart", meta + "<list count=\"" + n + "\">" + pts + "</list>");
    }

    private JSONObject chartResult(String sym, String range, String interval, long maxAge) throws Exception {
        String url = CHART + URLEncoder.encode(sym, "UTF-8") + "?range=" + range + "&interval=" + interval
                + "&includePrePost=false";
        JSONObject chart = new JSONObject(fetch(url, maxAge)).getJSONObject("chart");
        JSONArray res = chart.optJSONArray("result");
        if (res == null || res.length() == 0) throw new java.io.IOException("no data for " + sym);
        return res.getJSONObject(0);
    }

    private String fetch(String url, long maxAge) throws Exception {
        synchronized (cache) {
            Object[] hit = cache.get(url);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < maxAge) return (String) hit[1];
        }
        String body = Net.getString(url);
        synchronized (cache) {
            cache.put(url, new Object[]{System.currentTimeMillis(), body});
        }
        return body;
    }

    private static double firstValue(JSONObject r, String field, double fallback) {
        try {
            JSONArray a = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).optJSONArray(field);
            return at(a, 0, fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double at(JSONArray a, int i, double fallback) {
        return a == null || i >= a.length() || a.isNull(i) ? fallback : a.optDouble(i, fallback);
    }

    private static String result(String type, String inner) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<response><result type=\"" + type
                + "\" timestamp=\"" + System.currentTimeMillis() / 1000 + "\">" + inner + "</result></response>";
    }

    private static void el(StringBuilder sb, String name, String value) {
        sb.append('<').append(name).append('>').append(esc(value)).append("</").append(name).append('>');
    }

    private static String num(double d) {
        return String.format(java.util.Locale.US, "%.4f", d);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // The app splices symbols and search phrases into its XML unescaped (an "&" in a search
    // would make it malformed), so read the request with forgiving regexes instead of a parser.

    private static String attr(String xml, String tag, String name) {
        Matcher m = Pattern.compile("<" + tag + "\\b[^>]*\\b" + name + "=\"([^\"]*)\"").matcher(xml);
        return m.find() ? m.group(1) : "";
    }

    private static List<String> all(String xml, String tag) {
        List<String> out = new ArrayList<String>();
        Matcher m = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            String v = unesc(m.group(1)).trim();
            if (v.length() > 0 && !out.contains(v)) out.add(v);
        }
        return out;
    }

    private static String first(String xml, String tag) {
        List<String> v = all(xml, tag);
        return v.isEmpty() ? "" : v.get(0);
    }

    private static String unesc(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }
}
