package com.pigfox.springboot.chain;

import com.pigfox.springboot.config.LedgerProperties;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.utils.Numeric;

/**
 * Anchors payload hashes in the {@code AssetRegistry} contract and reads them back.
 *
 * <p>The chain is treated as a best-effort side channel. A node that is down, a
 * transaction the node refuses, or a contract that reverts must never turn into a 5xx
 * for the caller: every path here returns a value instead of propagating, because an
 * asset with a valid local signature is still a useful record. The distinction between
 * "unreachable" and "rejected" is preserved so operators can tell an outage from a bad
 * configuration.
 */
@Service
public class AnchorService {

    private static final Logger log = LoggerFactory.getLogger(AnchorService.class);

    /** Solidity: {@code function registerAsset(bytes32 payloadHash)}. */
    private static final String REGISTER_FUNCTION = "registerAsset";
    /** Solidity: {@code function signerOf(bytes32 payloadHash) view returns (address)}. */
    private static final String SIGNER_OF_FUNCTION = "signerOf";
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    private final Web3j web3j;
    private final LedgerProperties.Chain chain;
    private final Credentials credentials;

    public AnchorService(Web3j web3j, LedgerProperties properties) {
        this.web3j = web3j;
        this.chain = properties.chain();
        this.credentials = Credentials.create(
                ECKeyPair.create(Numeric.toBigInt(properties.crypto().signingKey().trim())));
    }

    /**
     * Submits a payload hash to the registry contract.
     *
     * @param payloadHash raw 32-byte keccak256 digest
     * @return the transaction hash when the chain accepted it, otherwise a result
     *         explaining why it did not; never throws
     */
    public AnchorResult anchor(byte[] payloadHash) {
        if (!enabled()) {
            log.debug("Anchoring disabled, skipping chain write");
            return AnchorResult.none(AnchorResult.Status.DISABLED);
        }
        try {
            String data = FunctionEncoder.encode(new Function(
                    REGISTER_FUNCTION, List.of(new Bytes32(payloadHash)), List.of()));
            BigInteger nonce = web3j
                    .ethGetTransactionCount(credentials.getAddress(), DefaultBlockParameterName.PENDING)
                    .send()
                    .getTransactionCount();
            RawTransaction transaction = RawTransaction.createTransaction(
                    nonce, chain.gasPrice(), chain.gasLimit(), chain.registryAddress(), data);
            String signed = Numeric.toHexString(
                    TransactionEncoder.signMessage(transaction, chain.chainId(), credentials));
            EthSendTransaction response = web3j.ethSendRawTransaction(signed).send();
            if (response.hasError()) {
                log.warn("Chain rejected anchor transaction: {}", response.getError().getMessage());
                return AnchorResult.none(AnchorResult.Status.REJECTED);
            }
            log.info("Anchored payload hash in tx {}", response.getTransactionHash());
            return AnchorResult.anchored(response.getTransactionHash());
        } catch (Exception e) {
            // Includes IOException from an unreachable node and any web3j runtime failure.
            log.warn("Anchoring unavailable, continuing without a chain anchor: {}", e.getMessage());
            return AnchorResult.none(AnchorResult.Status.UNREACHABLE);
        }
    }

    /**
     * Reads the signer the registry recorded for a payload hash.
     *
     * @param payloadHash raw 32-byte keccak256 digest
     * @return the recorded signer address, or empty when anchoring is disabled, the node
     *         is unreachable, the call reverts, or the hash is unknown to the contract
     */
    public Optional<String> signerOf(byte[] payloadHash) {
        if (!enabled()) {
            return Optional.empty();
        }
        try {
            Function function = new Function(
                    SIGNER_OF_FUNCTION,
                    List.of(new Bytes32(payloadHash)),
                    List.of(new TypeReference<Address>() {
                    }));
            EthCall response = web3j
                    .ethCall(Transaction.createEthCallTransaction(
                                    credentials.getAddress(), chain.registryAddress(),
                                    FunctionEncoder.encode(function)),
                            DefaultBlockParameterName.LATEST)
                    .send();
            if (response.isReverted()) {
                log.warn("Registry call reverted: {}", response.getRevertReason());
                return Optional.empty();
            }
            List<Type> decoded =
                    FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
            if (decoded.isEmpty()) {
                return Optional.empty();
            }
            String address = decoded.get(0).getValue().toString();
            return ZERO_ADDRESS.equalsIgnoreCase(address) ? Optional.empty() : Optional.of(address);
        } catch (Exception e) {
            log.warn("Registry lookup unavailable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** @return true when both the feature flag and a registry address are present */
    public boolean enabled() {
        return chain.enabled()
                && chain.registryAddress() != null
                && !chain.registryAddress().isBlank();
    }
}
