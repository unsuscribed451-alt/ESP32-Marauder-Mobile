package com.marauder.mobile

import com.marauder.mobile.esp.EspProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the self-contained MD5 used for ESP flash verification against the
 * canonical RFC 1321 test-suite vectors. Correctness matters: a wrong digest would
 * make every flash "fail verification" even on a perfect write.
 */
class EspProtocolTest {

    private fun md5(s: String) = EspProtocol.md5Hex(s.toByteArray(Charsets.US_ASCII))

    @Test fun md5EmptyString() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5(""))
    }

    @Test fun md5SingleChar() {
        assertEquals("0cc175b9c0f1b6a831c399e269772661", md5("a"))
    }

    @Test fun md5Abc() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5("abc"))
    }

    @Test fun md5MessageDigest() {
        assertEquals("f96b697d7cb7938d525a2f31aaf161d0", md5("message digest"))
    }

    @Test fun md5Alphabet() {
        assertEquals("c3fcd3d76192e4007dfb496cca67e13b", md5("abcdefghijklmnopqrstuvwxyz"))
    }

    /** 80 bytes → forces a second padded block, exercising the multi-block path. */
    @Test fun md5MultiBlock() {
        assertEquals(
            "57edf4a22be3c955ac49da2e2107b67a",
            md5("12345678901234567890123456789012345678901234567890123456789012345678901234567890"),
        )
    }
}
