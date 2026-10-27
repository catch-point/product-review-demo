package ca.leighnet.reviews.storage.image;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import ca.leighnet.reviews.storage.config.ReviewsProperties;
import ca.leighnet.reviews.storage.jsonld.Iris;
import ca.leighnet.reviews.storage.person.CurrentUser;
import ca.leighnet.reviews.storage.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ImageServiceTest {

	private static final String SHA256 = "a".repeat(64);

	@Mock
	private CurrentUser currentUser;

	@Mock
	private ImageStore store;

	@Mock
	private Iris iris;

	private ImageService service;

	@BeforeEach
	void setUp() {
		var images = new ReviewsProperties.Images(DataSize.ofKilobytes(16), 1_000_000);
		var properties = new ReviewsProperties(null, null, images, null, null, null);
		service = new ImageService(new ImageValidator(images.maxPixels()), store, currentUser, iris, properties);
		lenient().when(iris.collection(anyString()))
			.thenAnswer(call -> "http://localhost" + call.getArgument(0));
	}

	@Test
	void describesTheUploadedImage() {
		byte[] png = TestImages.encode("png", 30, 20);
		var uploaded = Instant.parse("2026-10-21T12:00:00Z");
		given(store.save(any(), eq(ImageFormat.PNG))).willReturn(
				new ImageStore.Stored(SHA256 + ".png", SHA256, ImageFormat.PNG, png.length, uploaded, true),
				new ImageStore.Stored(SHA256 + ".png", SHA256, ImageFormat.PNG, png.length, uploaded, false));

		var upload = service.upload(new MockMultipartFile("file", "photo.jpg", "image/jpeg", png));

		assertThat(upload.created()).isTrue();
		var image = upload.image();
		assertThat(image.type()).isEqualTo("ImageObject");
		assertThat(image.encodingFormat()).as("detected, not declared").isEqualTo("image/png");
		assertThat(image.id()).isEqualTo(image.contentUrl()).endsWith("/images/" + image.sha256() + ".png");
		assertThat(List.of(image.width(), image.height())).containsExactly(30, 20);
		assertThat(image.uploadDate().toInstant()).isEqualTo(uploaded);
		assertThat(service.upload(new MockMultipartFile("file", png)).created()).isFalse();
	}

	@Test
	void storesTheImageWithoutItsMetadata() {
		byte[] png = TestImages.encode("png", 3, 3);
		byte[] tagged = TestImages.withChunk(png, "tEXt", "GPS\0 49.28N 123.12W".getBytes(StandardCharsets.US_ASCII));
		given(store.save(any(), eq(ImageFormat.PNG))).willAnswer(call -> new ImageStore.Stored(SHA256 + ".png",
				SHA256, ImageFormat.PNG, call.<byte[]>getArgument(0).length, Instant.now(), true));

		service.upload(new MockMultipartFile("file", "photo.png", "image/png", tagged));

		then(store).should().save(png, ImageFormat.PNG);
	}

	@Test
	void refusesImagesWhoseStructureIsBroken() {
		byte[] png = TestImages.encode("png", 3, 3);
		byte[] truncated = Arrays.copyOf(png, png.length - 20);

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> service.upload(new MockMultipartFile("file", "photo.png", "image/png", truncated)))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
		then(store).shouldHaveNoInteractions();
	}

	@Test
	void enforcesTheConfiguredSizeLimit() {
		byte[] large = TestImages.noise("png", 100, 100);
		assertThat(large.length).isGreaterThan(16 * 1024);

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> service.upload(new MockMultipartFile("file", "big.png", "image/png", large)))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE))
			.withMessageContaining("16 KB");
	}

	@Test
	void rejectsEmptyUploads() {
		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> service.upload(new MockMultipartFile("file", new byte[0])))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
	}

	@Test
	void onlyRegisteredPeopleUpload() {
		willThrow(ApiException.forbidden("Register first")).given(currentUser).requirePersonId();

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> service.upload(new MockMultipartFile("file", TestImages.encode("png", 1, 1))))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
	}

	@Test
	void unknownImagesAreNotFound() {
		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.find("a".repeat(64) + ".png"))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
	}

}
