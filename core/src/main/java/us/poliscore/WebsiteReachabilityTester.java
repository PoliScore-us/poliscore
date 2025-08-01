package us.poliscore;

import lombok.experimental.UtilityClass;
import lombok.val;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Set;

/**
 * Opens URLs in a way that:
 *   • Prefers HTTPS (falls back to the original scheme if needed)  
 *   • Adds realistic browser headers  
 *   • Accepts 401/403/405/406/416/429 as proof that the resource exists  
 *   • Follows redirects
 */
@UtilityClass
public class WebsiteReachabilityTester {

    private static final int TIMEOUT_MS = 5_000;

    private static final Set<Integer> OK = Set.of(
            // success & redirects
            HttpURLConnection.HTTP_OK,
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            HttpURLConnection.HTTP_NOT_MODIFIED,
            // resource exists but access is denied / method not supported / rate-limited
            HttpURLConnection.HTTP_UNAUTHORIZED,    // 401
            HttpURLConnection.HTTP_FORBIDDEN,       // 403
            HttpURLConnection.HTTP_BAD_METHOD,      // 405
            406, 416, 429
    );

    /** Quick probe: returns {@code true} when the resource *exists*. */
    public static boolean isReachable(String urlStr) {
        try {
            val conn = open(urlStr);
            conn.disconnect();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * “Fetch” the URL: returns an open {@link HttpURLConnection} positioned at the first
     * successful endpoint.  Callers can read the stream or inspect headers; remember to
     * {@code disconnect()} when finished.
     *
     * @throws IOException if all attempts fail
     */
    public static HttpURLConnection open(String urlStr) throws IOException {
        URL upgraded = upgradeToHttps(urlStr);

        // 1️⃣  Try HTTPS first
        HttpURLConnection conn = tryUrl(upgraded);
        if (conn != null) return conn;

        // 2️⃣  If the original scheme was HTTP and HTTPS failed, fall back
        if (!upgraded.toString().equalsIgnoreCase(urlStr)) {
            conn = tryUrl(new URL(urlStr));
            if (conn != null) return conn;
        }

        throw new IOException("Unreachable: " + urlStr);
    }

    /* ────────────────────────── internal helpers ────────────────────────── */

    private static HttpURLConnection tryUrl(URL url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestMethod("GET");
        spoofBrowserHeaders(c);

        int code = c.getResponseCode();
        if (code < 400 || OK.contains(code)) {
            return c;                     // resource confirmed
        }
        c.disconnect();                   // prevent leaked sockets
        return null;                      // treat as failure, caller decides on fallback
    }

    private static URL upgradeToHttps(String urlStr) throws MalformedURLException {
        URL u = new URL(urlStr);
        if ("http".equalsIgnoreCase(u.getProtocol())) {
            return new URL("https", u.getHost(), u.getPort(), u.getFile());
        }
        return u;
    }

    private static void spoofBrowserHeaders(HttpURLConnection c) {
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
              + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115 Safari/537.36");
        c.setRequestProperty("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
        // Don’t set “Connection: keep-alive”; HTTP/2 servers may reject it.
    }
}
