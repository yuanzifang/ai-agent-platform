package com.platform.capability.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolInputValidatorTest {

    private final ToolInputValidator validator = new ToolInputValidator(new ObjectMapper());

    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["amount", "currency"],
              "properties": {
                "amount": {"type": "integer", "minimum": 1, "maximum": 1000},
                "currency": {"type": "string", "enum": ["CNY", "USD"]},
                "note": {"type": "string", "maxLength": 5},
                "items": {
                  "type": "array",
                  "items": {"type": "object", "required": ["name"],
                            "properties": {"name": {"type": "string", "pattern": "[a-z]+"}}}
                }
              },
              "additionalProperties": false
            }
            """;

    @Test
    void acceptsValidObject() {
        assertTrue(validator.validate(SCHEMA, """
                {"amount": 10, "currency": "CNY", "note": "ok", "items": [{"name": "abc"}]}
                """).valid());
    }

    @Test
    void reportsMissingRequiredAndWrongType() {
        ToolInputValidator.Result result = validator.validate(SCHEMA, """
                {"currency": "CNY", "amount": "10"}
                """);
        assertFalse(result.valid());
        assertTrue(result.violations().stream().anyMatch(v -> v.rule().equals("required")
                && v.path().equals("$.amount") || v.rule().equals("type") && v.path().equals("$.amount")));
    }

    @Test
    void reportsEnumBoundsAndAdditionalProperties() {
        ToolInputValidator.Result result = validator.validate(SCHEMA, """
                {"amount": 1001, "currency": "EUR", "extra": true}
                """);
        assertFalse(result.valid());
        assertTrue(result.violations().stream().anyMatch(v -> v.rule().equals("maximum")));
        assertTrue(result.violations().stream().anyMatch(v -> v.rule().equals("enum")));
        assertTrue(result.violations().stream().anyMatch(v -> v.rule().equals("additionalProperties")));
    }

    @Test
    void reportsNestedArrayPatternAndLength() {
        ToolInputValidator.Result result = validator.validate(SCHEMA, """
                {"amount": 1, "currency": "USD", "note": "too-long",
                 "items": [{"name": "ABC"}]}
                """);
        assertFalse(result.valid());
        assertTrue(result.violations().stream().anyMatch(v -> v.path().equals("$.note")
                && v.rule().equals("maxLength")));
        assertTrue(result.violations().stream().anyMatch(v -> v.path().equals("$.items[0].name")
                && v.rule().equals("pattern")));
    }

    @Test
    void rejectsMalformedJsonAndNonObject() {
        assertFalse(validator.validate(SCHEMA, "{bad json").valid());
        assertFalse(validator.validate(SCHEMA, "[]").valid());
        assertEquals(1, validator.validate(SCHEMA, "[]").violations().size());
    }

    @Test
    void acceptsMapWithDefaults() {
        assertTrue(validator.validate(SCHEMA, Map.of("amount", 1, "currency", "CNY")).valid());
    }

    @Test
    void validatesOneOfAnyOfAndFormat() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "id": {"oneOf": [{"type": "string"}, {"type": "integer"}]},
                    "email": {"type": "string", "format": "email"}
                  },
                  "required": ["id", "email"]
                }
                """;
        assertTrue(validator.validate(schema, """
                {"id": "abc", "email": "a@example.com"}
                """).valid());
        assertFalse(validator.validate(schema, """
                {"id": true, "email": "not-an-email"}
                """).valid());
        ToolInputValidator.Result result = validator.validate(schema, """
                {"id": true, "email": "bad"}
                """);
        assertTrue(result.violations().stream().anyMatch(v -> v.rule().equals("oneOf")));
        assertTrue(result.violations().stream().anyMatch(v -> v.rule().equals("format")));
    }

    @Test
    void resolvesLocalRef() {
        String schema = """
                {
                  "type": "object",
                  "properties": {"amount": {"$ref": "#/$defs/amount"}},
                  "required": ["amount"],
                  "$defs": {"amount": {"type": "integer", "minimum": 1}}
                }
                """;
        assertTrue(validator.validate(schema, "{\"amount\": 2}").valid());
        assertFalse(validator.validate(schema, "{\"amount\": \"x\"}").valid());
    }
}
