package com.github.erosb.jsonsKema

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultValuesTest {

    private fun validator(schemaJson: String): Validator =
        Validator.forSchema(SchemaLoader(JsonParser(schemaJson)())())

    private fun json(source: String) = JsonParser(source)()

    @Test
    fun `validate does not apply defaults`() {
        val validator = validator("""
            {
                "allOf": [
                    {
                        "properties": {
                            "a": { "default": 1 }
                        }
                    },
                    { "required": ["a"] }
                ]
            }
        """)

        assertNotNull(validator.validate(json("{}")))
        assertNull(validator.validateWithDefaults(json("{}")).failure)
        assertNotNull(validator.validate(json("{}")))
    }

    @Test
    fun `missing property gets default, present property is kept`() {
        val validator = validator("""
            {
                "properties": {
                    "a": { "type": "number", "default": 1 },
                    "b": { "type": "string", "default": "x" }
                }
            }
        """)
        val instance = json("""{"b": "y"}""")

        val actual = validator.validateWithDefaults(instance)

        assertNull(actual.failure)
        assertEquals(json("""{"b": "y", "a": 1}"""), actual.instance)
        assertEquals(json("""{"b": "y"}"""), instance)
    }

    @Test
    fun `instance is returned as-is if no defaults apply`() {
        val validator = validator("""
            {
                "properties": {
                    "a": { "type": "number", "default": 1 },
                    "nested": { "type": "object" }
                }
            }
        """)
        val instance = json("""{"a": 2, "nested": {"x": [1, {}]}}""")

        val actual = validator.validateWithDefaults(instance)

        assertNull(actual.failure)
        assertSame(instance, actual.instance)
    }

    @Test
    fun `defaults are applied in nested objects and arrays`() {
        val validator = validator("""
            {
                "properties": {
                    "nested": {
                        "properties": {
                            "a": { "default": true }
                        }
                    },
                    "arr": {
                        "items": {
                            "properties": {
                                "b": { "default": [1, 2] }
                            }
                        }
                    }
                }
            }
        """)

        val actual = validator.validateWithDefaults(json("""
            {
                "nested": {},
                "arr": [{}, {"b": []}, 3]
            }
        """))

        assertNull(actual.failure)
        assertEquals(json("""
            {
                "nested": {"a": true},
                "arr": [{"b": [1, 2]}, {"b": []}, 3]
            }
        """), actual.instance)
    }

    @Test
    fun `defaults are applied in default objects`() {
        val validator = validator("""
            {
                "properties": {
                    "first": { "${'$'}ref": "#/${'$'}defs/withDefault", "default": {} },
                    "second": { "${'$'}ref": "#/${'$'}defs/withDefault", "default": {} }
                },
                "${'$'}defs": {
                    "withDefault": {
                        "properties": {
                            "a": { "default": 1 }
                        }
                    }
                }
            }
        """)

        val actual = validator.validateWithDefaults(json("""{"second": {"a": 2}}"""))

        assertNull(actual.failure)
        assertEquals(json("""{"second": {"a": 2}, "first": {}}"""), actual.instance)
    }

    @Test
    fun `default is not validated`() {
        val validator = validator("""
            {
                "properties": {
                    "a": { "type": "number", "default": "not a number" }
                }
            }
        """)

        val actual = validator.validateWithDefaults(json("{}"))

        assertNull(actual.failure)
        assertEquals(json("""{"a": "not a number"}"""), actual.instance)
    }

    @Test
    fun `default of referred schema is not used`() {
        val validator = validator("""
            {
                "properties": {
                    "a": { "${'$'}ref": "#/${'$'}defs/num" },
                    "b": { "${'$'}ref": "#/${'$'}defs/num", "default": 2 }
                },
                "${'$'}defs": {
                    "num": { "type": "number", "default": 1 }
                }
            }
        """)

        val actual = validator.validateWithDefaults(json("{}"))

        assertNull(actual.failure)
        assertEquals(json("""{"b": 2}"""), actual.instance)
    }

    @Test
    fun `only the matching oneOf branch contributes defaults`() {
        for (branches in listOf("query, path", "path, query")) {
            val (first, second) = branches.split(", ")
            val validator = validator("""
                {
                    "type": "object",
                    "required": ["parameter1", "parameter2"],
                    "additionalProperties": false,
                    "properties": {
                        "parameter1": { "${'$'}ref": "#/${'$'}defs/nonBodyParameter" },
                        "parameter2": { "${'$'}ref": "#/${'$'}defs/nonBodyParameter" }
                    },
                    "${'$'}defs": {
                        "nonBodyParameter": {
                            "type": "object",
                            "required": ["in"],
                            "oneOf": [
                                { "${'$'}ref": "#/${'$'}defs/$first" },
                                { "${'$'}ref": "#/${'$'}defs/$second" }
                            ]
                        },
                        "query": {
                            "additionalProperties": false,
                            "properties": {
                                "in": { "type": "string", "enum": ["query"] },
                                "allowEmptyValue": { "type": "boolean", "default": false }
                            }
                        },
                        "path": {
                            "additionalProperties": false,
                            "properties": {
                                "in": { "type": "string", "enum": ["path"] }
                            }
                        }
                    }
                }
            """)

            val actual = validator.validateWithDefaults(json("""
                {
                    "parameter1": { "in": "query" },
                    "parameter2": { "in": "path" }
                }
            """))

            assertNull(actual.failure, branches)
            assertEquals(json("""
                {
                    "parameter1": { "in": "query", "allowEmptyValue": false },
                    "parameter2": { "in": "path" }
                }
            """), actual.instance, branches)
        }
    }

    @Test
    fun `defaults of failing anyOf branch are rolled back`() {
        val validator = validator("""
            {
                "anyOf": [
                    {
                        "properties": {
                            "a": { "default": 1 },
                            "kind": { "const": "first" }
                        }
                    },
                    {
                        "properties": {
                            "b": { "default": 2 },
                            "kind": { "const": "second" }
                        }
                    }
                ]
            }
        """)

        val actual = validator.validateWithDefaults(json("""{"kind": "second"}"""))

        assertNull(actual.failure)
        assertEquals(json("""{"kind": "second", "b": 2}"""), actual.instance)
    }

    @Test
    fun `defaults of nested objects in failing oneOf branch are rolled back`() {
        val validator = validator("""
            {
                "oneOf": [
                    {
                        "properties": {
                            "nested": {
                                "properties": {
                                    "a": { "default": 1 }
                                }
                            },
                            "arr": {
                                "items": {
                                    "properties": {
                                        "b": { "default": 2 }
                                    }
                                }
                            },
                            "kind": { "const": "x" }
                        }
                    },
                    {
                        "properties": {
                            "kind": { "const": "y" }
                        }
                    }
                ]
            }
        """)

        val actual = validator.validateWithDefaults(json("""{"nested": {}, "arr": [{}], "kind": "y"}"""))

        assertNull(actual.failure)
        assertEquals(json("""{"nested": {}, "arr": [{}], "kind": "y"}"""), actual.instance)
    }

    @Test
    fun `defaults of failing branch do not affect later branches`() {
        val validator = validator("""
            {
                "oneOf": [
                    {
                        "properties": {
                            "nested": {
                                "properties": {
                                    "a": { "default": 1 }
                                }
                            },
                            "kind": { "const": "x" }
                        }
                    },
                    {
                        "properties": {
                            "nested": { "additionalProperties": false },
                            "kind": { "const": "y" }
                        }
                    }
                ]
            }
        """)

        val actual = validator.validateWithDefaults(json("""{"nested": {}, "kind": "y"}"""))

        assertNull(actual.failure)
        assertEquals(json("""{"nested": {}, "kind": "y"}"""), actual.instance)
    }

    @Test
    fun `defaults are applied after required and dependentRequired are checked`() {
        val validator = validator("""
            {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                    "arrayPropEnabled": { "type": "boolean" },
                    "arrayProp": {
                        "type": "array",
                        "items": { "type": "number" },
                        "default": [0, 1, 2, 3]
                    }
                },
                "allOf": [
                    {
                        "if": {
                            "properties": { "arrayPropEnabled": { "const": true } }
                        },
                        "then": {
                            "dependentRequired": { "arrayPropEnabled": ["arrayProp"] }
                        }
                    }
                ]
            }
        """)

        repeat(20) {
            val actual = validator.validateWithDefaults(json("""{"arrayPropEnabled": true}"""))
            assertNotNull(actual.failure)
        }

        val required = validator("""
            {
                "required": ["a"],
                "properties": {
                    "a": { "default": 1 }
                }
            }
        """)
        assertNotNull(required.validate(json("{}")))
    }

    @Test
    fun `later subschemas see applied defaults`() {
        val validator = validator("""
            {
                "allOf": [
                    {
                        "properties": {
                            "a": { "default": 1 }
                        }
                    },
                    { "required": ["a"] },
                    { "const": { "a": 1 } },
                    { "enum": [{ "a": 1 }] },
                    { "minProperties": 1 }
                ]
            }
        """)

        val actual = validator.validateWithDefaults(json("{}"))

        assertNull(actual.failure)
        assertEquals(json("""{"a": 1}"""), actual.instance)
    }

    @Test
    fun `const, enum and uniqueItems still work on objects`() {
        val validator = validator("""
            {
                "properties": {
                    "c": { "const": { "x": 1 } },
                    "e": { "enum": [{ "x": 1 }] },
                    "u": {
                        "allOf": [
                            {
                                "items": {
                                    "properties": {
                                        "a": { "default": 1 }
                                    }
                                }
                            },
                            { "uniqueItems": true }
                        ]
                    }
                }
            }
        """)

        assertNull(validator.validateWithDefaults(json("""{"c": {"x": 1}, "e": {"x": 1}, "u": [{}, {"a": 2}]}""")).failure)
        assertNotNull(validator.validateWithDefaults(json("""{"u": [{}, {"a": 1}]}""")).failure)
    }

    @Test
    fun `applied default counts as evaluated`() {
        val validator = validator("""
            {
                "properties": {
                    "a": { "default": 1 }
                },
                "unevaluatedProperties": false
            }
        """)

        val actual = validator.validateWithDefaults(json("{}"))

        assertNull(actual.failure)
        assertEquals(json("""{"a": 1}"""), actual.instance)
    }

    @Test
    fun `instance is returned unchanged if validation fails`() {
        val validator = validator("""
            {
                "properties": {
                    "nested": {
                        "properties": {
                            "a": { "default": 1 }
                        }
                    },
                    "b": { "default": 2 },
                    "c": { "type": "string" }
                }
            }
        """)
        val instance = json("""{"nested": {}, "c": 3}""")

        val actual = validator.validateWithDefaults(instance)

        assertNotNull(actual.failure)
        assertSame(instance, actual.instance)
    }

    @Test
    fun `defaults do not leak between validations`() {
        val validator = validator("""
            {
                "properties": {
                    "a": { "default": 1 }
                }
            }
        """)
        val instance = json("{}")

        validator.validateWithDefaults(instance)
        val actual = validator.validateWithDefaults(json("""{"a": 2}"""))

        assertEquals(json("""{"a": 2}"""), actual.instance)
        assertEquals(json("{}"), instance)
    }

    @Test
    fun `failure instances behave like json objects`() {
        val validator = validator("""{"required": ["x"], "minProperties": 2}""")
        val expected = json("""{"a": 1}""")

        val failure = validator.validateWithDefaults(json("""{"a": 1}""")).failure!!

        assertEquals(2, failure.causes.size)
        failure.causes.forEach {
            assertTrue(it.instance == expected)
            assertEquals(expected.hashCode(), it.instance.hashCode())
            assertEquals(expected.toString(), it.instance.toString())
        }

        val unevaluated = validator("""{"required": ["x"], "unevaluatedProperties": false}""")
        assertEquals(expected.toString(), unevaluated.validateWithDefaults(json("""{"a": 1}""")).failure!!.instance.toString())
        assertEquals(expected.toString(), unevaluated.validate(json("""{"a": 1}"""))!!.instance.toString())
    }
}
