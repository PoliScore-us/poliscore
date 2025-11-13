package us.poliscore;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

@Provider
@Priority(Priorities.AUTHENTICATION) // early
public class AccessLogFilter implements ContainerRequestFilter, ContainerResponseFilter {
	private static final Logger LOG = Logger.getLogger(AccessLogFilter.class);
	private static final String START = "accessLogStartNanos";

	@Override
	public void filter(ContainerRequestContext req) {
		req.setProperty(START, System.nanoTime());
		UriInfo uri = req.getUriInfo();
		// These headers are often present via API Gateway; harmless if missing
		String trace = req.getHeaderString("X-Amzn-Trace-Id");
		String sourceIp = req.getHeaderString("X-Forwarded-For");

		LOG.infof("REQ %s %s trace=%s ip=%s", req.getMethod(), uri.getRequestUri().getPath(), nullToDash(trace),
				nullToDash(sourceIp));
	}

	@Override
	public void filter(ContainerRequestContext req, ContainerResponseContext res) {
		Long start = (Long) req.getProperty(START);
		long durMs = (start == null) ? -1 : (System.nanoTime() - start) / 1_000_000;
		UriInfo uri = req.getUriInfo();

		LOG.infof("RES %s %s -> %d in %dms", req.getMethod(), uri.getRequestUri().getPath(), res.getStatus(), durMs);
	}

	private static String nullToDash(String s) {
		return (s == null || s.isBlank()) ? "-" : s;
	}
}
