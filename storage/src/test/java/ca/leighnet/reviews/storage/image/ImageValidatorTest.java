package ca.leighnet.reviews.storage.image;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import ca.leighnet.reviews.storage.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ImageValidatorTest {

	private final ImageValidator validator = new ImageValidator(1_000_000);

	@ParameterizedTest
	@CsvSource({ "png, PNG", "jpeg, JPEG", "gif, GIF" })
	void acceptsDecodableImages(String writer, ImageFormat format) {
		var image = validator.validate(TestImages.encode(writer, 30, 20));

		assertThat(image.format()).isEqualTo(format);
		assertThat(image.width()).isEqualTo(30);
		assertThat(image.height()).isEqualTo(20);
	}

	@Test
	void acceptsWebp() {
		var image = validator.validate(TestImages.WEBP_1X1);

		assertThat(image.format()).isEqualTo(ImageFormat.WEBP);
		assertThat(image.width()).isEqualTo(1);
		assertThat(image.height()).isEqualTo(1);
	}

	@Test
	void rejectsFilesThatAreNotImages() {
		assertRejected("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8),
				HttpStatus.UNSUPPORTED_MEDIA_TYPE, "not a JPEG, PNG, GIF or WebP");
		assertRejected("%PDF-1.7".getBytes(StandardCharsets.US_ASCII), HttpStatus.UNSUPPORTED_MEDIA_TYPE, "not a");
		assertRejected(new byte[0], HttpStatus.UNSUPPORTED_MEDIA_TYPE, "not a");
	}

	@Test
	void rejectsImagesThatOnlyStartLikeOne() {
		byte[] png = TestImages.encode("png", 30, 20);
		byte[] truncated = Arrays.copyOf(png, png.length / 2);
		byte[] signatureOnly = Arrays.copyOf(png, 8);

		assertRejected(truncated, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "not a valid PNG");
		assertRejected(signatureOnly, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "not a valid PNG");
	}

	@Test
	void rejectsTooManyPixelsBeforeDecoding() {
		assertRejected(TestImages.encode("png", 1001, 1000), HttpStatus.CONTENT_TOO_LARGE, "1001x1000");
	}

	private void assertRejected(byte[] bytes, HttpStatus status, String message) {
		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> validator.validate(bytes))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(status))
			.withMessageContaining(message);
	}

}
