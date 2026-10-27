package ca.leighnet.reviews.admin;

import de.codecentric.boot.admin.server.config.AdminServerProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * People sign in to the UI with a form; services register over HTTP Basic.
 * Both use the account in {@code spring.security.user.*}.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	@Bean
	SecurityFilterChain adminSecurity(HttpSecurity http, AdminServerProperties admin) throws Exception {
		String path = admin.getContextPath();
		var signedIn = new SavedRequestAwareAuthenticationSuccessHandler();
		signedIn.setTargetUrlParameter("redirectTo");
		signedIn.setDefaultTargetUrl(path + "/");
		var matcher = PathPatternRequestMatcher.withDefaults();
		return http
			.authorizeHttpRequests(requests -> requests
				.requestMatchers(path + "/assets/**", path + "/login", path + "/actuator/health/**").permitAll()
				.anyRequest().authenticated())
			.formLogin(form -> form.loginPage(path + "/login").successHandler(signedIn))
			.logout(logout -> logout.logoutUrl(path + "/logout"))
			.httpBasic(Customizer.withDefaults())
			.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
				.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
				// Services register and deregister without a browser session.
				.ignoringRequestMatchers(matcher.matcher(HttpMethod.POST, path + "/instances"),
						matcher.matcher(HttpMethod.DELETE, path + "/instances/*"), matcher.matcher(path + "/actuator/**")))
			.build();
	}

}
