// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.24;

/// @title AssetRegistry
/// @notice Maps a keccak256 payload hash to the address that registered it.
/// @dev The contract stores a hash, never the asset payload. Anything written here is
///      permanently public, so the payload stays off chain and only its digest is
///      anchored; that keeps the anchor useful as proof of existence without leaking the
///      document it proves.
contract AssetRegistry {
    /// @notice Signer recorded for a payload hash, or the zero address if unknown.
    mapping(bytes32 => address) private signers;

    /// @notice Block timestamp at which a payload hash was first anchored.
    mapping(bytes32 => uint256) private anchoredAt;

    /// @notice Emitted once per payload hash, when it is first anchored.
    /// @param payloadHash keccak256 digest of the canonical asset payload
    /// @param signer address that submitted the anchor
    /// @param anchoredAtBlockTime block timestamp of the anchoring transaction
    event AssetAnchored(
        bytes32 indexed payloadHash,
        address indexed signer,
        uint256 anchoredAtBlockTime
    );

    /// @notice Raised when a hash has already been anchored.
    error AlreadyAnchored(bytes32 payloadHash, address signer);
    /// @notice Raised when the zero hash is submitted.
    error EmptyPayloadHash();

    /// @notice Anchors a payload hash against the caller.
    /// @dev First write wins. Allowing an overwrite would let a later caller replace the
    ///      recorded signer, which would destroy the only property the anchor provides.
    /// @param payloadHash keccak256 digest of the canonical asset payload
    function registerAsset(bytes32 payloadHash) external {
        if (payloadHash == bytes32(0)) {
            revert EmptyPayloadHash();
        }
        address existing = signers[payloadHash];
        if (existing != address(0)) {
            revert AlreadyAnchored(payloadHash, existing);
        }
        signers[payloadHash] = msg.sender;
        anchoredAt[payloadHash] = block.timestamp;
        emit AssetAnchored(payloadHash, msg.sender, block.timestamp);
    }

    /// @notice Reads the signer recorded for a payload hash.
    /// @param payloadHash keccak256 digest to look up
    /// @return signer the recording address, or the zero address when never anchored
    function signerOf(bytes32 payloadHash) external view returns (address signer) {
        return signers[payloadHash];
    }

    /// @notice Reads when a payload hash was anchored.
    /// @param payloadHash keccak256 digest to look up
    /// @return blockTime the anchoring block timestamp, or zero when never anchored
    function anchoredAtOf(bytes32 payloadHash) external view returns (uint256 blockTime) {
        return anchoredAt[payloadHash];
    }

    /// @notice Convenience predicate over {signerOf}.
    /// @param payloadHash keccak256 digest to look up
    /// @return anchored true when the hash has been anchored
    function isAnchored(bytes32 payloadHash) external view returns (bool anchored) {
        return signers[payloadHash] != address(0);
    }
}
