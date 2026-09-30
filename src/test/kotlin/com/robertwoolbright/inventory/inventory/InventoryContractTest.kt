// Study notes: docs/learning/notes/test/inventory/InventoryContractTest.kt.md
package com.robertwoolbright.inventory.inventory

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleResponse
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.robertwoolbright.inventory.support.TestcontainersConfiguration
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.parser.OpenAPIV3Parser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract validation: every response each endpoint can give, checked against inventory.yaml by
 * Atlassian's OpenAPI validator (declared status, content type, and body schema). `InventoryItem`
 * is checked strictly: both fields required, and (the validator's default) no extra fields. Also
 * proves the validator rejects responses that break each of those rules.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class InventoryContractTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jdbcClient: JdbcClient,
) {

    /** Validates the request and the response; for requests that follow the contract. */
    private val validator = OpenApiInteractionValidator.createFor(strictContract()).build()

    // Why: the 400 tests send invalid requests on purpose. Only the response is on trial there,
    // so request errors are ignored instead of failing the test for doing its job.
    /** Validates the response only; for requests that break the contract on purpose. */
    private val responseOnlyValidator = OpenApiInteractionValidator.createFor(strictContract())
        .withLevelResolver(
            LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build()
        )
        .build()

    /** Starts every test from an empty table, since all tests in this context share one database. */
    @BeforeEach
    fun clearInventory() {
        jdbcClient.sql("DELETE FROM inventory").update()
    }

    private fun insert(skuId: String, quantity: Int) {
        jdbcClient.sql("INSERT INTO inventory (sku_id, quantity) VALUES (?, ?)")
            .params(skuId, quantity)
            .update()
    }

    private fun post(path: String, body: String): ResultActionsDsl =
        mockMvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    // GET /inventory

    /** 200 with an array of items. */
    @Test
    fun `GET inventory matches the contract`() {
        insert("gadget", 0)
        insert("widget", 5)

        mockMvc.get("/inventory").andExpect {
            status { isOk() }
            match(openApi().isValid(validator))
        }
    }

    /** 200 with an empty array. */
    @Test
    fun `GET inventory with no SKUs matches the contract`() {
        mockMvc.get("/inventory").andExpect {
            status { isOk() }
            match(openApi().isValid(validator))
        }
    }

    // GET /inventory/{skuId}

    /** 200 with one item. */
    @Test
    fun `GET inventory by SKU matches the contract`() {
        insert("widget", 5)

        mockMvc.get("/inventory/widget").andExpect {
            status { isOk() }
            match(openApi().isValid(validator))
        }
    }

    /** 404 with a text/plain error. */
    @Test
    fun `GET inventory by SKU for a missing SKU matches the contract`() {
        mockMvc.get("/inventory/missing").andExpect {
            status { isNotFound() }
            match(openApi().isValid(validator))
        }
    }

    // POST /inventory/{skuId}

    /** 200 with the updated item. */
    @Test
    fun `POST inventory matches the contract`() {
        post("/inventory/widget", """{"quantity": 3}""").andExpect {
            status { isOk() }
            match(openApi().isValid(validator))
        }
    }

    /** 400 with a text/plain error, for a request that breaks `minimum: 1`. */
    @Test
    fun `POST inventory with an invalid quantity matches the contract`() {
        post("/inventory/widget", """{"quantity": 0}""").andExpect {
            status { isBadRequest() }
            match(openApi().isValid(responseOnlyValidator))
        }
    }

    // POST /inventory/{skuId}/purchase

    /** 200 with the remaining inventory. */
    @Test
    fun `POST purchase matches the contract`() {
        insert("widget", 5)

        post("/inventory/widget/purchase", """{"quantity": 2}""").andExpect {
            status { isOk() }
            match(openApi().isValid(validator))
        }
    }

    /** 400 with a text/plain error, for a valid request the stock can't cover. */
    @Test
    fun `POST purchase with insufficient stock matches the contract`() {
        insert("widget", 1)

        post("/inventory/widget/purchase", """{"quantity": 2}""").andExpect {
            status { isBadRequest() }
            match(openApi().isValid(validator))
        }
    }

    /** 400 with a text/plain error, for a request that breaks `minimum: 1`. */
    @Test
    fun `POST purchase with an invalid quantity matches the contract`() {
        insert("widget", 5)

        post("/inventory/widget/purchase", """{"quantity": 0}""").andExpect {
            status { isBadRequest() }
            match(openApi().isValid(responseOnlyValidator))
        }
    }

    /** 404 with a text/plain error. */
    @Test
    fun `POST purchase for a missing SKU matches the contract`() {
        post("/inventory/missing/purchase", """{"quantity": 1}""").andExpect {
            status { isNotFound() }
            match(openApi().isValid(validator))
        }
    }

    // The validator itself: these responses are built by hand, not by the app.

    // Why: each check asserts the exact validation key, not just "has errors", so a response
    // rejected for some other reason can't make it pass.
    private fun assertRejected(expectedKey: String, response: SimpleResponse) {
        val report = validator.validateResponse("/inventory/widget", Request.Method.GET, response)
        val keys = report.messages.map { it.key }
        assertTrue(expectedKey in keys, "expected $expectedKey, got ${report.messages}")
    }

    private fun jsonItem(body: String): SimpleResponse =
        SimpleResponse.Builder.ok().withContentType("application/json").withBody(body).build()

    /** Control: a correct body passes, so the failures below come from what each one breaks. */
    @Test
    fun `the validator accepts a correct item`() {
        val report = validator.validateResponse(
            "/inventory/widget", Request.Method.GET, jsonItem("""{"skuId": "widget", "quantity": 5}"""),
        )

        assertFalse(report.hasErrors(), report.messages.toString())
    }

    /** Strict schema: a missing field is an error (the YAML alone would allow it). */
    @Test
    fun `the validator rejects an item with a missing field`() {
        assertRejected("validation.response.body.schema.required", jsonItem("""{"skuId": "widget"}"""))
    }

    /** Undeclared fields are an error: the validator's default, even though the YAML doesn't forbid them. */
    @Test
    fun `the validator rejects an item with an extra field`() {
        assertRejected(
            "validation.response.body.schema.additionalProperties",
            jsonItem("""{"skuId": "widget", "quantity": 5, "price": 9}"""),
        )
    }

    /** `InventoryItem.quantity` has `minimum: 0` in the YAML. */
    @Test
    fun `the validator rejects a negative quantity`() {
        assertRejected("validation.response.body.schema.minimum", jsonItem("""{"skuId": "widget", "quantity": -1}"""))
    }

    /** A 200 must be `application/json`. */
    @Test
    fun `the validator rejects an item sent as plain text`() {
        assertRejected(
            "validation.response.contentType.notAllowed",
            SimpleResponse.Builder.ok().withContentType("text/plain").withBody("widget: 5").build(),
        )
    }

    /** A 404 must be `text/plain`, not a JSON error object. */
    @Test
    fun `the validator rejects an error sent as JSON`() {
        assertRejected(
            "validation.response.contentType.notAllowed",
            SimpleResponse.Builder.notFound().withContentType("application/json").withBody("""{"error": "SKU not found"}""").build(),
        )
    }

    /** Only the statuses the YAML lists are allowed, so a 500 (or Spring's default 415) would fail. */
    @Test
    fun `the validator rejects an undeclared status`() {
        assertRejected(
            "validation.response.status.unknown",
            SimpleResponse.Builder.serverError().withContentType("text/plain").withBody("Internal error").build(),
        )
    }

    private companion object {
        // Why: the YAML marks no InventoryItem field as required, so a literal check would accept `{}`.
        // Both are required here (decided 2026-09-30); the YAML itself is unchanged.
        /** inventory.yaml as parsed from the classpath, with both `InventoryItem` fields required. */
        fun strictContract(): OpenAPI {
            val yaml = checkNotNull(InventoryContractTest::class.java.getResource("/openapi/inventory.yaml")) {
                "inventory.yaml is not on the classpath"
            }.readText()
            val contract = checkNotNull(OpenAPIV3Parser().readContents(yaml).openAPI) { "inventory.yaml did not parse" }
            val item = contract.components.schemas.getValue("InventoryItem")
            item.required = listOf("skuId", "quantity")
            return contract
        }
    }
}
