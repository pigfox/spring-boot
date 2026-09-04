package com.pigfox.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the ledger node.
 *
 * <p>The node is API-first: {@code src/main/resources/openapi/asset-api.yaml} is the
 * contract and every handler here is written against it.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LedgerNodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerNodeApplication.class, args);
    }
}
