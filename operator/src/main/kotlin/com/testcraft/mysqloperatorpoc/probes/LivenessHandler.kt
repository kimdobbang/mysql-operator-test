package com.testcraft.mysqloperatorpoc.probes

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import io.javaoperatorsdk.operator.Operator
import java.io.IOException

class LivenessHandler(private val operator: Operator) : HttpHandler {
    @Throws(IOException::class)
    override fun handle(httpExchange: HttpExchange) {
        if (operator.runtimeInfo.allEventSourcesAreHealthy()) {
            StartupHandler.sendMessage(httpExchange, 200, "healthy")
        } else {
            StartupHandler.sendMessage(httpExchange, 400, "an event source is not healthy")
        }
    }
}
