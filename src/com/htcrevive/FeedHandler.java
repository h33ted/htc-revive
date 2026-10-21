package com.htcrevive;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stands in for BlinkFeed's Prism v2 servers: geo-prism.htcsense.com (JSON API, reached over
 * HTTPS) and img-prism.htcsense.com (images). Paths and JSON keys come from NewsPlugin's
 * com.htc.prism.feed.corridor.{Constants,ServerCorridor,FeedItem,Category,...}. Content is
 * built from ordinary RSS/Atom feeds, one BlinkFeed topic per feed.
 */
final class FeedHandler {
    static final String DEFAULT_FEEDS =
            "BBC World|https://feeds.bbci.co.uk/news/world/rss.xml\n"
            + "BBC Technology|https://feeds.bbci.co.uk/news/technology/rss.xml\n"
            + "ORF News|https://rss.orf.at/news.xml\n"
            + "Ars Technica|https://feeds.arstechnica.com/arstechnica/index\n"
            + "The Verge|https://www.theverge.com/rss/index.xml\n"
            + "NASA|https://www.nasa.gov/news-release/feed/";

    private static final int EDITION_ID = 1;
    private static final long FEED_TTL_MS = 15 * 60 * 1000L;
    // Tiers the device can request; the big highlight tiles are full screen width, so cap high.
    private static final String[] THUMB_WIDTHS = {"360", "720", "1080"};

    private static final class Topic {
        int cid;
        String name, url;
        long fetchedAt;
        List<Item> items = new ArrayList<Item>();
    }

    private static final class Item {
        String id, title, link, summary, image;
        long ts;
        Topic topic;
    }

    private final Context ctx;
    private final Map<String, Item> itemsById = new HashMap<String, Item>();
    private List<Topic> topics;
    private String topicsSource;

    FeedHandler(Context ctx) {
        this.ctx = ctx;
    }

    // ---- geo-prism: JSON API -------------------------------------------------------------

    final MiniHttp.Handler api = new MiniHttp.Handler() {
        public MiniHttp.Response handle(MiniHttp.Request req) throws Exception {
            String p = req.path;
            // Tolerate a version prefix in case a DM-provided base URI ever adds one.
            if (p.startsWith("/v2/")) p = p.substring(3);
            JSONObject res;
            if (p.equals("/edition/default")) {
                res = new JSONObject().put("edition", edition());
            } else if (p.equals("/edition/list")) {
                res = new JSONObject().put("editions", new JSONArray().put(edition()));
            } else if (p.equals("/category/list") || p.equals("/category/super/list")) {
                res = new JSONObject().put("c", categories());
            } else if (p.equals("/category/feature/list")) {
                JSONObject group = new JSONObject().put("gid", 1).put("n", "News").put("p", 1).put("c", categories());
                res = new JSONObject().put("g", new JSONArray().put(group));
            } else if (p.startsWith("/fi/")) {
                res = feedItems(p, req);
            } else if (p.startsWith("/feedItem/item/")) {
                Item it = itemsById.get(java.net.URLDecoder.decode(p.substring("/feedItem/item/".length()), "UTF-8"));
                if (it == null) return MiniHttp.Response.notFound();
                JSONObject item = new JSONObject().put("meta", meta(it)).put("body", body(it));
                res = new JSONObject().put("item", item);
            } else if (p.equals("/thumbrules")) {
                res = new JSONObject()
                        .put("h", new JSONArray().put("1080"))
                        .put("m", new JSONArray().put("720"))
                        .put("l", new JSONArray().put("360"));
            } else if (p.equals("/legaldoc")) {
                return MiniHttp.Response.text(200, "text/html; charset=utf-8",
                        "<html><body><p>BlinkFeed content is served locally by HTC Revive from public RSS feeds.</p></body></html>");
            } else {
                Log.w(MiniHttp.TAG, "unhandled Prism API path " + req.path);
                return MiniHttp.Response.notFound();
            }
            return json(new JSONObject().put("res", res));
        }
    };

    private JSONObject feedItems(String p, MiniHttp.Request req) throws Exception {
        // /fi/hl/{i,r,bg}/<n>  (highlights)   /fi/sc/{r,l}/<n>  (single topic refresh / load more)
        String[] seg = p.split("/");
        int amount = seg.length > 4 ? parseInt(seg[4], 50) : 50;
        List<Item> pool = new ArrayList<Item>();
        for (String cid : req.q("cids").split(",")) {
            Topic t = topic(parseInt(cid, -1));
            if (t != null) pool.addAll(load(t));
        }
        Collections.sort(pool, new Comparator<Item>() {
            public int compare(Item a, Item b) {
                return a.ts < b.ts ? 1 : (a.ts > b.ts ? -1 : 0);
            }
        });
        long olderThan = "l".equals(seg.length > 3 ? seg[3] : "") ? parseLong(req.q("st"), Long.MAX_VALUE) : Long.MAX_VALUE;
        JSONArray items = new JSONArray();
        for (Item it : pool) {
            if (it.ts >= olderThan) continue;
            items.put(new JSONObject().put("meta", meta(it)));
            if (items.length() >= amount) break;
        }
        return new JSONObject().put("items", items);
    }

    private JSONObject edition() throws Exception {
        return new JSONObject().put("eid", EDITION_ID).put("n", "HTC Revive").put("p", 1);
    }

    private JSONArray categories() throws Exception {
        JSONArray arr = new JSONArray();
        for (Topic t : topics()) {
            // "sc" must be present (even empty): NewsDbHelper.insertOrUpdateTagList calls
            // getSubCategoryList().size() with no null check and crashes otherwise.
            arr.put(new JSONObject().put("cid", t.cid).put("n", t.name).put("ttl", 900).put("p", t.cid)
                    .put("desc", t.name).put("k", "").put("th", t.cid).put("tp", 0)
                    .put("sc", new JSONArray()));
        }
        return arr;
    }

    private JSONObject meta(Item it) throws Exception {
        JSONObject m = new JSONObject()
                .put("id", it.id)
                .put("u", it.link)
                .put("src", it.topic.name)
                .put("tl", it.title)
                .put("ts", it.ts)
                .put("tp", 3)
                .put("tids", new JSONArray().put(it.topic.cid))
                .put("pid", it.topic.cid);
        if (it.image != null) {
            JSONArray rs = new JSONArray();
            for (String w : THUMB_WIDTHS) rs.put(w);
            m.put("th", new JSONObject().put("id", it.id).put("rs", rs));
        }
        return m;
    }

    private static String body(Item it) {
        StringBuilder b = new StringBuilder("<div>");
        if (it.summary != null) b.append(it.summary);
        b.append("<p><a href=\"").append(WeatherHandler.esc(it.link)).append("\">Read the full article</a></p></div>");
        return b.toString();
    }

    // ---- img-prism: thumbnails, images, icons ---------------------------------------------

    final MiniHttp.Handler images = new MiniHttp.Handler() {
        public MiniHttp.Response handle(MiniHttp.Request req) throws Exception {
            if (req.path.equals("/thumbnail") || req.path.equals("/image")) {
                Item it = itemsById.get(req.q("id"));
                if (it == null || it.image == null) return MiniHttp.Response.notFound();
                byte[] raw = Net.get(it.image);
                int w = parseInt(req.q("w"), 0);
                return new MiniHttp.Response(200, "image/jpeg", w > 0 ? scale(raw, w) : raw);
            }
            if (req.path.equals("/provider/icon") || req.path.equals("/categorythumb")) {
                Topic t = topic(parseInt(req.q("id"), -1));
                return new MiniHttp.Response(200, "image/png", badge(t == null ? "?" : t.name));
            }
            return MiniHttp.Response.notFound();
        }
    };

    private static byte[] scale(byte[] raw, int width) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        if (o.outWidth <= 0) return raw;
        // Source already no wider than requested: serve it untouched (no upscaling, no re-encode).
        if (o.outWidth <= width) return raw;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= width) o.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        if (b == null) return raw;
        if (b.getWidth() > width) {
            Bitmap s = Bitmap.createScaledBitmap(b, width, Math.max(1, b.getHeight() * width / b.getWidth()), true);
            b.recycle();
            b = s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 92, out);
        b.recycle();
        return out.toByteArray();
    }

    /** Provider icons: a colored square with the source's initial. */
    private static byte[] badge(String name) {
        Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.HSVToColor(new float[]{(name.hashCode() & 0x7fffffff) % 360, 0.55f, 0.75f}));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextSize(60);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(name.substring(0, 1).toUpperCase(Locale.US), 48, 68, p);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        b.recycle();
        return out.toByteArray();
    }

    // ---- topics and feed loading -------------------------------------------------------------

    private synchronized List<Topic> topics() {
        String src = Prefs.feeds(ctx);
        if (topics == null || !src.equals(topicsSource)) {
            topics = new ArrayList<Topic>();
            int cid = 101;
            for (String line : src.split("\n")) {
                int bar = line.indexOf('|');
                if (bar <= 0) continue;
                Topic t = new Topic();
                t.cid = cid++;
                t.name = line.substring(0, bar).trim();
                t.url = line.substring(bar + 1).trim();
                topics.add(t);
            }
            topicsSource = src;
        }
        return topics;
    }

    private Topic topic(int cid) {
        for (Topic t : topics()) if (t.cid == cid) return t;
        return null;
    }

    private List<Item> load(Topic t) {
        synchronized (t) {
            if (System.currentTimeMillis() - t.fetchedAt < FEED_TTL_MS) return t.items;
            try {
                List<Item> fresh = parseFeed(Net.get(t.url), t);
                synchronized (itemsById) {
                    for (Item it : fresh) itemsById.put(it.id, it);
                }
                t.items = fresh;
                t.fetchedAt = System.currentTimeMillis();
            } catch (Exception e) {
                Log.w(MiniHttp.TAG, "feed " + t.name + " failed: " + e);
            }
            return t.items;
        }
    }

    private static final Pattern IMG = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /** RSS 2.0 and Atom, the common subset. */
    private static List<Item> parseFeed(byte[] xml, Topic t) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new ByteArrayInputStream(xml), null);
        List<Item> out = new ArrayList<Item>();
        Item cur = null;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = p.getName();
                if (n.equals("item") || n.equals("entry")) {
                    cur = new Item();
                    cur.topic = t;
                } else if (cur != null) {
                    if (n.equals("title")) cur.title = text(p);
                    else if (n.equals("link")) {
                        String href = p.getAttributeValue(null, "href");
                        String rel = p.getAttributeValue(null, "rel");
                        if (href != null) {
                            if (cur.link == null || "alternate".equals(rel)) cur.link = href;
                        } else cur.link = text(p);
                    } else if (n.equals("description") || n.equals("summary") || n.equals("content:encoded") || n.equals("content")) {
                        String s = text(p);
                        if (cur.summary == null || s.length() > cur.summary.length()) cur.summary = s;
                    } else if (n.equals("pubDate") || n.equals("published") || n.equals("updated") || n.equals("dc:date")) {
                        long ts = parseDate(text(p));
                        if (ts > 0 && cur.ts == 0) cur.ts = ts;
                    } else if (n.equals("media:thumbnail") || n.equals("media:content") || n.equals("enclosure")) {
                        String url = p.getAttributeValue(null, "url");
                        String type = p.getAttributeValue(null, "type");
                        if (url != null && cur.image == null && (type == null || type.startsWith("image"))) cur.image = url;
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && cur != null && (p.getName().equals("item") || p.getName().equals("entry"))) {
                if (cur.title != null && cur.link != null) {
                    if (cur.image == null && cur.summary != null) {
                        Matcher m = IMG.matcher(cur.summary);
                        if (m.find()) cur.image = m.group(1).replace("&amp;", "&");
                    }
                    if (cur.image != null) cur.image = upgradeImageUrl(cur.image);
                    if (cur.ts == 0) cur.ts = System.currentTimeMillis() - out.size() * 60000L;
                    cur.title = stripTags(cur.title);
                    cur.id = t.cid + "-" + Integer.toHexString(cur.link.hashCode());
                    out.add(cur);
                }
                cur = null;
            }
        }
        return out;
    }

    private static String text(XmlPullParser p) throws Exception {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT) sb.append(p.getText());
            else if (ev == XmlPullParser.START_TAG) depth++;
            else if (ev == XmlPullParser.END_TAG) depth--;
            else if (ev == XmlPullParser.END_DOCUMENT) break;
        }
        return sb.toString().trim();
    }

    /** Bump known thumbnail CDNs up to a larger size so full-width tiles aren't blurry. */
    private static String upgradeImageUrl(String url) {
        // BBC ichef: .../ace/standard/240/cpsprodpb/... -> the number is the pixel width.
        if (url.contains("ichef.bbci.co.uk")) {
            return url.replaceFirst("/(\\d{2,4})/cpsprodpb/", "/976/cpsprodpb/");
        }
        return url;
    }

    private static String stripTags(String s) {
        return s.replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").trim();
    }

    private static final String[] DATE_FORMATS = {
            "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"};

    private static long parseDate(String s) {
        // Java 6 SimpleDateFormat has no "XXX": turn +01:00 into +0100.
        String norm = s.trim().replaceAll("([+-]\\d\\d):(\\d\\d)$", "$1$2");
        for (String f : DATE_FORMATS) {
            SimpleDateFormat df = new SimpleDateFormat(f, Locale.US);
            df.setTimeZone(TimeZone.getTimeZone("UTC"));
            ParsePosition pos = new ParsePosition(0);
            Date d = df.parse(norm, pos);
            if (d != null && pos.getIndex() == norm.length()) return d.getTime();
        }
        return 0;
    }

    private static MiniHttp.Response json(JSONObject o) {
        return MiniHttp.Response.text(200, "application/json; charset=utf-8", o.toString());
    }

    private static int parseInt(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    private static long parseLong(String s, long dflt) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return dflt;
        }
    }
}
