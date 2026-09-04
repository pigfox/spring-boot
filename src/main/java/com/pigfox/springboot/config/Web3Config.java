package com.pigfox.springboot.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

/** Wires the JSON-RPC client. Building it opens no connection, so startup never blocks. */
@Configuration
public class Web3Config {

    /**
     * @param properties node configuration supplying {@code ETH_RPC_URL}
     * @return a JSON-RPC client for the configured endpoint
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public Web3j web3j(LedgerProperties properties) {
        return Web3j.build(new HttpService(properties.chain().rpcUrl()));
    }
}
