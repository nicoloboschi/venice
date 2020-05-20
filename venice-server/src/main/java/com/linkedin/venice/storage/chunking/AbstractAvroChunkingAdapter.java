package com.linkedin.venice.storage.chunking;

import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.compression.CompressorFactory;
import com.linkedin.venice.compression.VeniceCompressor;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.listener.response.ReadResponse;
import com.linkedin.venice.meta.ReadOnlySchemaRepository;
import com.linkedin.venice.serializer.RecordDeserializer;
import com.linkedin.venice.storage.protocol.ChunkedValueManifest;
import com.linkedin.venice.store.AbstractStorageEngine;
import com.linkedin.venice.store.record.ValueRecord;
import com.linkedin.venice.utils.LatencyUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;


/**
 * Read compute and write compute chunking adapter
 */
public abstract class AbstractAvroChunkingAdapter<T> implements ChunkingAdapter<ChunkedValueInputStream, T> {
  private static final int UNUSED_INPUT_BYTES_LENGTH = -1;

  protected abstract RecordDeserializer<T> getDeserializer(String storeName, int schemaId, ReadOnlySchemaRepository schemaRepo, boolean fastAvroEnabled);

  /**
   * The default {@link DecoderFactory} will allocate 8k buffer by default for every input stream, which seems to be
   * over-kill for Venice use case.
   * Here we create a {@link DecoderFactory} with a much smaller buffer size.
   * I think the reason behind this allocation for each input stream is that today {@link BinaryDecoder} supports
   * several types of {@literal org.apache.avro.io.BinaryDecoder.ByteSource}, and the buffer of some implementation
   * can be reused, such as {@literal org.apache.avro.io.BinaryDecoder.InputStreamByteSource}, but it couldn't be
   * reused if the source is {@literal org.apache.avro.io.BinaryDecoder.ByteArrayByteSource} since the buffer is pointing
   * to the original passed array.
   * A potential improvement is to have the type check in {@literal BinaryDecoder#configure(InputStream, int)}, and
   * if both the current source and new source are {@literal org.apache.avro.io.BinaryDecoder.InputStreamByteSource},
   * we don't need to create a new buffer, but just reuse the existing buffer.
   *
   * TODO: we need to evaluate the impact of 8KB per request to Venice Server and de-serialization performance when
   * compression or chunking is enabled.
   * public static final DecoderFactory DECODER_FACTORY = new DecoderFactory().configureDecoderBufferSize(512);
   */

  @Override
  public T constructValue(
      int schemaId,
      byte[] fullBytes,
      int bytesLength,
      T reusedValue,
      BinaryDecoder reusedDecoder,
      ReadResponse response,
      CompressionStrategy compressionStrategy,
      boolean fastAvroEnabled,
      ReadOnlySchemaRepository schemaRepo,
      String storeName) {
    return getByteArrayDecoder(compressionStrategy, response).decode(
        reusedDecoder,
        fullBytes,
        bytesLength,
        reusedValue,
        compressionStrategy,
        getDeserializer(storeName, schemaId, schemaRepo, fastAvroEnabled),
        response);
  }

  @Override
  public void addChunkIntoContainer(ChunkedValueInputStream chunkedValueInputStream, int chunkIndex, byte[] valueChunk) {
    chunkedValueInputStream.setChunk(chunkIndex, valueChunk);
  }

  @Override
  public ChunkedValueInputStream constructChunksContainer(ChunkedValueManifest chunkedValueManifest) {
    return new ChunkedValueInputStream(chunkedValueManifest.keysWithChunkIdSuffix.size());
  }

  @Override
  public T constructValue(
      int schemaId,
      ChunkedValueInputStream chunkedValueInputStream,
      T reusedValue,
      BinaryDecoder reusedDecoder,
      ReadResponse response,
      CompressionStrategy compressionStrategy,
      boolean fastAvroEnabled,
      ReadOnlySchemaRepository schemaRepo,
      String storeName) {
    return getInputStreamDecoder(response).decode(
        reusedDecoder,
        chunkedValueInputStream,
        UNUSED_INPUT_BYTES_LENGTH,
        reusedValue,
        compressionStrategy,
        getDeserializer(storeName, schemaId, schemaRepo, fastAvroEnabled),
        response);
  }

  public T get(
      AbstractStorageEngine store,
      int partition,
      ByteBuffer key,
      boolean isChunked,
      T reusedValue,
      BinaryDecoder reusedDecoder,
      ReadResponse response,
      CompressionStrategy compressionStrategy,
      boolean fastAvroEnabled,
      ReadOnlySchemaRepository schemaRepo,
      String storeName) {
    if (isChunked) {
      key = ByteBuffer.wrap(ChunkingUtils.KEY_WITH_CHUNKING_SUFFIX_SERIALIZER.serializeNonChunkedKey(key));
    }
    return ChunkingUtils.getFromStorage(this, store, partition, key, response, reusedValue,
        reusedDecoder, compressionStrategy, fastAvroEnabled, schemaRepo, storeName);
  }

  public T get(
      String storeName,
      AbstractStorageEngine store,
      int partition,
      byte[] key,
      ByteBuffer reusedRawValue,
      T reusedValue,
      BinaryDecoder reusedDecoder,
      boolean isChunked,
      CompressionStrategy compressionStrategy,
      boolean fastAvroEnabled,
      ReadOnlySchemaRepository schemaRepo,
      ReadResponse response) {
    if (isChunked) {
      key = ChunkingUtils.KEY_WITH_CHUNKING_SUFFIX_SERIALIZER.serializeNonChunkedKey(key);
    }
    return ChunkingUtils.getFromStorage(this, store, partition, key, reusedRawValue, reusedValue,
        reusedDecoder, response, compressionStrategy, fastAvroEnabled, schemaRepo, storeName);
  }

  private final DecoderWrapper<byte[], T> byteArrayDecoder =
      (reusedDecoder, bytes, inputBytesLength, reusedValue, compressionStrategy, deserializer, readResponse) ->
          deserializer.deserialize(
              reusedValue,
              DecoderFactory.defaultFactory().createBinaryDecoder(
                  bytes,
                  ValueRecord.SCHEMA_HEADER_LENGTH,
                  inputBytesLength - ValueRecord.SCHEMA_HEADER_LENGTH,
                  reusedDecoder));

  private final DecoderWrapper<InputStream, T> decompressingInputStreamDecoder =
      (reusedDecoder, inputStream, inputBytesLength, reusedValue, compressionStrategy, deserializer, readResponse) -> {
        VeniceCompressor compressor = CompressorFactory.getCompressor(compressionStrategy);
        try (InputStream decompressedInputStream = compressor.decompress(inputStream)) {
          BinaryDecoder decoder = DecoderFactory.defaultFactory().createBinaryDecoder(decompressedInputStream, reusedDecoder);
          return deserializer.deserialize(reusedValue, decoder);
        } catch (IOException e) {
          throw new VeniceException("Failed to decompress, compressionStrategy: " + compressionStrategy.name(), e);
        }
      };

  private final DecoderWrapper<byte[], T> decompressingByteArrayDecoder =
      (reusedDecoder, bytes, inputBytesLength, reusedValue, compressionStrategy, deserializer, readResponse) -> {
        InputStream inputStream = new VeniceByteArrayInputStream(
            bytes,
            ValueRecord.SCHEMA_HEADER_LENGTH,
            inputBytesLength - ValueRecord.SCHEMA_HEADER_LENGTH);

        return decompressingInputStreamDecoder.decode(reusedDecoder, inputStream, inputBytesLength, reusedValue, compressionStrategy,
            deserializer, readResponse);
      };

  private final DecoderWrapper<byte[], T> instrumentedByteArrayDecoder =
      new InstrumentedDecoderWrapper<>(byteArrayDecoder);

  private final DecoderWrapper<byte[], T> instrumentedDecompressingByteArrayDecoder =
      new InstrumentedDecoderWrapper(decompressingByteArrayDecoder);

  private final DecoderWrapper<InputStream, T> instrumentedDecompressingInputStreamDecoder =
      new InstrumentedDecoderWrapper(decompressingInputStreamDecoder);

  private DecoderWrapper<byte[], T> getByteArrayDecoder(CompressionStrategy compressionStrategy, ReadResponse response) {
    if (compressionStrategy == CompressionStrategy.NO_OP) {
      return (null == response)
          ? byteArrayDecoder
          : instrumentedByteArrayDecoder;
    } else {
      return (null == response)
          ? decompressingByteArrayDecoder
          : instrumentedDecompressingByteArrayDecoder;
    }
  }

  private DecoderWrapper<InputStream, T> getInputStreamDecoder(ReadResponse response) {
    return (null == response)
        ? decompressingInputStreamDecoder
        : instrumentedDecompressingInputStreamDecoder;
  }

  private interface DecoderWrapper<INPUT, OUTPUT> {
    OUTPUT decode(
        BinaryDecoder reusedDecoder,
        INPUT input,
        int inputBytesLength,
        OUTPUT reusedValue,
        CompressionStrategy compressionStrategy,
        RecordDeserializer<OUTPUT> deserializer,
        ReadResponse response);
  }

  private class InstrumentedDecoderWrapper<INPUT, OUTPUT> implements DecoderWrapper<INPUT, OUTPUT> {
    final private DecoderWrapper<INPUT, OUTPUT> delegate;

    InstrumentedDecoderWrapper(DecoderWrapper<INPUT, OUTPUT> delegate) {
      this.delegate = delegate;
    }

    public OUTPUT decode(
        BinaryDecoder reusedDecoder,
        INPUT input,
        int inputBytesLength,
        OUTPUT reusedValue,
        CompressionStrategy compressionStrategy,
        RecordDeserializer<OUTPUT> deserializer,
        ReadResponse response) {
      long deserializeStartTimeInNS = System.nanoTime();
      OUTPUT output = delegate.decode(reusedDecoder, input, inputBytesLength, reusedValue, compressionStrategy, deserializer, response);
      response.addReadComputeDeserializationLatency(LatencyUtils.getLatencyInMS(deserializeStartTimeInNS));
      return output;
    }
  }
}