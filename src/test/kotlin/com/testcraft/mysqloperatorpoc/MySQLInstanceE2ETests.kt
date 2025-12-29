package com.testcraft.mysqloperatorpoc

import com.testcraft.mysqloperatorpoc.operator.common.buildTunedConfig
import com.testcraft.mysqloperatorpoc.operator.common.renderMyCnf
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.CloneSourceSpec
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.InitStrategy
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.MySQLInstance
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.MySQLSpec
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.ResourceQuantity
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.ResourceSpec
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.StorageSpec
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.time.Duration
import java.time.Instant

@EnabledIfEnvironmentVariable(named = "RUN_E2E", matches = "true")
class MySQLInstanceE2ETests {
    private val client: KubernetesClient = KubernetesClientBuilder().build()
    private val namespace = System.getenv("TEST_NAMESPACE") ?: "default"
    private val name = "mysql-e2e"

    private fun baseSpec(): MySQLSpec = MySQLSpec(
        database = "testcraft",
        rootPassword = "password",
        resources = ResourceSpec(
            limits = ResourceQuantity(cpu = "500m", memory = "512Mi")
        ),
        storage = StorageSpec(size = "1Gi"),
        mysqlConfig = mapOf("max_connections" to "200"),
        initStrategy = InitStrategy.EMPTY,
        cloneSource = null,
    )

    private fun newResource(): MySQLInstance = MySQLInstance().apply {
        metadata.name = name
        metadata.namespace = namespace
        spec = baseSpec()
    }

    private fun applyCrdIfPresent() {
        val crdFile = File("build/resources/main/mysqlinstances.testcraft.com-v1.yml")
        if (crdFile.exists()) {
            crdFile.inputStream().use { stream ->
                client.load(stream).createOrReplace()
            }
        }
    }

    private fun waitFor(condition: () -> Boolean, timeout: Duration, label: String) {
        val deadline = Instant.now().plus(timeout)
        while (Instant.now().isBefore(deadline)) {
            if (condition()) return
            Thread.sleep(2000)
        }
        error("Timeout waiting for $label")
    }

    private fun getPods(): List<Pod> =
        client.pods().inNamespace(namespace).withLabel("app", name).list().items

    private fun getStatefulSet(): StatefulSet? =
        client.apps().statefulSets().inNamespace(namespace).withName(name).get()

    private fun getService(): Service? =
        client.services().inNamespace(namespace).withName(name).get()

    private fun getConfigMap(): ConfigMap? =
        client.configMaps().inNamespace(namespace).withName(name).get()

    private fun getSecret(): Secret? =
        client.secrets().inNamespace(namespace).withName("${name}-mysql-secret").get()

    private fun getPvc(): io.fabric8.kubernetes.api.model.PersistentVolumeClaim? =
        client.persistentVolumeClaims().inNamespace(namespace).withName("mysql-data-${name}-0").get()

    private fun forceDeletePvc() {
        // PoC 환경에서 PVC 보호 finalizer로 삭제가 지연될 수 있어 강제 정리
        val pvcClient = client.persistentVolumeClaims().inNamespace(namespace).withName("mysql-data-${name}-0")
        val pvc = pvcClient.get() ?: return
        val finalizers = pvc.metadata.finalizers
        if (!finalizers.isNullOrEmpty()) {
            pvcClient.edit { current ->
                current.metadata.finalizers = emptyList()
                current
            }
        }
        pvcClient.delete()
    }

    private fun waitForReady() {
        waitFor(
            condition = {
                val sts = getStatefulSet()
                (sts?.status?.readyReplicas ?: 0) >= 1
            },
            timeout = Duration.ofMinutes(5),
            label = "statefulset ready"
        )
    }

    @AfterEach
    fun cleanup() {
        client.resources(MySQLInstance::class.java).inNamespace(namespace).withName(name).delete()
        forceDeletePvc()
        waitFor(
            condition = { getStatefulSet() == null && getService() == null && getConfigMap() == null && getSecret() == null },
            timeout = Duration.ofMinutes(2),
            label = "resources deleted"
        )
    }

    @Test
    // PoC #1 멱등성: 오퍼레이터가 중복 리소스 없이 원하는 상태로 수렴하는지 확인
    fun idempotencyCreatesSingleResources() {
        applyCrdIfPresent()
        val cr = newResource()
        val crClient = client.resources(MySQLInstance::class.java).inNamespace(namespace)
        crClient.createOrReplace(cr)
        crClient.createOrReplace(cr)
        crClient.createOrReplace(cr)

        waitForReady()

        assertNotNull(getStatefulSet())
        assertNotNull(getService())
        assertNotNull(getConfigMap())
        assertNotNull(getSecret())
        assertNotNull(getPvc())

        assertEquals(1, client.apps().statefulSets().inNamespace(namespace).withLabel("app", name).list().items.size)
        assertEquals(1, client.services().inNamespace(namespace).withLabel("app", name).list().items.size)
        assertEquals(1, client.configMaps().inNamespace(namespace).withLabel("app", name).list().items.size)
        assertEquals(1, client.secrets().inNamespace(namespace).withLabel("managed", "true").list().items.size)
    }

    @Test
    // PoC #2 Self-healing: 오퍼레이터/StatefulSet이 Pod 삭제를 감지해 복구하는지 확인
    fun selfHealingRecreatesPod() {
        applyCrdIfPresent()
        val cr = newResource()
        client.resources(MySQLInstance::class.java).inNamespace(namespace).createOrReplace(cr)
        waitForReady()

        val podsBefore = getPods()
        assertTrue(podsBefore.isNotEmpty())
        val targetPod = podsBefore.first()
        client.pods().inNamespace(namespace).withName(targetPod.metadata.name).delete()

        waitFor(
            condition = {
                val podsAfter = getPods()
                podsAfter.isNotEmpty() && podsAfter.first().metadata.uid != targetPod.metadata.uid
            },
            timeout = Duration.ofMinutes(3),
            label = "pod recreated"
        )
    }

    @Test
    // PoC #3 GC: 오퍼레이터가 OwnerReference로 하위 리소스 GC를 유도하는지 확인
    fun gcDeletesResourcesOnCrDeletion() {
        applyCrdIfPresent()
        val cr = newResource()
        client.resources(MySQLInstance::class.java).inNamespace(namespace).createOrReplace(cr)
        waitForReady()

        client.resources(MySQLInstance::class.java).inNamespace(namespace).withName(name).delete()
        waitFor(
            condition = {
                val ready = getStatefulSet() == null && getService() == null && getConfigMap() == null && getSecret() == null
                if (!ready) return@waitFor false
                if (getPvc() == null) return@waitFor true
                forceDeletePvc()
                false
            },
            timeout = Duration.ofMinutes(5),
            label = "all resources deleted"
        )
    }

    @Test
    // PoC #5 Smart Tuning: 오퍼레이터가 메모리 기반 튜닝값을 my.cnf에 반영하는지 확인
    fun smartTuningUpdatesConfigMap() {
        applyCrdIfPresent()
        val cr = newResource()
        val crClient = client.resources(MySQLInstance::class.java).inNamespace(namespace)
        crClient.createOrReplace(cr)
        waitForReady()

        val tunedSpec = cr.spec.copy(resources = ResourceSpec(limits = ResourceQuantity(memory = "2Gi")))
        val patched = MySQLInstance().apply {
            metadata.name = name
            metadata.namespace = namespace
            spec = tunedSpec
        }
        crClient.createOrReplace(patched)

        waitFor(
            condition = {
                val cm = getConfigMap()
                val myCnf = cm?.data?.get("my.cnf") ?: return@waitFor false
                val tuned = buildTunedConfig("2Gi")
                tuned.all { (key, value) -> myCnf.contains("$key=$value") }
            },
            timeout = Duration.ofMinutes(2),
            label = "configmap updated"
        )

        val tuned = buildTunedConfig("2Gi")
        val cm = getConfigMap()
        val actual = cm?.data?.get("my.cnf") ?: ""
        tuned.forEach { (key, value) ->
            assertTrue(actual.contains("$key=$value"), "missing $key=$value in my.cnf: $actual")
        }
        assertTrue(actual.contains("max_connections=200"), "missing max_connections=200 in my.cnf: $actual")
    }
}
