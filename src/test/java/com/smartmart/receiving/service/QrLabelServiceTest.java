package com.smartmart.receiving.service;

import com.smartmart.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QrLabelServiceTest {

    private final QrLabelService service = new QrLabelService();

    @Test
    void buildsVersionedPayloadAndRendersPng() {
        var payload = new QrLabelService.CartonQrPayload(1, "CTN-0001042", 7L, "MILK-2026-10-B02", 12);
        byte[] png = service.renderLabel(payload);
        // PNG magic number
        assertThat(png.length).isGreaterThan(200);
        assertThat(png[0]).isEqualTo((byte) 0x89);
        assertThat(png[1]).isEqualTo((byte) 'P');
        assertThat(png[2]).isEqualTo((byte) 'N');
        assertThat(png[3]).isEqualTo((byte) 'G');
    }

    @Test
    void parsesScannedJsonPayload() {
        String raw = "{\"version\":1,\"cartonId\":\"CTN-0001042\",\"productId\":7,\"batchCode\":\"MILK-2026-10-B02\",\"unitsPerCarton\":12}";
        var payload = service.parseScanned(raw);
        assertThat(payload.version()).isEqualTo(1);
        assertThat(payload.cartonId()).isEqualTo("CTN-0001042");
        assertThat(payload.productId()).isEqualTo(7L);
        assertThat(payload.batchCode()).isEqualTo("MILK-2026-10-B02");
        assertThat(payload.unitsPerCarton()).isEqualTo(12);
    }

    @Test
    void rejectsUnsupportedVersion() {
        assertThatThrownBy(() -> service.parseScanned("{\"version\":9,\"cartonId\":\"CTN-1\",\"productId\":7,\"unitsPerCarton\":12}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("version");
    }

    @Test
    void rejectsPayloadWithoutCartonId() {
        assertThatThrownBy(() -> service.parseScanned("{\"version\":1,\"productId\":7,\"unitsPerCarton\":12}"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsInvalidUnitCount() {
        assertThatThrownBy(() -> service.parseScanned("{\"version\":1,\"cartonId\":\"CTN-1\",\"productId\":7,\"unitsPerCarton\":0}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("units per carton");
    }

    @Test
    void manualEntryMapsToPlainCartonCode() {
        var payload = service.parseScanned("  ctn-0001042 ");
        assertThat(payload.version()).isEqualTo(QrLabelService.PAYLOAD_VERSION);
        assertThat(payload.cartonId()).isEqualTo("CTN-0001042");
        assertThat(payload.productId()).isNull();
    }

    @Test
    void rejectsEmptyScan() {
        assertThatThrownBy(() -> service.parseScanned(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.parseScanned("   ")).isInstanceOf(BusinessException.class);
    }

    @Test
    void ignoresUnknownKeysDefensively() {
        var payload = service.parseScanned("{\"version\":1,\"hacker\":\"x\",\"cartonId\":\"CTN-9\",\"productId\":3,\"unitsPerCarton\":5}");
        assertThat(payload.cartonId()).isEqualTo("CTN-9");
        assertThat(payload.productId()).isEqualTo(3L);
    }
}
