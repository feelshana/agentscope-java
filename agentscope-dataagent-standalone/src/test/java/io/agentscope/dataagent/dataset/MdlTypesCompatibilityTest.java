package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Legacy JSON was captured from the pre-extraction compiled application, not the new types. */
class MdlTypesCompatibilityTest {
    @Test
    void neutralContractsKeepAllThirteenLegacyWireShapes() throws Exception {
        var actual = new TreeMap<String, Object>();
        for (Class<?> type : MdlTypes.class.getDeclaredClasses())
            if (type.isRecord() && type != MdlTypes.AssetReviewTarget.class)
                actual.put(type.getSimpleName(), sample(type, type.getSimpleName()));
        var json = new ObjectMapper();
        var expected =
                json.readTree(
                        Path.of("src/test/resources/mdl-contracts/before-read-extraction.json")
                                .toFile());
        assertThat(json.readTree(json.writeValueAsString(actual))).isEqualTo(expected);
    }

    @Test
    void publishesOptionalIssueTargetsWhenPresent() throws Exception {
        var json = new ObjectMapper();
        var incomplete = new MdlTypes.MdlIssue("error", "缺少视图定义", "local", "sales");
        var unverified =
                new MdlTypes.MdlIssue(
                        "error",
                        "视图待验证",
                        "local",
                        null,
                        new MdlTypes.AssetReviewTarget("view", "sales", "VERIFY_PENDING"));
        assertThat(json.readTree(json.writeValueAsString(incomplete)))
                .isEqualTo(
                        json.readTree(
                                """
                                {"severity":"error","message":"缺少视图定义","source":"local","incompleteView":"sales"}
                                """));
        assertThat(json.readTree(json.writeValueAsString(unverified)))
                .isEqualTo(
                        json.readTree(
                                """
                                {"severity":"error","message":"视图待验证","source":"local",
                                 "assetReview":{"kind":"view","name":"sales","step":"VERIFY_PENDING"}}
                                """));
    }

    private static Object sample(java.lang.reflect.Type type, String field) throws Exception {
        if (type instanceof java.lang.reflect.ParameterizedType list)
            return java.util.List.of(sample(list.getActualTypeArguments()[0], field));
        Class<?> raw = (Class<?>) type;
        if (raw == String.class) return field;
        if (raw == int.class) return 7;
        if (raw == boolean.class) return true;
        if (raw == MdlTypes.MdlIssue.class)
            return new MdlTypes.MdlIssue("severity", "message", "source");
        var components = raw.getRecordComponents();
        Class<?>[] signature = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            signature[i] = components[i].getType();
            arguments[i] = sample(components[i].getGenericType(), components[i].getName());
        }
        return raw.getConstructor(signature).newInstance(arguments);
    }
}
