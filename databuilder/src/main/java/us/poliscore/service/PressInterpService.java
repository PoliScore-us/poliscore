package us.poliscore.service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;

import org.jsoup.Connection.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Node;

import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.SneakyThrows;
import us.poliscore.model.InterpretationOrigin;
import us.poliscore.press.RedditFetcher;

@ApplicationScoped
public class PressInterpService {

    @SneakyThrows
    public String fetchArticleText(InterpretationOrigin origin) {
        if (origin.getUrl().contains("reddit.com/")) {
            return RedditFetcher.fetch(origin);
        }

        return fetchHtmlOriginArticleText(origin);
    }
    
    @SneakyThrows
	protected String fetchHtmlOriginArticleText(InterpretationOrigin origin)
	{
    	RetryPolicy<String> retryPolicy = RetryPolicy.<String>builder()
                .handle(SocketTimeoutException.class)
                .handle(ConnectException.class)
                .withBackoff(Duration.ofSeconds(1), Duration.ofSeconds(5))
                .withMaxAttempts(3)
                .onRetry(e -> System.out.println("Retrying due to: " + e.getLastException()))
                .build();

        return Failsafe.with(retryPolicy).get(() -> {
			Response linkResp = Jsoup.connect(origin.getUrl()).followRedirects(true).userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:126.0) Gecko/20100101 Firefox/126.0").ignoreHttpErrors(true).execute();
			
			if (linkResp.statusCode() >= 200 && linkResp.statusCode() < 400)
			{
				var fetched = linkResp.parse();
				
				// Clean up the HTML to remove things we know we don't want to process
				var body = fetched.body();
				body.select("script,.hidden,style,noscript").remove(); // Strip scripts
				body.select("[style~=(?i)display:\\s*none|visibility:\\s*hidden|opacity:\\s*0]").remove(); // Strip hidden elements
				for (Node node : body.childNodes()) { if (node.nodeName().equals("#comment")) { node.remove(); } } // Strip comments
				body.select("nav, footer, header, aside").remove(); // Strip navigational content
				for (String className : new String[]{"navbar", "menu", "sidebar", "footer", "legal"}) { body.select("." + className).remove(); } // Strip common classes
				
	//			var text = StringUtils.join(" ", body.nodeStream().filter(n -> n instanceof Element && ((Element)n).text().length() > 50).map(n -> ((Element)n).text()).toList());
				
				String articleText = body.text();
				
				return articleText;
			}
			
			return null;
        });
	}
}

