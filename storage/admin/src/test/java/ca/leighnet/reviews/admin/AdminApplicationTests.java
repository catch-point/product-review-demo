package ca.leighnet.reviews.admin;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

// The password is the test's own (there is no default in application.yml);
// production deployments get it from ADMIN_PASSWORD through compose.
@SpringBootTest(properties = "spring.security.user.password=admin")
@AutoConfigureMockMvc
class AdminApplicationTests {

	@Autowired
	private MockMvcTester mvc;

	@Test
	void theUiRequiresSigningIn() {
		var result = mvc.get().uri("/applications").accept(MediaType.TEXT_HTML).exchange();

		assertThat(result).hasStatus3xxRedirection();
		assertThat(result.getResponse().getRedirectedUrl()).endsWith("/login");
		assertThat(mvc.get().uri("/login")).hasStatusOk();
	}

	@Test
	void servicesRegisterWithBasicAuthentication() {
		String registration = """
				{"name": "storage", "managementUrl": "http://storage:8081/actuator",
				 "healthUrl": "http://storage:8081/actuator/health", "serviceUrl": "http://storage:8080/"}
				""";

		assertThat(mvc.post().uri("/instances").contentType(MediaType.APPLICATION_JSON).content(registration))
			.hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(mvc.post()
			.uri("/instances")
			.with(httpBasic("admin", "admin"))
			.contentType(MediaType.APPLICATION_JSON)
			.content(registration)).hasStatus(HttpStatus.CREATED);
	}

}
