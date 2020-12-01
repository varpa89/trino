/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.array;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class TestDoubleBigArray
{
    @Test
    public void testFill()
    {
        DoubleBigArray array = new DoubleBigArray();
        assertFillCapacity(array, 0, 0.1);
        assertFillCapacity(array, 1, 0.2);
        assertFillCapacity(array, 1000, 0.3);
        assertFillCapacity(array, BigArrays.SEGMENT_SIZE, 0.4);
        assertFillCapacity(array, BigArrays.SEGMENT_SIZE + 1, 0.5);
    }

    private static void assertFillCapacity(DoubleBigArray array, long capacity, double value)
    {
        array.ensureCapacity(capacity);
        array.fill(value);

        for (int i = 0; i < capacity; i++) {
            assertEquals(array.get(i), value);
        }
    }
}
