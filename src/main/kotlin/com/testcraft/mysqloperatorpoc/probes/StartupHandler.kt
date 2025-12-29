package com.testcraft.mysqloperatorpoc.probes

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import io.javaoperatorsdk.operator.Operator
import java.io.IOException
import java.nio.charset.StandardCharsets

class StartupHandler(private val operator: Operator) : HttpHandler {
    @Throws(IOException::class)
    override fun handle(httpExchange: HttpExchange) {
        if (operator.runtimeInfo.isStarted) {
            sendMessage(httpExchange, 200, "started")
        } else {
            sendMessage(httpExchange, 400, "not started yet")
        }
    }

    companion object {
        @Throws(IOException::class)
        fun sendMessage(httpExchange: HttpExchange, code: Int, message: String) {
            httpExchange.responseBody.use { outputStream ->
                val bytes = message.toByteArray(StandardCharsets.UTF_8)
                httpExchange.sendResponseHeaders(code, bytes.size.toLong())
                outputStream.write(bytes)
                outputStream.flush()
            }
        }
    }
}
