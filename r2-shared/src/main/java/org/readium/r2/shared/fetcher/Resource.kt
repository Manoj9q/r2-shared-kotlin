/*
 * Module: r2-shared-kotlin
 * Developers: Quentin Gliosca
 *
 * Copyright (c) 2020. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.shared.fetcher

import org.readium.r2.shared.extensions.read
import org.readium.r2.shared.format.mediaType
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.flatMap
import java.io.InputStream
import java.lang.IllegalArgumentException
import java.nio.charset.Charset

/** Acts as a proxy to an actual resource by handling read access. */
internal interface Resource {

    /**
     * The link from which the resource was retrieved.
     *
     * It might be modified by the [Resource] to include additional metadata, e.g. the
     * `Content-Type` HTTP header in Link::type.
     */
    val link: Link

    /**
     * Data length from metadata if available, or calculated from reading the bytes otherwise.
     *
     * This value must be treated as a hint, as it might not reflect the actual bytes length. To get
     * the real length, you need to read the whole resource.
     */
    val length: Try<Long, Error>

    /**
     * Reads the bytes at the given range.
     *
     * When [range] is null, the whole content is returned. Out-of-range indexes are clamped to the
     * available length automatically.
     */
    fun read(range: LongRange? = null): Try<ByteArray, Error>

    /**
     * Reads the full content as a [String].
     *
     * If [charset] is null, then it is parsed from the `charset` parameter of `link.type`, or falls
     * back on UTF-8.
     */
    fun readAsString(charset: Charset? = null): Try<String, Error> =
        read().map {
            String(it, charset = charset ?: link.mediaType?.charset ?: Charsets.UTF_8)
        }

    /**
     * Closes any opened file handles.
     */
    fun close()

    sealed class Error {

        /** Equivalent to a 404 HTTP error. */
        object NotFound : Error()

        /**
         * Equivalent to a 403 HTTP error.
         *
         * This can be returned when trying to read a resource protected with a DRM that is not unlocked.
         */
        object Forbidden : Error()

        /**
         * Equivalent to a 503 HTTP error.
         *
         * Used when the source can't be reached, e.g. no Internet connection, or an issue with the
         * file system. Usually this is a temporary error.
         */
        object Unavailable : Error()

        /** For any other error, such as HTTP 500. */
        class Other(val exception: Exception) : Error()
    }
}

/**
 * Implements the transformation of a Resource. It can be used, for example, to decrypt,
 * deobfuscate, inject CSS or JavaScript, correct content – e.g. adding a missing dir="rtl" in an
 * HTML document, pre-process – e.g. before indexing a publication's content, etc.
 *
 * If the transformation doesn't apply, simply return resource unchanged.
 */
internal typealias ResourceTransformer = (Resource) -> Resource

/** Creates a Resource that will always return the given [error]. */
internal class FailureResource(override val link: Link, private val error: Resource.Error) : Resource {

    override fun read(range: LongRange?): Try<ByteArray, Resource.Error> = Try.failure(error)

    override val length:  Try<Long, Resource.Error> = Try.failure(error)

    override fun close() {}
}

/** Creates a Resource serving an array of [bytes]. */
internal open class BytesResource(override val link: Link, private val bytes: ByteArray) : Resource {

    override fun read(range: LongRange?): Try<ByteArray, Resource.Error> {
        if (range == null)
            return Try.success(bytes.copyOf())

        @Suppress("NAME_SHADOWING")
        val range = checkedRange(range)
        val byteRange = bytes.sliceArray(range.map(Long::toInt))
        return Try.success(byteRange)
    }

    override val length: Try<Long, Resource.Error> = Try.success(bytes.size.toLong())

    override fun close() {}
}

/** Creates a Resource serving a string encoded as UTF-8. */
internal class StringResource(link: Link, string: String) : BytesResource(link, string.toByteArray())

internal abstract class StreamResource : Resource {

    protected abstract fun stream(): Try<InputStream, Resource.Error>

    /** An estimate of data length from metadata */
    protected abstract val metadataLength: Long?

    override fun read(range: LongRange?): Try<ByteArray, Resource.Error> =
        if (range == null)
            readFully()
        else
            readRange(range)

    private fun readFully(): Try<ByteArray, Resource.Error> =
        stream().flatMap { stream ->
            stream.use {
                try {
                    Try.success(it.readBytes())
                } catch (e: Exception) {
                    Try.failure(Resource.Error.Other(e))
                }
            }
        }

    private fun readRange(range: LongRange): Try<ByteArray, Resource.Error> =
        stream().flatMap { stream ->
            @Suppress("NAME_SHADOWING")
            val range = checkedRange(range)

            stream.use {
                val skipped = it.skip(range.first)
                if (skipped != range.first) {
                    return@use Try.success(ByteArray(0))
                }
                val length = range.last - range.first + 1
                return@use try {
                    Try.success(it.read(length))
                } catch (e: Exception) {
                    Try.failure(Resource.Error.Other(e))
                }
            }
        }

    override val length: Try<Long, Resource.Error>
        get() =
            metadataLength?.let { Try.success(it) }
                ?: readFully().map { it.size.toLong() }
}

private fun checkedRange(range: LongRange): LongRange =
    if (range.first >= range.last)
        0 until 0L
    else if (range.last - range.first + 1 > Int.MAX_VALUE)
        throw IllegalArgumentException("Range length greater than Int.MAX_VALUE")
    else
        LongRange(range.first.coerceAtLeast(0), range.last)