package io.archi.collab.client;

import com.archimatetool.collab.emf.OpMapper;
import com.archimatetool.collab.util.SimpleJson;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

class OpMapperNotationInventoryTest {

    @Test
    void updateViewObjectOpaqueEmitsExactlyWhitelistedNotationKeys() {
        OpMapper mapper = new OpMapper();
        IDiagramModelArchimateObject viewObject = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        viewObject.setId("vo-1");
        viewObject.setBounds(10, 20, 120, 55);

        String submit = mapper.toUpdateViewObjectOpaqueSubmitOps(viewObject, "model-a", 0L, "user", "session");
        String notationJson = notationJsonFromSubmitEnvelope(submit);

        Set<String> actual = topLevelKeys(notationJson);
        Set<String> expected = Set.of(
                "x", "y", "width", "height",
                "type", "alpha", "lineAlpha", "lineWidth", "lineStyle",
                "textAlignment", "textPosition", "gradient", "iconVisibleState",
                "deriveElementLineColor",
                "fillColor", "lineColor", "font", "fontColor", "iconColor",
                "imagePath", "imagePosition",
                "name", "documentation");
        Assertions.assertEquals(expected, actual);
    }

    @Test
    void updateConnectionOpaqueEmitsExactlyWhitelistedNotationKeys() {
        OpMapper mapper = new OpMapper();
        IDiagramModelArchimateConnection connection = IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
        connection.setId("conn-1");
        IDiagramModelBendpoint bendpoint = IArchimateFactory.eINSTANCE.createDiagramModelBendpoint();
        bendpoint.setStartX(1);
        bendpoint.setStartY(2);
        bendpoint.setEndX(3);
        bendpoint.setEndY(4);
        connection.getBendpoints().add(bendpoint);

        String submit = mapper.toUpdateConnectionOpaqueSubmitOps(connection, "model-a", 0L, "user", "session");
        String notationJson = notationJsonFromSubmitEnvelope(submit);

        Set<String> actual = topLevelKeys(notationJson);
        Set<String> expected = Set.of(
                "type", "nameVisible", "textAlignment", "textPosition", "lineWidth",
                "name", "lineColor", "font", "fontColor", "documentation",
                "bendpoints");
        Assertions.assertEquals(expected, actual);
    }

    private static String notationJsonFromSubmitEnvelope(String submitEnvelopeJson) {
        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(submitEnvelopeJson, "payload"));
        Assertions.assertNotNull(payload, "submit envelope must contain payload");

        String rawOps = SimpleJson.readRawField(payload, "ops");
        Assertions.assertNotNull(rawOps, "payload must contain ops array");
        var ops = SimpleJson.splitArrayObjects(rawOps);
        Assertions.assertFalse(ops.isEmpty(), "ops array must contain at least one op");

        String notationJson = SimpleJson.asJsonObject(SimpleJson.readRawField(ops.get(0), "notationJson"));
        Assertions.assertNotNull(notationJson, "op must contain notationJson object");
        return notationJson;
    }

    private static Set<String> topLevelKeys(String jsonObject) {
        Set<String> keys = new TreeSet<>();
        if(jsonObject == null || jsonObject.isBlank()) {
            return keys;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for(int i = 0; i < jsonObject.length(); i++) {
            char c = jsonObject.charAt(i);
            if(inString) {
                if(escaped) {
                    escaped = false;
                }
                else if(c == '\\') {
                    escaped = true;
                }
                else if(c == '"') {
                    inString = false;
                }
                continue;
            }

            if(c == '"') {
                if(depth == 1) {
                    int prev = previousNonWhitespace(jsonObject, i - 1);
                    if(prev == '{' || prev == ',') {
                        int keyStart = i + 1;
                        int keyEnd = findStringEnd(jsonObject, keyStart);
                        if(keyEnd < 0) {
                            break;
                        }
                        int colon = skipWhitespace(jsonObject, keyEnd + 1);
                        if(colon < jsonObject.length() && jsonObject.charAt(colon) == ':') {
                            keys.add(SimpleJson.decodeString(jsonObject.substring(keyStart, keyEnd)));
                        }
                        i = keyEnd;
                        continue;
                    }
                }
                inString = true;
                continue;
            }

            if(c == '{' || c == '[') {
                depth++;
            }
            else if(c == '}' || c == ']') {
                depth--;
            }
        }
        return keys;
    }

    private static int skipWhitespace(String s, int index) {
        int i = index;
        while(i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int previousNonWhitespace(String s, int index) {
        int i = index;
        while(i >= 0 && Character.isWhitespace(s.charAt(i))) {
            i--;
        }
        return i < 0 ? -1 : s.charAt(i);
    }

    private static int findStringEnd(String s, int from) {
        boolean escaped = false;
        for(int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if(escaped) {
                escaped = false;
            }
            else if(c == '\\') {
                escaped = true;
            }
            else if(c == '"') {
                return i;
            }
        }
        return -1;
    }
}
