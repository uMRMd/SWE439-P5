/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.netty5.handler.codec.http2;

import io.netty5.buffer.Buffer;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.embedded.EmbeddedChannel;
import io.netty5.handler.codec.ByteToMessageDecoder;
import io.netty5.handler.codec.compression.Brotli;
import io.netty5.handler.codec.compression.BrotliCompressor;
import io.netty5.handler.codec.compression.BrotliOptions;
import io.netty5.handler.codec.compression.CompressionOptions;
import io.netty5.handler.codec.compression.Compressor;
import io.netty5.handler.codec.compression.DeflateOptions;
import io.netty5.handler.codec.compression.GzipOptions;
import io.netty5.handler.codec.compression.StandardCompressionOptions;
import io.netty5.handler.codec.compression.ZlibCompressor;
import io.netty5.handler.codec.compression.ZlibWrapper;
import io.netty5.handler.codec.compression.ZstdCompressor;
import io.netty5.handler.codec.compression.ZstdOptions;
import io.netty5.handler.codec.compression.SnappyCompressor;
import io.netty5.handler.codec.compression.SnappyOptions;
import io.netty5.handler.codec.http2.headers.Http2Headers;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.Promise;
import io.netty5.util.concurrent.PromiseCombiner;
import io.netty5.util.internal.ObjectUtil;
import io.netty5.util.internal.UnstableApi;

import static io.netty5.handler.codec.http.HttpHeaderNames.CONTENT_ENCODING;
import static io.netty5.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty5.handler.codec.http.HttpHeaderValues.BR;
import static io.netty5.handler.codec.http.HttpHeaderValues.DEFLATE;
import static io.netty5.handler.codec.http.HttpHeaderValues.GZIP;
import static io.netty5.handler.codec.http.HttpHeaderValues.IDENTITY;
import static io.netty5.handler.codec.http.HttpHeaderValues.SNAPPY;
import static io.netty5.handler.codec.http.HttpHeaderValues.X_DEFLATE;
import static io.netty5.handler.codec.http.HttpHeaderValues.X_GZIP;
import static io.netty5.handler.codec.http.HttpHeaderValues.ZSTD;
import static java.util.Objects.requireNonNull;


/**
 * A decorating HTTP2 encoder that will compress data frames according to the {@code content-encoding} header for each
 * stream. The compression provided by this class will be applied to the data for the entire stream.
 */
@UnstableApi
public class CompressorHttp2ConnectionEncoder extends DecoratingHttp2ConnectionEncoder {
    private final Http2Connection.PropertyKey propertyKey;

    private final boolean supportsCompressionOptions;

    private BrotliOptions brotliOptions;
    private GzipOptions gzipCompressionOptions;
    private DeflateOptions deflateOptions;
    private ZstdOptions zstdOptions;
    private SnappyOptions snappyOptions;

    /**
     * Create a new {@link CompressorHttp2ConnectionEncoder} instance
     * with default implementation of {@link StandardCompressionOptions}
     */
    public CompressorHttp2ConnectionEncoder(Http2ConnectionEncoder delegate) {
        this(delegate, defaultCompressionOptions());
    }

    /**
     * Create a new {@link CompressorHttp2ConnectionEncoder} with
     * specified {@link StandardCompressionOptions}
     */
    public CompressorHttp2ConnectionEncoder(Http2ConnectionEncoder delegate,
                                            CompressionOptions... compressionOptionsArgs) {
        super(delegate);
        requireNonNull(compressionOptionsArgs, "CompressionOptions");
        ObjectUtil.deepCheckNotNull("CompressionOptions", compressionOptionsArgs);

        for (CompressionOptions compressionOptions : compressionOptionsArgs) {
            // BrotliOptions' class initialization depends on Brotli classes being on the classpath.
            // The Brotli.isAvailable check ensures that BrotliOptions will only get instantiated if Brotli is on
            // the classpath.
            // This results in the static analysis of native-image identifying the instanceof BrotliOptions check
            // and thus BrotliOptions itself as unreachable, enabling native-image to link all classes at build time
            // and not complain about the missing Brotli classes.
            if (Brotli.isAvailable() && compressionOptions instanceof BrotliOptions) {
                brotliOptions = (BrotliOptions) compressionOptions;
            } else if (compressionOptions instanceof GzipOptions) {
                gzipCompressionOptions = (GzipOptions) compressionOptions;
            } else if (compressionOptions instanceof DeflateOptions) {
                deflateOptions = (DeflateOptions) compressionOptions;
            } else if (compressionOptions instanceof ZstdOptions) {
                zstdOptions = (ZstdOptions) compressionOptions;
            } else if (compressionOptions instanceof SnappyOptions) {
                snappyOptions = (SnappyOptions) compressionOptions;
            } else {
                throw new IllegalArgumentException("Unsupported " + CompressionOptions.class.getSimpleName() +
                        ": " + compressionOptions);
            }
        }

        supportsCompressionOptions = true;

        propertyKey = connection().newKey();
        connection().addListener(new Http2ConnectionAdapter() {
            @Override
            public void onStreamRemoved(Http2Stream stream) {
                final Compressor compressor = stream.getProperty(propertyKey);
                if (compressor != null) {
                    cleanup(stream, compressor);
                }
            }
        });
    }

    private static CompressionOptions[] defaultCompressionOptions() {
        if (Brotli.isAvailable()) {
            return new CompressionOptions[] {
                    StandardCompressionOptions.brotli(),
                    StandardCompressionOptions.snappy(),
                    StandardCompressionOptions.gzip(),
                    StandardCompressionOptions.deflate() };
        }
        return new CompressionOptions[] {
                StandardCompressionOptions.snappy(),
                StandardCompressionOptions.gzip(),
                StandardCompressionOptions.deflate()
        };
    }

    @Override
    public Future<Void> writeData(final ChannelHandlerContext ctx, final int streamId, Buffer data, int padding,
                                  final boolean endOfStream) {
        final Http2Stream stream = connection().stream(streamId);
        final Compressor compressor = stream == null ? null : (Compressor) stream.getProperty(propertyKey);
        if (compressor == null) {
            // The compressor may be null if no compatible encoding type was found in this stream's headers
            return super.writeData(ctx, streamId, data, padding, endOfStream);
        }

        // The ownership is not transferred to "Compressor.compress"
        try (data) {
            Buffer buf = compressor.compress(data, ctx.bufferAllocator());
            if (buf.readableBytes() == 0) {
                buf.close();
                if (endOfStream) {
                    buf = compressor.finish(ctx.bufferAllocator());
                    return super.writeData(ctx, streamId, buf, padding,
                            true);
                }
                // END_STREAM is not set and the assumption is data is still forthcoming.
                return ctx.newSucceededFuture();
            }

            Future<Void> future = super.writeData(ctx, streamId, buf, padding, false);
            if (endOfStream) {
                Promise<Void> promise = ctx.newPromise();
                PromiseCombiner combiner = new PromiseCombiner(ctx.executor());
                combiner.add(future);

                buf = compressor.finish(ctx.bufferAllocator());

                // Padding is only communicated once on the first iteration
                future = super.writeData(ctx, streamId, buf, 0, true);
                combiner.add(future);
                combiner.finish(promise);
                return promise.asFuture();
            }
            return future;

        } catch (Throwable cause) {
            return ctx.newFailedFuture(cause);
        } finally {
            if (endOfStream) {
                cleanup(stream, compressor);
            }
        }
    }

    @Override
    public Future<Void> writeHeaders(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding,
                                     boolean endStream) {
        try {
            // Determine if compression is required and sanitize the headers.
            Compressor compressor = newCompressor(ctx, headers, endStream);

            // Write the headers and create the stream object.
            Future<Void> future = super.writeHeaders(ctx, streamId, headers, padding, endStream);

            // After the stream object has been created, then attach the compressor as a property for data compression.
            bindCompressorToStream(compressor, streamId);

            return future;
        } catch (Throwable e) {
            return ctx.newFailedFuture(e);
        }
    }

    @Override
    public Future<Void> writeHeaders(final ChannelHandlerContext ctx, final int streamId, final Http2Headers headers,
            final int streamDependency, final short weight, final boolean exclusive, final int padding,
            final boolean endOfStream) {
        try {
            // Determine if compression is required and sanitize the headers.
            Compressor compressor = newCompressor(ctx, headers, endOfStream);

            // Write the headers and create the stream object.
            Future<Void> future = super.writeHeaders(ctx, streamId, headers, streamDependency, weight, exclusive,
                                                      padding, endOfStream);

            // After the stream object has been created, then attach the compressor as a property for data compression.
            bindCompressorToStream(compressor, streamId);

            return future;
        } catch (Throwable e) {
            return ctx.newFailedFuture(e);
        }
    }

    /**
     * Returns a new {@link Compressor} that encodes the HTTP2 message content encoded in the specified
     * {@code contentEncoding}.
     *
     * @param ctx the context.
     * @param contentEncoding the value of the {@code content-encoding} header
     * @return a new {@link ByteToMessageDecoder} if the specified encoding is supported.
     * Otherwise {@code null}.
     * Alternatively, you can throw a {@link Http2Exception} to block unknown encoding.
     * @throws Http2Exception If the specified encoding is not supported and warrants an exception
     */
    protected Compressor newContentCompressor(ChannelHandlerContext ctx, CharSequence contentEncoding)
            throws Http2Exception {
        if (GZIP.contentEqualsIgnoreCase(contentEncoding) || X_GZIP.contentEqualsIgnoreCase(contentEncoding)) {
            return newCompressionChannel(ctx, ZlibWrapper.GZIP);
        }
        if (DEFLATE.contentEqualsIgnoreCase(contentEncoding) || X_DEFLATE.contentEqualsIgnoreCase(contentEncoding)) {
            return newCompressionChannel(ctx, ZlibWrapper.ZLIB);
        }
        if (Brotli.isAvailable() && brotliOptions != null && BR.contentEqualsIgnoreCase(contentEncoding)) {
            return BrotliCompressor.newFactory(brotliOptions.parameters()).get();
        }
        if (zstdOptions != null && ZSTD.contentEqualsIgnoreCase(contentEncoding)) {
            return ZstdCompressor.newFactory(zstdOptions.compressionLevel(),
                    zstdOptions.blockSize(), zstdOptions.maxEncodeSize()).get();
        }
        if (snappyOptions != null && SNAPPY.contentEqualsIgnoreCase(contentEncoding)) {
            return SnappyCompressor.newFactory().get();
        }
        // 'identity' or unsupported
        return null;
    }

    /**
     * Returns the expected content encoding of the decoded content. Returning {@code contentEncoding} is the default
     * behavior, which is the case for most compressors.
     *
     * @param contentEncoding the value of the {@code content-encoding} header
     * @return the expected content encoding of the new content.
     * @throws Http2Exception if the {@code contentEncoding} is not supported and warrants an exception
     */
    protected CharSequence getTargetContentEncoding(CharSequence contentEncoding) throws Http2Exception {
        return contentEncoding;
    }

    /**
     * Generate a new instance of an {@link Compressor} capable of compressing data
     * @param ctx the context.
     * @param wrapper Defines what type of encoder should be used
     */
    private Compressor newCompressionChannel(final ChannelHandlerContext ctx, ZlibWrapper wrapper) {
        if (supportsCompressionOptions) {
            if (wrapper == ZlibWrapper.GZIP && gzipCompressionOptions != null) {
                return ZlibCompressor.newFactory(wrapper, gzipCompressionOptions.compressionLevel()).get();
            } else if (wrapper == ZlibWrapper.ZLIB && deflateOptions != null) {
                return ZlibCompressor.newFactory(wrapper, deflateOptions.compressionLevel()).get();
            } else {
                throw new IllegalArgumentException("Unsupported ZlibWrapper: " + wrapper);
            }
        }
        return ZlibCompressor.newFactory(wrapper).get();
    }

    /**
     * Checks if a new compressor object is needed for the stream identified by {@code streamId}. This method will
     * modify the {@code content-encoding} header contained in {@code headers}.
     *
     * @param ctx the context.
     * @param headers Object representing headers which are to be written
     * @param endOfStream Indicates if the stream has ended
     * @return The channel used to compress data.
     * @throws Http2Exception if any problems occur during initialization.
     */
    private Compressor newCompressor(ChannelHandlerContext ctx, Http2Headers headers, boolean endOfStream)
            throws Http2Exception {
        if (endOfStream) {
            return null;
        }

        CharSequence encoding = headers.get(CONTENT_ENCODING);
        if (encoding == null) {
            encoding = IDENTITY;
        }
        final Compressor compressor = newContentCompressor(ctx, encoding);
        if (compressor != null) {
            CharSequence targetContentEncoding = getTargetContentEncoding(encoding);
            if (IDENTITY.contentEqualsIgnoreCase(targetContentEncoding)) {
                headers.remove(CONTENT_ENCODING);
            } else {
                headers.set(CONTENT_ENCODING, targetContentEncoding);
            }

            // The content length will be for the decompressed data. Since we will compress the data
            // this content-length will not be correct. Instead of queuing messages or delaying sending
            // header frames...just remove the content-length header
            headers.remove(CONTENT_LENGTH);
        }

        return compressor;
    }

    /**
     * Called after the super class has written the headers and created any associated stream objects.
     * @param compressor The compressor associated with the stream identified by {@code streamId}.
     * @param streamId The stream id for which the headers were written.
     */
    private void bindCompressorToStream(Compressor compressor, int streamId) {
        if (compressor != null) {
            Http2Stream stream = connection().stream(streamId);
            if (stream != null) {
                stream.setProperty(propertyKey, compressor);
            }
        }
    }

    /**
     * Release remaining content from {@link EmbeddedChannel} and remove the compressor from the {@link Http2Stream}.
     *
     * @param stream The stream for which {@code compressor} is the compressor for
     * @param compressor The compressor for {@code stream}
     */
    void cleanup(Http2Stream stream, Compressor compressor) {
        compressor.close();
        stream.removeProperty(propertyKey);
    }
}
