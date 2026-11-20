package ro.talktext;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** One HTTPS POST with retries for network errors, 429 and 5xx. Never logs keys or text. */
final class Http {
    static final class Failure extends Exception {
        Failure(String message) { super(message); }
    }

    static String post(String url, Map<String, String> headers, byte[] body, String service) throws Failure {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) sleep(attempt == 1 ? 1500 : 4000);
            HttpsURLConnection c = null;
            try {
                c = (HttpsURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setRequestMethod("POST");
                c.setConnectTimeout(15_000);
                c.setReadTimeout(180_000);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
                try (var out = c.getOutputStream()) { out.write(body); }
                int status = c.getResponseCode();
                if (status == 200) return read(c.getInputStream());
                String detail = message(read(c.getErrorStream()));
                if (status == 429 || status >= 500) { last = new Failure(service + ": HTTP " + status + detail); continue; }
                throw new Failure(service + ": HTTP " + status + detail);
            } catch (Failure f) {
                throw f;
            } catch (Exception e) {
                last = e;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw new Failure(service + ": fără conexiune (" + (last == null ? "?" : last.getClass().getSimpleName()) + ")");
    }

    private static String message(String body) {
        try {
            String m = new JSONObject(body).getJSONObject("error").optString("message", "");
            if (m.length() > 160) m = m.substring(0, 160) + "…";
            return m.isEmpty() ? "" : " — " + m;
        } catch (Exception e) {
            return "";
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        if (in == null) return "";
        try (in) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private Http() {}
}
