package gxopendtu.opendtu;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * OpenDTU numeric fields are either a bare number or {@code {"v": ..., "u": ..., "d": ...}}.
 * Port of src/opendtu_client.py's _extract_value.
 */
public final class JsonValues {

    private JsonValues() {}

    public static double extractValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0.0;
        }
        if (node.isObject()) {
            return node.path("v").asDouble(0.0);
        }
        return node.asDouble(0.0);
    }
}
