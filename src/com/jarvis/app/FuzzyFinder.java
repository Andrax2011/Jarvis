package com.jarvis.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FuzzyFinder {

    public static class AppMatch {
        public final String label;
        public final String packageName;
        public final double score;

        public AppMatch(String label, String packageName, double score) {
            this.label = label;
            this.packageName = packageName;
            this.score = score;
        }
    }

    private static final Map<String, String> ALIASES = new HashMap<String, String>();
    static {
        ALIASES.put("телеграм", "telegram"); ALIASES.put("телега", "telegram");
        ALIASES.put("телегу", "telegram");   ALIASES.put("тг", "telegram");
        ALIASES.put("тележку", "telegram");  ALIASES.put("ютуб", "youtube");
        ALIASES.put("ютьюб", "youtube");     ALIASES.put("ютубчик", "youtube");
        ALIASES.put("вк", "vk");             ALIASES.put("вконтакте", "vk");
        ALIASES.put("тикток", "tiktok");     ALIASES.put("тик ток", "tiktok");
        ALIASES.put("тт", "tiktok");         ALIASES.put("ватсап", "whatsapp");
        ALIASES.put("вацап", "whatsapp");    ALIASES.put("вотсап", "whatsapp");
        ALIASES.put("инста", "instagram");   ALIASES.put("инстаграм", "instagram");
        ALIASES.put("инсту", "instagram");   ALIASES.put("спотик", "spotify");
        ALIASES.put("спотифай", "spotify");  ALIASES.put("хром", "chrome");
        ALIASES.put("дискорд", "discord");   ALIASES.put("дс", "discord");
        ALIASES.put("майн", "minecraft");    ALIASES.put("майнкрафт", "minecraft");
        ALIASES.put("роблокс", "roblox");    ALIASES.put("калькулятор", "calculator");
        ALIASES.put("кальк", "calculator");  ALIASES.put("камера", "camera");
        ALIASES.put("камеру", "camera");     ALIASES.put("фотик", "camera");
        ALIASES.put("галерея", "gallery");   ALIASES.put("галерею", "gallery");
        ALIASES.put("фотки", "photos");      ALIASES.put("часы", "clock");
        ALIASES.put("настройки", "settings");ALIASES.put("карты", "maps");
        ALIASES.put("почта", "gmail");       ALIASES.put("гмейл", "gmail");
        ALIASES.put("браузер", "browser");   ALIASES.put("проводник", "files");
        ALIASES.put("файлы", "files");       ALIASES.put("заметки", "notes");
        ALIASES.put("блокнот", "notes");     ALIASES.put("переводчик", "translate");
        ALIASES.put("плей маркет", "play");  ALIASES.put("маркет", "play");
        ALIASES.put("музыка", "music");      ALIASES.put("музыку", "music");
    }

    public static AppMatch findBestApp(Context context, String rawQuery) {
        if (rawQuery == null) return null;
        String cleaned = rawQuery.toLowerCase(new Locale("ru"))
                .replaceAll("\\b(приложение|программу|прогу|игру|пожалуйста|быстро)\\b", "")
                .replaceAll("[^a-zа-яё0-9\\s]", " ")
                .replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) return null;

        String alias = ALIASES.containsKey(cleaned) ? ALIASES.get(cleaned) : cleaned;
        String transQ = ruToEn(cleaned);
        String phonQ = phonetic(transQ);

        PackageManager pm = context.getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);

        AppMatch best = null;
        for (ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(context.getPackageName())) continue;

            String label = ri.loadLabel(pm).toString();
            String labelLow = label.toLowerCase(new Locale("ru"));
            String labelEn = ruToEn(labelLow);
            String labelPhon = phonetic(labelEn);

            String pkgSimple = pkg.toLowerCase(Locale.US)
                    .replace("com.", "").replace("org.", "").replace("net.", "")
                    .replace("android.", "").replace("google.", "").replace(".", " ");

            double s = 0;
            s = Math.max(s, scorePair(cleaned, labelLow));
            s = Math.max(s, scorePair(alias, labelLow));
            s = Math.max(s, scorePair(alias, labelEn));
            s = Math.max(s, scorePair(transQ, labelEn));
            s = Math.max(s, scorePair(phonQ, labelPhon));
            s = Math.max(s, scorePair(alias, pkgSimple) * 0.92);
            s = Math.max(s, scorePair(transQ, pkgSimple) * 0.90);

            if (best == null || s > best.score) {
                best = new AppMatch(label, pkg, s);
            }
        }
        return (best != null && best.score >= 0.52) ? best : null;
    }

    public static double scorePair(String query, String target) {
        if (query == null || target == null || query.isEmpty() || target.isEmpty()) return 0;
        if (target.equals(query)) return 1.0;
        if (target.startsWith(query)) return 0.93;
        if (target.contains(query) && query.length() >= 3) return 0.86;

        double best = similarity(query, target);
        String[] words = target.split("[\\s_\\-]+");
        for (String w : words) {
            if (w.length() < 2) continue;
            if (w.equals(query)) return 0.95;
            if (w.startsWith(query) && query.length() >= 3) best = Math.max(best, 0.88);
            best = Math.max(best, similarity(query, w) * 0.94);
        }
        return best;
    }

    public static double similarity(String a, String b) {
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        int dist = levenshtein(a, b);
        return 1.0 - ((double) dist / maxLen);
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }

    public static String ruToEn(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case 'а': sb.append('a'); break;  case 'б': sb.append('b'); break;
                case 'в': sb.append('v'); break;  case 'г': sb.append('g'); break;
                case 'д': sb.append('d'); break;  case 'е': case 'ё': case 'э': sb.append('e'); break;
                case 'ж': sb.append("zh"); break; case 'з': sb.append('z'); break;
                case 'и': case 'й': case 'ы': sb.append('i'); break;
                case 'к': sb.append('k'); break;  case 'л': sb.append('l'); break;
                case 'м': sb.append('m'); break;  case 'н': sb.append('n'); break;
                case 'о': sb.append('o'); break;  case 'п': sb.append('p'); break;
                case 'р': sb.append('r'); break;  case 'с': sb.append('s'); break;
                case 'т': sb.append('t'); break;  case 'у': sb.append('u'); break;
                case 'ф': sb.append('f'); break;  case 'х': sb.append('h'); break;
                case 'ц': sb.append("ts"); break; case 'ч': sb.append("ch"); break;
                case 'ш': case 'щ': sb.append("sh"); break;
                case 'ю': sb.append("yu"); break; case 'я': sb.append("ya"); break;
                case 'ъ': case 'ь': break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String phonetic(String s) {
        return s.replace("ph", "f").replace("ck", "k").replace("qu", "k")
                .replace('c', 'k').replace('w', 'v').replace('y', 'i').replace('j', 'i')
                .replaceAll("(.)\\1+", "$1");
    }
          }
