package ca.leighnet.reviews.storage.image;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;

import ca.leighnet.reviews.storage.config.ReviewsProperties;
import ca.leighnet.reviews.storage.web.ApiException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Accepts only real images: the leading bytes must name a supported format,
 * the dimensions must be within the pixel limit (checked from the header,
 * before decoding), and the whole image must then decode.
 */
@Component
public class ImageValidator {

	public record Image(ImageFormat format, int width, int height) {
	}

	private static final String SUPPORTED = "JPEG, PNG, GIF or WebP";

	private final long maxPixels;

	@Autowired
	public ImageValidator(ReviewsProperties properties) {
		this(properties.images().maxPixels());
	}

	ImageValidator(long maxPixels) {
		this.maxPixels = maxPixels;
		// Register plugins (WebP) visible to this class loader, not only the JDK's.
		ImageIO.scanForPlugins();
	}

	public Image validate(byte[] bytes) {
		ImageFormat format = ImageFormat.detect(bytes)
			.orElseThrow(() -> ApiException.unsupportedMediaType("The upload is not a " + SUPPORTED + " image"));
		var readers = ImageIO.getImageReadersByFormatName(format.readerName());
		if (!readers.hasNext()) {
			throw new IllegalStateException("No ImageIO reader for " + format);
		}
		ImageReader reader = readers.next();
		try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			reader.setInput(input, true, true);
			int width = reader.getWidth(0);
			int height = reader.getHeight(0);
			if (width <= 0 || height <= 0) {
				throw invalid(format);
			}
			if ((long) width * height > maxPixels) {
				throw ApiException.payloadTooLarge(
						"The image is " + width + "x" + height + "; at most " + maxPixels + " pixels are allowed");
			}
			reader.read(0);
			return new Image(format, width, height);
		}
		catch (IOException | RuntimeException ex) {
			if (ex instanceof ApiException apiException) {
				throw apiException;
			}
			throw invalid(format);
		}
		finally {
			reader.dispose();
		}
	}

	static ApiException invalid(ImageFormat format) {
		return ApiException.unsupportedMediaType("The upload is not a valid " + format + " image");
	}

}
