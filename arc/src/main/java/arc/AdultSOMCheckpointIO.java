package arc;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * AdultSOM checkpoints: - JSON manifest (self-describing, small) - Binary
 * weights (fast/small)
 *
 * weights shape: [h][w][dim] binary order: row-major y then x then dim
 * endianness: little-endian
 */
public final class AdultSOMCheckpointIO {

	private AdultSOMCheckpointIO() {
	}

	public static final int VERSION = 1;

	// ----------------------------
	// Public convenience: write both files per run
	// ----------------------------
	public static void writeCheckpointPair(AdultSOM adult, File outDir, String baseName, long createdAtMs,
			boolean clamped01) throws IOException {

		if (outDir == null)
			outDir = new File(".");
		if (!outDir.exists() && !outDir.mkdirs())
			throw new IOException("Could not create dir: " + outDir.getAbsolutePath());

		File bin = new File(outDir, baseName + ".bin");
		File json = new File(outDir, baseName + ".json");

		writeBinary(adult, bin, createdAtMs, clamped01);

		// Optional: hash the bin to detect mismatch/corruption
		String sha256 = sha256Hex(bin);

		writeManifestJson(adult, json, createdAtMs, clamped01, bin.getName(), sha256);
	}

	// ----------------------------
	// Read pair (manifest -> binary)
	// ----------------------------
	public static double[][][] readCheckpointPair(File manifestJson, File binDir) throws IOException {
		Manifest m = readManifestJson(manifestJson);

		File bin = new File(binDir == null ? manifestJson.getParentFile() : binDir, m.binaryFile);
		if (!bin.exists())
			throw new IOException("Binary weights file not found: " + bin.getAbsolutePath());

		// If manifest had a hash, we can verify (optional hard fail)
		if (m.sha256 != null && !m.sha256.isBlank()) {
			String actual = sha256Hex(bin);
			if (!actual.equalsIgnoreCase(m.sha256)) {
				throw new IOException(
						"SHA256 mismatch for " + bin.getName() + " expected=" + m.sha256 + " actual=" + actual);
			}
		}

		return readBinary(bin, m.w, m.h, m.dim);
	}

	// ----------------------------
	// JSON MANIFEST (small, stable)
	// ----------------------------
	private static void writeManifestJson(AdultSOM adult, File outFile, long createdAtMs, boolean clamped01,
			String binaryFileName, String sha256) throws IOException {

		String meta = "order=row-major;y,x,dim;endian=little;clamp=" + (clamped01 ? "0..1" : "none");

		try (BufferedWriter bw = new BufferedWriter(new FileWriter(outFile))) {
			bw.write("{\n");
			bw.write("  \"type\": \"AdultSOM\",\n");
			bw.write("  \"version\": " + VERSION + ",\n");
			bw.write("  \"createdAtMs\": " + createdAtMs + ",\n");
			bw.write("  \"w\": " + adult.getW() + ",\n");
			bw.write("  \"h\": " + adult.getH() + ",\n");
			bw.write("  \"dim\": " + adult.getDim() + ",\n");
			bw.write("  \"order\": \"row-major\",\n");
			bw.write("  \"endian\": \"little\",\n");
			bw.write("  \"clamped01\": " + (clamped01 ? "true" : "false") + ",\n");
			bw.write("  \"meta\": \"" + escapeJson(meta) + "\",\n");
			bw.write("  \"binaryFile\": \"" + escapeJson(binaryFileName) + "\",\n");
			bw.write("  \"sha256\": \"" + escapeJson(sha256) + "\"\n");
			bw.write("}\n");
		}
	}

	/**
	 * Minimal manifest reader: expects the exact keys we write (no external JSON
	 * libs).
	 */
	private static Manifest readManifestJson(File f) throws IOException {
		String s = readAllUtf8(f);

		Manifest m = new Manifest();
		m.type = pickString(s, "type");
		m.version = (int) pickLong(s, "version");
		m.createdAtMs = pickLong(s, "createdAtMs");
		m.w = (int) pickLong(s, "w");
		m.h = (int) pickLong(s, "h");
		m.dim = (int) pickLong(s, "dim");
		m.order = pickString(s, "order");
		m.endian = pickString(s, "endian");
		m.clamped01 = pickBoolean(s, "clamped01");
		m.meta = pickString(s, "meta");
		m.binaryFile = pickString(s, "binaryFile");
		m.sha256 = pickString(s, "sha256");

		if (m.version != VERSION)
			throw new IOException("Unsupported manifest version: " + m.version);
		if (!"AdultSOM".equals(m.type))
			throw new IOException("Manifest type mismatch: " + m.type);
		if (m.w <= 0 || m.h <= 0 || m.dim <= 0)
			throw new IOException("Bad dimensions in manifest.");
		if (!"row-major".equalsIgnoreCase(m.order))
			throw new IOException("Unsupported order: " + m.order);
		if (!"little".equalsIgnoreCase(m.endian))
			throw new IOException("Unsupported endian: " + m.endian);
		if (m.binaryFile == null || m.binaryFile.isBlank())
			throw new IOException("binaryFile missing in manifest.");

		return m;
	}

	private static final class Manifest {
		String type;
		int version;
		long createdAtMs;
		int w, h, dim;
		String order;
		String endian;
		boolean clamped01;
		String meta;
		String binaryFile;
		String sha256;
	}

	// Super small “parser” helpers (string search by key)
	private static String pickString(String json, String key) throws IOException {
		String pat = "\"" + key + "\"";
		int i = json.indexOf(pat);
		if (i < 0)
			return null;
		int c = json.indexOf(':', i);
		if (c < 0)
			throw new IOException("Bad json near key: " + key);
		int q1 = json.indexOf('"', c + 1);
		if (q1 < 0)
			return null;
		int q2 = json.indexOf('"', q1 + 1);
		while (q2 > 0 && json.charAt(q2 - 1) == '\\')
			q2 = json.indexOf('"', q2 + 1);
		if (q2 < 0)
			throw new IOException("Unterminated string for key: " + key);
		return unescapeJson(json.substring(q1 + 1, q2));
	}

	private static long pickLong(String json, String key) throws IOException {
		String pat = "\"" + key + "\"";
		int i = json.indexOf(pat);
		if (i < 0)
			throw new IOException("Missing key: " + key);
		int c = json.indexOf(':', i);
		if (c < 0)
			throw new IOException("Bad json near key: " + key);
		int j = c + 1;
		while (j < json.length() && Character.isWhitespace(json.charAt(j)))
			j++;
		int k = j;
		while (k < json.length() && ("-0123456789".indexOf(json.charAt(k)) >= 0))
			k++;
		return Long.parseLong(json.substring(j, k));
	}

	private static boolean pickBoolean(String json, String key) throws IOException {
		String pat = "\"" + key + "\"";
		int i = json.indexOf(pat);
		if (i < 0)
			return false;
		int c = json.indexOf(':', i);
		if (c < 0)
			throw new IOException("Bad json near key: " + key);
		String tail = json.substring(c + 1).trim();
		return tail.startsWith("true");
	}

	private static String readAllUtf8(File f) throws IOException {
		try (InputStream is = new FileInputStream(f)) {
			return new String(is.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String escapeJson(String s) {
		if (s == null)
			return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static String unescapeJson(String s) {
		if (s == null)
			return null;
		return s.replace("\\\"", "\"").replace("\\\\", "\\");
	}

	// ----------------------------
	// BINARY WEIGHTS
	// ----------------------------
	public static void writeBinary(AdultSOM adult, File outFile, long createdAtMs, boolean clamped01)
			throws IOException {
		int w = adult.getW();
		int h = adult.getH();
		int dim = adult.getDim();

		double[][][] W = adult.getWeightsCopy(); // [h][w][dim]

		if (W.length != h)
			throw new IOException("weights[h] mismatch: " + W.length + " != " + h);
		for (int y = 0; y < h; y++)
			if (W[y].length != w)
				throw new IOException("weights[w] mismatch at y=" + y);

		String meta = "order=row-major;y,x,dim;endian=little;clamp=" + (clamped01 ? "0..1" : "none");
		byte[] metaBytes = meta.getBytes(StandardCharsets.UTF_8);

		try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(outFile))) {

			// header
			writeBytes(bos, new byte[] { 'A', 'S', 'O', 'M' });
			writeIntLE(bos, VERSION);
			writeIntLE(bos, w);
			writeIntLE(bos, h);
			writeIntLE(bos, dim);
			int flags = clamped01 ? 1 : 0;
			writeIntLE(bos, flags);
			writeLongLE(bos, createdAtMs);
			writeIntLE(bos, metaBytes.length);
			writeBytes(bos, metaBytes);

			// weights: h*w*dim doubles
			final int DOUBLES_PER_CHUNK = 4096;
			byte[] buf = new byte[8 * DOUBLES_PER_CHUNK];
			ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);

			int inBuf = 0;

			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					double[] v = W[y][x];
					if (v.length != dim)
						throw new IOException("dim mismatch at (" + x + "," + y + "): " + v.length + " != " + dim);

					for (int d = 0; d < dim; d++) {
						bb.putDouble(v[d]);
						inBuf++;

						if (inBuf == DOUBLES_PER_CHUNK) {
							bos.write(buf, 0, 8 * DOUBLES_PER_CHUNK);
							bb.clear();
							inBuf = 0;
						}
					}
				}
			}

			if (inBuf > 0) {
				bos.write(buf, 0, 8 * inBuf);
			}
		}
	}

	/** Reads weights from a bin file (expects the same format we write). */
	public static double[][][] readBinary(File binFile, int expectedW, int expectedH, int expectedDim)
			throws IOException {
		try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(binFile))) {

			byte[] magic = bis.readNBytes(4);
			if (magic.length != 4 || magic[0] != 'A' || magic[1] != 'S' || magic[2] != 'O' || magic[3] != 'M') {
				throw new IOException("Bad magic (not ASOM): " + binFile.getName());
			}

			int version = readIntLE(bis);
			if (version != VERSION)
				throw new IOException("Unsupported bin version: " + version);

			int w = readIntLE(bis);
			int h = readIntLE(bis);
			int dim = readIntLE(bis);
			int flags = readIntLE(bis);
			long createdAtMs = readLongLE(bis);
			int metaLen = readIntLE(bis);
			byte[] metaBytes = bis.readNBytes(metaLen);

			if (w != expectedW || h != expectedH || dim != expectedDim) {
				throw new IOException("Dim mismatch: file=" + w + "x" + h + " dim=" + dim + " expected=" + expectedW
						+ "x" + expectedH + " dim=" + expectedDim);
			}

			// weights
			double[][][] out = new double[h][w][dim];

			byte[] buf = new byte[8 * 4096];
			ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);

			int y = 0, x = 0, d = 0;

			while (y < h) {
				int n = bis.read(buf);
				if (n < 0)
					throw new EOFException("Unexpected EOF reading weights: " + binFile.getName());
				int doubles = n / 8;

				bb.clear();
				bb.limit(n);

				for (int i = 0; i < doubles; i++) {
					double v = bb.getDouble();
					out[y][x][d] = v;

					d++;
					if (d == dim) {
						d = 0;
						x++;
						if (x == w) {
							x = 0;
							y++;
							if (y == h)
								break;
						}
					}
				}
			}

			return out;
		}
	}

	// ----------------------------
	// Small IO helpers
	// ----------------------------
	private static void writeBytes(OutputStream os, byte[] b) throws IOException {
		os.write(b);
	}

	private static void writeIntLE(OutputStream os, int v) throws IOException {
		os.write(v & 0xFF);
		os.write((v >>> 8) & 0xFF);
		os.write((v >>> 16) & 0xFF);
		os.write((v >>> 24) & 0xFF);
	}

	private static void writeLongLE(OutputStream os, long v) throws IOException {
		os.write((int) (v & 0xFF));
		os.write((int) ((v >>> 8) & 0xFF));
		os.write((int) ((v >>> 16) & 0xFF));
		os.write((int) ((v >>> 24) & 0xFF));
		os.write((int) ((v >>> 32) & 0xFF));
		os.write((int) ((v >>> 40) & 0xFF));
		os.write((int) ((v >>> 48) & 0xFF));
		os.write((int) ((v >>> 56) & 0xFF));
	}

	private static int readIntLE(InputStream is) throws IOException {
		int b0 = is.read();
		int b1 = is.read();
		int b2 = is.read();
		int b3 = is.read();
		if ((b0 | b1 | b2 | b3) < 0)
			throw new EOFException();
		return (b0 & 0xFF) | ((b1 & 0xFF) << 8) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 24);
	}

	private static long readLongLE(InputStream is) throws IOException {
		long b0 = is.read();
		long b1 = is.read();
		long b2 = is.read();
		long b3 = is.read();
		long b4 = is.read();
		long b5 = is.read();
		long b6 = is.read();
		long b7 = is.read();
		if ((b0 | b1 | b2 | b3 | b4 | b5 | b6 | b7) < 0)
			throw new EOFException();
		return (b0 & 0xFF) | ((b1 & 0xFF) << 8) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 24) | ((b4 & 0xFF) << 32)
				| ((b5 & 0xFF) << 40) | ((b6 & 0xFF) << 48) | ((b7 & 0xFF) << 56);
	}

	public static void writeBinary(double[][][] W, int w, int h, int dim, File outFile, long createdAtMs,
			boolean clamped01) throws IOException {

		if (W.length != h)
			throw new IOException("W.h mismatch: " + W.length + " != " + h);
		for (int y = 0; y < h; y++) {
			if (W[y].length != w)
				throw new IOException("W.w mismatch at y=" + y);
			for (int x = 0; x < w; x++) {
				if (W[y][x].length != dim)
					throw new IOException("W.dim mismatch at (" + x + "," + y + ")");
			}
		}

		int flags = clamped01 ? 1 : 0;
		String meta = "order=row-major;clamp=" + (clamped01 ? "0..1" : "none");
		byte[] metaBytes = meta.getBytes(StandardCharsets.UTF_8);

		try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(outFile))) {
			writeBytes(bos, new byte[] { 'A', 'S', 'O', 'M' });
			writeIntLE(bos, 1);
			writeIntLE(bos, w);
			writeIntLE(bos, h);
			writeIntLE(bos, dim);
			writeIntLE(bos, flags);
			writeLongLE(bos, createdAtMs);
			writeIntLE(bos, metaBytes.length);
			writeBytes(bos, metaBytes);

			byte[] buf = new byte[8 * 4096];
			ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
			int countInBuf = 0;

// order: y, x, d
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					double[] v = W[y][x];
					for (int d = 0; d < dim; d++) {
						bb.putDouble(v[d]);
						countInBuf++;
						if (countInBuf == 4096) {
							bos.write(buf, 0, 8 * 4096);
							bb.clear();
							countInBuf = 0;
						}
					}
				}
			}

			if (countInBuf > 0)
				bos.write(buf, 0, 8 * countInBuf);
		}
	}

	public static void writeJsonManifest(File outFile, long createdAtMs, int w, int h, int dim, String order,
			String endian, String weightClamp, String binaryFileName) throws IOException {

		try (BufferedWriter bw = new BufferedWriter(new FileWriter(outFile))) {
			bw.write("{\n");
			bw.write("  \"type\": \"AdultSOM\",\n");
			bw.write("  \"version\": 1,\n");
			bw.write("  \"createdAtMs\": " + createdAtMs + ",\n");
			bw.write("  \"w\": " + w + ",\n");
			bw.write("  \"h\": " + h + ",\n");
			bw.write("  \"dim\": " + dim + ",\n");
			bw.write("  \"order\": \"" + escapeJson(order) + "\",\n");
			bw.write("  \"endian\": \"" + escapeJson(endian) + "\",\n");
			bw.write("  \"weightClamp\": \"" + escapeJson(weightClamp) + "\",\n");
			bw.write("  \"binaryFile\": \"" + escapeJson(binaryFileName) + "\"\n");
			bw.write("}\n");
		}
	}

	private static String sha256Hex(File f) throws IOException {
		try (InputStream is = new FileInputStream(f)) {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] buf = new byte[1024 * 64];
			int n;
			while ((n = is.read(buf)) > 0)
				md.update(buf, 0, n);
			return HexFormat.of().formatHex(md.digest());
		} catch (Exception e) {
			throw new IOException("SHA-256 failed: " + e.getMessage(), e);
		}
	}
}
