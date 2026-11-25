package com.pollen.rag.document.parser;

import java.util.List;

public record ParsedDocument(List<ParsedPage> pages, int totalChars) {
}
