/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.redaction;

import ai.philterd.batch.Documents;
import ai.philterd.batch.Fixtures;
import ai.philterd.batch.run.DocumentType;
import ai.philterd.batch.worker.ReasonCode;
import ai.philterd.phileas.PhileasConfiguration;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.zip.ZipInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhileasDocumentRedactorTest {

    private static final PhileasConfiguration CONFIGURATION = LoadedPolicy.phileasConfiguration(List.of());
    private static final String REDACTED = "{{{REDACTED-email-address}}}";

    private static PhileasRedactor redactor;

    @BeforeAll
    static void redactor() throws Exception {
        redactor = new PhileasRedactor(LoadedPolicy.load(Fixtures.REGEX_POLICY.getBytes(StandardCharsets.UTF_8),
                Path.of("/nonexistent")), CONFIGURATION);
    }

    static Stream<Arguments> documents() throws Exception {
        return Stream.of(
                Arguments.of(DocumentType.PDF, Documents.pdf()),
                Arguments.of(DocumentType.DOCX, Documents.docx()),
                Arguments.of(DocumentType.XLSX, Documents.xlsx()),
                Arguments.of(DocumentType.RTF, Documents.rtf()),
                Arguments.of(DocumentType.EMAIL, Documents.eml()),
                Arguments.of(DocumentType.EMAIL, Documents.msg(null)),
                Arguments.of(DocumentType.CSV, Documents.csv()));
    }

    @ParameterizedTest
    @MethodSource("documents")
    void detectsEachTypeFromItsContent(final DocumentType type, final byte[] data) {
        final DocumentType byExtension = type.textual() || type == DocumentType.EMAIL ? type : DocumentType.TEXT;
        assertEquals(type, DocumentTypes.detect(data, byExtension).orElseThrow());
    }

    @ParameterizedTest
    @MethodSource("documents")
    void redactsEachTypeToText(final DocumentType type, final byte[] data) throws Exception {
        final TextRedactor.DocumentRedaction r = redactor.redactDocument(type, data, true);
        assertNull(r.document());
        final String text = String.join("\n", r.sections().stream().map(TextRedactor.Section::text).toList());
        assertTrue(text.contains(REDACTED), type + ": " + text);
        assertFalse(text.contains(Documents.EMAIL), type + ": " + text);
        assertTrue(r.piiCounts().getOrDefault("email-address", 0) >= 1, type + ": " + r.piiCounts());
        assertFalse(r.leftoverChecked());
    }

    @ParameterizedTest
    @MethodSource("documents")
    void redactsEachTypeToADocumentOfItsType(final DocumentType type, final byte[] data) throws Exception {
        final TextRedactor.DocumentRedaction r = redactor.redactDocument(type, data, false);
        assertNull(r.sections());
        assertNotNull(r.document());
        assertTrue(r.piiCounts().getOrDefault("email-address", 0) >= 1, type + ": " + r.piiCounts());
        final DocumentType byExtension = type.textual() || type == DocumentType.EMAIL ? type : DocumentType.TEXT;
        assertEquals(type, DocumentTypes.detect(r.document(), byExtension).orElseThrow());
        if (type != DocumentType.PDF) {
            // A redacted PDF's pages are images, so only the other types still hold the address as text, if at all.
            assertFalse(new String(r.document(), StandardCharsets.ISO_8859_1).contains(Documents.EMAIL), type.json());
        }
    }

    @Test
    void aRedactedWordDocumentReadsWithTheReplacement() throws Exception {
        final byte[] out = redactor.redactDocument(DocumentType.DOCX, Documents.docx(), false).document();
        String body = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out))) {
            for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (e.getName().equals("word/document.xml")) {
                    body = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertNotNull(body);
        assertTrue(body.contains(REDACTED), body);
        assertFalse(body.contains(Documents.EMAIL), body);
    }

    @Test
    void aPdfPageWithNoTextIsRecordedAsSuch() throws Exception {
        final TextRedactor.DocumentRedaction r = redactor.redactDocument(DocumentType.PDF, Documents.pdf(), true);
        assertEquals(List.of("page 1", "page 2"), r.sections().stream().map(TextRedactor.Section::label).toList());
        assertEquals(List.of("text", "no_text"), r.sections().stream().map(TextRedactor.Section::status).toList());
        try (PDDocument document = Loader.loadPDF(redactor.redactDocument(DocumentType.PDF, Documents.pdf(), false)
                .document())) {
            assertEquals(2, document.getNumberOfPages());
        }
    }

    @Test
    void contentWithoutItsTypesSignatureIsNotThatType() {
        final byte[] text = "Plain words, no header.".getBytes(StandardCharsets.UTF_8);
        assertTrue(DocumentTypes.detect(text, DocumentType.PDF).isEmpty());
        assertTrue(DocumentTypes.detect(text, DocumentType.DOCX).isEmpty());
        assertTrue(DocumentTypes.detect(text, DocumentType.RTF).isEmpty());
        assertEquals(DocumentType.TEXT, DocumentTypes.detect(text, DocumentType.TEXT).orElseThrow());
        // A text file that mentions a PDF header past its first byte is still text.
        assertEquals(DocumentType.TEXT, DocumentTypes.detect("See %PDF-1.7 above.".getBytes(StandardCharsets.UTF_8),
                DocumentType.TEXT).orElseThrow());
        // A ZIP that is not a Word document or Excel workbook.
        assertTrue(DocumentTypes.detect(new byte[] {'P', 'K', 3, 4, 0, 0}, DocumentType.DOCX).isEmpty());
        // OLE2, as legacy .doc and encrypted Office files are, is an Outlook message only as email.
        final byte[] ole2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        assertTrue(DocumentTypes.detect(ole2, DocumentType.DOCX).isEmpty());
        assertEquals(DocumentType.EMAIL, DocumentTypes.detect(ole2, DocumentType.EMAIL).orElseThrow());
    }

    @Test
    void malformedDocumentsFailWithAReasonForTheirFormat() {
        final Exception rtf = assertThrows(Exception.class, () -> redactor.redactDocument(DocumentType.RTF,
                "{\\rtf1 unbalanced".getBytes(StandardCharsets.US_ASCII), true));
        assertEquals(ReasonCode.MALFORMED, ReasonCode.forFailure(rtf));
        final Exception email = assertThrows(Exception.class, () -> redactor.redactDocument(DocumentType.EMAIL,
                "not a message at all".getBytes(StandardCharsets.US_ASCII), true));
        assertEquals(ReasonCode.MALFORMED, ReasonCode.forFailure(email));
        final Exception pdf = assertThrows(Exception.class, () -> redactor.redactDocument(DocumentType.PDF,
                Documents.encryptedPdf(), true));
        assertEquals(ReasonCode.ENCRYPTED, ReasonCode.forFailure(pdf), pdf.getClass().getName());
        assertEquals(ReasonCode.REDACTION_FAILED, ReasonCode.forFailure(new IllegalStateException()));
    }

    @Test
    void anOutlookMessagesAttachmentIsRedactedWhenThePolicySays() throws Exception {
        final PhileasRedactor withAttachments = new PhileasRedactor(LoadedPolicy.load(Fixtures.REGEX_POLICY
                .replace("{\"name\":\"test\",", "{\"name\":\"test\",\"config\":{\"email\":{\"attachments\":\"redact\"}},")
                .getBytes(StandardCharsets.UTF_8), Path.of("/nonexistent")), CONFIGURATION);
        final byte[] msg = Documents.msg(Documents.pdf());
        assertEquals(DocumentType.EMAIL, DocumentTypes.detect(msg, DocumentType.EMAIL).orElseThrow());
        final TextRedactor.DocumentRedaction text = withAttachments.redactDocument(DocumentType.EMAIL, msg, true);
        final TextRedactor.Section page = text.sections().stream()
                .filter(x -> x.label().startsWith("attachment: ") && x.label().endsWith(" / page 1")).findFirst()
                .orElseThrow(() -> new AssertionError(text.sections().stream().map(TextRedactor.Section::label).toList()));
        assertTrue(page.text().contains(REDACTED), page.text());
        assertFalse(page.text().contains(Documents.EMAIL), page.text());
        final byte[] document = withAttachments.redactDocument(DocumentType.EMAIL, msg, false).document();
        assertFalse(attachmentText(document).contains(Documents.EMAIL));
    }

    @Test
    void anOutlookMessagesAttachmentIsRemovedByDefault() throws Exception {
        // Phileas's default, config.email.attachments: remove (philterd/phileas#559). The attachment is not in the
        // text, and the redacted message has none.
        final byte[] msg = Documents.msg(Documents.pdf());
        final TextRedactor.DocumentRedaction text = redactor.redactDocument(DocumentType.EMAIL, msg, true);
        assertTrue(text.sections().stream().noneMatch(x -> x.label().startsWith("attachment")),
                text.sections().stream().map(TextRedactor.Section::label).toList().toString());
        final byte[] document = redactor.redactDocument(DocumentType.EMAIL, msg, false).document();
        assertEquals("", attachmentText(document), "the attachment is removed");
        assertFalse(new String(document, StandardCharsets.ISO_8859_1).contains(Documents.EMAIL));
    }

    @Test
    void anOutlookMessagesAttachmentIsKeptUnreadWhenThePolicySays() throws Exception {
        // config.email.attachments: keep. The attachment is not in the text, and it is copied into the redacted
        // message as it was.
        final PhileasRedactor keeping = new PhileasRedactor(LoadedPolicy.load(Fixtures.REGEX_POLICY
                .replace("{\"name\":\"test\",", "{\"name\":\"test\",\"config\":{\"email\":{\"attachments\":\"keep\"}},")
                .getBytes(StandardCharsets.UTF_8), Path.of("/nonexistent")), CONFIGURATION);
        final byte[] msg = Documents.msg(Documents.pdf());
        final TextRedactor.DocumentRedaction text = keeping.redactDocument(DocumentType.EMAIL, msg, true);
        assertTrue(text.sections().stream().noneMatch(x -> x.label().startsWith("attachment")),
                text.sections().stream().map(TextRedactor.Section::label).toList().toString());
        final byte[] document = keeping.redactDocument(DocumentType.EMAIL, msg, false).document();
        assertTrue(attachmentText(document).contains(Documents.EMAIL), "the kept attachment is unredacted");
    }

    /** The text of a MIME message's PDF attachments. */
    private static String attachmentText(final byte[] message) throws Exception {
        final jakarta.mail.internet.MimeMessage parsed = new jakarta.mail.internet.MimeMessage(
                jakarta.mail.Session.getInstance(new java.util.Properties()), new ByteArrayInputStream(message));
        final StringBuilder out = new StringBuilder();
        if (parsed.getContent() instanceof jakarta.mail.Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                final jakarta.mail.BodyPart part = multipart.getBodyPart(i);
                if (part.isMimeType("application/pdf")) {
                    try (PDDocument pdf = Loader.loadPDF(part.getInputStream().readAllBytes())) {
                        out.append(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf));
                    }
                }
            }
        }
        return out.toString();
    }

    @Test
    void plainTextIsNotADocument() {
        assertThrows(IllegalArgumentException.class, () -> redactor.redactDocument(DocumentType.TEXT, new byte[1], true));
    }

}
