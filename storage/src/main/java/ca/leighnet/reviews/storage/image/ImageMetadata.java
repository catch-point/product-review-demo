package ca.leighnet.reviews.storage.image;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Removes what an uploaded image says beyond its pixels before it is stored:
 * EXIF (camera, time, GPS location), XMP, IPTC, comments and text, and
 * anything after the image's end. What changes how it looks is kept: colour
 * profiles, transparency, animation and, for JPEG, the EXIF orientation,
 * rewritten as the only EXIF tag. Only the file's structure is read and
 * rewritten; the pixels are not decoded or re-encoded.
 */
final class ImageMetadata {

	// JPEG markers.
	private static final int SOS = 0xDA;

	private static final int EOI = 0xD9;

	private static final int APP0 = 0xE0;

	private static final int APP1 = 0xE1;

	private static final int APP2 = 0xE2;

	private static final int APP14 = 0xEE;

	private static final int APP15 = 0xEF;

	private static final int COM = 0xFE;

	private static final byte[] EXIF = ascii("Exif\0\0");

	private static final byte[] ICC_PROFILE = ascii("ICC_PROFILE\0");

	private static final int ORIENTATION = 0x0112;

	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	/** PNG's ancillary chunks that change how the image looks (critical ones are always kept). */
	private static final Set<String> PNG_KEPT = Set.of("tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "cICP", "mDCV",
			"cLLI", "sBIT", "bKGD", "pHYs", "hIST", "sPLT", "acTL", "fcTL", "fdAT");

	/** WebP's chunks other than EXIF, XMP and unknown ones. */
	private static final Set<String> WEBP_KEPT = Set.of("VP8 ", "VP8L", "VP8X", "ALPH", "ANIM", "ANMF", "ICCP");

	// VP8X flags for the chunks dropped.
	private static final int WEBP_EXIF_AND_XMP = 0x08 | 0x04;

	/** GIF application extensions that change how it plays or looks: looping, and a colour profile. */
	private static final Set<String> GIF_APPLICATIONS_KEPT = Set.of("NETSCAPE2.0", "ANIMEXTS1.0", "ICCRGBG1012");

	private ImageMetadata() {
	}

	/**
	 * The image without its metadata.
	 * @throws IllegalArgumentException if its structure is not that of the format
	 */
	static byte[] strip(byte[] bytes, ImageFormat format) {
		try {
			return switch (format) {
				case JPEG -> jpeg(bytes);
				case PNG -> png(bytes);
				case GIF -> gif(bytes);
				case WEBP -> webp(bytes);
			};
		}
		catch (IndexOutOfBoundsException ex) {
			throw new IllegalArgumentException("The " + format + " image is truncated", ex);
		}
	}

	/**
	 * Segments up to the end of the image (EOI), less every APPn but JFIF
	 * (APP0), colour profiles (APP2 ICC_PROFILE) and Adobe's colour transform
	 * (APP14), and less comments. A scan's entropy-coded data is copied as it
	 * is, up to the next marker.
	 */
	private static byte[] jpeg(byte[] bytes) {
		if (u8(bytes, 0) != 0xFF || u8(bytes, 1) != 0xD8) {
			throw new IllegalArgumentException("No JPEG start of image");
		}
		var out = new ByteArrayOutputStream(bytes.length);
		out.write(bytes, 0, 2);
		boolean oriented = false;
		int at = 2;
		while (true) {
			if (u8(bytes, at) != 0xFF) {
				throw new IllegalArgumentException("No JPEG marker at " + at);
			}
			// Any number of 0xFF may pad a marker.
			while (u8(bytes, at) == 0xFF) {
				at++;
			}
			int marker = u8(bytes, at++);
			if (marker == EOI) {
				out.write(0xFF);
				out.write(EOI);
				return out.toByteArray();
			}
			int length = u16(bytes, at);
			if (length < 2) {
				throw new IllegalArgumentException("A JPEG segment of length " + length);
			}
			int end = at + length;
			if (end > bytes.length) {
				throw new IndexOutOfBoundsException(end);
			}
			if (kept(marker, bytes, at + 2)) {
				out.write(0xFF);
				out.write(marker);
				out.write(bytes, at, length);
			}
			else if (marker == APP1 && !oriented && startsWith(bytes, at + 2, EXIF)) {
				int orientation = orientation(bytes, at + 2 + EXIF.length, end);
				if (orientation != 1) {
					out.writeBytes(orientationOnly(orientation));
				}
				oriented = true;
			}
			at = end;
			if (marker == SOS) {
				// Entropy-coded data, up to a marker: 0xFF other than a stuffed
				// 0xFF00 or a restart marker (0xFFD0 to 0xFFD7).
				int start = at;
				while (u8(bytes, at) != 0xFF || u8(bytes, at + 1) == 0x00
						|| (u8(bytes, at + 1) >= 0xD0 && u8(bytes, at + 1) <= 0xD7)) {
					at++;
				}
				out.write(bytes, start, at - start);
			}
		}
	}

	private static boolean kept(int marker, byte[] bytes, int payload) {
		if (marker == APP2) {
			return startsWith(bytes, payload, ICC_PROFILE);
		}
		if (marker == APP0 || marker == APP14) {
			return true;
		}
		return marker != COM && (marker < APP0 || marker > APP15);
	}

	/** EXIF IFD0's orientation, 1 (as stored) when it has none or cannot be read. */
	private static int orientation(byte[] bytes, int tiff, int end) {
		try {
			var exif = ByteBuffer.wrap(bytes, tiff, end - tiff).slice();
			exif.order(exif.getShort(0) == 0x4949 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
			int ifd = exif.getInt(4);
			int entries = Short.toUnsignedInt(exif.getShort(ifd));
			for (int i = 0; i < entries; i++) {
				int entry = ifd + 2 + 12 * i;
				// A SHORT, its value in the entry's first two bytes.
				if (Short.toUnsignedInt(exif.getShort(entry)) == ORIENTATION && exif.getShort(entry + 2) == 3) {
					int orientation = Short.toUnsignedInt(exif.getShort(entry + 8));
					return orientation >= 1 && orientation <= 8 ? orientation : 1;
				}
			}
			return 1;
		}
		catch (IndexOutOfBoundsException | IllegalArgumentException ex) {
			return 1;
		}
	}

	/** An APP1 segment whose EXIF has the orientation alone. */
	private static byte[] orientationOnly(int orientation) {
		var segment = ByteBuffer.allocate(36);
		segment.put((byte) 0xFF).put((byte) APP1).putShort((short) 34).put(EXIF);
		// Big-endian TIFF, IFD0 straight after its header, with one entry.
		segment.put(ascii("MM")).putShort((short) 42).putInt(8).putShort((short) 1);
		segment.putShort((short) ORIENTATION).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
		segment.putInt(0);
		return segment.array();
	}

	/** Chunks up to IEND: every critical one, and the ancillary ones in {@link #PNG_KEPT}. */
	private static byte[] png(byte[] bytes) {
		if (!startsWith(bytes, 0, PNG_SIGNATURE)) {
			throw new IllegalArgumentException("No PNG signature");
		}
		var out = new ByteArrayOutputStream(bytes.length);
		out.write(bytes, 0, PNG_SIGNATURE.length);
		int at = PNG_SIGNATURE.length;
		while (true) {
			long length = Integer.toUnsignedLong(ByteBuffer.wrap(bytes).getInt(at));
			// Length, type, data and CRC.
			long end = at + 12 + length;
			if (end > bytes.length) {
				throw new IndexOutOfBoundsException("A PNG chunk ends past the file");
			}
			String type = new String(bytes, at + 4, 4, StandardCharsets.US_ASCII);
			// A chunk is critical when its type's first letter is upper case.
			if ((bytes[at + 4] & 0x20) == 0 || PNG_KEPT.contains(type)) {
				out.write(bytes, at, (int) (end - at));
			}
			at = (int) end;
			if (type.equals("IEND")) {
				return out.toByteArray();
			}
		}
	}

	/**
	 * The RIFF container's chunks in {@link #WEBP_KEPT}, with VP8X's EXIF and
	 * XMP flags cleared and the container's size set to what is left.
	 */
	private static byte[] webp(byte[] bytes) {
		if (!startsWith(bytes, 0, ascii("RIFF")) || !startsWith(bytes, 8, ascii("WEBP"))) {
			throw new IllegalArgumentException("No WebP header");
		}
		var little = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		long riffEnd = 8 + Integer.toUnsignedLong(little.getInt(4));
		if (riffEnd > bytes.length) {
			throw new IndexOutOfBoundsException("The WebP container ends past the file");
		}
		var out = new ByteArrayOutputStream(bytes.length);
		out.write(bytes, 0, 12);
		int flags = -1;
		int at = 12;
		while (at < riffEnd) {
			String fourcc = new String(bytes, at, 4, StandardCharsets.US_ASCII);
			long size = Integer.toUnsignedLong(little.getInt(at + 4));
			// Chunks are padded to an even size; tolerate a last one that is not.
			long end = Math.min(at + 8 + size + (size & 1), riffEnd);
			if (at + 8 + size > riffEnd) {
				throw new IndexOutOfBoundsException("A WebP chunk ends past its container");
			}
			if (WEBP_KEPT.contains(fourcc)) {
				if (fourcc.equals("VP8X")) {
					flags = out.size() + 8;
				}
				out.write(bytes, at, (int) (end - at));
			}
			at = (int) end;
		}
		byte[] stripped = out.toByteArray();
		var header = ByteBuffer.wrap(stripped).order(ByteOrder.LITTLE_ENDIAN);
		header.putInt(4, stripped.length - 8);
		if (flags >= 0) {
			stripped[flags] &= (byte) ~WEBP_EXIF_AND_XMP;
		}
		return stripped;
	}

	/**
	 * Blocks up to the trailer: images, graphic control and plain text
	 * extensions, and the application extensions in
	 * {@link #GIF_APPLICATIONS_KEPT}; no comments, XMP or others.
	 */
	private static byte[] gif(byte[] bytes) {
		if (!startsWith(bytes, 0, ascii("GIF87a")) && !startsWith(bytes, 0, ascii("GIF89a"))) {
			throw new IllegalArgumentException("No GIF header");
		}
		// The header and logical screen descriptor, then any global colour table.
		int at = 13 + colourTable(u8(bytes, 10));
		var out = new ByteArrayOutputStream(bytes.length);
		out.write(bytes, 0, at);
		while (true) {
			int block = u8(bytes, at);
			int end;
			boolean kept;
			switch (block) {
				case 0x3B -> {
					out.write(0x3B);
					return out.toByteArray();
				}
				case 0x2C -> {
					// Image descriptor, any local colour table, the LZW code size, then data.
					end = subBlocks(bytes, at + 10 + colourTable(u8(bytes, at + 9)) + 1);
					kept = true;
				}
				case 0x21 -> {
					int label = u8(bytes, at + 1);
					end = subBlocks(bytes, at + 2);
					kept = label == 0xF9 || label == 0x01 || (label == 0xFF && u8(bytes, at + 2) == 11
							&& GIF_APPLICATIONS_KEPT.contains(new String(bytes, at + 3, 11, StandardCharsets.US_ASCII)));
				}
				default -> throw new IllegalArgumentException("An unknown GIF block, " + block);
			}
			if (kept) {
				out.write(bytes, at, end - at);
			}
			at = end;
		}
	}

	private static int colourTable(int packed) {
		return (packed & 0x80) == 0 ? 0 : 3 << ((packed & 0x07) + 1);
	}

	/** Where the sub-blocks starting at {@code at} end, after their empty terminator. */
	private static int subBlocks(byte[] bytes, int at) {
		while (true) {
			int size = u8(bytes, at);
			at += 1 + size;
			if (size == 0) {
				return at;
			}
		}
	}

	private static int u8(byte[] bytes, int at) {
		return bytes[at] & 0xFF;
	}

	private static int u16(byte[] bytes, int at) {
		return (u8(bytes, at) << 8) | u8(bytes, at + 1);
	}

	private static boolean startsWith(byte[] bytes, int at, byte[] prefix) {
		if (at < 0 || bytes.length - at < prefix.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if (bytes[at + i] != prefix[i]) {
				return false;
			}
		}
		return true;
	}

	private static byte[] ascii(String value) {
		return value.getBytes(StandardCharsets.US_ASCII);
	}

}
