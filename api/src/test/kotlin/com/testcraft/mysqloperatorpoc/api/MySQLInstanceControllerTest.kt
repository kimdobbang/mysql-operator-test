package com.testcraft.mysqloperatorpoc.api

import com.testcraft.mysqloperatorpoc.operator.resource.mysql.MySQLInstance
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.MySQLSpec
import io.fabric8.kubernetes.api.model.KubernetesResourceList
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
class MySQLInstanceControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @MockBean(answer = Answers.RETURNS_DEEP_STUBS)
    private lateinit var client: KubernetesClient

    @MockBean
    private lateinit var opsService: MySQLInstanceOpsService

    @MockBean
    private lateinit var validationService: MySQLInstanceValidationService

    @MockBean
    private lateinit var crdService: CrdService

    @BeforeEach
    fun setUp() {
        @Suppress("UNCHECKED_CAST")
        val crClient = Mockito.mock(MixedOperation::class.java, Answers.RETURNS_DEEP_STUBS)
            as MixedOperation<MySQLInstance, KubernetesResourceList<MySQLInstance>, Resource<MySQLInstance>>
        @Suppress("UNCHECKED_CAST")
        val list = Mockito.mock(KubernetesResourceList::class.java) as KubernetesResourceList<MySQLInstance>
        Mockito.`when`(client.resources(MySQLInstance::class.java)).thenReturn(crClient)
        Mockito.`when`(crClient.inNamespace("default")).thenReturn(crClient)
        Mockito.`when`(crClient.list()).thenReturn(list)
        Mockito.`when`(list.items).thenReturn(emptyList())
        Mockito.`when`(
            validationService.validateForApply(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(MySQLSpec::class.java) ?: MySQLSpec()
            )
        )
            .thenReturn(MySQLInstanceValidationService.ValidationResult())
    }

    @Test
    fun `create mysql instance returns 201`() {
        val payload = """
            {
              "name": "mysql-demo",
              "namespace": "default"
            }
        """.trimIndent()

        mockMvc.post("/api/mysqlinstances") {
            contentType = MediaType.APPLICATION_JSON
            content = payload
        }.andExpect {
            status { isCreated() }
            jsonPath("$.name") { value("mysql-demo") }
            jsonPath("$.namespace") { value("default") }
        }
    }

    @Test
    fun `list mysql instances returns 200`() {
        mockMvc.get("/api/mysqlinstances") {
            param("namespace", "default")
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `status returns not found message`() {
        mockMvc.get("/api/mysqlinstances/mysql-demo/status") {
            param("namespace", "default")
        }.andExpect {
            status { isOk() }
            jsonPath("$.message") { value("Not found") }
        }
    }

    @Test
    fun `resources returns 200`() {
        mockMvc.get("/api/mysqlinstances/mysql-demo/resources") {
            param("namespace", "default")
        }.andExpect {
            status { isOk() }
            jsonPath("$.name") { value("mysql-demo") }
        }
    }

    @Test
    fun `verify returns output`() {
        Mockito.`when`(opsService.verifyData("mysql-demo", "default", "testcraft", "users"))
            .thenReturn("ok")
        mockMvc.get("/api/mysqlinstances/mysql-demo/verify") {
            param("namespace", "default")
        }.andExpect {
            status { isOk() }
            jsonPath("$.output") { value("ok") }
        }
    }

    @Test
    fun `restart returns 200`() {
        mockMvc.post("/api/mysqlinstances/mysql-demo/restart") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isOk() }
            jsonPath("$.name") { value("mysql-demo") }
            jsonPath("$.namespace") { value("default") }
            jsonPath("$.action") { value("restart") }
        }
    }

    @Test
    fun `reset delete returns delete action`() {
        Mockito.`when`(opsService.triggerReset("mysql-demo", "default", "delete"))
            .thenReturn(true)
        mockMvc.post("/api/mysqlinstances/mysql-demo/reset") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "action": "delete" }"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.action") { value("delete") }
        }
    }

    @Test
    fun `clone returns 200`() {
        val payload = """
            {
              "initStrategy": "CLONE",
              "cloneSource": {
                "host": "source-mysql",
                "port": 3306,
                "username": "root",
                "password": "password",
                "database": "sample"
              }
            }
        """.trimIndent()

        mockMvc.post("/api/mysqlinstances/mysql-demo/clone") {
            contentType = MediaType.APPLICATION_JSON
            content = payload
        }.andExpect {
            status { isOk() }
            jsonPath("$.action") { value("clone") }
        }
    }
}
