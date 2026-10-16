package ru.pivnoysovet.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Checks the shared base in the background (~every 15 min) and shows notifications about new events. */
public class PollJob extends JobService {
    static final String PREFS = "beer";
    static final String CHANNEL = "beer-news";
    static final int JOB_ID = 7701;
    private static final long WINDOW_MS = 24L * 60 * 60 * 1000;
    private static final String[] WD = {"Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб"};
    private static final String[] MON = {"января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"};

    static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, PollJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
        js.schedule(info);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            try { check(getApplicationContext()); } catch (Exception ignored) { }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }

    static void check(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        String me = sp.getString("me", ""), url = sp.getString("url", ""), key = sp.getString("key", "");
        if (me.isEmpty() || url.isEmpty() || key.isEmpty()) return;
        long now = System.currentTimeMillis();
        long since = sp.getLong("since", 0);
        if (since == 0) { sp.edit().putLong("since", now).apply(); return; } // first run: no history spam

        HttpURLConnection c = (HttpURLConnection) new URL(url + "/rest/v1/docs?select=id,data").openConnection();
        c.setRequestProperty("apikey", key);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        if (c.getResponseCode() != 200) return;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String line; while ((line = r.readLine()) != null) sb.append(line);
        }
        JSONArray rows = new JSONArray(sb.toString());
        Map<String, JSONObject> docs = new HashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            JSONObject d = row.optJSONObject("data");
            if (d != null) docs.put(row.getString("id"), d);
        }
        Set<String> sent = new HashSet<>(sp.getStringSet("sent", new HashSet<>()));
        long floor = Math.max(since, now - WINDOW_MS);
        int n = 0;
        for (Map.Entry<String, JSONObject> e : docs.entrySet()) {
            String id = e.getKey(); JSONObject d = e.getValue();
            int slash = id.indexOf('/'); if (slash < 0) continue;
            String coll = id.substring(0, slash), k = id.substring(slash + 1);
            if (coll.equals("proposals")) {
                String by = d.optString("by");
                if (!by.equals(me) && d.optLong("at") > floor && sent.add("new:" + k))
                    notify(ctx, n++, name(docs, by) + " предложил сходку", "Какой твой ответ, май френд? " + when(d) + " · " + place(docs, d.optJSONObject("place")));
                JSONObject mine = docs.get("answers/" + k + "__" + me);
                if (d.optBoolean("confirmed") && d.optLong("confirmedAt") > floor && !by.equals(me)
                        && mine != null && mine.optBoolean("in") && sent.add("conf:" + k))
                    notify(ctx, n++, "Сходка в силе", when(d) + " · " + place(docs, d.optJSONObject("place")));
            } else if (coll.equals("answers")) {
                int sep = k.indexOf("__"); if (sep < 0) continue;
                String pid = k.substring(0, sep), who = k.substring(sep + 2);
                JSONObject p = docs.get("proposals/" + pid);
                if (p == null || !p.optString("by").equals(me) || who.equals(me)) continue;
                boolean in = d.optBoolean("in");
                if (d.optLong("at") > floor && sent.add("ans:" + k + ":" + (in ? "in" : "out")))
                    notify(ctx, n++, name(docs, who) + (in ? " в пиве" : " пас"), when(p) + " · " + place(docs, p.optJSONObject("place")));
            } else if (coll.equals("members")) {
                if (!k.equals(me) && d.optLong("joinedAt") > floor && sent.add("mem:" + k))
                    notify(ctx, n++, name(docs, k) + " вступил в тусу", "Пивной совет стал больше");
            }
        }
        if (sent.size() > 400) sent.clear();
        sp.edit().putStringSet("sent", sent).putLong("since", now - 60 * 1000).apply();
    }

    private static String name(Map<String, JSONObject> docs, String id) {
        JSONObject m = docs.get("members/" + id);
        String n = m == null ? "" : m.optString("name");
        return n.isEmpty() ? "Кто-то" : n;
    }

    private static String when(JSONObject p) {
        String date = p.optString("date"), time = p.optString("time");
        try {
            String[] s = date.split("-");
            Calendar cal = Calendar.getInstance();
            cal.set(Integer.parseInt(s[0]), Integer.parseInt(s[1]) - 1, Integer.parseInt(s[2]));
            return WD[cal.get(Calendar.DAY_OF_WEEK) - 1] + ", " + Integer.parseInt(s[2]) + " " + MON[Integer.parseInt(s[1]) - 1] + " · " + time;
        } catch (Exception e) { return date + " " + time; }
    }

    private static String place(Map<String, JSONObject> docs, JSONObject pl) {
        if (pl == null) return "";
        String type = pl.optString("type");
        if (type.equals("bar")) return pl.optString("name").isEmpty() ? "Бар" : "Бар «" + pl.optString("name") + "»";
        if (type.equals("online")) return "Онлайн";
        JSONObject m = docs.get("members/" + type);
        String gen = m == null ? "" : m.optString("gen");
        return gen.isEmpty() ? "Хата: " + name(docs, type) : "У " + gen;
    }

    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Сходки и ответы", NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    @SuppressWarnings("deprecation")
    private static void notify(Context ctx, int n, String title, String text) {
        ensureChannel(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL) : new Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_stat_beer).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(pi);
        nm.notify((int) (System.currentTimeMillis() % 100000) + n, b.build());
    }
}
