package com.wmods.wppenhacer.xposed.features.media

import android.content.SharedPreferences
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import java.io.InputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.URL
import java.util.Locale

/**
 * Multi-threaded chunked downloader for large WhatsApp media (photos, videos, documents >= 6 MB).
 * Intercepts WhatsApp's Tigon media download response and wraps its InputStream in a
 * [ParallelDownloadingInputStream] using concurrent HTTP Range connections.
 */
class ParallelDownloading(classLoader: ClassLoader, preferences: SharedPreferences) :
    Feature(classLoader, preferences) {

    override fun doHook() {
        if (!xprefs.getBoolean(PREF_KEY, false)) return

        val downloadMethod = runCatching {
            Unobfuscator.loadTigonMediaDownloadMethod(classLoader)
        }.onFailure {
            logDebug("TigonMediaDownload method not found, skipping Parallel Downloading", it)
        }.getOrNull() ?: return

        downloadMethod.hook {
            after {
                if (!xprefs.getBoolean(PREF_KEY, false)) return@after
                val response = result ?: return@after

                var url: URL? = null
                var startOffset = 0L
                for (arg in args) {
                    if (arg is URL && url == null) {
                        url = arg
                    } else if (arg is Long && startOffset == 0L) {
                        startOffset = arg
                    }
                }
                if (url == null) return@after

                val contentLength = resolveContentLength(response)
                if (contentLength < MIN_PARALLEL_SIZE_BYTES) return@after

                val connections = readConnectionCount()
                val proxy = createResponseProxy(response, contentLength, connections, url, startOffset)
                if (proxy != null) {
                    result = proxy
                }
            }
        }
    }

    private fun readConnectionCount(): Int {
        val raw = runCatching {
            xprefs.getString(PREF_CONNECTIONS_KEY, DEFAULT_CONNECTIONS.toString())
        }.getOrNull()
        val parsed = raw?.toIntOrNull() ?: DEFAULT_CONNECTIONS
        return if (parsed in 2..16) parsed else DEFAULT_CONNECTIONS
    }

    private fun resolveContentLength(response: Any): Long {
        return try {
            for (m in response.javaClass.methods) {
                if (m.parameterTypes.isEmpty()) {
                    val name = m.name.lowercase(Locale.ROOT)
                    if (name.contains("contentlength") || name == "length" || name == "getlength") {
                        val res = m.invoke(response)
                        if (res is Number) return res.toLong()
                    }
                }
            }
            -1L
        } catch (t: Throwable) {
            logDebug(t)
            -1L
        }
    }

    private fun createResponseProxy(
        response: Any,
        contentLength: Long,
        connections: Int,
        url: URL,
        startOffset: Long
    ): Any? {
        val interfaces = response.javaClass.interfaces
        if (interfaces.isEmpty()) return null

        val handler = object : InvocationHandler {
            private var parallelStream: ParallelDownloadingInputStream? = null

            override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
                val res = if (args != null) {
                    method.invoke(response, *args)
                } else {
                    method.invoke(response)
                }
                if (res is InputStream) {
                    if (parallelStream == null && res !is ParallelDownloadingInputStream) {
                        parallelStream = ParallelDownloadingInputStream(
                            originalStream = res,
                            totalLength = contentLength,
                            connectionCount = connections,
                            downloadUrl = url,
                            startOffset = startOffset
                        )
                    }
                    return parallelStream ?: res
                }
                return res
            }
        }

        return try {
            Proxy.newProxyInstance(response.javaClass.classLoader, interfaces, handler)
        } catch (t: Throwable) {
            logDebug("Failed to create response proxy", t)
            null
        }
    }

    override fun getPluginName(): String = "Parallel Downloading"

    companion object {
        const val PREF_KEY = "parallel_downloading"
        const val PREF_CONNECTIONS_KEY = "parallel_downloading_connections"
        const val DEFAULT_CONNECTIONS = 4
        private const val MIN_PARALLEL_SIZE_BYTES = 6L * 1024L * 1024L // 6 MB threshold
    }
}
