/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Small documents of each input type, built in the test, with invented values only. Word and Excel files are written
 * as their minimal OPC parts, since Phileas leaves out the XMLBeans that POI's document classes need.
 */
public final class Documents {

    /** The invented address every document holds, which the regex policy redacts. */
    public static final String EMAIL = "jane.roe@example.com";
    public static final String LINE = "Write to " + EMAIL + " about the order.";

    private Documents() {
    }

    /** A PDF with one page of text, and a second page with nothing on it. */
    public static byte[] pdf() throws IOException {
        return pdf(LINE);
    }

    public static byte[] pdf(final String line) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText(line);
                content.endText();
            }
            document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        }
    }

    /** A PDF that needs a password to open. */
    public static byte[] encryptedPdf() throws IOException {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf()); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy protection =
                    new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner-secret", "user-secret",
                            new org.apache.pdfbox.pdmodel.encryption.AccessPermission());
            protection.setEncryptionKeyLength(128);
            document.protect(protection);
            document.save(out);
            return out.toByteArray();
        }
    }

    public static byte[] docx() throws IOException {
        return docx(LINE);
    }

    public static byte[] docx(final String line) throws IOException {
        final Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/"
                + "vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
        parts.put("_rels/.rels", rels("word/document.xml"));
        parts.put("word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:p><w:r><w:t>" + line + "</w:t></w:r></w:p></w:body></w:document>");
        return zip(parts);
    }

    /** A workbook with a sheet named after an invented person, so the test can check names stay out of labels. */
    public static byte[] xlsx() throws IOException {
        return xlsx(EMAIL);
    }

    /** The workbook, with {@code cell} as the second row's second cell. */
    public static byte[] xlsx(final String cell) throws IOException {
        final Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/"
                + "vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/"
                + "vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
        parts.put("_rels/.rels", rels("xl/workbook.xml"));
        parts.put("xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"Avery Quill\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                + "relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
        parts.put("xl/worksheets/sheet1.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Name</t></is></c>"
                + "<c r=\"B1\" t=\"inlineStr\"><is><t>Email</t></is></c></row>"
                + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>Row one</t></is></c>"
                + "<c r=\"B2\" t=\"inlineStr\"><is><t>" + cell + "</t></is></c></row>"
                + "</sheetData></worksheet>");
        return zip(parts);
    }

    private static String rels(final String main) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                + "relationships/officeDocument\" Target=\"" + main + "\"/></Relationships>";
    }

    private static byte[] zip(final Map<String, String> parts) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (final Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    public static byte[] rtf() {
        return rtf(LINE);
    }

    public static byte[] rtf(final String line) {
        return ("{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Helvetica;}}\\f0 " + line + "\\par}").getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] eml() {
        return eml(LINE);
    }

    public static byte[] eml(final String line) {
        return ("From: sender@example.org\r\nTo: receiver@example.net\r\nSubject: Order\r\n"
                + "Date: Fri, 27 Nov 2026 12:00:00 +0000\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n" + line + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    /** An Outlook message with the line in its body and, when {@code attachment} is set, that PDF attached. */
    public static byte[] msg(final byte[] attachment) throws IOException {
        return msg(LINE, attachment);
    }

    public static byte[] msg(final String body, final byte[] attachment) throws IOException {
        // Adapted from the Outlook writer in Phileas's own email tests: POI's OLE2 file system, holding the
        // property chunks (UTF-16LE strings) Outlook uses for these fields.
        try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final DirectoryEntry root = fs.getRoot();
            string(root, 0x001A, "IPM.Note");
            string(root, 0x0037, "Order");
            string(root, 0x0C1A, "Sender Example");
            string(root, 0x0C1F, "sender@example.org");
            string(root, 0x0E04, "Receiver Example");
            string(root, 0x1000, body);
            root.createDocument("__properties_version1.0", new ByteArrayInputStream(new byte[32]));
            final DirectoryEntry recipient = root.createDirectory("__recip_version1.0_#00000000");
            string(recipient, 0x3001, "Receiver Example");
            string(recipient, 0x3003, "receiver@example.net");
            string(recipient, 0x39FE, "receiver@example.net");
            // PR_RECIPIENT_TYPE (0x0C15), PT_LONG: To.
            final ByteBuffer properties = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            properties.put(new byte[8]).putShort((short) 0x0003).putShort((short) 0x0C15).putInt(6).putInt(1).putInt(0);
            recipient.createDocument("__properties_version1.0", new ByteArrayInputStream(properties.array()));
            if (attachment != null) {
                final DirectoryEntry dir = root.createDirectory("__attach_version1.0_#00000000");
                string(dir, 0x3707, "Avery Quill.pdf");
                string(dir, 0x3001, "Avery Quill.pdf");
                string(dir, 0x370E, "application/pdf");
                dir.createDocument("__substg1.0_37010102", new ByteArrayInputStream(attachment));
                dir.createDocument("__properties_version1.0", new ByteArrayInputStream(new byte[8]));
            }
            fs.writeFilesystem(out);
            return out.toByteArray();
        }
    }

    /**
     * A redacted document's bytes as searchable text: a Word or Excel file's unzipped parts, a PDF's extracted text,
     * a MIME message's text parts and the text of its PDF attachments, or the bytes themselves.
     */
    public static String readable(final byte[] data) throws Exception {
        if (data.length > 1 && data[0] == 'P' && data[1] == 'K') {
            final StringBuilder out = new StringBuilder();
            try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(data))) {
                for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                    out.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            return out.toString();
        }
        if (new String(data, 0, Math.min(5, data.length), StandardCharsets.ISO_8859_1).equals("%PDF-")) {
            try (PDDocument pdf = org.apache.pdfbox.Loader.loadPDF(data)) {
                return new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
            }
        }
        final String raw = new String(data, StandardCharsets.ISO_8859_1);
        if (raw.startsWith("From:") || raw.contains("\nMIME-Version:")) {
            final jakarta.mail.internet.MimeMessage message = new jakarta.mail.internet.MimeMessage(
                    jakarta.mail.Session.getInstance(new java.util.Properties()), new ByteArrayInputStream(data));
            final StringBuilder out = new StringBuilder(raw);
            mime(message, out);
            return out.toString();
        }
        return raw;
    }

    private static void mime(final jakarta.mail.Part part, final StringBuilder out) throws Exception {
        if (part.isMimeType("text/*")) {
            out.append('\n').append(part.getContent());
        } else if (part.isMimeType("application/pdf")) {
            out.append('\n').append(readable(part.getInputStream().readAllBytes()));
        } else if (part.getContent() instanceof jakarta.mail.Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                mime(multipart.getBodyPart(i), out);
            }
        }
    }

    private static void string(final DirectoryEntry dir, final int property, final String value) throws IOException {
        dir.createDocument(String.format("__substg1.0_%04X001F", property),
                new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_16LE)));
    }

    public static byte[] csv() {
        return csv(EMAIL);
    }

    /** A CSV with {@code value} as the second field of its one data row. */
    public static byte[] csv(final String value) {
        return ("name,email\nRow one," + value + "\n").getBytes(StandardCharsets.UTF_8);
    }

}
