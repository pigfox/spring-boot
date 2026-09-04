package com.pigfox.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigfox.ledger.IntegrationTestBase;
import com.pigfox.ledger.config.OpenApiConfig;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/**
 * Holds the API-first claim to account.
 *
 * <p>{@code src/main/resources/openapi/asset-api.yaml} is the contract, and springdoc
 * describes what the code actually serves. Anyone can write a spec and then drift from it;
 * this test compares the two on every build, so a route, an operation id, or a schema that
 * exists in one and not the other fails CI rather than quietly misleading a client.
 */
class OpenApiContractTest extends IntegrationTestBase {

    @Autowired
    private ObjectMapper objectMapper;

    private Map<String, Object> contract;
    private JsonNode generated;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void loadBoth() throws Exception {
        try (InputStream spec = new ClassPathResource("openapi/asset-api.yaml").getInputStream()) {
            contract = new Yaml().load(spec);
        }
        String body = mockMvc.perform(get("/v3/api-docs").with(jwt()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        generated = objectMapper.readTree(body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> contractPaths() {
        return (Map<String, Object>) contract.get("paths");
    }

    private Set<String> generatedPathNames() {
        return new TreeSet<>(iterate(generated.get("paths").fieldNames()));
    }

    private List<String> iterate(java.util.Iterator<String> names) {
        List<String> collected = new ArrayList<>();
        names.forEachRemaining(collected::add);
        return collected;
    }

    @Test
    @DisplayName("the contract and the implementation expose exactly the same paths")
    void pathsAgree() {
        assertThat(generatedPathNames()).isEqualTo(new TreeSet<>(contractPaths().keySet()));
    }

    @Test
    @DisplayName("the contract declares the five documented operations and nothing else")
    void contractDeclaresExpectedOperations() {
        assertThat(contractOperationIds()).containsExactlyInAnyOrder(
                "issueToken", "createAsset", "listAssets", "getAsset", "verifyAsset");
    }

    @Test
    @DisplayName("every path and method pair maps to the same operation id on both sides")
    @SuppressWarnings("unchecked")
    void operationIdsAgree() {
        contractPaths().forEach((path, methodsObject) -> {
            Map<String, Object> methods = (Map<String, Object>) methodsObject;
            methods.forEach((method, operationObject) -> {
                Map<String, Object> operation = (Map<String, Object>) operationObject;
                JsonNode generatedOperation = generated.get("paths").get(path).get(method);

                assertThat(generatedOperation)
                        .as("%s %s is served by the implementation", method.toUpperCase(), path)
                        .isNotNull();
                assertThat(generatedOperation.get("operationId").asText())
                        .as("operationId for %s %s", method.toUpperCase(), path)
                        .isEqualTo(operation.get("operationId"));
            });
        });
    }

    @Test
    @DisplayName("the implementation serves no method the contract does not declare")
    @SuppressWarnings("unchecked")
    void implementationAddsNoUndocumentedMethod() {
        contractPaths().forEach((path, methodsObject) -> {
            Set<String> declared = ((Map<String, Object>) methodsObject).keySet();
            Set<String> served = new LinkedHashSet<>(iterate(generated.get("paths").get(path).fieldNames()));

            assertThat(served).as("methods served on %s", path).isEqualTo(new LinkedHashSet<>(declared));
        });
    }

    @Test
    @DisplayName("both sides name the same API at the same version")
    @SuppressWarnings("unchecked")
    void identityAgrees() {
        Map<String, Object> info = (Map<String, Object>) contract.get("info");

        assertThat(generated.get("info").get("title").asText())
                .isEqualTo(info.get("title"))
                .isEqualTo(OpenApiConfig.TITLE);
        assertThat(generated.get("info").get("version").asText())
                .isEqualTo(info.get("version"))
                .isEqualTo(OpenApiConfig.VERSION);
    }

    @Test
    @DisplayName("both sides are the same OpenAPI major and minor version")
    void openApiVersionAgrees() {
        String contractVersion = String.valueOf(contract.get("openapi"));
        String generatedVersion = generated.get("openapi").asText();

        assertThat(majorMinor(generatedVersion)).isEqualTo(majorMinor(contractVersion));
    }

    @Test
    @DisplayName("both sides define the same component schemas")
    @SuppressWarnings("unchecked")
    void schemasAgree() {
        Map<String, Object> components = (Map<String, Object>) contract.get("components");
        Set<String> declared = new TreeSet<>(((Map<String, Object>) components.get("schemas")).keySet());
        Set<String> produced =
                new TreeSet<>(iterate(generated.get("components").get("schemas").fieldNames()));

        assertThat(produced).isEqualTo(declared);
    }

    @Test
    @DisplayName("both sides declare the same bearer security scheme")
    @SuppressWarnings("unchecked")
    void securitySchemeAgrees() {
        Map<String, Object> components = (Map<String, Object>) contract.get("components");
        Map<String, Object> declared =
                (Map<String, Object>) ((Map<String, Object>) components.get("securitySchemes"))
                        .get(OpenApiConfig.BEARER_SCHEME);
        JsonNode produced =
                generated.get("components").get("securitySchemes").get(OpenApiConfig.BEARER_SCHEME);

        assertThat(produced).isNotNull();
        assertThat(produced.get("type").asText()).isEqualTo(declared.get("type"));
        assertThat(produced.get("scheme").asText()).isEqualTo(declared.get("scheme"));
        assertThat(produced.get("bearerFormat").asText()).isEqualTo(declared.get("bearerFormat"));
    }

    @Test
    @DisplayName("the token operation is the only one exempt from bearer authentication")
    @SuppressWarnings("unchecked")
    void tokenOperationIsTheOnlyPublicOne() {
        JsonNode tokenOperation = generated.get("paths").get("/api/v1/auth/token").get("post");

        assertThat(tokenOperation.get("security")).isNotNull();
        assertThat(tokenOperation.get("security")).isEmpty();
        contractPaths().forEach((path, methodsObject) -> {
            if (!"/api/v1/auth/token".equals(path)) {
                ((Map<String, Object>) methodsObject).keySet().forEach(method ->
                        assertThat(generated.get("paths").get(path).get(method).get("security"))
                                .as("%s %s inherits the global bearer requirement", method, path)
                                .isNull());
            }
        });
    }

    @Test
    @DisplayName("the implementation requires a bearer token globally")
    void globalSecurityRequirementIsPresent() {
        assertThat(generated.get("security")).isNotNull();
        assertThat(generated.get("security").findValuesAsText("bearerAuth")).isNotNull();
        assertThat(generated.get("security").toString()).contains(OpenApiConfig.BEARER_SCHEME);
    }

    @Test
    @DisplayName("path templated operations take the id parameter the contract describes")
    @SuppressWarnings("unchecked")
    void pathParametersAgree() {
        contractPaths().keySet().stream()
                .filter(path -> path.contains("{id}"))
                .forEach(path -> ((Map<String, Object>) contractPaths().get(path)).keySet()
                        .forEach(method -> {
                            JsonNode parameters = generated.get("paths").get(path).get(method).get("parameters");
                            assertThat(parameters).as("parameters on %s %s", method, path).isNotNull();
                            assertThat(parameters.toString()).contains("\"name\":\"id\"").contains("\"in\":\"path\"");
                        }));
    }

    @Test
    @DisplayName("error responses are documented as problem details on both sides")
    @SuppressWarnings("unchecked")
    void errorResponsesAgree() {
        contractPaths().forEach((path, methodsObject) ->
                ((Map<String, Object>) methodsObject).forEach((method, operationObject) -> {
                    Map<String, Object> responses =
                            (Map<String, Object>) ((Map<String, Object>) operationObject).get("responses");
                    JsonNode generatedResponses = generated.get("paths").get(path).get(method).get("responses");

                    assertThat(new TreeSet<>(iterate(generatedResponses.fieldNames())))
                            .as("status codes documented for %s %s", method.toUpperCase(), path)
                            .isEqualTo(new TreeSet<>(responses.keySet()));
                }));
    }

    private Set<String> contractOperationIds() {
        Set<String> ids = new TreeSet<>();
        contractPaths().values().forEach(methodsObject ->
                castMap(methodsObject).values().forEach(operationObject ->
                        ids.add(String.valueOf(castMap(operationObject).get("operationId")))));
        return ids;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private String majorMinor(String version) {
        String[] parts = version.split("\\.");
        return parts[0] + "." + parts[1];
    }
}
