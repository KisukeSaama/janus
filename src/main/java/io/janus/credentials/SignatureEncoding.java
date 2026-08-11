package io.janus.credentials;

import java.util.Base64;
import java.util.HexFormat;

/**
 * How a computed signature is written down before it is sent.
 *
 * <p>There is no reasoning to be had here: each API simply decided. A request signed correctly and
 * encoded the other way is rejected exactly as a wrong one is, which is why this is recorded rather
 * than defaulted quietly.
 */
public enum SignatureEncoding {
    /** Lowercase hexadecimal. Binance, Coinbase, and most of the rest. */
    HEX {
        @Override
        public String encode(byte[] signature) {
            return HexFormat.of().formatHex(signature);
        }
    },
    /** Standard base64, padded. Kraken. */
    BASE64 {
        @Override
        public String encode(byte[] signature) {
            return Base64.getEncoder().encodeToString(signature);
        }
    };

    public abstract String encode(byte[] signature);
}
