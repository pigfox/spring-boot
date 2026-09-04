package com.pigfox.ledger.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pigfox.ledger.TestFixtures;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.abi.TypeEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthSendTransaction;

/**
 * The point of these tests is the degradation contract: a node that is down, a
 * transaction the node refuses, and a contract that reverts must all produce a value
 * rather than an exception, because none of them should turn into a 5xx for an API caller.
 */
class AnchorServiceTest {

    private static final String TX_HASH =
            "0x1111111111111111111111111111111111111111111111111111111111111111";

    private Web3j web3j;
    private final byte[] payloadHash = Hash.sha3("payload".getBytes());

    @BeforeEach
    void setUp() {
        web3j = mock(Web3j.class);
    }

    private AnchorService enabledService() {
        return new AnchorService(web3j, TestFixtures.properties(true, TestFixtures.REGISTRY_ADDRESS));
    }

    @Test
    @DisplayName("anchoring is skipped, and the chain untouched, when the feature is off")
    void skipsWhenDisabled() {
        AnchorService service = new AnchorService(web3j, TestFixtures.properties(false, TestFixtures.REGISTRY_ADDRESS));

        AnchorResult result = service.anchor(payloadHash);

        assertThat(result.status()).isEqualTo(AnchorResult.Status.DISABLED);
        assertThat(result.anchored()).isFalse();
        assertThat(result.txHash()).isNull();
        assertThat(service.enabled()).isFalse();
        verifyNoInteractions(web3j);
    }

    @Test
    @DisplayName("anchoring is skipped when no registry address is configured")
    void skipsWhenNoRegistryAddress() {
        AnchorService service = new AnchorService(web3j, TestFixtures.properties(true, ""));

        assertThat(service.anchor(payloadHash).status()).isEqualTo(AnchorResult.Status.DISABLED);
        assertThat(service.signerOf(payloadHash)).isEmpty();
        assertThat(service.enabled()).isFalse();
        verifyNoInteractions(web3j);
    }

    @Test
    @DisplayName("anchoring is skipped when the registry address is blank")
    void skipsWhenRegistryAddressBlank() {
        AnchorService service = new AnchorService(web3j, TestFixtures.properties(true, "   "));

        assertThat(service.anchor(payloadHash).status()).isEqualTo(AnchorResult.Status.DISABLED);
        assertThat(service.enabled()).isFalse();
    }

    @Test
    @DisplayName("an accepted transaction returns its hash")
    void anchorsSuccessfully() throws Exception {
        stubNonce("0x2");
        stubSendRawTransaction(sendResponse(TX_HASH, null));

        AnchorResult result = enabledService().anchor(payloadHash);

        assertThat(result.status()).isEqualTo(AnchorResult.Status.ANCHORED);
        assertThat(result.anchored()).isTrue();
        assertThat(result.txHash()).isEqualTo(TX_HASH);
    }

    @Test
    @DisplayName("a node that refuses the transaction yields REJECTED, not an exception")
    void reportsRejection() throws Exception {
        stubNonce("0x0");
        stubSendRawTransaction(sendResponse(null, new Response.Error(-32000, "nonce too low")));

        AnchorResult result = enabledService().anchor(payloadHash);

        assertThat(result.status()).isEqualTo(AnchorResult.Status.REJECTED);
        assertThat(result.txHash()).isNull();
    }

    @Test
    @DisplayName("an unreachable node yields UNREACHABLE, not an exception")
    void reportsUnreachableNode() throws Exception {
        Request<?, EthGetTransactionCount> request = mock(Request.class);
        when(request.send()).thenThrow(new IOException("connection refused"));
        doReturn(request).when(web3j).ethGetTransactionCount(anyString(), any());

        AnchorResult result = enabledService().anchor(payloadHash);

        assertThat(result.status()).isEqualTo(AnchorResult.Status.UNREACHABLE);
        assertThat(result.anchored()).isFalse();
    }

    @Test
    @DisplayName("a runtime failure while broadcasting yields UNREACHABLE, not an exception")
    void reportsRuntimeFailure() throws Exception {
        stubNonce("0x1");
        Request<?, EthSendTransaction> request = mock(Request.class);
        when(request.send()).thenThrow(new IllegalStateException("transport closed"));
        doReturn(request).when(web3j).ethSendRawTransaction(anyString());

        assertThat(enabledService().anchor(payloadHash).status())
                .isEqualTo(AnchorResult.Status.UNREACHABLE);
    }

    @Test
    @DisplayName("a recorded signer is read back from the registry")
    void readsSignerFromRegistry() throws Exception {
        stubEthCall(ethCall("0x" + TypeEncoder.encode(new Address(TestFixtures.ADDRESS)), null));

        assertThat(enabledService().signerOf(payloadHash))
                .isPresent()
                .get()
                .asString()
                .isEqualToIgnoringCase(TestFixtures.ADDRESS);
    }

    @Test
    @DisplayName("the zero address means the hash was never anchored")
    void treatsZeroAddressAsUnanchored() throws Exception {
        String zero = "0x0000000000000000000000000000000000000000";
        stubEthCall(ethCall("0x" + TypeEncoder.encode(new Address(zero)), null));

        assertThat(enabledService().signerOf(payloadHash)).isEmpty();
    }

    @Test
    @DisplayName("a reverting call yields empty rather than propagating")
    void handlesRevert() throws Exception {
        stubEthCall(ethCall(null, new Response.Error(3, "execution reverted")));

        assertThat(enabledService().signerOf(payloadHash)).isEmpty();
    }

    @Test
    @DisplayName("an empty return value yields empty")
    void handlesEmptyReturnValue() throws Exception {
        stubEthCall(ethCall("0x", null));

        assertThat(enabledService().signerOf(payloadHash)).isEmpty();
    }

    @Test
    @DisplayName("an unreachable node during a read yields empty rather than propagating")
    void handlesUnreachableNodeOnRead() throws Exception {
        Request<?, EthCall> request = mock(Request.class);
        when(request.send()).thenThrow(new IOException("connection refused"));
        doReturn(request).when(web3j).ethCall(any(), any());

        assertThat(enabledService().signerOf(payloadHash)).isEmpty();
    }

    @Test
    @DisplayName("enabled reports true only with both the flag and an address")
    void reportsEnabledState() {
        assertThat(enabledService().enabled()).isTrue();
        assertThat(new AnchorService(web3j, TestFixtures.properties(true, null)).enabled()).isFalse();
    }

    private void stubNonce(String hexCount) throws Exception {
        EthGetTransactionCount count = new EthGetTransactionCount();
        count.setResult(hexCount);
        Request<?, EthGetTransactionCount> request = mock(Request.class);
        when(request.send()).thenReturn(count);
        doReturn(request).when(web3j).ethGetTransactionCount(anyString(), any());
    }

    private void stubSendRawTransaction(EthSendTransaction response) throws Exception {
        Request<?, EthSendTransaction> request = mock(Request.class);
        when(request.send()).thenReturn(response);
        doReturn(request).when(web3j).ethSendRawTransaction(anyString());
    }

    private void stubEthCall(EthCall response) throws Exception {
        Request<?, EthCall> request = mock(Request.class);
        when(request.send()).thenReturn(response);
        doReturn(request).when(web3j).ethCall(any(), any());
    }

    private EthSendTransaction sendResponse(String txHash, Response.Error error) {
        EthSendTransaction response = new EthSendTransaction();
        response.setResult(txHash);
        response.setError(error);
        return response;
    }

    private EthCall ethCall(String value, Response.Error error) {
        EthCall response = new EthCall();
        response.setResult(value);
        response.setError(error);
        return response;
    }
}
