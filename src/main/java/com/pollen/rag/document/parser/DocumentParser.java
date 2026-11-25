package com.pollen.rag.document.parser;

import com.pollen.rag.common.BizException;
import com.pollen.rag.common.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocumentParser.class);

    public ParsedDocument parse(Path path, String fileType) {
        if (!Files.exists(path)) {
            throw new BizException(ErrorCode.PARSE_FAILED, "文件不存在: " + path);
        }
        try {
            return switch (fileType) {
                case "PDF" -> parsePdf(path);
                case "DOCX" -> parseTika(path);
                case "MD", "TXT" -> parsePlainText(path);
                default -> throw new BizException(ErrorCode.BAD_REQUEST, "不支持的文件类型: " + fileType);
            };
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("parse failed: {} {}", path, e.getMessage());
            throw new BizException(ErrorCode.PARSE_FAILED, "文档解析失败: " + e.getMessage(), e);
        }
    }

    private ParsedDocument parsePdf(Path path) throws IOException {
        List<ParsedPage> pages = new ArrayList<>();
        int total = 0;
        try (PDDocument pdf = Loader.loadPDF(path.toFile())) {
            int pageCount = pdf.getNumberOfPages();
            for (int i = 1; i <= pageCount; i++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = normalize(stripper.getText(pdf));
                if (!text.isBlank()) {
                    pages.add(new ParsedPage(i, text));
                    total += text.length();
                }
            }
            if (total == 0) {
                throw new BizException(ErrorCode.PARSE_FAILED,
                        "未提取到文本。若为扫描版 PDF，需要 OCR（二期支持）");
            }
            if (total < 50 && pageCount > 1) {
                throw new BizException(ErrorCode.PARSE_FAILED,
                        "提取到的文本过少（" + total + " 字），可能是扫描件或图片型 PDF");
            }
        }
        return new ParsedDocument(pages, total);
    }

    private ParsedDocument parseTika(Path path) {
        TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(path.toFile()));
        List<org.springframework.ai.document.Document> documents = reader.get();
        String text = normalize(documents.stream()
                .map(org.springframework.ai.document.Document::getText)
                .reduce("", (a, b) -> a + "\n" + b));
        if (text.isBlank()) {
            throw new BizException(ErrorCode.PARSE_FAILED, "未提取到文本");
        }
        return new ParsedDocument(List.of(new ParsedPage(null, text)), text.length());
    }

    private ParsedDocument parsePlainText(Path path) throws IOException {
        String text = normalize(Files.readString(path, StandardCharsets.UTF_8));
        if (text.isBlank()) {
            throw new BizException(ErrorCode.PARSE_FAILED, "文件内容为空");
        }
        return new ParsedDocument(List.of(new ParsedPage(null, text)), text.length());
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
