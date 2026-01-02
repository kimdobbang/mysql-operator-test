package com.testcraft.mysqloperatorpoc

import com.testcraft.mysqloperatorpoc.operator.resource.mysql.CloneSourceSpec
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.InitStrategy
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.MySQLInstance
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.MySQLSpec
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.ResourceQuantity
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.ResourceSpec
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.StorageSpec
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Duration
import java.time.Instant

@EnabledIfEnvironmentVariable(named = "RUN_EXTENDED", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySQLInstanceExtendedE2ETests {
    private val client: KubernetesClient = KubernetesClientBuilder().build()
    private val namespace = System.getenv("TEST_NAMESPACE") ?: "default"
    private val name = "mysql-e2e"
    private val timeoutSeconds = System.getenv("EXTENDED_TIMEOUT_SECONDS")?.toLongOrNull() ?: 300

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

    private fun newResource(spec: MySQLSpec = baseSpec()): MySQLInstance = MySQLInstance().apply {
        metadata.name = name
        metadata.namespace = namespace
        this.spec = spec
    }

    private fun waitFor(
        condition: () -> Boolean,
        timeout: Duration,
        label: String,
        diagnostics: () -> String = { collectDiagnostics() },
    ) {
        val deadline = Instant.now().plus(timeout)
        while (Instant.now().isBefore(deadline)) {
            if (condition()) return
            Thread.sleep(2000)
        }
        error("Timeout waiting for $label\n${diagnostics()}")
    }

    private fun getStatefulSet(): StatefulSet? =
        client.apps().statefulSets().inNamespace(namespace).withName(name).get()

    private fun getJob(jobName: String): Job? =
        client.batch().v1().jobs().inNamespace(namespace).withName(jobName).get()

    private fun collectDiagnostics(jobName: String? = null): String {
        val sb = StringBuilder()
        val operatorPods = client.pods().inNamespace(namespace).withLabel("app", "mysql-operator").list().items
        if (operatorPods.isEmpty()) {
            sb.append("operatorPods: none\n")
        } else {
            sb.append("operatorPods:\n")
            operatorPods.forEach { pod ->
                val phase = pod.status?.phase
                val ready = pod.status?.containerStatuses?.all { it.ready } == true
                sb.append("- ${pod.metadata?.name} phase=$phase ready=$ready\n")
            }
        }

        val sts = getStatefulSet()
        if (sts == null) {
            sb.append("statefulSet: none\n")
        } else {
            val ready = sts.status?.readyReplicas ?: 0
            val replicas = sts.status?.replicas ?: 0
            sb.append("statefulSet: ${sts.metadata?.name} ready=$ready replicas=$replicas\n")
        }

        val pods = client.pods().inNamespace(namespace).withLabel("app", name).list().items
        if (pods.isEmpty()) {
            sb.append("mysqlPods: none\n")
        } else {
            sb.append("mysqlPods:\n")
            pods.forEach { pod ->
                val phase = pod.status?.phase
                val reason = pod.status?.reason ?: pod.status?.containerStatuses?.firstOrNull { it.state?.waiting != null }?.state?.waiting?.reason
                sb.append("- ${pod.metadata?.name} phase=$phase reason=$reason\n")
            }
        }

        if (jobName != null) {
            val job = getJob(jobName)
            if (job == null) {
                sb.append("job $jobName: none\n")
            } else {
                val status = job.status
                sb.append("job $jobName: active=${status?.active ?: 0} succeeded=${status?.succeeded ?: 0} failed=${status?.failed ?: 0}\n")
            }
        }

        return sb.toString().trim()
    }

    private fun requireOperatorReady() {
        val pods = client.pods().inNamespace(namespace).withLabel("app", "mysql-operator").list().items
        val ready = pods.any { pod -> pod.status?.containerStatuses?.all { it.ready } == true }
        check(ready) {
            "mysql-operator pod이 준비되지 않았습니다.\n${collectDiagnostics()}"
        }
    }

    @BeforeEach
    fun ensureOperatorReady() {
        requireOperatorReady()
    }

    @org.junit.jupiter.api.AfterEach
    fun cleanup() {
        client.resources(MySQLInstance::class.java).inNamespace(namespace).withName(name).delete()
        waitFor(
            condition = { getStatefulSet() == null },
            timeout = Duration.ofSeconds(timeoutSeconds),
            label = "statefulset deleted"
        )
    }

    @Test
    // Restart 테스트: 오퍼레이터가 restart annotation을 처리해 PodTemplate에 restartedAt을 찍는지 확인
    fun restartAnnotationTriggersRollout() {
        val crClient = client.resources(MySQLInstance::class.java).inNamespace(namespace)
        val cr = newResource()
        crClient.resource(cr).createOrReplace()

        waitFor(
            condition = { (getStatefulSet()?.status?.readyReplicas ?: 0) >= 1 },
            timeout = Duration.ofSeconds(timeoutSeconds),
            label = "statefulset ready"
        )

        val patched = MySQLInstance().apply {
            metadata.name = name
            metadata.namespace = namespace
            metadata.annotations = mapOf("action.mysql.sandbox/restart" to "true")
            spec = cr.spec
        }
        crClient.withName(name).edit { current ->
            val meta = current.metadata
            meta.annotations = mapOf("action.mysql.sandbox/restart" to "true")
            current
        }

        waitFor(
            condition = {
                val sts = getStatefulSet() ?: return@waitFor false
                val annotations = sts.spec.template.metadata.annotations ?: return@waitFor false
                annotations["mysql.sandbox/restartedAt"] != null
            },
            timeout = Duration.ofSeconds(timeoutSeconds),
            label = "restart annotation applied"
        )
    }

    @Test
    // Reset 테스트: 오퍼레이터가 reset annotation을 처리하고 Job 결과를 status에 반영하는지 확인
    fun resetAnnotationUpdatesStatus() {
        val crClient = client.resources(MySQLInstance::class.java).inNamespace(namespace)
        val cr = newResource()
        crClient.resource(cr).createOrReplace()

        waitFor(
            condition = { (getStatefulSet()?.status?.readyReplicas ?: 0) >= 1 },
            timeout = Duration.ofSeconds(timeoutSeconds),
            label = "statefulset ready"
        )

        crClient.withName(name).edit { current ->
            val meta = current.metadata
            meta.annotations = mapOf("action.mysql.sandbox/reset" to "truncate")
            current
        }

        waitFor(
            condition = {
                val updated = crClient.withName(name).get()
                val status = updated?.status
                status?.resetPhase in setOf("SUCCESS", "FAILED") && status?.lastResetTime != null
            },
            timeout = Duration.ofSeconds(timeoutSeconds),
            label = "reset status"
        )
    }

    @Test
    // Clone 테스트: 오퍼레이터가 SCHEMA_CLONE 전략에서 Clone Job을 생성하는지 확인
    fun cloneJobIsCreated() {
        val yaml = """
apiVersion: testcraft.com/v1
kind: MySQLInstance
metadata:
  name: $name
  namespace: $namespace
spec:
  database: testcraft
  rootPassword: password
  resources:
    limits:
      cpu: 500m
      memory: 512Mi
  storage:
    size: 1Gi
  mysqlConfig:
    max_connections: "200"
  initStrategy: SCHEMA_CLONE
  cloneSource:
    host: "example-source"
    port: 3306
    username: "root"
    password: "password"
    database: "sample"
""".trimIndent()
        client.load(yaml.byteInputStream()).inNamespace(namespace).createOrReplace()

        val jobName = "${name}-clone"
        waitFor(
            condition = { getJob(jobName) != null },
            timeout = Duration.ofSeconds(timeoutSeconds),
            label = "clone job created",
            diagnostics = { collectDiagnostics(jobName) }
        )

        assertNotNull(getJob(jobName))
    }

}
