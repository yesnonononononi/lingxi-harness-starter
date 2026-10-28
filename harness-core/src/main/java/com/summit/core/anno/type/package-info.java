/**
 * Structured declarations for object roots with scalar or scalar-array properties.
 * Object and nested-array properties are deliberately outside this contract to prevent cyclic
 * annotation element types. ValueType selects the active constraints; inactive declarations must
 * remain at their defaults.
 *
 * Optional scalar arrays contain zero or one element, except enums which may contain many.
 * Java permits scalar shorthand such as minimum = 0 and defaultValue = "" for these array elements.
 * The multiple element represents JSON Schema's multipleOf keyword.
 * A length/item limit of -1 means omitted; UNSET omits boolean keywords. Empty pattern/format
 * strings are omitted. The tool definition factory validates cardinality, ranges, finite numbers, active
 * constraint branches and required-property names before registration.
 */
package com.summit.core.anno.type;
