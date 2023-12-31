/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty5.handler.codec.http;

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
import io.netty5.handler.codec.compression.Zstd;
import io.netty5.handler.codec.compression.ZstdCompressor;
import io.netty5.handler.codec.compression.ZstdOptions;
import io.netty5.handler.codec.compression.SnappyCompressor;
import io.netty5.handler.codec.compression.SnappyOptions;
import io.netty5.util.internal.ObjectUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;


/**
 * Compresses an {@link HttpMessage} and an {@link HttpContent} in {@code gzip} or
 * {@code deflate} encoding while respecting the {@code "Accept-Encoding"} header.
 * If there is no matching encoding, no compression is done.  For more
 * information on how this handler modifies the message, please refer to
 * {@link HttpContentEncoder}.
 */
public class HttpContentCompressor extends HttpContentEncoder {

    private final boolean supportsCompressionOptions;
    private final BrotliOptions brotliOptions;
    private final GzipOptions gzipOptions;
    private final DeflateOptions deflateOptions;
    private final ZstdOptions zstdOptions;
    private final SnappyOptions snappyOptions;

    private final int compressionLevel;
    private final int contentSizeThreshold;
    private final Map<String, Supplier<? extends Compressor>> factories;

    /**
     * Creates a new handler with the default compression level (<tt>6</tt>),
     * default window size (<tt>15</tt>) and default memory level (<tt>8</tt>).
     */
    public HttpContentCompressor() {
        this(6);
    }

    /**
     * Creates a new handler with the specified compression level.
     *
     * @param compressionLevel
     *        {@code 1} yields the fastest compression and {@code 9} yields the
     *        best compression.  {@code 0} means no compression.  The default
     *        compression level is {@code 6}.
     */
    @Deprecated
    public HttpContentCompressor(int compressionLevel) {
        this(compressionLevel, 0);
    }

    /**
     * Creates a new handler with the specified compression level, window size,
     * and memory level..
     *
     * @param compressionLevel
     *        {@code 1} yields the fastest compression and {@code 9} yields the
     *        best compression.  {@code 0} means no compression.  The default
     *        compression level is {@code 6}.
     * @param contentSizeThreshold
     *        The response body is compressed when the size of the response
     *        body exceeds the threshold. The value should be a non negative
     *        number. {@code 0} will enable compression for all responses.
     */
    @Deprecated
    public HttpContentCompressor(int compressionLevel, int contentSizeThreshold) {
        this.compressionLevel = ObjectUtil.checkInRange(compressionLevel, 0, 9, "compressionLevel");
        this.contentSizeThreshold = ObjectUtil.checkPositiveOrZero(contentSizeThreshold, "contentSizeThreshold");
        this.brotliOptions = null;
        this.gzipOptions = null;
        this.deflateOptions = null;
        this.zstdOptions = null;
        this.snappyOptions = null;
        this.factories = null;
        this.supportsCompressionOptions = false;
    }

    /**
     * Create a new {@link HttpContentCompressor} Instance with specified
     * {@link CompressionOptions}s and contentSizeThreshold set to {@code 0}
     *
     * @param compressionOptions {@link CompressionOptions} or {@code null} if the default
     *        should be used.
     */
    public HttpContentCompressor(CompressionOptions... compressionOptions) {
        this(0, compressionOptions);
    }

    /**
     * Create a new {@link HttpContentCompressor} instance with specified
     * {@link CompressionOptions}s
     *
     * @param contentSizeThreshold
     *        The response body is compressed when the size of the response
     *        body exceeds the threshold. The value should be a non negative
     *        number. {@code 0} will enable compression for all responses.
     * @param compressionOptions {@link CompressionOptions} or {@code null}
     *        if the default should be used.
     */
    public HttpContentCompressor(int contentSizeThreshold, CompressionOptions... compressionOptions) {
        this.contentSizeThreshold = ObjectUtil.checkPositiveOrZero(contentSizeThreshold, "contentSizeThreshold");
        BrotliOptions brotliOptions = null;
        GzipOptions gzipOptions = null;
        DeflateOptions deflateOptions = null;
        ZstdOptions zstdOptions = null;
        SnappyOptions snappyOptions = null;
        if (compressionOptions == null || compressionOptions.length == 0) {
            brotliOptions = Brotli.isAvailable() ? StandardCompressionOptions.brotli() : null;
            gzipOptions = StandardCompressionOptions.gzip();
            deflateOptions = StandardCompressionOptions.deflate();
            zstdOptions = Zstd.isAvailable() ? StandardCompressionOptions.zstd() : null;
            snappyOptions = StandardCompressionOptions.snappy();
        } else {
            ObjectUtil.deepCheckNotNull("compressionOptions", compressionOptions);
            for (CompressionOptions compressionOption : compressionOptions) {
                // BrotliOptions' class initialization depends on Brotli classes being on the classpath.
                // The Brotli.isAvailable check ensures that BrotliOptions will only get instantiated if Brotli is
                // on the classpath.
                // This results in the static analysis of native-image identifying the instanceof BrotliOptions check
                // and thus BrotliOptions itself as unreachable, enabling native-image to link all classes
                // at build time and not complain about the missing Brotli classes.
                if (Brotli.isAvailable() && compressionOption instanceof BrotliOptions) {
                    brotliOptions = (BrotliOptions) compressionOption;
                } else if (compressionOption instanceof GzipOptions) {
                    gzipOptions = (GzipOptions) compressionOption;
                } else if (compressionOption instanceof DeflateOptions) {
                    deflateOptions = (DeflateOptions) compressionOption;
                } else if (compressionOption instanceof ZstdOptions) {
                    zstdOptions = (ZstdOptions) compressionOption;
                } else if (compressionOption instanceof SnappyOptions) {
                    snappyOptions = (SnappyOptions) compressionOption;
                } else {
                    throw new IllegalArgumentException("Unsupported " + CompressionOptions.class.getSimpleName() +
                            ": " + compressionOption);
                }
            }
        }

        this.gzipOptions = gzipOptions;
        this.deflateOptions = deflateOptions;
        this.brotliOptions = brotliOptions;
        this.zstdOptions = zstdOptions;
        this.snappyOptions = snappyOptions;

        factories = new HashMap<>();

        if (this.gzipOptions != null) {
            factories.put("gzip", ZlibCompressor.newFactory(
                    ZlibWrapper.GZIP, gzipOptions.compressionLevel()));
        }
        if (this.deflateOptions != null) {
            factories.put("deflate", ZlibCompressor.newFactory(
                    ZlibWrapper.ZLIB, deflateOptions.compressionLevel()));
        }
        if (this.snappyOptions != null) {
            this.factories.put("snappy", SnappyCompressor.newFactory());
        }

        if (Brotli.isAvailable() && this.brotliOptions != null) {
            factories.put("br", BrotliCompressor.newFactory(brotliOptions.parameters()));
        }
        if (this.zstdOptions != null) {
            factories.put("zstd", ZstdCompressor.newFactory(zstdOptions.compressionLevel(),
                    zstdOptions.blockSize(), zstdOptions.maxEncodeSize()));
        }

        compressionLevel = -1;
        supportsCompressionOptions = true;
    }

    @Override
    protected Result beginEncode(HttpResponse httpResponse, String acceptEncoding) {
        if (contentSizeThreshold > 0) {
            if (httpResponse instanceof HttpContent &&
                    ((HttpContent<?>) httpResponse).payload().readableBytes() < contentSizeThreshold) {
                return null;
            }
        }

        CharSequence contentEncoding = httpResponse.headers().get(HttpHeaderNames.CONTENT_ENCODING);
        if (contentEncoding != null) {
            // Content-Encoding was set, either as something specific or as the IDENTITY encoding
            // Therefore, we should NOT encode here
            return null;
        }

        if (supportsCompressionOptions) {
            String targetContentEncoding = determineEncoding(acceptEncoding);
            if (targetContentEncoding == null) {
                return null;
            }

            Supplier<? extends Compressor> compressorFactory = factories.get(targetContentEncoding);

            if (compressorFactory == null) {
                throw new Error();
            }

            return new Result(targetContentEncoding, compressorFactory.get());
        } else {
            ZlibWrapper wrapper = determineWrapper(acceptEncoding);
            if (wrapper == null) {
                return null;
            }

            String targetContentEncoding;
            switch (wrapper) {
                case GZIP:
                    targetContentEncoding = "gzip";
                    break;
                case ZLIB:
                    targetContentEncoding = "deflate";
                    break;
                default:
                    throw new Error();
            }

            return new Result(
                    targetContentEncoding, ZlibCompressor.newFactory(wrapper, compressionLevel).get());
        }
    }

    @SuppressWarnings("FloatingPointEquality")
    protected String determineEncoding(String acceptEncoding) {
        float starQ = -1.0f;
        float brQ = -1.0f;
        float zstdQ = -1.0f;
        float snappyQ = -1.0f;
        float gzipQ = -1.0f;
        float deflateQ = -1.0f;
        for (String encoding : acceptEncoding.split(",")) {
            float q = 1.0f;
            int equalsPos = encoding.indexOf('=');
            if (equalsPos != -1) {
                try {
                    q = Float.parseFloat(encoding.substring(equalsPos + 1));
                } catch (NumberFormatException e) {
                    // Ignore encoding
                    q = 0.0f;
                }
            }
            if (encoding.contains("*")) {
                starQ = q;
            } else if (encoding.contains("br") && q > brQ) {
                brQ = q;
            } else if (encoding.contains("zstd") && q > zstdQ) {
                zstdQ = q;
            } else if (encoding.contains("snappy") && q > snappyQ) {
                snappyQ = q;
            } else if (encoding.contains("gzip") && q > gzipQ) {
                gzipQ = q;
            } else if (encoding.contains("deflate") && q > deflateQ) {
                deflateQ = q;
            }
        }
        if (brQ > 0.0f || zstdQ > 0.0f || snappyQ > 0.0f || gzipQ > 0.0f || deflateQ > 0.0f) {
            if (brQ != -1.0f && brQ >= zstdQ && this.brotliOptions != null) {
                return "br";
            } else if (zstdQ != -1.0f && zstdQ >= snappyQ && this.zstdOptions != null) {
                return "zstd";
            } else if (snappyQ != -1.0f && snappyQ >= gzipQ && this.snappyOptions != null) {
                return "snappy";
            } else if (gzipQ != -1.0f && gzipQ >= deflateQ && this.gzipOptions != null) {
                return "gzip";
            } else if (deflateQ != -1.0f && deflateOptions != null) {
                return "deflate";
            }
        }
        if (starQ > 0.0f) {
            if (brQ == -1.0f && brotliOptions != null) {
                return "br";
            }
            if (zstdQ == -1.0f && zstdOptions != null) {
                return "zstd";
            }
            if (snappyQ == -1.0f && this.snappyOptions != null) {
                return "snappy";
            }
            if (gzipQ == -1.0f && this.gzipOptions != null) {
                return "gzip";
            }
            if (deflateQ == -1.0f && deflateOptions != null) {
                return "deflate";
            }
        }
        return null;
    }

    @Deprecated
    @SuppressWarnings("FloatingPointEquality")
    protected ZlibWrapper determineWrapper(String acceptEncoding) {
        float starQ = -1.0f;
        float gzipQ = -1.0f;
        float deflateQ = -1.0f;
        for (String encoding : acceptEncoding.split(",")) {
            float q = 1.0f;
            int equalsPos = encoding.indexOf('=');
            if (equalsPos != -1) {
                try {
                    q = Float.parseFloat(encoding.substring(equalsPos + 1));
                } catch (NumberFormatException e) {
                    // Ignore encoding
                    q = 0.0f;
                }
            }
            if (encoding.contains("*")) {
                starQ = q;
            } else if (encoding.contains("gzip") && q > gzipQ) {
                gzipQ = q;
            } else if (encoding.contains("deflate") && q > deflateQ) {
                deflateQ = q;
            }
        }
        if (gzipQ > 0.0f || deflateQ > 0.0f) {
            if (gzipQ >= deflateQ) {
                return ZlibWrapper.GZIP;
            } else {
                return ZlibWrapper.ZLIB;
            }
        }
        if (starQ > 0.0f) {
            if (gzipQ == -1.0f) {
                return ZlibWrapper.GZIP;
            }
            if (deflateQ == -1.0f) {
                return ZlibWrapper.ZLIB;
            }
        }
        return null;
    }
}
