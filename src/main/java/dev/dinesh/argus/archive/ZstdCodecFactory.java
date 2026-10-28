package dev.dinesh.argus.archive;

import com.github.luben.zstd.Zstd;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.compression.CompressionCodecFactory;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

/**
 * ZSTD pages for Parquet, straight through zstd-jni. Parquet's default codec factory builds a
 * Hadoop {@code Configuration} first, which needs Hadoop's runtime (XML parser and all) on the
 * class path; this keeps the archive down to Hadoop's API jar. Only ZSTD is supported: the archive
 * writes nothing else. Stateless, so one instance can be shared.
 */
public final class ZstdCodecFactory implements CompressionCodecFactory {

  /** zstd's own default: about 3x faster than level 9 for a few percent less compression. */
  public static final int DEFAULT_LEVEL = 3;

  private final int level;

  public ZstdCodecFactory(int level) {
    this.level = level;
  }

  @Override
  public BytesInputCompressor getCompressor(CompressionCodecName codec) {
    requireZstd(codec);
    return new BytesInputCompressor() {
      @Override
      public BytesInput compress(BytesInput bytes) throws IOException {
        return BytesInput.from(Zstd.compress(bytes.toByteArray(), level));
      }

      @Override
      public CompressionCodecName getCodecName() {
        return CompressionCodecName.ZSTD;
      }

      @Override
      public void release() {}
    };
  }

  @Override
  public BytesInputDecompressor getDecompressor(CompressionCodecName codec) {
    requireZstd(codec);
    return new BytesInputDecompressor() {
      @Override
      public BytesInput decompress(BytesInput bytes, int uncompressedSize) throws IOException {
        return BytesInput.from(Zstd.decompress(bytes.toByteArray(), uncompressedSize));
      }

      @Override
      public void decompress(
          ByteBuffer input, int compressedSize, ByteBuffer output, int uncompressedSize)
          throws IOException {
        byte[] compressed = new byte[compressedSize];
        input.get(compressed);
        output.put(Zstd.decompress(compressed, uncompressedSize));
      }

      @Override
      public void release() {}
    };
  }

  @Override
  public void release() {}

  private static void requireZstd(CompressionCodecName codec) {
    if (codec != CompressionCodecName.ZSTD) {
      throw new UnsupportedOperationException("Only ZSTD is supported, not " + codec);
    }
  }
}
