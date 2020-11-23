package org.mozilla.fenix

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import mozilla.components.support.ktx.android.content.appVersionName
import java.io.*
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*


class SendChinaMetrics(private val context: Context) {
    @SuppressLint("LongLogTag")
    fun uploadPing(
        pingType: Int
    ): Boolean {
        var connectionCN: HttpURLConnection? = null
        val appVersion = context.appVersionName
        val brand = Build.BRAND
        val device = Build.PRODUCT
        val timeStamp: Long = SimpleDateFormat("yyyyMMddHHmmss").format(Date()).toLong()
        val random: String = (0..timeStamp).random().toString()
        val endpoint = "https://m.g-fox.cn/cmonline.gif?" +
                "version=" + appVersion + "&brand=" + brand +
                "&device=" + device + "&random=" + random
        var var6: Boolean
        try {
            when (pingType) {
                START -> {
                    connectionCN = openConnectionConnection(
                        endpoint, "&type=start"
                    )
                }
                ACTIVATE -> {
                    connectionCN = openConnectionConnection(
                        endpoint, "&type=activate"
                    )
                }
                UPDATE -> {
                    connectionCN = openConnectionConnection(
                        endpoint, "&type=update"
                    )
                }
            }

            connectionCN!!.connectTimeout = 10000
            connectionCN.readTimeout = 10000
            connectionCN.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connectionCN.setRequestProperty("Date", createDateHeaderValue())
            connectionCN.requestMethod = "GET"
            Log.e(LOG_TAG, "ChinaPing")
            val responseCodeCN: Int = uploadUsingGet(connectionCN)
            Log.d(LOG_TAG, "Ping upload: $responseCodeCN")
            if (responseCodeCN >= 200 && responseCodeCN <= 299) {
                var6 = true
                Log.e(
                    LOG_TAG,
                    " CN Server returned client error code: $responseCodeCN"
                )
                return var6
            }
            if (responseCodeCN >= 400 && responseCodeCN <= 499) {
                Log.e(
                    LOG_TAG,
                    "CN Server returned client error code: $responseCodeCN"
                )
                var6 = true
                return var6
            }
            Log.w(
                LOG_TAG,
                "CN Server returned response code: $responseCodeCN"
            )
            var6 = false
        } catch (var11: MalformedURLException) {
            Log.e(
                LOG_TAG,
                "Could not upload telemetry due to malformed URL",
                var11
            )
            var6 = true
            return var6
        } catch (var12: IOException) {
            Log.w(LOG_TAG, "IOException while uploading ping", var12)
            var6 = false
            return var6
        } finally {
            if (connectionCN != null) {
                connectionCN.disconnect()
                Log.e(LOG_TAG, "connectionCN Disconnect")
            }
        }
        return var6
    }

    @Throws(IOException::class)
    fun uploadUsingGet(connection: HttpURLConnection?): Int {
        var `in`: BufferedReader? = null
        var var5 = 0
        try {
            `in` = BufferedReader(
                InputStreamReader(
                    connection!!.inputStream
                )
            )
            val line: String
            line = `in`.readLine()
            var5 = connection.responseCode
            println(line)
        } catch (e: Exception) {
            println("发送GET请求出现异常！$e")
            e.printStackTrace()
        } finally {
            try {
                `in`?.close()
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
        return var5
    }

    @SuppressLint("LongLogTag")
    @VisibleForTesting
    @Throws(IOException::class)
    fun openConnectionConnection(endpoint: String, path: String): HttpURLConnection {
        val url = URL(endpoint + path)
        Log.e(LOG_TAG, url.toString())
        return url.openConnection() as HttpURLConnection
    }

    @VisibleForTesting
    fun createDateHeaderValue(): String {
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("GMT")
        return dateFormat.format(calendar.time)
    }

    companion object {
        private const val LOG_TAG = "HttpURLTelemetryClientCN"
        const val START = 0
        const val ACTIVATE = 1
        const val UPDATE =2
    }
}