/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText(LINE);
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
                + "<w:body><w:p><w:r><w:t>" + LINE + "</w:t></w:r></w:p></w:body></w:document>");
        return zip(parts);
    }

    /** A workbook with a sheet named after an invented person, so the test can check names stay out of labels. */
    public static byte[] xlsx() throws IOException {
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
                + "<c r=\"B2\" t=\"inlineStr\"><is><t>" + EMAIL + "</t></is></c></row>"
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
        return ("{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Helvetica;}}\\f0 " + LINE + "\\par}").getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] eml() {
        return ("From: sender@example.org\r\nTo: receiver@example.net\r\nSubject: Order\r\n"
                + "Date: Fri, 27 Nov 2026 12:00:00 +0000\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n" + LINE + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] csv() {
        return ("name,email\nRow one," + EMAIL + "\n").getBytes(StandardCharsets.UTF_8);
    }

}
