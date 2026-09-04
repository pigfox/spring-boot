package com.pigfox.springboot.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigfox.springboot.IntegrationTestBase;
import com.pigfox.springboot.TestFixtures;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end exercise of the contract over the real filter chain: obtain a token, use it,
 * and confirm the routes behave as {@code asset-api.yaml} says they do.
 */
class AssetApiIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ObjectMapper objectMapper;

    private String token(String clientId, String clientSecret) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("clientId", clientId, "clientSecret", clientSecret));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private String token() throws Exception {
        return token(TestFixtures.CLIENT_ID, TestFixtures.CLIENT_SECRET);
    }

    private MockHttpServletRequestBuilder createAsset(String name) throws Exception {
        return post("/api/v1/assets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "name", name,
                        "assetType", "REAL_ESTATE",
                        "owner", "Estate JV",
                        "metadata", Map.of("jurisdiction", "GB"))));
    }

    private JsonNode register(String name) throws Exception {
        MvcResult result = mockMvc.perform(createAsset(name).header("Authorization", "Bearer " + token()))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("the token endpoint issues a bearer token to a valid client")
    void issuesToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientId", TestFixtures.CLIENT_ID,
                                "clientSecret", TestFixtures.CLIENT_SECRET))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.scope").value("ledger.read ledger.write"));
    }

    @Test
    @DisplayName("the token endpoint rejects a bad secret with 401 and no hint")
    void rejectsBadCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientId", TestFixtures.CLIENT_ID,
                                "clientSecret", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid client credentials"));
    }

    @Test
    @DisplayName("the token endpoint validates its request body")
    void rejectsEmptyCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientId", "", "clientSecret", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failure"));
    }

    @Test
    @DisplayName("a registered asset comes back signed, with a Location header")
    void registersAsset() throws Exception {
        MvcResult result = mockMvc.perform(createAsset("Unit 4B")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Unit 4B"))
                .andExpect(jsonPath("$.owner").value("Estate JV"))
                .andExpect(jsonPath("$.metadata.jurisdiction").value("GB"))
                .andExpect(jsonPath("$.payloadHash").isNotEmpty())
                .andExpect(jsonPath("$.signature").isNotEmpty())
                .andExpect(jsonPath("$.signerAddress").value(TestFixtures.ADDRESS))
                .andExpect(jsonPath("$.anchored").value(false))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("payloadHash").asText()).matches("^0x[0-9a-f]{64}$");
        assertThat(body.get("signature").asText()).matches("^0x[0-9a-f]{130}$");
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/assets/" + body.get("id").asText());
    }

    @Test
    @DisplayName("an unreachable chain leaves anchorTxHash absent rather than failing")
    void degradesWhenChainUnavailable() throws Exception {
        mockMvc.perform(createAsset("Unanchored").header("Authorization", "Bearer " + token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchored").value(false))
                .andExpect(jsonPath("$.anchorTxHash").doesNotExist());
    }

    @Test
    @DisplayName("registration publishes an event")
    void publishesEvent() throws Exception {
        register("Published");

        org.mockito.Mockito.verify(assetEventPublisher, org.mockito.Mockito.atLeastOnce())
                .publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("an invalid body is rejected with a 400 naming the offending fields")
    void rejectsInvalidAsset() throws Exception {
        mockMvc.perform(post("/api/v1/assets")
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "", "assetType", "", "owner", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failure"))
                .andExpect(jsonPath("$.detail").value(
                        "assetType must not be blank; name must not be blank; owner must not be blank"));
    }

    @Test
    @DisplayName("a registered asset can be fetched by id")
    void fetchesAssetById() throws Exception {
        String id = register("Fetchable").get("id").asText();

        mockMvc.perform(get("/api/v1/assets/" + id).header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Fetchable"));
    }

    @Test
    @DisplayName("an unknown id is a 404 problem detail")
    void returnsNotFoundForUnknownId() throws Exception {
        mockMvc.perform(get("/api/v1/assets/does-not-exist")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Asset not found"));
    }

    @Test
    @DisplayName("listing returns registered assets")
    void listsAssets() throws Exception {
        register("Listed");

        mockMvc.perform(get("/api/v1/assets").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.hasItem("Listed")));
    }

    @Test
    @DisplayName("verification recovers the signer and reports the signature valid")
    void verifiesAsset() throws Exception {
        String id = register("Verifiable").get("id").asText();

        mockMvc.perform(post("/api/v1/assets/" + id + "/verify")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(id))
                .andExpect(jsonPath("$.signatureValid").value(true))
                .andExpect(jsonPath("$.recoveredAddress").value(TestFixtures.ADDRESS))
                .andExpect(jsonPath("$.signerAddress").value(TestFixtures.ADDRESS))
                .andExpect(jsonPath("$.anchored").value(false))
                .andExpect(jsonPath("$.verifiedAt").isNotEmpty());
    }

    @Test
    @DisplayName("verifying an unknown id is a 404")
    void failsVerifyingUnknownAsset() throws Exception {
        mockMvc.perform(post("/api/v1/assets/does-not-exist/verify")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("timestamps are serialised as ISO-8601, not epoch numbers")
    void serialisesTimestampsAsIso() throws Exception {
        JsonNode body = register("Timestamped");

        assertThat(body.get("createdAt").asText()).matches("^\\d{4}-\\d{2}-\\d{2}T.*Z$");
    }
}
