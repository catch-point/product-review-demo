package ca.leighnet.reviews.storage.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import ca.leighnet.reviews.storage.config.ReviewsProperties;
import ca.leighnet.reviews.storage.image.ImageConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.UrlPathHelper;

/**
 * Bounds request bodies before anything reads them: {@code reviews.requests.max-body-size}
 * for JSON, and the multipart limit ({@link ImageConfig}) for image uploads. A
 * Content-Length over the limit is refused with 413 at once, unread; a body
 * without one (chunked) stops at the limit, and the request fails with 413
 * ({@link RequestBodyTooLargeException}). Runs after the forwarded headers are
 * applied and before authentication, so no one can make the service read more.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RequestBodyLimit extends OncePerRequestFilter {

	// Resolved on the first 413, not at construction: the resolver is the MVC
	// graph's composite, and building it here would start the database while
	// the container is still starting (before dynamic properties, such as
	// Testcontainers', are applied).
	private final ObjectProvider<HandlerExceptionResolver> resolver;

	private final DataSize maxBodySize;

	private final DataSize maxUploadSize;

	private final DataSize maxImageSize;

	RequestBodyLimit(@Qualifier("handlerExceptionResolver") ObjectProvider<HandlerExceptionResolver> resolver,
			ReviewsProperties properties) {
		this.resolver = resolver;
		this.maxBodySize = properties.requests().maxBodySize();
		this.maxUploadSize = ImageConfig.maxRequestSize(properties);
		this.maxImageSize = properties.images().maxSize();
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		boolean upload = "/images".equals(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
		DataSize limit = upload ? maxUploadSize : maxBodySize;
		if (request.getContentLengthLong() > limit.toBytes()) {
			var tooLarge = upload ? ApiException.payloadTooLarge("Images may be at most " + maxImageSize.toKilobytes() + " KB")
					: tooLarge(limit);
			resolver.getObject().resolveException(request, response, null, tooLarge);
			return;
		}
		// Multipart is parsed by the servlet container, within its own limit;
		// everything else is read through this stream.
		chain.doFilter(upload ? request : new LimitedRequest(request, limit), response);
	}

	static ApiException tooLarge(DataSize limit) {
		return ApiException.payloadTooLarge("Request bodies may be at most " + limit.toKilobytes() + " KB");
	}

	private static final class LimitedRequest extends HttpServletRequestWrapper {

		private final DataSize limit;

		private ServletInputStream body;

		LimitedRequest(HttpServletRequest request, DataSize limit) {
			super(request);
			this.limit = limit;
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			if (body == null) {
				body = new LimitedInputStream(super.getInputStream(), limit);
			}
			return body;
		}

		@Override
		public BufferedReader getReader() throws IOException {
			String encoding = getCharacterEncoding();
			Charset charset = (encoding != null) ? Charset.forName(encoding) : StandardCharsets.ISO_8859_1;
			return new BufferedReader(new InputStreamReader(getInputStream(), charset));
		}

	}

	private static final class LimitedInputStream extends ServletInputStream {

		private final ServletInputStream in;

		private final DataSize limit;

		private long read;

		LimitedInputStream(ServletInputStream in, DataSize limit) {
			this.in = in;
			this.limit = limit;
		}

		@Override
		public int read() throws IOException {
			int b = in.read();
			if (b >= 0) {
				count(1);
			}
			return b;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			int n = in.read(buffer, offset, length);
			if (n > 0) {
				count(n);
			}
			return n;
		}

		private void count(int n) throws RequestBodyTooLargeException {
			read += n;
			if (read > limit.toBytes()) {
				throw new RequestBodyTooLargeException(limit);
			}
		}

		@Override
		public boolean isFinished() {
			return in.isFinished();
		}

		@Override
		public boolean isReady() {
			return in.isReady();
		}

		@Override
		public void setReadListener(ReadListener listener) {
			in.setReadListener(listener);
		}

	}

}
