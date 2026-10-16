package com.dcg.tv.data

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Conexões do app.
 *
 * Muitos provedores de internet bloqueiam endereços de IPTV no DNS do aparelho
 * (o serviço que traduz o nome do site para o endereço do servidor). Por isso o app
 * consulta primeiro um DNS seguro (Cloudflare e Google, via HTTPS) e só depois o DNS
 * do aparelho.
 */
object Net {

    const val API_USER_AGENT = "IPTVSmartersPro"
    const val PLAYER_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

    private const val DNS_CACHE_MS = 10 * 60 * 1000L

    private val bootstrapClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val secureDns: List<Dns> by lazy {
        listOf(
            DnsOverHttps.Builder().client(bootstrapClient)
                .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
                .build(),
            DnsOverHttps.Builder().client(bootstrapClient)
                .url("https://dns.google/dns-query".toHttpUrl())
                .bootstrapDnsHosts(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4"))
                .build(),
        )
    }

    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            cache[hostname]?.let { (time, addresses) ->
                if (System.currentTimeMillis() - time < DNS_CACHE_MS) return addresses
            }
            val resolvers = secureDns + Dns.SYSTEM
            for (resolver in resolvers) {
                try {
                    val found = resolver.lookup(hostname).filterNot { it.isAnyLocalAddress || it.isLoopbackAddress }
                    if (found.isNotEmpty()) {
                        cache[hostname] = System.currentTimeMillis() to found
                        return found
                    }
                } catch (e: Exception) {
                    // tenta o próximo
                }
            }
            throw UnknownHostException("endereço não encontrado: $hostname")
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(dns)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun call(url: String, userAgent: String) =
        client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute()

    fun fetchText(url: String, userAgent: String = API_USER_AGENT): String =
        call(url, userAgent).use { response ->
            if (!response.isSuccessful) throw IOException("o servidor respondeu com código ${response.code}")
            response.body?.string() ?: ""
        }

    fun download(url: String, dest: File, userAgent: String = API_USER_AGENT) {
        call(url, userAgent).use { response ->
            if (!response.isSuccessful) throw IOException("o servidor respondeu com código ${response.code}")
            val body = response.body ?: throw IOException("resposta vazia")
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        }
    }

    /** Traduz erros de rede para uma frase curta. */
    fun describe(e: Throwable): String = when (e) {
        is UnknownHostException -> "endereço não encontrado"
        is SocketTimeoutException -> "o servidor não respondeu a tempo"
        is ConnectException -> "conexão recusada"
        is javax.net.ssl.SSLException -> "falha na conexão segura"
        else -> e.message ?: e.javaClass.simpleName
    }
}
