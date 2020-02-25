/*
 * Module: r2-shared-kotlin
 * Developers: Mickaël Menu
 *
 * Copyright (c) 2020. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.shared

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.readium.r2.shared.extensions.toList
import org.readium.r2.shared.extensions.toMap

/**
 * Asserts that two [JSONObject] are equal.
 */
fun assertJSONEquals(expected: JSONObject, actual: JSONObject) {
    assertEquals(expected.toMap(), actual.toMap())
}

/**
 * Asserts that two [JSONArray] are equal.
 */
fun assertJSONEquals(expected: JSONArray, actual: JSONArray) {
    assertEquals(expected.toList(), actual.toList())
}
