package com.github.erosb.jsonschema

interface Visitor {
    fun visitCompositeSchema(schema: CompositeSchema)
    fun visitTrueSchema(schema: TrueSchema)
    fun visitFalseSchema(schema: FalseSchema)
    fun visitMinLengthSchema(schema: MinLengthSchema)
    fun visitMaxLengthSchema(schema: MaxLengthSchema)
}