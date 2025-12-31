package com.testcraft.mysqloperatorpoc.api

import io.fabric8.kubernetes.client.KubernetesClient
import org.springframework.stereotype.Component

@Component
class SourceMySQLBootstrapService(
    private val client: KubernetesClient,
) {
    fun ensureSourceMySQL(namespace: String): String? {
        if (namespace != "default") {
            return null
        }
        val existing = runCatching {
            client.apps().deployments().inNamespace(namespace).withName("source-mysql").get()
        }.getOrNull()
        if (existing != null) {
            return null
        }

        val stream = javaClass.classLoader.getResourceAsStream("k8s/source-mysql.yaml")
            ?: return "source-mysql 매니페스트를 찾을 수 없습니다."

        return runCatching {
            client.load(stream).createOrReplace()
            null
        }.getOrElse { "source-mysql 자동 생성 실패: ${it.message}" }
    }
}
