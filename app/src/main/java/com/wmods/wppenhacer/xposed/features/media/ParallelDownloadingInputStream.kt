package com.wmods.wppenhacer.xposed.features.media

import com.wmods.wppenhacer.xposed.utils.YukiLog
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Accelerates large WhatsApp media downloads by splitting them into 2 MB chunks
 * and prefetching upcoming chunks in parallel using concurrent HTTP Range requests.
 *
 * Chunk 0 is streamed directly from WhatsApp's original connection, while chunks 1..N
 * are fetched concurrently across daemon worker threads. If Range requests are rejected
 * or return an unexpected Content-Range, the stream falls back cleanly to sequential reading.
 */
class ParallelDownloadingInputStream(
    private val originalStream: InputStream,
    private val totalLength: Long,
    connectionCount: Int,
    private val downloadUrl: URL,
    startOffset: Long
) : InputStream() {

    private val chunkSize = CHUNK_SIZE
    private val startOffset = maxOf(0L, startOffset)
    private val totalChunks: Int
    private val maxAheadQueue: Int
    private val threadPool: ThreadPoolExecutor
    private val chunkFutures = HashMap<Int, Future<ByteArray>>()

    private var nextChunkToSubmit = 1
    private var currentChunkIndex = 0
    private var currentChunkBuffer: ByteArray? = null
    private var currentChunkOffset = 0
    private var originalBytesConsumed = 0L

    private var fallbackToSequential = false
    private var isClosed = false

    init {
        require(totalLength > 0 && connectionCount > 0) {
            "Invalid length $totalLength or connections $connectionCount"
        }
        totalChunks = ((totalLength + chunkSize - 1) / chunkSize).toInt()
        maxAheadQueue = maxOf(2, connectionCount + 2)

        val threadId = AtomicInteger(1)
        val factory = ThreadFactory { runnable ->
            Thread(runnable, "WaeParallelDl-${threadId.getAndIncrement()}").apply {
                isDaemon = true
            }
        }
        threadPool = ThreadPoolExecutor(
            connectionCount,
            connectionCount,
            30L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            factory
        ).apply {
            allowCoreThreadTimeOut(true)
        }

        enqueueUpcomingChunks()
    }

    private fun getChunkLength(chunkIndex: Int): Int {
        return minOf(chunkSize.toLong(), totalLength - (chunkIndex.toLong() * chunkSize)).toInt()
    }

    @Synchronized
    private fun enqueueUpcomingChunks() {
        while (nextChunkToSubmit < totalChunks && (nextChunkToSubmit - currentChunkIndex) <= maxAheadQueue) {
            val chunkToFetch = nextChunkToSubmit++
            chunkFutures[chunkToFetch] = threadPool.submit<ByteArray> {
                val offsetInFile = chunkToFetch.toLong() * chunkSize
                val expectedLength = getChunkLength(chunkToFetch)
                fetchRange(offsetInFile, expectedLength)
            }
        }
    }

    @Throws(IOException::class)
    private fun fetchRange(offsetInFile: Long, length: Int): ByteArray {
        val rangeStart = startOffset + offsetInFile
        val rangeEnd = rangeStart + length - 1

        val rawConn = downloadUrl.openConnection()
        if (rawConn !is HttpURLConnection) {
            throw IOException("URL is not HTTP/HTTPS: $downloadUrl")
        }
        try {
            rawConn.connectTimeout = 15_000
            rawConn.readTimeout = 30_000
            rawConn.setRequestProperty("Range", "bytes=$rangeStart-$rangeEnd")
            rawConn.setRequestProperty("Accept-Encoding", "identity")

            val code = rawConn.responseCode
            val contentRange = rawConn.getHeaderField("Content-Range").orEmpty()

            if (code == 206) {
                val expectedPrefix = "bytes $rangeStart-$rangeEnd/"
                if (!contentRange.startsWith(expectedPrefix)) {
                    throw IOException("Unexpected Content-Range: $contentRange, expected: $expectedPrefix")
                }
                val completeLength = contentRange.substring(expectedPrefix.length)
                if (completeLength != "*") {
                    val reportedTotal = completeLength.toLongOrNull()
                        ?: throw IOException("Invalid Content-Range total: $contentRange")
                    if (reportedTotal <= rangeEnd) {
                        throw IOException("Content-Range total does not include range: $contentRange")
                    }
                }

                val data = ByteArray(length)
                rawConn.inputStream.use { input ->
                    var bytesRead = 0
                    while (bytesRead < length) {
                        val r = input.read(data, bytesRead, length - bytesRead)
                        if (r < 0) {
                            throw IOException("Range $rangeStart truncated at $bytesRead of $length")
                        }
                        bytesRead += r
                    }
                }
                return data
            } else {
                throw IOException("Range request failed: HTTP $code range=$rangeStart-$rangeEnd")
            }
        } finally {
            rawConn.disconnect()
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) {
            throw IndexOutOfBoundsException("Invalid buffer offset=$offset length=$length")
        }
        if (isClosed) throw IOException("Stream closed")
        if (length == 0) return 0

        if (fallbackToSequential) {
            return readSequentialOrThrow(buffer, offset, length)
        }

        if (currentChunkIndex == 0) {
            val chunk0Remaining = getChunkLength(0) - currentChunkOffset
            if (chunk0Remaining > 0) {
                val bytesRead = originalStream.read(buffer, offset, minOf(length, chunk0Remaining))
                if (bytesRead > 0) {
                    currentChunkOffset += bytesRead
                    originalBytesConsumed += bytesRead
                } else if (bytesRead < 0) {
                    throw IOException(
                        "Original download truncated at $currentChunkOffset of ${getChunkLength(0)} first-chunk bytes"
                    )
                }
                return bytesRead
            }

            if (totalChunks == 1) return -1

            val nextBytes: ByteArray? = try {
                val firstPrefetch = synchronized(this) { chunkFutures.remove(1) }
                firstPrefetch?.get()
            } catch (t: Throwable) {
                YukiLog.log("[ParallelDownload] Prefetched chunk 1 failed, falling back: ${t.message}")
                null
            }

            if (nextBytes == null) {
                fallbackToSequential = true
                cancelPendingTasks()
                return readSequentialOrThrow(buffer, offset, length)
            }

            runCatching { originalStream.close() }

            currentChunkIndex = 1
            currentChunkBuffer = nextBytes
            currentChunkOffset = 0
            enqueueUpcomingChunks()
        }

        var activeBuffer = currentChunkBuffer
        if (activeBuffer != null && currentChunkOffset == activeBuffer.size) {
            val nextIndex = currentChunkIndex + 1
            if (nextIndex >= totalChunks) return -1

            currentChunkIndex = nextIndex
            val nextBytes: ByteArray = try {
                val future = synchronized(this) { chunkFutures.remove(nextIndex) }
                if (future != null) {
                    future.get()
                } else {
                    fetchRange(nextIndex.toLong() * chunkSize, getChunkLength(nextIndex))
                }
            } catch (_: ExecutionException) {
                fetchRange(nextIndex.toLong() * chunkSize, getChunkLength(nextIndex))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Download interrupted", e)
            }

            currentChunkBuffer = nextBytes
            currentChunkOffset = 0
            activeBuffer = nextBytes
            enqueueUpcomingChunks()
        }

        val buf = activeBuffer ?: return -1
        val available = buf.size - currentChunkOffset
        val bytesToCopy = minOf(length, available)
        System.arraycopy(buf, currentChunkOffset, buffer, offset, bytesToCopy)
        currentChunkOffset += bytesToCopy
        return bytesToCopy
    }

    @Throws(IOException::class)
    private fun readSequentialOrThrow(buffer: ByteArray, offset: Int, length: Int): Int {
        val remaining = totalLength - originalBytesConsumed
        if (remaining <= 0L) return -1

        val bytesRead = originalStream.read(buffer, offset, minOf(remaining, length.toLong()).toInt())
        if (bytesRead < 0) {
            throw IOException(
                "Original sequential download truncated at $originalBytesConsumed of $totalLength bytes"
            )
        }
        if (bytesRead > 0) originalBytesConsumed += bytesRead
        return bytesRead
    }

    @Throws(IOException::class)
    override fun read(): Int {
        val single = ByteArray(1)
        val r = read(single, 0, 1)
        return if (r < 0) -1 else single[0].toInt() and 0xFF
    }

    @Synchronized
    private fun cancelPendingTasks() {
        for (f in chunkFutures.values) {
            f.cancel(true)
        }
        chunkFutures.clear()
        threadPool.shutdownNow()
    }

    @Throws(IOException::class)
    override fun close() {
        if (isClosed) return
        isClosed = true
        cancelPendingTasks()
        runCatching { originalStream.close() }
    }

    companion object {
        const val CHUNK_SIZE = 2 * 1024 * 1024 // 2 MB
    }
}
