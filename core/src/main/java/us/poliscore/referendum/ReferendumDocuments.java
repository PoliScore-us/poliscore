package us.poliscore.referendum;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

/** Read-only official-document retrieval. Cache lasts for one fetcher run. */
public class ReferendumDocuments {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();
    public byte[] get(String url) throws Exception {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || !officialHost(uri.getHost())) throw new IllegalArgumentException("Unsupported official source: " + url);
        if (cache.containsKey(url)) return cache.get(url);
        var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(90))
            .header("User-Agent", "PoliScore referendum research").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || !officialHost(response.uri().getHost())) throw new IllegalStateException("Failed official source " + url + ": " + response.statusCode());
        if (response.body().length > 30_000_000) throw new IllegalArgumentException("Document too large");
        cache.put(url, response.body()); return response.body();
    }
    private boolean officialHost(String host) {
        return host != null && (host.equals("coloradosos.gov") || host.endsWith(".coloradosos.gov")
            || host.equals("sos.state.co.us") || host.endsWith(".sos.state.co.us")
            || host.equals("tracer.sos.colorado.gov") || host.equals("leg.colorado.gov") || host.endsWith(".leg.colorado.gov"));
    }
    public String pdf(byte[] bytes, int start, int end) throws Exception {
        try (var document = Loader.loadPDF(bytes)) {
            if (start < 1 || end < start || end > document.getNumberOfPages()) throw new IllegalArgumentException("Invalid PDF page range");
            var stripper = new PDFTextStripper(); stripper.setSortByPosition(true);
            stripper.setStartPage(start); stripper.setEndPage(end);
            return stripper.getText(document).strip();
        }
    }
    public String pdf(byte[] bytes) throws Exception {
        try (var document = Loader.loadPDF(bytes)) { return pdf(bytes, 1, document.getNumberOfPages()); }
    }
    public static String hash(String text) {
        try { return hash(text.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

}
