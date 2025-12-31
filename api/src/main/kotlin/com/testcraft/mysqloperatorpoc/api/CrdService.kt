package com.testcraft.mysqloperatorpoc.api

import io.fabric8.kubernetes.client.KubernetesClient
import org.springframework.stereotype.Service

@Service
class CrdService(
    private val client: KubernetesClient,
) {
    private val crdName = "mysqlinstances.testcraft.com"
    private val crdResourcePath = "crd/mysqlinstances.testcraft.com-v1.yml"

    fun ensureMySQLInstanceCrd() {
        val existing = client.apiextensions().v1().customResourceDefinitions().withName(crdName).get()
        if (existing != null) return

        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream(crdResourcePath)) {
            "CRD manifest not found at $crdResourcePath"
        }
        client.load(stream).createOrReplace()
    }
}
