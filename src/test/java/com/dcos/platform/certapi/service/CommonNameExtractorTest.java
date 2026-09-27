package com.dcos.platform.certapi.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CommonNameExtractorTest {

    @Test
    void acceptsExplicitCommonName() {
        String result = CommonNameExtractor.extract("explicit-name", "CN=other,O=org");
        assertEquals("explicit-name", result);
    }

    @Test
    void derivesFromSubjectWhenNotProvided() {
        String result = CommonNameExtractor.extract(null, "CN=service-alpha,OU=platform,O=DCOS");
        assertEquals("service-alpha", result);
    }

    @Test
    void derivesFromSubjectWhenBlank() {
        String result = CommonNameExtractor.extract("", "CN=service-bravo,OU=platform,O=DCOS");
        assertEquals("service-bravo", result);
    }

    @Test
    void derivesFromSubjectWhenWhitespace() {
        String result = CommonNameExtractor.extract("   ", "CN=service-charlie,OU=platform,O=DCOS");
        assertEquals("service-charlie", result);
    }

    @Test
    void handlesCNAtEnd() {
        String result = CommonNameExtractor.extract(null, "O=DCOS,OU=platform,CN=end-name");
        assertEquals("end-name", result);
    }

    @Test
    void handlesCNAtStart() {
        String result = CommonNameExtractor.extract(null, "CN=start-name,OU=platform,O=DCOS");
        assertEquals("start-name", result);
    }

    @Test
    void stripsQuotesFromCN() {
        String result = CommonNameExtractor.extract(null, "CN=\"quoted-name\",OU=platform,O=DCOS");
        assertEquals("quoted-name", result);
    }

    @Test
    void rejectsWhenNoCNInSubjectAndNoneProvided() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CommonNameExtractor.extract(null, "O=DCOS,OU=platform"));
    }

    @Test
    void rejectsWhenBlankCNInSubjectAndNoneProvided() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CommonNameExtractor.extract(null, "CN=,OU=platform,O=DCOS"));
    }

    @Test
    void rejectsWhenNullSubjectAndNoneProvided() {
        assertThrows(IllegalArgumentException.class, () -> CommonNameExtractor.extract(null, null));
    }

    @Test
    void prioritizesExplicitOverSubject() {
        String result =
                CommonNameExtractor.extract("explicit", "CN=fromSubject,OU=platform,O=DCOS");
        assertEquals("explicit", result);
    }

    @Test
    void handlesComplexDN() {
        String result =
                CommonNameExtractor.extract(
                        null, "CN=example.com,OU=Services,O=Company,L=City,ST=State,C=US");
        assertEquals("example.com", result);
    }

    @Test
    void handlesWhitespaceAroundCN() {
        String result = CommonNameExtractor.extract(null, "CN = spaced-name , O = DCOS");
        assertEquals("spaced-name", result);
    }
}
