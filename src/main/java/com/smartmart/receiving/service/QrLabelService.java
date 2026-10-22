package com.smartmart.receiving.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.smartmart.common.exception.BusinessException;
import com.smartmart.product.entity.Product;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds, validates and renders versioned QR carton labels.
 * The backend never trusts an arbitrary decoded payload: parsing enforces the exact
 * version/shape and callers re-validate the referenced product, batch and receipt.
 * Labels contain only a product reference and a declared carton size - never secrets,
 * credentials or customer data.
 */
@Service
public class QrLabelService {

    public static final int PAYLOAD_VERSION = 1;

    public record CartonQrPayload(
            @JsonProperty("version") int version,
            @JsonProperty("cartonId") String cartonId,
            @JsonProperty("productId") Long productId,
            @JsonProperty("batchCode") String batchCode,
            @JsonProperty("unitsPerCarton") Integer unitsPerCarton
    ) {}

    /** Builds and renders a printable PNG QR label for a validated carton. */
    public byte[] renderLabel(CartonQrPayload payload) {
        try {
            String json = PayloadJson.write(payload);
            BitMatrix matrix = new QRCodeWriter().encode(json, BarcodeFormat.QR_CODE, 320, 320,
                    Map.of(EncodeHintType.MARGIN, 1));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException("Could not generate the carton QR label",
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "QR_GENERATION_FAILED");
        }
    }

    /** Parses a raw scanned string into a validated payload; rejects junk early with clear errors. */
    public CartonQrPayload parseScanned(String raw) {
        if (raw == null || raw.isBlank()) throw BusinessException.badRequest("Scanned code is empty");
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            CartonQrPayload payload = PayloadJson.read(trimmed);
            validate(payload);
            return payload;
        }
        // Manual entry / plain carton code: the rest of the payload is looked up from the issued record.
        return new CartonQrPayload(PAYLOAD_VERSION, trimmed.toUpperCase(), null, null, null);
    }

    public void validate(CartonQrPayload payload) {
        if (payload == null) throw BusinessException.badRequest("Invalid QR payload");
        if (payload.version() != PAYLOAD_VERSION) throw BusinessException.badRequest("Unsupported QR version: " + payload.version());
        if (payload.cartonId() == null || payload.cartonId().isBlank()) throw BusinessException.badRequest("QR payload has no carton id");
        if (payload.productId() == null || payload.productId() <= 0) throw BusinessException.badRequest("QR payload has an unknown product");
        if (payload.unitsPerCarton() == null || payload.unitsPerCarton() <= 0) throw BusinessException.badRequest("QR payload has invalid units per carton");
    }

    public CartonQrPayload forCarton(Product product, String cartonId, String batchCode, int unitsPerCarton) {
        CartonQrPayload payload = new CartonQrPayload(PAYLOAD_VERSION, cartonId, product.getId(), batchCode, unitsPerCarton);
        validate(payload);
        return payload;
    }

    /** Minimal JSON IoC-free helpers so parsing never depends on untrusted data tripping a full mapper. */
    static final class PayloadJson {
        static String write(CartonQrPayload p) {
            StringBuilder sb = new StringBuilder();
            build(p, sb);
            return sb.toString();
        }

        private static void build(CartonQrPayload p, StringBuilder sb) {
            Map<String, Object> fields = new HashMap<>();
            fields.put("version", p.version());
            fields.put("cartonId", p.cartonId());
            if (p.productId() != null) fields.put("productId", p.productId());
            if (p.batchCode() != null && !p.batchCode().isBlank()) fields.put("batchCode", p.batchCode());
            if (p.unitsPerCarton() != null) fields.put("unitsPerCarton", p.unitsPerCarton());
            sb.setLength(0);
            sb.append('{');
            boolean first = true;
            for (var e : fields.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('\"').append(e.getKey()).append("\":");
                Object v = e.getValue();
                if (v instanceof Integer || v instanceof Long) sb.append(v);
                else sb.append('\"').append(escape(String.valueOf(v))).append('\"');
            }
            sb.append('}');
        }

        static CartonQrPayload read(String json) {
            Integer version = null;
            String cartonId = null;
            Long productId = null;
            String batchCode = null;
            Integer units = null;
            for (java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"([A-Za-z]+)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|-?\\d+)").matcher(json); m.find(); ) {
                String key = m.group(1);
                String strVal = m.group(3);
                String numVal = m.group(2);
                switch (key) {
                    case "version" -> version = Integer.parseInt(numVal);
                    case "cartonId" -> cartonId = strVal != null ? strVal : null;
                    case "productId" -> productId = Long.parseLong(numVal);
                    case "batchCode" -> batchCode = strVal;
                    case "unitsPerCarton" -> units = Integer.parseInt(numVal);
                    default -> { /* ignore unknown keys defensively */ }
                }
            }
            if (version == null || cartonId == null) throw BusinessException.badRequest("Unrecognised QR payload");
            return new CartonQrPayload(version, unescape(cartonId), productId, batchCode != null ? unescape(batchCode) : null, units);
        }

        private static String escape(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
        private static String unescape(String s) { return s.replace("\\\"", "\"").replace("\\\\", "\\"); }
    }
}
