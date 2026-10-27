package ca.leighnet.reviews.storage.web;

import java.util.List;

import ca.leighnet.reviews.storage.config.ReviewsProperties;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Gives handler methods a {@link Preconditions} parameter, read from the
 * request's If-Match and If-Unmodified-Since. Whether one of them is required
 * is {@code reviews.concurrency.require-preconditions}.
 */
@Component
class PreconditionsResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

	private final boolean required;

	PreconditionsResolver(ReviewsProperties properties) {
		this.required = properties.concurrency().requirePreconditions();
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(this);
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType() == Preconditions.class;
	}

	@Override
	public Preconditions resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
			NativeWebRequest request, WebDataBinderFactory binderFactory) {
		HttpHeaders headers = new ServletServerHttpRequest(request.getNativeRequest(HttpServletRequest.class))
			.getHeaders();
		return Preconditions.from(headers, required);
	}

}
