package us.poliscore.model;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;
import us.poliscore.WebsiteReachabilityTester;

@Data
@DynamoDbBean
@NoArgsConstructor
@AllArgsConstructor
public class InterpretationOrigin {
	public static final InterpretationOrigin POLISCORE = new InterpretationOrigin("https://poliscore.us", "PoliScore");
	
	@NonNull public String url;
	
	@NonNull public String title;
	
	@DynamoDbIgnore
	@JsonIgnore
	public String getIdHash() {
		try {
//			java.net.URI uri = java.net.URI.create(url);
//		    java.net.URL parsedUrl = uri.toURL();
			
			// Even though "new URL" here is deprecated, we actually don't have a real replacement for it here.
			// The recommended 'URI.create' solution is more strict in its validation which occasionally causes issues
			@SuppressWarnings("deprecation")
			URL parsedUrl = new URL(url);
			
		    String host = parsedUrl.getHost();

		    if (host == null || host.isEmpty()) {
		        String temp = url.replaceAll("^https?://", "");
		        int slashIndex = temp.indexOf('/');
		        host = (slashIndex == -1) ? temp : temp.substring(0, slashIndex);
		    }

		    if (host.startsWith("www.")) {
		        host = host.substring(4);
		    }

		    String cleaned = host.replaceAll("[^A-Za-z0-9]", "");
		    if (cleaned.length() > 6) {
		        cleaned = cleaned.substring(0, 6);
		    }

		    String path = parsedUrl.getPath();
		    if (path != null && !path.isEmpty() && !path.equals("/")) {
		        if (host.contains("reddit.com")) {
		            String[] parts = path.split("/");
		            for (int i = 0; i < parts.length - 1; i++) {
		                if (parts[i].equalsIgnoreCase("r") && !parts[i + 1].isEmpty()) {
		                    String subreddit = parts[i + 1].replaceAll("[^A-Za-z0-9]", "");
		                    if (subreddit.length() > 10) {
		                        subreddit = subreddit.substring(0, 10);
		                    }
		                    return "reddit/" + subreddit.toLowerCase();
		                }
		            }
		        }

		        String[] segments = path.split("/");
		        for (String seg : segments) {
		            if (!seg.isEmpty()) {
		                String readable = seg.replaceAll("[^A-Za-z0-9]", "");
		                if (readable.length() > 10) {
		                    readable = readable.substring(0, 10);
		                }
		                return (cleaned + "/" + readable).toLowerCase();
		            }
		        }
		    }

		    return cleaned.toLowerCase();

		} catch (Exception e) {
		    e.printStackTrace();
		    return "unknown";
		}

	}
	
	public void validate(String officialBillUrl) {
		if (getIdHash().equals("unknown")) {
			throw new InvalidOriginException("Invalid interpretation origin. Id hash could not be generated.");
		}
		
		try {
			if (isOfficialBillUrl(officialBillUrl)) {
				throw new InvalidOriginException("origin is bill's officlal url");
			}
			
			verifyReachable();
		} catch (Throwable t) {
			if (t instanceof InvalidOriginException) {
				throw ((InvalidOriginException)t);
			}
			
			throw new InvalidOriginException(t);
		}
	}
	
	@SneakyThrows
	public boolean isOfficialBillUrl(String officialBillUrl) {
		URI thisUri = new URI(this.url).normalize();
		URI officialUri = new URI(officialBillUrl).normalize();

		String thisHost = thisUri.getHost();
		String officialHost = officialUri.getHost();
		String thisPath = thisUri.getPath();
		String officialPath = officialUri.getPath();

		if (thisHost == null || officialHost == null || thisPath == null || officialPath == null) {
			return false;
		}

		// Normalize host: strip "www." and lowercase
		thisHost = thisHost.replaceFirst("^www\\.", "").toLowerCase();
		officialHost = officialHost.replaceFirst("^www\\.", "").toLowerCase();

		// Normalize paths:
		// - lowercase
		// - strip trailing slashes
		// - normalize "119th-congress" ↔ "119-congress"
		thisPath = thisPath.replaceAll("/+$", "").toLowerCase()
		                   .replaceAll("(\\d+)(st|nd|rd|th)-congress", "$1-congress");
		officialPath = officialPath.replaceAll("/+$", "").toLowerCase()
		                           .replaceAll("(\\d+)(st|nd|rd|th)-congress", "$1-congress");

		return thisHost.equals(officialHost) && thisPath.startsWith(officialPath);
	}

	
	@SneakyThrows
	public void verifyReachable() {
		if (!WebsiteReachabilityTester.isReachable(getUrl())) {
	        throw new InvalidOriginException("URL is unreachable: " + getUrl());
	    }
	}

	private void spoofBrowserHeaders(HttpURLConnection connection) {
		connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
			"AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36");
		connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
		connection.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
		connection.setRequestProperty("Connection", "keep-alive");
		connection.setRequestProperty("Referer", "https://www.google.com");
	}
	
	@Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InterpretationOrigin)) return false;
        InterpretationOrigin that = (InterpretationOrigin) o;
        return this.getIdHash().equals(that.getIdHash());
    }

    @Override
    public int hashCode() {
        return getIdHash().hashCode();
    }
    
    public class InvalidOriginException extends RuntimeException {

        private static final long serialVersionUID = 6053809157332136666L;

		public InvalidOriginException(String message) {
            super(message);
        }

        public InvalidOriginException(String message, Throwable cause) {
            super(message, cause);
        }

        public InvalidOriginException(Throwable cause) {
            super(cause);
        }
    }
}