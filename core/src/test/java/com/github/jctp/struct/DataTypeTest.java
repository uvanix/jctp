package com.github.jctp.struct;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DataTypeTest {

    @Test
    void dataTypeLookup() {
        assertEquals(DataType.Direction.Sell, DataType.of(DataType.Direction.class, '1'));
        assertEquals(DataType.OrderStatus.Unknown, DataType.of(DataType.OrderStatus.class, 'a'));
        assertEquals(DataType.ProductClass.MI, DataType.of(DataType.ProductClass.class, 'I'));
        assertNull(DataType.of(DataType.Direction.class, '\0'));
    }
}
