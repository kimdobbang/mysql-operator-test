package com.testcraft.mysqloperatorpoc.api

import com.testcraft.mysqloperatorpoc.operator.resource.mysql.MySQLInstance
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class MySQLInstanceOpsService(
    private val client: KubernetesClient,
) {
    private fun annotateAction(
        name: String,
        namespace: String,
        key: String,
        value: String,
    ) {
        client.resources(MySQLInstance::class.java)
            .inNamespace(namespace)
            .withName(name)
            .edit { current ->
                val metadata = requireNotNull(current.metadata)
                val annotations = metadata.annotations?.toMutableMap() ?: mutableMapOf()
                annotations[key] = value
                metadata.annotations = annotations
                current
            }
    }

    fun triggerRestart(name: String, namespace: String) {
        annotateAction(name, namespace, "action.mysql.sandbox/restart", "true")
    }

    fun triggerReset(name: String, namespace: String, action: String): Boolean {
        val normalized = action.trim().lowercase()
        if (normalized == "delete" || normalized == "delete-instance") {
            client.resources(MySQLInstance::class.java)
                .inNamespace(namespace)
                .withName(name)
                .delete()
            return true
        }
        val token = "${normalized}:${Instant.now().toEpochMilli()}"
        annotateAction(name, namespace, "action.mysql.sandbox/reset", token)
        return false
    }

    fun verifyData(
        name: String,
        namespace: String,
        database: String,
        table: String,
        timeout: Duration = Duration.ofSeconds(60),
    ): String {
        val resource = client.resources(MySQLInstance::class.java)
            .inNamespace(namespace)
            .withName(name)
            .get() ?: return "Instance not found."

        val rootPassword = resource.spec.rootPassword
        val jobName = "${name}-verify-${System.currentTimeMillis()}"
        val job = buildVerifyJob(name, namespace, database, table, rootPassword, jobName)
        client.batch().v1().jobs().inNamespace(namespace).resource(job).createOrReplace()

        val deadline = Instant.now().plus(timeout)
        while (Instant.now().isBefore(deadline)) {
            val current = client.batch().v1().jobs().inNamespace(namespace).withName(jobName).get()
            val status = current?.status
            if ((status?.succeeded ?: 0) > 0 || (status?.failed ?: 0) > 0) {
                break
            }
            Thread.sleep(2000)
        }

        val pod = client.pods()
            .inNamespace(namespace)
            .withLabel("job-name", jobName)
            .list()
            .items
            .firstOrNull()

        val logs = if (pod != null) {
            client.pods().inNamespace(namespace).withName(pod.metadata.name).log
        } else {
            "No verify job pod found."
        }

        client.batch().v1().jobs().inNamespace(namespace).withName(jobName).delete()
        return logs.trim()
    }

    private fun buildVerifyJob(
        name: String,
        namespace: String,
        database: String,
        table: String,
        rootPassword: String,
        jobName: String,
    ): Job {
        val targetHost = "${name}-0.${name}.${namespace}.svc.cluster.local"
        val command = """
            set -e
            until MYSQL_PWD=${'$'}TGT_PASSWORD mysqladmin ping -h $targetHost -P 3306 -uroot --silent; do
              sleep 2
            done
            DB_EXISTS=${'$'}(MYSQL_PWD=${'$'}TGT_PASSWORD mysql -h $targetHost -P 3306 -uroot -N -e "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='${database}';")
            if [ -z "${'$'}DB_EXISTS" ]; then
              echo "Database not found: ${database}"
              MYSQL_PWD=${'$'}TGT_PASSWORD mysql -h $targetHost -P 3306 -uroot -e "SHOW DATABASES;"
              exit 0
            fi
            TABLES=${'$'}(MYSQL_PWD=${'$'}TGT_PASSWORD mysql -h $targetHost -P 3306 -uroot -N -e "SHOW TABLES FROM ${database};")
            if [ -z "${'$'}TABLES" ]; then
              echo "No tables found in ${database}"
            else
              echo "${'$'}TABLES"
            fi
            if [ -z "${table}" ]; then
              echo "No table provided. Skipping row count."
              exit 0
            fi
            TABLE_EXISTS=${'$'}(MYSQL_PWD=${'$'}TGT_PASSWORD mysql -h $targetHost -P 3306 -uroot -N -e "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='${database}' AND TABLE_NAME='${table}';")
            if [ -z "${'$'}TABLE_EXISTS" ]; then
              echo "No table found: ${database}.${table}"
              exit 0
            fi
            MYSQL_PWD=${'$'}TGT_PASSWORD mysql -h $targetHost -P 3306 -uroot -e "SELECT COUNT(*) AS count FROM ${database}.${table};"
            MYSQL_PWD=${'$'}TGT_PASSWORD mysql -h $targetHost -P 3306 -uroot -e "SELECT * FROM ${database}.${table} LIMIT 10;"
        """.trimIndent()

        return JobBuilder()
            .withNewMetadata()
                .withName(jobName)
                .withNamespace(namespace)
            .endMetadata()
            .withNewSpec()
                .withBackoffLimit(1)
                .withNewTemplate()
                    .withNewSpec()
                        .addNewContainer()
                            .withName("mysql-verify")
                            .withImage("mysql:8.0")
                            .withCommand("sh", "-c", command)
                            .addNewEnv()
                                .withName("TGT_PASSWORD")
                                .withValue(rootPassword)
                            .endEnv()
                        .endContainer()
                        .withRestartPolicy("Never")
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build()
    }
}
