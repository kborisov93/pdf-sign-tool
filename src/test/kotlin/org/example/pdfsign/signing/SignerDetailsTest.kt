package org.example.pdfsign.signing

import javax.security.auth.x500.X500Principal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignerDetailsTest {
    @Test
    fun `the DN reads like Adobe Acrobat's, most specific part first`() {
        // An RFC 2253 string lists the most specific part first, so the certificate stores C first, as cards do.
        val subject = X500Principal(
            "EMAILADDRESS=k.borisov@key.dev, O=Key Development, 2.5.4.97=NTRBG-203409100, SURNAME=BORISOV, " +
                "GIVENNAME=KAMEN, SERIALNUMBER=PNOBG-1234567890, CN=KAMEN BORISOV, C=BG",
        )
        assertEquals(
            "email=k.borisov@key.dev, o=Key Development, 2.5.4.97=NTRBG-203409100, sn=BORISOV, givenName=KAMEN, " +
                "serialNumber=PNOBG-1234567890, cn=KAMEN BORISOV, c=BG",
            subject.displayDn(),
        )
    }

    @Test
    fun parsing() {
        assertEquals(SignerDetails.DN, SignerDetails.parse(" DN "))
        assertEquals(SignerDetails.NAME, SignerDetails.parse("name"))
        assertNull(SignerDetails.parse("full"))
    }
}
