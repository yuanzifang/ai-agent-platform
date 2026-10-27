package com.platform.capability.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 工具入参 JSON Schema 校验（本地执行前兜底，不依赖模型遵守 schema）。
 * 支持常用子集：type/required/properties/additionalProperties、enum/const、
 * 字符串长度/正则、数值范围、数组 items/长度、对象属性数。
 * 未知关键字/复杂 $ref 不在这里解释，交由工具自身或远程服务校验。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolInputValidator {

    public record Violation(String path, String rule, String expected, String actual) {
    }

    public record Result(boolean valid, List<Violation> violations) {
        public static Result ok() {
            return new Result(true, List.of());
        }
    }

    private final ObjectMapper objectMapper;

    public Result validate(String schemaJson, String inputJson) {
        JsonNode schema = parseSchema(schemaJson);
        if (schema == null) return Result.ok();
        JsonNode input;
        try {
            input = inputJson == null || inputJson.isBlank()
                    ? objectMapper.createObjectNode() : objectMapper.readTree(inputJson);
        } catch (Exception e) {
            return new Result(false, List.of(new Violation("$", "json", "合法 JSON 对象",
                    "无法解析")));
        }
        return validate(schema, input);
    }

    public Result validate(String schemaJson, Map<String, Object> args) {
        JsonNode schema = parseSchema(schemaJson);
        if (schema == null) return Result.ok();
        return validate(schema, objectMapper.valueToTree(args == null ? Map.of() : args));
    }

    private JsonNode parseSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) return null;
        try {
            JsonNode schema = objectMapper.readTree(schemaJson);
            return schema.isObject() ? schema : null;
        } catch (Exception e) {
            log.warn("工具 inputSchema 不是合法 JSON，跳过本地 schema 校验：{}", e.getMessage());
            return null;
        }
    }

    private Result validate(JsonNode schema, JsonNode input) {
        if (!input.isObject()) {
            return new Result(false, List.of(new Violation("$", "type", "object", typeOf(input))));
        }
        List<Violation> violations = new ArrayList<>();
        validateNode(schema, input, "$", violations);
        return new Result(violations.isEmpty(), List.copyOf(violations));
    }

    private void validateNode(JsonNode schema, JsonNode value, String path, List<Violation> out) {
        if (schema == null || !schema.isObject() || value == null) return;

        JsonNode type = schema.get("type");
        if (type != null && !matchesType(value, type)) {
            out.add(new Violation(path, "type", String.valueOf(type), typeOf(value)));
            return;
        }

        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && enumValues.isArray()) {
            boolean found = false;
            for (JsonNode allowed : enumValues) {
                if (allowed.equals(value)) {
                    found = true;
                    break;
                }
            }
            if (!found) out.add(new Violation(path, "enum", enumValues.toString(), typeOf(value)));
        }

        JsonNode constValue = schema.get("const");
        if (constValue != null && !constValue.equals(value)) {
            out.add(new Violation(path, "const", constValue.toString(), typeOf(value)));
        }

        validateCombinators(schema, value, path, out);

        if (value.isObject()) validateObject(schema, value, path, out);
        else if (value.isArray()) validateArray(schema, value, path, out);
        else if (value.isTextual()) validateString(schema, value, path, out);
        else if (value.isNumber()) validateNumber(schema, value, path, out);
    }

    private void validateCombinators(JsonNode schema, JsonNode value, String path, List<Violation> out) {
        JsonNode allOf = schema.get("allOf");
        if (allOf != null && allOf.isArray()) {
            for (JsonNode sub : allOf) validateNode(sub, value, path, out);
        }
        JsonNode anyOf = schema.get("anyOf");
        if (anyOf != null && anyOf.isArray() && !matchesAny(anyOf, value, path)) {
            out.add(new Violation(path, "anyOf", "至少匹配一个子 schema", typeOf(value)));
        }
        JsonNode oneOf = schema.get("oneOf");
        if (oneOf != null && oneOf.isArray()) {
            int matched = 0;
            for (JsonNode sub : oneOf) {
                if (validAgainst(sub, value, path)) matched++;
            }
            if (matched != 1) {
                out.add(new Violation(path, "oneOf", "必须且只能匹配一个子 schema",
                        "匹配数量=" + matched));
            }
        }
    }

    private boolean matchesAny(JsonNode schemas, JsonNode value, String path) {
        for (JsonNode sub : schemas) {
            if (validAgainst(sub, value, path)) return true;
        }
        return false;
    }

    private boolean validAgainst(JsonNode schema, JsonNode value, String path) {
        List<Violation> tmp = new ArrayList<>();
        validateNode(schema, value, path, tmp);
        return tmp.isEmpty();
    }

    private void validateObject(JsonNode schema, JsonNode value, String path, List<Violation> out) {
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode name : required) {
                if (value.get(name.asText()) == null) {
                    out.add(new Violation(path + "." + name.asText(), "required", "必须提供", "缺失"));
                }
            }
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = properties.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode child = value.get(entry.getKey());
                if (child != null) validateNode(entry.getValue(), child, path + "." + entry.getKey(), out);
            }
        }
        JsonNode additional = schema.get("additionalProperties");
        if (additional != null && additional.isBoolean() && !additional.asBoolean() && properties != null) {
            Iterator<String> names = value.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!properties.has(name)) {
                    out.add(new Violation(path + "." + name, "additionalProperties", "不允许", "多余字段"));
                }
            }
        }
        int min = schema.path("minProperties").asInt(-1);
        int max = schema.path("maxProperties").asInt(-1);
        int size = value.size();
        if (min >= 0 && size < min) out.add(new Violation(path, "minProperties", ">=" + min, String.valueOf(size)));
        if (max >= 0 && size > max) out.add(new Violation(path, "maxProperties", "<=" + max, String.valueOf(size)));
    }

    private void validateArray(JsonNode schema, JsonNode value, String path, List<Violation> out) {
        int min = schema.path("minItems").asInt(-1);
        int max = schema.path("maxItems").asInt(-1);
        if (min >= 0 && value.size() < min) out.add(new Violation(path, "minItems", ">=" + min, String.valueOf(value.size())));
        if (max >= 0 && value.size() > max) out.add(new Violation(path, "maxItems", "<=" + max, String.valueOf(value.size())));
        JsonNode items = schema.get("items");
        if (items != null) {
            for (int i = 0; i < value.size(); i++) {
                validateNode(items, value.get(i), path + "[" + i + "]", out);
            }
        }
    }

    private void validateString(JsonNode schema, JsonNode value, String path, List<Violation> out) {
        int min = schema.path("minLength").asInt(-1);
        int max = schema.path("maxLength").asInt(-1);
        int length = value.asText().length();
        if (min >= 0 && length < min) out.add(new Violation(path, "minLength", ">=" + min, String.valueOf(length)));
        if (max >= 0 && length > max) out.add(new Violation(path, "maxLength", "<=" + max, String.valueOf(length)));
        JsonNode pattern = schema.get("pattern");
        if (pattern != null && pattern.isTextual()) {
            try {
                if (!Pattern.compile(pattern.asText()).matcher(value.asText()).find()) {
                    out.add(new Violation(path, "pattern", pattern.asText(), "不匹配"));
                }
            } catch (Exception e) {
                log.warn("工具 inputSchema pattern 非法，跳过：{}", e.getMessage());
            }
        }
        JsonNode format = schema.get("format");
        if (format != null && format.isTextual()) {
            String text = value.asText();
            boolean valid = switch (format.asText()) {
                case "email" -> text.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
                case "uuid" -> text.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
                case "date" -> {
                    try { LocalDate.parse(text); yield true; } catch (Exception e) { yield false; }
                }
                case "date-time" -> {
                    try { OffsetDateTime.parse(text); yield true; } catch (Exception e) { yield false; }
                }
                case "uri" -> {
                    try { yield URI.create(text).getScheme() != null; } catch (Exception e) { yield false; }
                }
                default -> true;
            };
            if (!valid) out.add(new Violation(path, "format", format.asText(), "格式不匹配"));
        }
    }

    private void validateNumber(JsonNode schema, JsonNode value, String path, List<Violation> out) {
        if (schema.has("minimum") && value.asDouble() < schema.get("minimum").asDouble()) {
            out.add(new Violation(path, "minimum", ">=" + schema.get("minimum").asText(), value.asText()));
        }
        if (schema.has("maximum") && value.asDouble() > schema.get("maximum").asDouble()) {
            out.add(new Violation(path, "maximum", "<=" + schema.get("maximum").asText(), value.asText()));
        }
        if (schema.has("exclusiveMinimum") && value.asDouble() <= schema.get("exclusiveMinimum").asDouble()) {
            out.add(new Violation(path, "exclusiveMinimum", ">" + schema.get("exclusiveMinimum").asText(), value.asText()));
        }
        if (schema.has("exclusiveMaximum") && value.asDouble() >= schema.get("exclusiveMaximum").asDouble()) {
            out.add(new Violation(path, "exclusiveMaximum", "<" + schema.get("exclusiveMaximum").asText(), value.asText()));
        }
    }

    private static boolean matchesType(JsonNode value, JsonNode type) {
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (matchesType(value, t)) return true;
            }
            return false;
        }
        if (!type.isTextual()) return true;
        return switch (type.asText()) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private static String typeOf(JsonNode value) {
        if (value == null || value.isNull()) return "null";
        if (value.isObject()) return "object";
        if (value.isArray()) return "array";
        if (value.isTextual()) return "string";
        if (value.isBoolean()) return "boolean";
        if (value.isIntegralNumber()) return "integer";
        if (value.isNumber()) return "number";
        return value.getNodeType().name().toLowerCase();
    }
}
