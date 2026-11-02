package com.winter.awss3uploadv2.upload;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UploadRulesTest {
    @Test void rejectsWrongChunkCount() {
        assertThrows(IllegalArgumentException.class, () -> UploadRules.validate("a".repeat(32), 9 * 1024 * 1024L, 1));
    }
    @Test void rejectsWrongPartSize() {
        assertThrows(IllegalArgumentException.class, () -> UploadRules.validatePart(9 * 1024 * 1024L, 2, 1, 1024));
    }
    @Test void acceptsLastPart() {
        assertDoesNotThrow(() -> UploadRules.validatePart(9 * 1024 * 1024L, 2, 2, 1024 * 1024));
    }
    @Test void rejectsRecoveredObjectWithWrongSize() {
        assertThrows(IllegalStateException.class, () -> UploadRules.validateObjectSize(9, 8));
    }
}
